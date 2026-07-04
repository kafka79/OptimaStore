package com.example.inventory.repository;

import com.example.inventory.exception.DuplicateSkuException;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import com.example.inventory.dto.PageResponse;
import org.springframework.stereotype.Repository;

import java.io.PrintWriter;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryJdbcRepository {

    private final DataSource dataSource;

    public InventoryJdbcRepository(DataSource dataSource) {
        this.dataSource = new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(dataSource);
    }

    public List<Item> findAll() {
        String sql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at
                FROM items ORDER BY name
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Item> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load items", e);
        }
    }

    public PageResponse<Item> findAll(int page, int size, String search, String category) {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM items WHERE 1=1");
        StringBuilder selectSql = new StringBuilder("SELECT id, sku, name, quantity, unit_price, category, updated_at FROM items WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            countSql.append(" AND (LOWER(sku) LIKE ? OR LOWER(name) LIKE ?)");
            selectSql.append(" AND (LOWER(sku) LIKE ? OR LOWER(name) LIKE ?)");
            params.add(searchPattern);
            params.add(searchPattern);
        }

        if (category != null && !category.trim().isEmpty() && !category.equalsIgnoreCase("all")) {
            countSql.append(" AND LOWER(category) = ?");
            selectSql.append(" AND LOWER(category) = ?");
            params.add(category.trim().toLowerCase());
        }

        long totalItems = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql.toString())) {
            int idx = 1;
            for (Object p : params) {
                ps.setObject(idx++, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totalItems = rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count items", e);
        }

        selectSql.append(" ORDER BY name LIMIT ? OFFSET ?");
        List<Object> selectParams = new ArrayList<>(params);
        selectParams.add(size);
        selectParams.add(page * size);

        List<Item> items = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql.toString())) {
            int idx = 1;
            for (Object p : selectParams) {
                ps.setObject(idx++, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to page items", e);
        }

        return new PageResponse<>(items, page, size, totalItems);
    }

    public void streamAll(PrintWriter writer) {
        String sql = "SELECT id, sku, name, quantity, unit_price, category, updated_at FROM items ORDER BY name";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
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
            }
        } catch (SQLException e) {
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
                SELECT id, sku, name, quantity, unit_price, category, updated_at
                FROM items WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load item", e);
        }
    }

    public Item insert(String sku, String name, int quantity, BigDecimal unitPrice, String category) {
        String sql = """
                INSERT INTO items (sku, name, quantity, unit_price, category, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            Instant now = Instant.now();
            ps.setString(1, sku.trim());
            ps.setString(2, name.trim());
            ps.setInt(3, quantity);
            ps.setBigDecimal(4, unitPrice);
            ps.setString(5, category.trim());
            ps.setTimestamp(6, Timestamp.from(now));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return new Item(id, sku.trim(), name.trim(), quantity, unitPrice, category.trim(), now);
                }
            }
            throw new IllegalStateException("No generated key");
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new DuplicateSkuException(sku.trim(), e);
            }
            throw new IllegalStateException("Failed to insert item", e);
        }
    }

    public boolean deleteById(long id) {
        String sql = "DELETE FROM items WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete item", e);
        }
    }

    /**
     * Applies a delta to quantity (add stock or remove). Quantity cannot go below zero.
     */
    public Optional<Item> adjustQuantity(long id, int delta) {
        String sql = """
                UPDATE items
                SET quantity = quantity + ?, updated_at = ?
                WHERE id = ? AND quantity + ? >= 0
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Instant now = Instant.now();
            ps.setInt(1, delta);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setLong(3, id);
            ps.setInt(4, delta);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return Optional.empty();
            }
            return findById(id);
        } catch (SQLException e) {
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
                """;
        String lowSql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at FROM items
                WHERE quantity < ? ORDER BY quantity, name
                """;
        try (Connection conn = dataSource.getConnection()) {
            long distinctItems = 0;
            long totalUnits = 0;
            BigDecimal totalValue = BigDecimal.ZERO;

            try (PreparedStatement ps = conn.prepareStatement(statsSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    distinctItems = rs.getLong("distinct_items");
                    totalUnits = rs.getLong("total_units");
                    totalValue = rs.getBigDecimal("total_value");
                }
            }

            List<Item> low = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(lowSql)) {
                ps.setInt(1, lowStockThreshold);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        low.add(mapRow(rs));
                    }
                }
            }
            return new InventoryReport(distinctItems, totalUnits, totalValue, low.size(), low);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to build report", e);
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
                updated
        );
    }

    private static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState());
    }
}
