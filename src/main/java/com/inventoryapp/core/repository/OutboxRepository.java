package com.inventoryapp.core.repository;

import com.inventoryapp.core.model.OutboxEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OutboxRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    public List<OutboxEvent> findPendingOutboxEvents(Instant processingCutoff) {
        String sql = """
                SELECT id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at
                FROM outbox_events
                WHERE status = 'PENDING' OR (status = 'PROCESSING' AND created_at < :cutoff)
                ORDER BY created_at
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(processingCutoff));
        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> new OutboxEvent(
                    rs.getLong("id"),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("status"),
                    rs.getInt("retry_count"),
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

    public void updateOutboxEventsStatus(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) return;
        String sql = "UPDATE outbox_events SET status = :status WHERE id IN (:ids)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("status", status);
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to bulk update outbox events status", e);
        }
    }

    public void incrementOutboxEventRetry(long id, int newRetryCount, String status) {
        String sql = "UPDATE outbox_events SET status = :status, retry_count = :retryCount WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("retryCount", newRetryCount);
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to increment outbox event retry count", e);
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

    public void purgeFailedEvents(Instant cutoff) {
        String sql = "DELETE FROM outbox_events WHERE status = 'FAILED' AND created_at < :cutoff";
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(cutoff));
        try {
            jdbcTemplate.update(sql, params);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Failed to purge failed outbox events", e);
        }
    }
}
