package com.example.inventory.event;

import com.example.inventory.repository.InventoryJdbcRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LowStockEventListener {

    private static final Logger logger = LoggerFactory.getLogger(LowStockEventListener.class);
    private final InventoryJdbcRepository repository;
    private final ObjectMapper objectMapper;

    public LowStockEventListener(InventoryJdbcRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void handleLowStockEvent(LowStockEvent event) {
        logger.info("PUSH ALERT: Local LowStockEvent caught for SKU [{}]. Storing in outbox table within active transaction...",
                event.getItem().sku());
        
        EventEnvelope envelope = EventEnvelope.wrap(
                "inventory.low-stock",
                "Item",
                String.valueOf(event.getItem().id()),
                event.getItem()
        );
        
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("Failed to serialize EventEnvelope to JSON for SKU [{}]", event.getItem().sku(), e);
            throw new RuntimeException("Failed to serialize event", e);
        }
        
        repository.insertOutboxEvent(
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.eventType(),
                payload
        );
    }
}
