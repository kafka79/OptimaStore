package com.example.inventory.repository;

import com.example.inventory.exception.DuplicateSkuException;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import com.example.inventory.model.OutboxEvent;
import com.example.inventory.dto.PageResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryJdbcRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<Item> itemRowMapper = (rs, rowNum) -> mapRow(rs);

    public InventoryJdbcRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // Configure fetch size of 500 on underlying JdbcTemplate for large streaming queries
        this.jdbcTemplate.getJdbcTemplate().setFetchSize(500);
    }

    public List<Item> findAll() {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived
                FROM items WHERE archived = false ORDER BY name
                """;
        try {
            return jdbcTemplate.query(sql, itemRowMapper);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to load items", e);
        }
    }

    public PageResponse<Item> findAll(int page, int size, String search, String category) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM items WHERE archived = false");
        StringBuilder selectSql = new StringBuilder("SELECT id, sku, name, quantity, unit_price, category, updated_at, archived FROM items WHERE archived = false");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            countSql.append(" AND (LOWER(sku) LIKE :searchPattern OR LOWER(name) LIKE :searchPattern)");
            selectSql.append(" AND (LOWER(sku) LIKE :searchPattern OR LOWER(name) LIKE :searchPattern)");
            params.addValue("searchPattern", searchPattern);
        }

        if (category != null && !category.trim().isEmpty() && !category.equalsIgnoreCase("all")) {
            countSql.append(" AND LOWER(category) = :category");
            selectSql.append(" AND LOWER(category) = :category");
            params.addValue("category", category.trim().toLowerCase());
        }

        long totalItems = 0;
        try {
            Long count = jdbcTemplate.queryForObject(countSql.toString(), params, Long.class);
            if (count != null) {
                totalItems = count;
            }
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to count items", e);
        }

        selectSql.append(" ORDER BY name LIMIT :limit OFFSET :offset");
        params.addValue("limit", size);
        params.addValue("offset", page * size);

        try {
            List<Item> items = jdbcTemplate.query(selectSql.toString(), params, itemRowMapper);
            return new PageResponse<>(items, page, size, totalItems);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to page items", e);
        }
    }

    public void streamAll(PrintWriter writer) {
        String sql = "SELECT id, sku, name, quantity, unit_price, category, updated_at, archived FROM items WHERE archived = false ORDER BY name";
        try {
            // Utilizing RowCallbackHandler streams result set chunks controlled by fetchSize config
            jdbcTemplate.query(sql, rs -> {
                Item item = mapRow(rs);
                writer.println(String.format("%s,%s,%s,%d,%s,%s,%s",
                        item.id() != null ? item.id().toString() : "",
                        csvEscape(item.sku()),
                        csvEscape(item.name()),
                        item.quantity(),
                        item.unitPrice().toString(),
                        csvEscape(item.category()),
                        item.updatedAt().toString()
                ));
            });
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to stream items", e);
        }
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    public Optional<Item> findById(long id) {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived
                FROM items WHERE id = :id AND archived = false
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        try {
            List<Item> results = jdbcTemplate.query(sql, params, itemRowMapper);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to load item", e);
        }
    }

    public Optional<Item> findByIdForUpdate(long id) {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived
                FROM items WHERE id = :id AND archived = false FOR UPDATE
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        try {
            List<Item> results = jdbcTemplate.query(sql, params, itemRowMapper);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to load item for update", e);
        }
    }

    private void logTransaction(long itemId, String sku, int delta, int previousQuantity, int newQuantity, String reason, String operator) {
        String sql = """
                INSERT INTO stock_transactions (item_id, sku, delta, previous_quantity, new_quantity, reason, operator, created_at)
                VALUES (:itemId, :sku, :delta, :previousQuantity, :newQuantity, :reason, :operator, :createdAt)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("itemId", itemId)
                .addValue("sku", sku)
                .addValue("delta", delta)
                .addValue("previousQuantity", previousQuantity)
                .addValue("newQuantity", newQuantity)
                .addValue("reason", reason)
                .addValue("operator", operator)
                .addValue("createdAt", Timestamp.from(Instant.now()));
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to log stock transaction for SKU: " + sku, e);
        }
    }

    public Optional<Item> findArchivedBySku(String sku) {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived
                FROM items WHERE sku = :sku AND archived = true
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("sku", sku.trim());
        try {
            List<Item> results = jdbcTemplate.query(sql, params, itemRowMapper);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to find archived item by sku", e);
        }
    }

    public Item reactivateItem(long id, String name, int quantity, BigDecimal unitPrice, String category, String operator) {
        String selectSql = "SELECT id, sku, name, quantity, unit_price, category, updated_at, archived FROM items WHERE id = :id FOR UPDATE";
        MapSqlParameterSource selectParams = new MapSqlParameterSource("id", id);
        List<Item> archivedResults = jdbcTemplate.query(selectSql, selectParams, itemRowMapper);
        if (archivedResults.isEmpty()) {
            throw new IllegalStateException("Failed to locate archived item for reactivation");
        }
        Item archived = archivedResults.get(0);

        String sql = """
                UPDATE items
                SET name = :name, quantity = :quantity, unit_price = :unitPrice, category = :category, updated_at = :updatedAt, archived = false
                WHERE id = :id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name.trim())
                .addValue("quantity", quantity)
                .addValue("unitPrice", unitPrice)
                .addValue("category", category.trim())
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            jdbcTemplate.update(sql, params);
            logTransaction(id, archived.sku(), quantity, 0, quantity, "REACTIVATE", operator);
            return findById(id).orElseThrow(() -> new IllegalStateException("Failed to load reactivated item"));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to reactivate item", e);
        }
    }

    public Item insert(String sku, String name, int quantity, BigDecimal unitPrice, String category, String operator) {
        String sql = """
                INSERT INTO items (sku, name, quantity, unit_price, category, updated_at, archived)
                VALUES (:sku, :name, :quantity, :unitPrice, :category, :updatedAt, :archived)
                """;
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sku", sku.trim())
                .addValue("name", name.trim())
                .addValue("quantity", quantity)
                .addValue("unitPrice", unitPrice)
                .addValue("category", category.trim())
                .addValue("updatedAt", Timestamp.from(now))
                .addValue("archived", false);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(sql, params, keyHolder);
            long id = extractId(keyHolder);
            logTransaction(id, sku.trim(), quantity, 0, quantity, "INITIAL_STOCK", operator);
            return new Item(id, sku.trim(), name.trim(), quantity, unitPrice, category.trim(), now, false);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            Optional<Item> archivedItem = findArchivedBySku(sku);
            if (archivedItem.isPresent()) {
                return reactivateItem(archivedItem.get().id(), name, quantity, unitPrice, category, operator);
            }
            throw new DuplicateSkuException(sku.trim(), e);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to insert item", e);
        }
    }

    public boolean deleteById(long id, String operator) {
        Optional<Item> currentOpt = findByIdForUpdate(id);
        if (currentOpt.isEmpty()) {
            return false;
        }
        Item current = currentOpt.get();

        String sql = "UPDATE items SET archived = true, updated_at = :updatedAt WHERE id = :id AND archived = false";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            boolean deleted = jdbcTemplate.update(sql, params) > 0;
            if (deleted) {
                logTransaction(id, current.sku(), -current.quantity(), current.quantity(), 0, "ARCHIVED", operator);
            }
            return deleted;
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to soft delete item", e);
        }
    }

    public Optional<Item> adjustQuantity(long id, int delta, String operator) {
        Optional<Item> currentOpt = findByIdForUpdate(id);
        if (currentOpt.isEmpty()) {
            return Optional.empty();
        }
        Item current = currentOpt.get();
        int previousQty = current.quantity();
        int newQty = previousQty + delta;
        if (newQty < 0) {
            throw new IllegalArgumentException("Insufficient stock: Current quantity is " + previousQty + ", adjustment was " + delta);
        }

        String sql = """
                UPDATE items
                SET quantity = :newQuantity, updated_at = :updatedAt
                WHERE id = :id AND archived = false
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("newQuantity", newQty)
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            int updated = jdbcTemplate.update(sql, params);
            if (updated == 0) {
                return Optional.empty();
            }
            logTransaction(id, current.sku(), delta, previousQty, newQty, delta >= 0 ? "RESTOCK" : "DISPATCH", operator);
            return findById(id);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to adjust quantity", e);
        }
    }

    public InventoryReport buildReport(int lowStockThreshold) {
        String statsSql = """
                SELECT 
                    COUNT(*) as distinct_items, 
                    COALESCE(SUM(quantity), 0) as total_units, 
                    COALESCE(SUM(quantity * unit_price), 0.0) as total_value
                FROM items
                WHERE archived = false
                """;
        String lowSql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived FROM items
                WHERE quantity < :threshold AND archived = false ORDER BY quantity, name
                """;
        try {
            long distinctItems = 0;
            long totalUnits = 0;
            BigDecimal totalValue = BigDecimal.ZERO;

            java.util.Map<String, Object> stats = jdbcTemplate.queryForMap(statsSql, new MapSqlParameterSource());
            if (stats != null && !stats.isEmpty()) {
                distinctItems = ((Number) stats.get("distinct_items")).longValue();
                totalUnits = ((Number) stats.get("total_units")).longValue();
                Object valueObj = stats.get("total_value");
                if (valueObj instanceof BigDecimal) {
                    totalValue = (BigDecimal) valueObj;
                } else if (valueObj instanceof Number) {
                    totalValue = BigDecimal.valueOf(((Number) valueObj).doubleValue());
                }
            }

            MapSqlParameterSource params = new MapSqlParameterSource("threshold", lowStockThreshold);
            List<Item> low = jdbcTemplate.query(lowSql, params, itemRowMapper);

            return new InventoryReport(distinctItems, totalUnits, totalValue, low.size(), low);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to build report", e);
        }
    }

    public List<String> findDistinctCategories() {
        String sql = "SELECT DISTINCT category FROM items WHERE archived = false ORDER BY category";
        try {
            return jdbcTemplate.queryForList(sql, new MapSqlParameterSource(), String.class);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to load distinct categories", e);
        }
    }

    private static Item mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("updated_at");
        Instant updated = ts != null ? ts.toInstant() : Instant.now();
        return new Item(
                rs.getLong("id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getString("category"),
                updated,
                rs.getBoolean("archived")
        );
    }

    private static long extractId(KeyHolder keyHolder) {
        java.util.Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("No generated keys returned");
        }
        for (java.util.Map.Entry<String, Object> entry : keys.entrySet()) {
            if ("id".equalsIgnoreCase(entry.getKey())) {
                return ((Number) entry.getValue()).longValue();
            }
        }
        Object firstValue = keys.values().iterator().next();
        if (firstValue instanceof Number) {
            return ((Number) firstValue).longValue();
        }
        throw new IllegalStateException("No numeric generated key found");
    }

    public void insertOutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        String sql = """
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, status)
                VALUES (:aggregateType, :aggregateId, :eventType, :payload, 'PENDING')
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType)
                .addValue("payload", payload);
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to insert outbox event", e);
        }
    }

    public List<OutboxEvent> findPendingOutboxEvents() {
        String sql = """
                SELECT id, aggregate_type, aggregate_id, event_type, payload, status, created_at
                FROM outbox_events
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """;
        try {
            return jdbcTemplate.query(sql, new MapSqlParameterSource(), (rs, rowNum) -> new OutboxEvent(
                    rs.getLong("id"),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()
            ));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to load pending outbox events", e);
        }
    }

    public void updateOutboxEventStatus(long id, String status) {
        String sql = "UPDATE outbox_events SET status = :status WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status);
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to update outbox event status", e);
        }
    }

    public void markOutboxEventProcessed(long id) {
        String sql = "DELETE FROM outbox_events WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to delete outbox event", e);
        }
    }
}
