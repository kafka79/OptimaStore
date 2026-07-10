package com.example.inventory.event;

import com.example.inventory.model.OutboxEvent;
import com.example.inventory.repository.InventoryJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
public class OutboxProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    private final InventoryJdbcRepository repository;
    private final MessagePublisher messagePublisher;
    private final TransactionTemplate transactionTemplate;

    private final java.util.concurrent.atomic.AtomicBoolean hasPending = new java.util.concurrent.atomic.AtomicBoolean(true);

    public void signal() {
        hasPending.set(true);
    }

    public OutboxProcessor(InventoryJdbcRepository repository, MessagePublisher messagePublisher, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.messagePublisher = messagePublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.scheduler.delay:5000}") // Runs every 5 seconds
    public void processOutbox() {
        if (!hasPending.get()) {
            return;
        }
        // 1. Lock next pending events and change their status to PROCESSING in a short transaction
        List<OutboxEvent> events = transactionTemplate.execute(status -> {
            List<OutboxEvent> pending = repository.findPendingOutboxEvents();
            if (pending != null && !pending.isEmpty()) {
                for (OutboxEvent event : pending) {
                    repository.updateOutboxEventStatus(event.id(), "PROCESSING");
                }
            }
            return pending;
        });

        if (events == null || events.isEmpty()) {
            hasPending.set(false);
            return;
        }

        logger.info("Found {} pending outbox events. Publishing to message broker...", events.size());
        
        // 2. Publish outside the transaction context
        for (OutboxEvent event : events) {
            try {
                messagePublisher.publish(event.eventType(), event.payload());
                
                // 3. Mark processed in a separate transaction
                transactionTemplate.executeWithoutResult(status -> {
                    repository.markOutboxEventProcessed(event.id());
                });
                logger.info("Successfully processed outbox event ID [{}].", event.id());
            } catch (Exception e) {
                logger.error("Failed to publish outbox event ID [{}]: {}", event.id(), e.getMessage());
                
                // 4. Revert status back to PENDING so it can be retried in a separate transaction
                transactionTemplate.executeWithoutResult(status -> {
                    repository.updateOutboxEventStatus(event.id(), "PENDING");
                });
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * ?") // Runs daily at midnight
    public void purgeOldData() {
        logger.info("Purging stock transactions older than 30 days...");
        try {
            repository.purgeOldTransactions(30);
        } catch (Exception e) {
            logger.error("Failed to purge stock transactions: {}", e.getMessage());
        }
    }
}
