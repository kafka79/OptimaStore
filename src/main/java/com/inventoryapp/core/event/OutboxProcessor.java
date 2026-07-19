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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        this.executor.setCorePoolSize(2);
        this.executor.setMaxPoolSize(4);
        this.executor.setQueueCapacity(20);
        this.executor.setThreadNamePrefix("outbox-worker-");
        this.executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        this.executor.setWaitForTasksToCompleteOnShutdown(true);
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
        var threadPool = executor.getThreadPoolExecutor();
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("OutboxProcessor executor did not terminate within 30s. Forcing shutdown.");
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for OutboxProcessor shutdown", e);
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
                // 1. Lock and fetch pending events in one transaction
                // FOR UPDATE SKIP LOCKED handles distributed locking across instances
                List<OutboxEvent> events = transactionTemplate.execute(status -> {
                    List<OutboxEvent> pending = outboxRepository.findPendingOutboxEvents(Instant.now().minusSeconds(300));
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

                // 2. Publish OUTSIDE the transaction to avoid holding DB connections during network calls
                for (OutboxEvent event : events) {
                    try {
                        messagePublisher.publish(event.eventType(), event.payload());

                        // Mark as processed in a short transaction
                        transactionTemplate.executeWithoutResult(status -> {
                            outboxRepository.markOutboxEventProcessed(event.id());
                        });

                        successCounter.increment();
                        io.micrometer.core.instrument.Metrics.counter("business.events.processed", "type", event.eventType()).increment();
                        logger.info("Successfully processed outbox event ID [{}].", event.id());
                    } catch (com.inventoryapp.core.event.KafkaPublishException e) {
                        logger.error("Kafka publish error (circuit breaker open or timeout) for outbox event ID [{}]. Aborting batch.", event.id(), e);
                        
                        // Collect IDs of this event and all remaining events in the batch
                        int currentIndex = events.indexOf(event);
                        List<Long> remainingIds = events.subList(currentIndex, events.size()).stream().map(OutboxEvent::id).toList();
                        
                        transactionTemplate.executeWithoutResult(status -> {
                            outboxRepository.updateOutboxEventsStatus(remainingIds, "PENDING");
                        });
                        break; // Stop processing the rest of the batch
                    } catch (Exception e) {
                        logger.error("Failed to publish outbox event ID [{}]: {}", event.id(), e.getMessage());
                        failedCounter.increment();

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

    @Scheduled(cron = "0 0 0 * * ?")
    public void purgeOldData() {
        logger.info("Purging old stock transactions, failed outbox events, and idempotency keys...");
        try {
            transactionRepository.purgeOldTransactions(30);

            Instant cutoff = Instant.now().minus(Duration.ofDays(30));
            outboxRepository.purgeFailedEvents(cutoff);
            idempotencyRepository.purgeOldKeys(cutoff);
        } catch (Exception e) {
            logger.error("Failed to purge old data: {}", e.getMessage());
        }
    }
}
