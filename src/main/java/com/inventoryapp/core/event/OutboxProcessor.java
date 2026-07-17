package com.inventoryapp.core.event;

import com.inventoryapp.core.model.OutboxEvent;
import com.inventoryapp.core.repository.OutboxRepository;
import com.inventoryapp.core.repository.StockTransactionRepository;
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
    private final OutboxRepository outboxRepository;
    private final StockTransactionRepository transactionRepository;
    private final com.inventoryapp.core.repository.IdempotencyRepository idempotencyRepository;
    private final MessagePublisher messagePublisher;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean isScheduled = new AtomicBoolean(false);
    private final ThreadPoolTaskExecutor executor;

    private final Timer processTimer;
    private final Counter successCounter;
    private final Counter failedCounter;

    public OutboxProcessor(OutboxRepository outboxRepository, 
                           StockTransactionRepository transactionRepository, 
                           com.inventoryapp.core.repository.IdempotencyRepository idempotencyRepository,
                           MessagePublisher messagePublisher, 
                           org.springframework.transaction.PlatformTransactionManager transactionManager, 
                           MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.messagePublisher = messagePublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout(30);
        
        this.processTimer = meterRegistry.timer("outbox.process.time");
        this.successCounter = meterRegistry.counter("outbox.events.processed.success");
        this.failedCounter = meterRegistry.counter("outbox.events.processed.failed");

        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(5);
        this.executor.setMaxPoolSize(10);
        this.executor.setQueueCapacity(50);
        this.executor.setThreadNamePrefix("outbox-worker-");
        this.executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        this.executor.initialize();
    }

    public void signal() {
        if (isScheduled.compareAndSet(false, true)) {
            executor.submit(() -> {
                try {
                    processOutboxInternal();
                } finally {
                    isScheduled.set(false);
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down OutboxProcessor executor...");
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${outbox.scheduler.delay:60000}")
    public void processOutbox() {
        if (isScheduled.compareAndSet(false, true)) {
            executor.submit(() -> {
                try {
                    processOutboxInternal();
                } finally {
                    isScheduled.set(false);
                }
            });
        }
    }

    private void processOutboxInternal() {
        processTimer.record(() -> {
            boolean hasMore = true;
            while (hasMore) {
                // 1. Lock next pending events and change their status to PROCESSING in a short transaction
                List<OutboxEvent> events = transactionTemplate.execute(status -> {
                    List<OutboxEvent> pending = outboxRepository.findPendingOutboxEvents(java.time.Instant.now().minusSeconds(300));
                    if (pending != null && !pending.isEmpty()) {
                        List<Long> ids = pending.stream().map(OutboxEvent::id).toList();
                        outboxRepository.updateOutboxEventsStatus(ids, "PROCESSING");
                    }
                    return pending;
                });

                if (events == null || events.isEmpty()) {
                    hasMore = false;
                    continue;
                }

                logger.info("Found {} pending outbox events. Publishing to message broker...", events.size());
                
                // 2. Publish inside the transaction context to ensure atomicity with the DB status update
                for (OutboxEvent event : events) {
                    try {
                        transactionTemplate.executeWithoutResult(status -> {
                            messagePublisher.publish(event.eventType(), event.payload());
                            outboxRepository.markOutboxEventProcessed(event.id());
                        });
                        successCounter.increment();
                        io.micrometer.core.instrument.Metrics.counter("business.events.processed", "type", event.eventType()).increment();
                        logger.info("Successfully processed outbox event ID [{}].", event.id());
                    } catch (Exception e) {
                        logger.error("Failed to publish outbox event ID [{}]: {}", event.id(), e.getMessage());
                        failedCounter.increment();
                        
                        // 4. Increment retry count or send to DLQ (status = FAILED) if limit exceeded
                        transactionTemplate.executeWithoutResult(status -> {
                            int nextRetry = event.retryCount() + 1;
                            if (nextRetry >= 5) {
                                logger.error("Outbox event ID [{}] reached max retry limit. Moving to DLQ (FAILED).", event.id());
                                outboxRepository.incrementOutboxEventRetry(event.id(), nextRetry, "FAILED");
                            } else {
                                outboxRepository.incrementOutboxEventRetry(event.id(), nextRetry, "PENDING");
                            }
                        });
                    }
                }
            }
        });
    }

    @Scheduled(cron = "0 0 0 * * ?") // Runs daily at midnight
    public void purgeOldData() {
        logger.info("Purging old stock transactions, failed outbox events, and idempotency keys...");
        try {
            transactionRepository.purgeOldTransactions(30);
            
            java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(30));
            outboxRepository.purgeFailedEvents(cutoff);
            idempotencyRepository.purgeOldKeys(cutoff);
        } catch (Exception e) {
            logger.error("Failed to purge old data: {}", e.getMessage());
        }
    }
}
