package com.example.inventory.event;

import com.example.inventory.model.OutboxEvent;
import com.example.inventory.repository.InventoryJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    private final InventoryJdbcRepository repository;
    private final MessagePublisher messagePublisher;

    public OutboxProcessor(InventoryJdbcRepository repository, MessagePublisher messagePublisher) {
        this.repository = repository;
        this.messagePublisher = messagePublisher;
    }

    @Scheduled(fixedDelay = 5000) // Runs every 5 seconds
    @Transactional
    public void processOutbox() {
        List<OutboxEvent> events = repository.findPendingOutboxEvents();
        if (events.isEmpty()) {
            return;
        }
        logger.info("Found {} pending outbox events. Publishing to message broker...", events.size());
        for (OutboxEvent event : events) {
            try {
                messagePublisher.publish(event.eventType(), event.payload());
                repository.markOutboxEventProcessed(event.id());
                logger.info("Successfully processed outbox event ID [{}].", event.id());
            } catch (Exception e) {
                logger.error("Failed to publish outbox event ID [{}]: {}", event.id(), e.getMessage());
            }
        }
    }
}
