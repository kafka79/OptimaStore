package com.inventoryapp.core.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIdempotencyKey(String key) {
        String sql = "INSERT INTO idempotency_keys (idempotency_key, created_at) VALUES (:key, :createdAt)";
        try {
            int updated = jdbcTemplate.update(sql, Map.of(
                    "key", key,
                    "createdAt", Timestamp.from(Instant.now())
            ));
            return updated > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<String> getIdempotencyResponse(String key) {
        String sql = "SELECT response_payload FROM idempotency_keys WHERE idempotency_key = :key";
        try {
            List<String> results = jdbcTemplate.query(sql, Map.of("key", key), (rs, rowNum) -> rs.getString("response_payload"));
            return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to check idempotency key", e);
        }
    }

    public void updateIdempotencyResponse(String key, String payload) {
        String sql = "UPDATE idempotency_keys SET response_payload = :payload WHERE idempotency_key = :key";
        try {
            jdbcTemplate.update(sql, Map.of(
                    "key", key,
                    "payload", payload
            ));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to update idempotency response", e);
        }
    }

    public void deleteIdempotencyKey(String key) {
        String sql = "DELETE FROM idempotency_keys WHERE idempotency_key = :key";
        try {
            jdbcTemplate.update(sql, Map.of("key", key));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to delete idempotency key", e);
        }
    }

    public void purgeOldKeys(Instant cutoff) {
        String sql = "DELETE FROM idempotency_keys WHERE created_at < :cutoff";
        try {
            jdbcTemplate.update(sql, Map.of("cutoff", Timestamp.from(cutoff)));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to purge old idempotency keys", e);
        }
    }
}
