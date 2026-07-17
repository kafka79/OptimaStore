package com.inventoryapp.core.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class StockTransactionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StockTransactionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
