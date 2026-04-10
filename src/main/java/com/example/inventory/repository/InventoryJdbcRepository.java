package com.example.inventory.repository;

import com.example.inventory.exception.DuplicateSkuException;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import org.springframework.stereotype.Repository;

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
        this.dataSource = dataSource;
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
        String allSql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at FROM items
                """;
        String lowSql = """
                SELECT id, sku, name, quantity, unit_price, category, updated_at FROM items
                WHERE quantity < ? ORDER BY quantity, name
                """;
        try (Connection conn = dataSource.getConnection()) {
            List<Item> all = new ArrayList<>();
            long totalUnits = 0;
            BigDecimal totalValue = BigDecimal.ZERO;
            try (PreparedStatement ps = conn.prepareStatement(allSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Item item = mapRow(rs);
                    all.add(item);
                    totalUnits += item.quantity();
                    totalValue = totalValue.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
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
            return new InventoryReport(all.size(), totalUnits, totalValue, low.size(), low);
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
