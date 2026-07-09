package com.example.inventory.event;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
    String eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    long timestamp,
    Object data
) {
    public static EventEnvelope wrap(String eventType, String aggregateType, String aggregateId, Object data) {
        return new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            aggregateType,
            aggregateId,
            Instant.now().toEpochMilli(),
            data
        );
    }
}