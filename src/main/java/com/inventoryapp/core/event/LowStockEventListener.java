package com.inventoryapp.core.event;

import com.inventoryapp.core.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final StockEventPublisher stockEventPublisher;

    public LowStockEventListener(OutboxRepository repository, ObjectMapper objectMapper, OutboxProcessor outboxProcessor, StockEventPublisher stockEventPublisher) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.outboxProcessor = outboxProcessor;
        this.stockEventPublisher = stockEventPublisher;
    }

    @EventListener
    @org.springframework.scheduling.annotation.Async
    public void handleLowStockEvent(LowStockEvent event) {
        logger.info("PUSH ALERT: Local LowStockEvent caught for SKU [{}]. Storing in outbox table within active transaction...",
                event.getItem().sku());

        EventEnvelope envelope = EventEnvelope.wrap(
                "inventory.low-stock",
                "Item",
                String.valueOf(event.getItem().id()),
                event.getItem()
        );

        try {
            String payload = objectMapper.writeValueAsString(envelope);
            repository.insertOutboxEvent(
                    envelope.aggregateType(),
                    envelope.aggregateId(),
                    envelope.eventType(),
                    payload
            );
            
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        stockEventPublisher.broadcast("low-stock", payload);
                        outboxProcessor.signal();
                    }
                });
            } else {
                stockEventPublisher.broadcast("low-stock", payload);
                outboxProcessor.signal();
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize EventEnvelope for SKU [{}]. Low stock alert will not be published.", event.getItem().sku(), e);
            return;
        }


    }
}
