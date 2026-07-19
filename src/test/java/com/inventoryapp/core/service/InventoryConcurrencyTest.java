package com.inventoryapp.core.service;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.repository.IdempotencyRepository;
import com.inventoryapp.core.repository.ItemRepository;
import com.inventoryapp.core.repository.StockTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

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

    private InventoryService service;

    private final Item sampleItem = new Item(1L, "SKU1", "Name1", 100, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
    private final java.util.concurrent.atomic.AtomicBoolean insertLock = new java.util.concurrent.atomic.AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

        service = new InventoryService(itemRepository, transactionRepository, idempotencyRepository,
                eventPublisher, objectMapper, transactionTemplate);
    }

    @Test
    void concurrentStockAdjustments_shouldMaintainConsistency() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger mockedQuantity = new java.util.concurrent.atomic.AtomicInteger(200);
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
                            service.adjustStock(1L, request, "operator", null);
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
        com.inventoryapp.core.dto.CursorResponse<Item> response =
                new com.inventoryapp.core.dto.CursorResponse<>(java.util.List.of(sampleItem), null);
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
                            service.listItems(null, 10, null, null);
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
        var duplicateEx = new com.inventoryapp.core.exception.DuplicateSkuException("SKU1", null);
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
                        service.addItem(new CreateItemRequest("SKU1", "Item", 10, BigDecimal.TEN, "Cat", 5), "operator");
                        successes.incrementAndGet();
                    } catch (com.inventoryapp.core.exception.DuplicateSkuException e) {
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
}
