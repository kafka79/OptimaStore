package com.inventoryapp.core.repository;

import com.inventoryapp.core.exception.DuplicateSkuException;
import com.inventoryapp.core.model.InventoryReport;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.dto.CursorResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ItemRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<Item> itemRowMapper = (rs, rowNum) -> mapRow(rs);
    private final int defaultLowStockThreshold;

    public ItemRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            @Value("${inventory.default-low-stock-threshold:5}") int defaultLowStockThreshold
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultLowStockThreshold = defaultLowStockThreshold;
        this.jdbcTemplate.getJdbcTemplate().setFetchSize(500);
    }

    public CursorResponse<Item> findAll(Long lastId, int size, String search, String category) {
        StringBuilder selectSql = new StringBuilder("SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version FROM items WHERE archived = false");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (lastId != null) {
            selectSql.append(" AND id > :lastId");
            params.addValue("lastId", lastId);
        }

        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            selectSql.append(" AND (LOWER(sku) LIKE :searchPattern OR LOWER(name) LIKE :searchPattern)");
            params.addValue("searchPattern", searchPattern);
        }

        if (category != null && !category.trim().isEmpty() && !category.equalsIgnoreCase("all")) {
            selectSql.append(" AND LOWER(category) = :category");
            params.addValue("category", category.trim().toLowerCase());
        }

        selectSql.append(" ORDER BY id ASC LIMIT :limit");
        params.addValue("limit", size);

        try {
            List<Item> items = jdbcTemplate.query(selectSql.toString(), params, itemRowMapper);
            Long nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).id();
            return new CursorResponse<>(items, nextCursor);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to page items", e);
        }
    }

    public List<Item> findItemsForExport(String search, String category) {
        StringBuilder sql = new StringBuilder("SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version FROM items WHERE archived = false");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            sql.append(" AND (LOWER(sku) LIKE :searchPattern OR LOWER(name) LIKE :searchPattern)");
            params.addValue("searchPattern", searchPattern);
        }

        if (category != null && !category.trim().isEmpty() && !category.equalsIgnoreCase("all")) {
            sql.append(" AND LOWER(category) = :category");
            params.addValue("category", category.trim().toLowerCase());
        }

        sql.append(" ORDER BY name");

        try {
            return jdbcTemplate.query(sql.toString(), params, itemRowMapper);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to load export items", e);
        }
    }

    public Optional<Item> findById(long id) {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
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
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
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

    public Optional<Item> findArchivedBySku(String sku) {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
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

    public Optional<Item> findBySkuAny(String sku) {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
                FROM items WHERE sku = :sku
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("sku", sku.trim());
        try {
            List<Item> results = jdbcTemplate.query(sql, params, itemRowMapper);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to find item by sku", e);
        }
    }

    public Item reactivateItem(long id, String name, int quantity, BigDecimal unitPrice, String category, Integer lowStockThreshold) {
        String sql = """
                UPDATE items
                SET name = :name, quantity = :quantity, unit_price = :unitPrice, category = :category, updated_at = :updatedAt, archived = false, low_stock_threshold = :lowStockThreshold, version = version + 1
                WHERE id = :id
                RETURNING id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name.trim())
                .addValue("quantity", quantity)
                .addValue("unitPrice", unitPrice)
                .addValue("category", normalizeCategory(category))
                .addValue("lowStockThreshold", lowStockThreshold != null ? lowStockThreshold : defaultLowStockThreshold)
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            return jdbcTemplate.queryForObject(sql, params, itemRowMapper);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to reactivate item", e);
        }
    }

    public Item insert(String sku, String name, int quantity, BigDecimal unitPrice, String category, Integer lowStockThreshold) {
        String sql = """
                INSERT INTO items (sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version)
                VALUES (:sku, :name, :quantity, :unitPrice, :category, :updatedAt, :archived, :lowStockThreshold, 0)
                """;
        Instant now = Instant.now();
        String normalizedCategory = normalizeCategory(category);
        int threshold = lowStockThreshold != null ? lowStockThreshold : defaultLowStockThreshold;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sku", sku.trim())
                .addValue("name", name.trim())
                .addValue("quantity", quantity)
                .addValue("unitPrice", unitPrice)
                .addValue("category", normalizedCategory)
                .addValue("updatedAt", Timestamp.from(now))
                .addValue("archived", false)
                .addValue("lowStockThreshold", threshold);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(sql, params, keyHolder);
            long id = extractId(keyHolder);
            return new Item(id, sku.trim(), name.trim(), quantity, unitPrice, normalizedCategory, now, false, threshold, 0);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateSkuException(sku.trim(), e);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to insert item", e);
        }
    }

    public boolean restoreById(long id) {
        String sql = "UPDATE items SET archived = false, updated_at = :updatedAt, version = version + 1 WHERE id = :id AND archived = true";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            return jdbcTemplate.update(sql, params) > 0;
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to restore item", e);
        }
    }

    public boolean deleteById(long id) {
        String sql = "UPDATE items SET archived = true, updated_at = :updatedAt WHERE id = :id AND archived = false";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            return jdbcTemplate.update(sql, params) > 0;
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to soft delete item", e);
        }
    }

    public Item updateQuantity(long id, int newQuantity) {
        String sql = """
                UPDATE items
                SET quantity = :quantity, updated_at = :updatedAt, version = version + 1
                WHERE id = :id AND archived = false
                RETURNING id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("quantity", newQuantity)
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            return jdbcTemplate.queryForObject(sql, params, itemRowMapper);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to update item quantity", e);
        }
    }

    public Item updateItem(long id, String name, BigDecimal unitPrice, String category) {
        String sql = """
                UPDATE items
                SET name = :name, unit_price = :unitPrice, category = :category, updated_at = :updatedAt, version = version + 1
                WHERE id = :id AND archived = false
                RETURNING id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name.trim())
                .addValue("unitPrice", unitPrice)
                .addValue("category", normalizeCategory(category))
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("id", id);
        try {
            return jdbcTemplate.queryForObject(sql, params, itemRowMapper);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to update item", e);
        }
    }

    public InventoryReport buildReport(int defaultThreshold) {
        String statsSql = """
                SELECT 
                    COUNT(*) as distinct_items, 
                    COALESCE(SUM(quantity), 0) as total_units, 
                    COALESCE(SUM(quantity * unit_price), 0.0) as total_value
                FROM items
                WHERE archived = false
                """;
        String lowSql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at, archived, low_stock_threshold, version FROM items
                WHERE quantity < low_stock_threshold AND archived = false ORDER BY quantity, name
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

            List<Item> low = jdbcTemplate.query(lowSql, new MapSqlParameterSource(), itemRowMapper);

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

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "General";
        }
        return category.trim();
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
                rs.getBoolean("archived"),
                rs.getInt("low_stock_threshold"),
                rs.getInt("version")
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
}
