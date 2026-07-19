package com.inventoryapp.core.repository;

import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.dto.StockTransactionResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class StockTransactionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private final RowMapper<StockTransactionResponse> transactionRowMapper = (rs, rowNum) -> new StockTransactionResponse(
            rs.getLong("id"),
            rs.getLong("item_id"),
            rs.getString("sku"),
            rs.getInt("delta"),
            rs.getInt("previous_quantity"),
            rs.getInt("new_quantity"),
            rs.getString("reason"),
            rs.getString("operator"),
            rs.getTimestamp("created_at").toInstant()
    );

    public StockTransactionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CursorResponse<StockTransactionResponse> findTransactions(Long lastId, int size, Long itemId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, item_id, sku, delta, previous_quantity, new_quantity, reason, operator, created_at
                FROM stock_transactions WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (lastId != null) {
            sql.append(" AND id > :lastId");
            params.addValue("lastId", lastId);
        }

        if (itemId != null) {
            sql.append(" AND item_id = :itemId");
            params.addValue("itemId", itemId);
        }

        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.addValue("limit", size);

        try {
            List<StockTransactionResponse> items = jdbcTemplate.query(sql.toString(), params, transactionRowMapper);
            Long nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).id();
            return new CursorResponse<>(items, nextCursor);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to query stock transactions", e);
        }
    }

    public void logTransaction(long itemId, String sku, int delta, int previousQuantity, int newQuantity, String reason, String operator) {
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

    public void purgeOldTransactions(int daysToKeep) {
        String sql = "DELETE FROM stock_transactions WHERE created_at < :cutoff";
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(daysToKeep))));
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to purge old transactions", e);
        }
    }
}
