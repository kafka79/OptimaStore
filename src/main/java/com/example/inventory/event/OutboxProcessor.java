package com.example.inventory.event;

import com.example.inventory.model.OutboxEvent;
import com.example.inventory.repository.InventoryJdbcRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class OutboxProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    private final InventoryJdbcRepository repository;
    private final MessagePublisher messagePublisher;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean isScheduled = new AtomicBoolean(false);
    private final ThreadPoolTaskExecutor executor;

    private final Timer processTimer;
    private final Counter successCounter;
    private final Counter failedCounter;

    public OutboxProcessor(InventoryJdbcRepository repository, MessagePublisher messagePublisher, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.messagePublisher = messagePublisher;
        this.transactionTemplate = transactionTemplate;
        
        this.processTimer = meterRegistry.timer("outbox.process.time");
        this.successCounter = meterRegistry.counter("outbox.events.processed.success");
        this.failedCounter = meterRegistry.counter("outbox.events.processed.failed");

        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(1);
        this.executor.setMaxPoolSize(1);
        this.executor.setQueueCapacity(10);
        this.executor.setThreadNamePrefix("outbox-worker-");
        this.executor.initialize();
    }

    public void signal() {
        if (isScheduled.compareAndSet(false, true)) {
            executor.submit(() -> {
                isScheduled.set(false);
                processOutboxInternal();
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down OutboxProcessor executor...");
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${outbox.scheduler.delay:5000}")
    public void processOutbox() {
        if (isScheduled.compareAndSet(false, true)) {
            executor.submit(() -> {
                isScheduled.set(false);
                processOutboxInternal();
            });
        }
    }

    private void processOutboxInternal() {
        processTimer.record(() -> {
            boolean hasMore = true;
            while (hasMore) {
                // 1. Lock next pending events and change their status to PROCESSING in a short transaction
                List<OutboxEvent> events = transactionTemplate.execute(status -> {
                    List<OutboxEvent> pending = repository.findPendingOutboxEvents(java.time.Instant.now().minusSeconds(300));
                    if (pending != null && !pending.isEmpty()) {
                        List<Long> ids = pending.stream().map(OutboxEvent::id).toList();
                        repository.updateOutboxEventsStatus(ids, "PROCESSING");
                    }
                    return pending;
                });

                if (events == null || events.isEmpty()) {
                    hasMore = false;
                    continue;
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
                        successCounter.increment();
                        logger.info("Successfully processed outbox event ID [{}].", event.id());
                    } catch (Exception e) {
                        logger.error("Failed to publish outbox event ID [{}]: {}", event.id(), e.getMessage());
                        failedCounter.increment();
                        
                        // 4. Increment retry count or send to DLQ (status = FAILED) if limit exceeded
                        transactionTemplate.executeWithoutResult(status -> {
                            int nextRetry = event.retryCount() + 1;
                            if (nextRetry >= 5) {
                                logger.error("Outbox event ID [{}] reached max retry limit. Moving to DLQ (FAILED).", event.id());
                                repository.incrementOutboxEventRetry(event.id(), nextRetry, "FAILED");
                            } else {
                                repository.incrementOutboxEventRetry(event.id(), nextRetry, "PENDING");
                            }
                        });
                    }
                }
            }
        });
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
