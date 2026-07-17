package com.inventoryapp.core.event;

import com.inventoryapp.core.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class LowStockEventListener {

    private static final Logger logger = LoggerFactory.getLogger(LowStockEventListener.class);
    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final OutboxProcessor outboxProcessor;

    public LowStockEventListener(OutboxRepository repository, ObjectMapper objectMapper, OutboxProcessor outboxProcessor) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.outboxProcessor = outboxProcessor;
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
        // ponytail: defer signal until transaction commits to avoid visibility race condition
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outboxProcessor.signal();
                }
            });
        } else {
            outboxProcessor.signal();
        }
    }
}
