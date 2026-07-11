package com.example.inventory.model;

import java.time.Instant;

public record OutboxEvent(
        Long id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String status,
        int retryCount,
        Instant createdAt
) {}
