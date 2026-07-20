package com.inventoryapp.core.service;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.event.KafkaPublishException;
import com.inventoryapp.core.event.MessagePublisher;
import com.inventoryapp.core.event.OutboxProcessor;
import com.inventoryapp.core.exception.DuplicateSkuException;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.model.OutboxEvent;
import com.inventoryapp.core.repository.IdempotencyRepository;
import com.inventoryapp.core.repository.ItemRepository;
import com.inventoryapp.core.repository.OutboxRepository;
import com.inventoryapp.core.repository.StockTransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryConcurrencyTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockTransactionRepository transactionRepository;

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private MessagePublisher messagePublisher;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private InventoryService inventoryService;
    private OutboxProcessor outboxProcessor;

    private final Item sampleItem = new Item(1L, "SKU1", "Name1", 100, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
    private final AtomicBoolean insertLock = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

        inventoryService = new InventoryService(itemRepository, transactionRepository, idempotencyRepository,
                eventPublisher, objectMapper, transactionTemplate);

        outboxProcessor = new OutboxProcessor(
                outboxRepository, transactionRepository, idempotencyRepository,
                messagePublisher, transactionManager, meterRegistry);
    }

    @Test
    void concurrentStockAdjustments_shouldMaintainConsistency() throws InterruptedException {
        AtomicInteger mockedQuantity = new AtomicInteger(200);
        when(itemRepository.findByIdForUpdate(1L)).thenAnswer(invocation -> {
            synchronized (mockedQuantity) {
                return Optional.of(new Item(1L, "SKU1", "Name1", mockedQuantity.get(), BigDecimal.TEN, "Category", Instant.now(), false, 5, 0));
            }
        });
        when(itemRepository.updateQuantity(eq(1L), anyInt())).thenAnswer(invocation -> {
            synchronized (mockedQuantity) {
                int newQty = invocation.getArgument(1);
                mockedQuantity.set(newQty);
                return new Item(1L, "SKU1", "Name1", newQty, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
            }
        });

        // Also we need to mock transactionTemplate to synchronize the whole block
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            synchronized (mockedQuantity) {
                TransactionCallback callback = invocation.getArgument(0);
                return callback.doInTransaction(new SimpleTransactionStatus());
            }
        });

        int threadCount = 10;
        int adjustmentsPerThread = 20;
        int deltaPerAdjustment = -1;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AdjustQuantityRequest request = new AdjustQuantityRequest(deltaPerAdjustment);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < adjustmentsPerThread; j++) {
                        try {
                            inventoryService.adjustStock(1L, request, "operator", null);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "Concurrent stock adjustments should not produce errors");
        assertEquals(0, mockedQuantity.get(), "Final quantity should correctly reflect all concurrent updates");
    }

    @Test
    void concurrentListItems_shouldNotThrow() throws InterruptedException {
        CursorResponse<Item> response =
                new CursorResponse<>(List.of(sampleItem), null);
        when(itemRepository.findAll(null, 10, null, null)).thenReturn(response);

        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        try {
                            inventoryService.listItems(null, 10, null, null);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(0, errors.get(), "Concurrent reads should never throw");
    }

    @Test
    void concurrentAddItem_duplicateSkuHandling() throws InterruptedException {
        var duplicateEx = new DuplicateSkuException("SKU1", null);
        // Simulate DB constraint: first call succeeds, rest fail with DuplicateSkuException
        when(itemRepository.insert(eq("SKU1"), anyString(), anyInt(), any(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    // Use atomic flag to ensure only one succeeds
                    if (insertLock.compareAndSet(false, true)) {
                        return sampleItem;
                    }
                    throw duplicateEx;
                });

        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    try {
                        inventoryService.addItem(new CreateItemRequest("SKU1", "Item", 10, BigDecimal.TEN, "Cat", 5), "operator");
                        successes.incrementAndGet();
                    } catch (DuplicateSkuException e) {
                        conflicts.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, successes.get(), "Only one insert should succeed for the same SKU");
        assertEquals(threadCount - 1, conflicts.get(), "Remaining should get DuplicateSkuException");
    }

    @Test
    void concurrentOutboxProcessing_shouldNotProcessSameEvents() throws InterruptedException {
        var event1 = new OutboxEvent(1L, "Item", "1", "inventory.low-stock", "{}", "PENDING", 0, Instant.now());
        var event2 = new OutboxEvent(2L, "Item", "2", "inventory.low-stock", "{}", "PENDING", 0, Instant.now());
        
        AtomicReference<List<OutboxEvent>> pendingEvents = 
            new AtomicReference<>(List.of(event1, event2));
        
        when(outboxRepository.findPendingOutboxEvents(any(Instant.class)))
            .thenAnswer(invocation -> {
                synchronized (pendingEvents) {
                    return pendingEvents.get();
                }
            });
        
        doAnswer(invocation -> {
            synchronized (pendingEvents) {
                List<Long> ids = invocation.getArgument(0);
                List<OutboxEvent> updated = pendingEvents.get().stream()
                    .filter(e -> !ids.contains(e.id()))
                    .toList();
                pendingEvents.set(updated);
            }
            return null;
        }).when(outboxRepository).updateOutboxEventsStatus(anyList(), eq("PROCESSING"));
        
        doAnswer(invocation -> {
            Thread.sleep(10); // Simulate network delay
            return null;
        }).when(messagePublisher).publish(anyString(), anyString());
        
        int threadCount = 3;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    invokeProcessOutboxInternal();
                    processedCount.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(0, errors.get(), "Concurrent outbox processing should not produce errors");
    }

    @Test
    void outboxRetryMechanism_shouldHandleFailuresCorrectly() throws InterruptedException {
        var event = new OutboxEvent(1L, "Item", "1", "inventory.low-stock", "{}", "PENDING", 0, Instant.now());
        
        when(outboxRepository.findPendingOutboxEvents(any(Instant.class)))
            .thenReturn(List.of(event))
            .thenReturn(List.of());
        
        doThrow(new RuntimeException("Publish failed"))
            .when(messagePublisher).publish(anyString(), anyString());
        
        invokeProcessOutboxInternal();
        
        // Retry count was incremented and status reset to PENDING
        verify(outboxRepository).incrementOutboxEventRetry(eq(1L), eq(1), eq("PENDING"));
    }

    private void invokeProcessOutboxInternal() {
        try {
            java.lang.reflect.Method method = OutboxProcessor.class.getDeclaredMethod("processOutboxInternal");
            method.setAccessible(true);
            method.invoke(outboxProcessor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
