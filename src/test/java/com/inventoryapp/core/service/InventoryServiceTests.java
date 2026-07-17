package com.inventoryapp.core.service;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.event.LowStockEvent;
import com.inventoryapp.core.model.InventoryReport;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.repository.IdempotencyRepository;
import com.inventoryapp.core.repository.ItemRepository;
import com.inventoryapp.core.repository.StockTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryServiceTests {

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

    @InjectMocks
    private InventoryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }


    @Test
    void testListItemsPaginated() {
        Item item = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        CursorResponse<Item> cursorResponse = new CursorResponse<>(List.of(item), 1L);
        when(itemRepository.findAll(0L, 10, "SKU1", "Category")).thenReturn(cursorResponse);

        CursorResponse<Item> result = service.listItems(0L, 10, "SKU1", "Category");
        assertEquals(1L, result.nextCursor());
        assertEquals(1, result.items().size());
        assertEquals("SKU1", result.items().get(0).sku());
    }

    @Test
    void testAddItem() {
        CreateItemRequest request = new CreateItemRequest("SKU1", "Name1", 10, BigDecimal.TEN, "Category", 5);
        Item item = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(itemRepository.insert(eq("SKU1"), eq("Name1"), eq(10), eq(BigDecimal.TEN), eq("Category"), eq(5))).thenReturn(item);

        Item result = service.addItem(request, "operator");
        assertNotNull(result);
        assertEquals("SKU1", result.sku());
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(10), eq(0), eq(10), eq("INITIAL_STOCK"), eq("operator"));
    }

    @Test
    void testRemoveItem() {
        Item item = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(item));
        when(itemRepository.deleteById(1L)).thenReturn(true);
        assertTrue(service.removeItem(1L, "operator"));
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(-10), eq(10), eq(0), eq("ARCHIVED"), eq("operator"));

        when(itemRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());
        assertFalse(service.removeItem(2L, "operator"));
    }

    @Test
    void testAdjustStock() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 15, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 15)).thenReturn(afterItem);

        Optional<Item> result = service.adjustStock(1L, request, "operator", null);
        assertTrue(result.isPresent());
        assertEquals(15, result.get().quantity());
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(5), eq(10), eq(15), eq("RESTOCK"), eq("operator"));
    }

    @Test
    void testAdjustStockNoEventIfAlreadyBelowThreshold() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-1);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 3, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 2, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 2)).thenReturn(afterItem);

        service.adjustStock(1L, request, "operator", null);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testAdjustStockFiresEventOnThresholdCross() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-2);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 6, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 4, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 4)).thenReturn(afterItem);

        service.adjustStock(1L, request, "operator", null);
        verify(eventPublisher, times(1)).publishEvent(any(LowStockEvent.class));
    }

    @Test
    void testReport() {
        InventoryReport report = new InventoryReport(1, 10, BigDecimal.TEN, 0, Collections.emptyList());
        when(itemRepository.buildReport(5)).thenReturn(report);

        InventoryReport result = service.report(5);
        assertNotNull(result);
        assertEquals(1, result.distinctItems());
        assertEquals(10, result.totalUnitsOnHand());
    }
}
