package com.inventoryapp.core.service;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.event.LowStockEvent;
import com.inventoryapp.core.exception.IdempotencyException;
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

    private Item sampleItem;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        sampleItem = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
    }

    @Test
    void testListItemsPaginated() {
        CursorResponse<Item> cursorResponse = new CursorResponse<>(List.of(sampleItem), 1L);
        when(itemRepository.findAll(0L, 10, "SKU1", "Category")).thenReturn(cursorResponse);

        CursorResponse<Item> result = service.listItems(0L, 10, "SKU1", "Category");
        assertEquals(1L, result.nextCursor());
        assertEquals(1, result.items().size());
        assertEquals("SKU1", result.items().get(0).sku());
    }

    @Test
    void testListItemsWithNullParams() {
        CursorResponse<Item> cursorResponse = new CursorResponse<>(List.of(sampleItem), null);
        when(itemRepository.findAll(null, 10, null, null)).thenReturn(cursorResponse);

        CursorResponse<Item> result = service.listItems(null, 10, null, null);
        assertEquals(1, result.items().size());
        assertNull(result.nextCursor());
    }

    @Test
    void testAddItem() {
        CreateItemRequest request = new CreateItemRequest("SKU1", "Name1", 10, BigDecimal.TEN, "Category", 5);
        when(itemRepository.insert(eq("SKU1"), eq("Name1"), eq(10), eq(BigDecimal.TEN), eq("Category"), eq(5))).thenReturn(sampleItem);

        Item result = service.addItem(request, "operator");
        assertNotNull(result);
        assertEquals("SKU1", result.sku());
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(10), eq(0), eq(10), eq("INITIAL_STOCK"), eq("operator"));
    }

    @Test
    void testAddItemTriggersLowStockEvent() {
        Item lowItem = new Item(2L, "SKU2", "Low", 2, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
        CreateItemRequest request = new CreateItemRequest("SKU2", "Low", 2, BigDecimal.TEN, "Category", 5);
        when(itemRepository.insert(eq("SKU2"), any(), eq(2), any(), any(), eq(5))).thenReturn(lowItem);

        service.addItem(request, "operator");
        verify(eventPublisher, times(1)).publishEvent(any(LowStockEvent.class));
    }

    @Test
    void testAddItemNoLowStockEventIfAboveThreshold() {
        when(itemRepository.insert(eq("SKU1"), any(), eq(10), any(), any(), eq(5))).thenReturn(sampleItem);

        service.addItem(requestForInsert(), "operator");
        verify(eventPublisher, never()).publishEvent(any(LowStockEvent.class));
    }

    private CreateItemRequest requestForInsert() {
        return new CreateItemRequest("SKU1", "Name1", 10, BigDecimal.TEN, "Category", 5);
    }

    @Test
    void testRemoveItem() {
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sampleItem));
        when(itemRepository.deleteById(1L)).thenReturn(true);
        assertTrue(service.removeItem(1L, "operator"));
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(-10), eq(10), eq(0), eq("ARCHIVED"), eq("operator"));
    }

    @Test
    void testRemoveItemNotFound() {
        when(itemRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());
        assertFalse(service.removeItem(2L, "operator"));
        verify(transactionRepository, never()).logTransaction(anyLong(), any(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void testAdjustStock() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        Item beforeItem = sampleItem;
        Item afterItem = new Item(1L, "SKU1", "Name1", 15, BigDecimal.TEN, "Category", Instant.now(), false, 5, 1);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 15)).thenReturn(afterItem);

        Optional<Item> result = service.adjustStock(1L, request, "operator", null);
        assertTrue(result.isPresent());
        assertEquals(15, result.get().quantity());
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(5), eq(10), eq(15), eq("RESTOCK"), eq("operator"));
    }

    @Test
    void testAdjustStockDispatch() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-3);
        Item beforeItem = sampleItem;
        Item afterItem = new Item(1L, "SKU1", "Name1", 7, BigDecimal.TEN, "Category", Instant.now(), false, 5, 1);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 7)).thenReturn(afterItem);

        Optional<Item> result = service.adjustStock(1L, request, "operator", null);
        assertTrue(result.isPresent());
        assertEquals(7, result.get().quantity());
        verify(transactionRepository).logTransaction(eq(1L), eq("SKU1"), eq(-3), eq(10), eq(7), eq("DISPATCH"), eq("operator"));
    }

    @Test
    void testAdjustStockInsufficientStock() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-20);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sampleItem));

        assertThrows(IllegalArgumentException.class, () -> service.adjustStock(1L, request, "operator", null));
    }

    @Test
    void testAdjustStockNoEventIfAlreadyBelowThreshold() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-1);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 3, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
        Item afterItem = new Item(1L, "SKU1", "Name1", 2, BigDecimal.TEN, "Category", Instant.now(), false, 5, 1);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 2)).thenReturn(afterItem);

        service.adjustStock(1L, request, "operator", null);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testAdjustStockFiresEventOnThresholdCross() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-2);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 6, BigDecimal.TEN, "Category", Instant.now(), false, 5, 0);
        Item afterItem = new Item(1L, "SKU1", "Name1", 4, BigDecimal.TEN, "Category", Instant.now(), false, 5, 1);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(beforeItem));
        when(itemRepository.updateQuantity(1L, 4)).thenReturn(afterItem);

        service.adjustStock(1L, request, "operator", null);
        verify(eventPublisher, times(1)).publishEvent(any(LowStockEvent.class));
    }

    @Test
    void testAdjustStockWithIdempotencyKeyFirstRequest() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 15, BigDecimal.TEN, "Category", Instant.now(), false, 5, 1);
        when(idempotencyRepository.insertIdempotencyKey("key-123")).thenReturn(true);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sampleItem));
        when(itemRepository.updateQuantity(1L, 15)).thenReturn(afterItem);

        Optional<Item> result = service.adjustStock(1L, request, "operator", "key-123");

        assertTrue(result.isPresent());
        assertEquals(15, result.get().quantity());
        verify(idempotencyRepository).insertIdempotencyKey("key-123");
        verify(idempotencyRepository).updateIdempotencyResponse(eq("key-123"), any());
    }

    @Test
    void testAdjustStockWithIdempotencyKeyDuplicateReturnsCached() throws Exception {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        when(idempotencyRepository.insertIdempotencyKey("key-123")).thenReturn(false);
        when(idempotencyRepository.getIdempotencyResponseForUpdate("key-123")).thenReturn(Optional.of("{\"id\":1}"));
        when(objectMapper.readValue("{\"id\":1}", Item.class)).thenReturn(sampleItem);

        Optional<Item> result = service.adjustStock(1L, request, "operator", "key-123");

        assertTrue(result.isPresent());
        assertEquals("SKU1", result.get().sku());
        verify(itemRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void testAdjustStockWithIdempotencyKeyReturnsNotFound() throws Exception {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        when(idempotencyRepository.insertIdempotencyKey("key-123")).thenReturn(false);
        when(idempotencyRepository.getIdempotencyResponseForUpdate("key-123")).thenReturn(Optional.of("NOT_FOUND"));

        Optional<Item> result = service.adjustStock(1L, request, "operator", "key-123");

        assertFalse(result.isPresent());
        verify(itemRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void testAdjustStockWithIdempotencyKeyTransactionFailureDoesNotDeleteKeyManually() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-100);
        when(idempotencyRepository.insertIdempotencyKey("key-123")).thenReturn(true);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sampleItem));

        assertThrows(IllegalArgumentException.class, () -> service.adjustStock(1L, request, "operator", "key-123"));

        verify(idempotencyRepository, never()).deleteIdempotencyKey(anyString());
    }

    @Test
    void testUpdateItem() {
        com.inventoryapp.core.dto.UpdateItemRequest request =
                new com.inventoryapp.core.dto.UpdateItemRequest("Updated", new BigDecimal("15.00"), "Home");
        Item updated = new Item(1L, "SKU1", "Updated", 10, new BigDecimal("15.00"), "Home", Instant.now(), false, 5, 1);
        when(itemRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(sampleItem));
        when(itemRepository.updateItem(1L, "Updated", new BigDecimal("15.00"), "Home")).thenReturn(updated);

        Optional<Item> result = service.updateItem(1L, request);
        assertTrue(result.isPresent());
        assertEquals("Updated", result.get().name());
    }

    @Test
    void testUpdateItemNotFound() {
        com.inventoryapp.core.dto.UpdateItemRequest request =
                new com.inventoryapp.core.dto.UpdateItemRequest("Updated", new BigDecimal("15.00"), "Home");
        when(itemRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        Optional<Item> result = service.updateItem(99L, request);
        assertFalse(result.isPresent());
        verify(itemRepository, never()).updateItem(anyLong(), any(), any(), any());
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

    @Test
    void testGetCategories() {
        when(itemRepository.findDistinctCategories()).thenReturn(List.of("A", "B"));
        assertEquals(2, service.getCategories().size());
    }

    @Test
    void testExportToWriter() {
        CursorResponse<Item> page = new CursorResponse<>(List.of(sampleItem), null);
        when(itemRepository.findAll(null, 100, null, null)).thenReturn(page);

        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        service.exportToWriter(pw, null, null);
        pw.flush();

        assertTrue(sw.toString().contains("SKU1"));
    }

    @Test
    void testAdjustStockItemNotFound() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        when(itemRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        Optional<Item> result = service.adjustStock(99L, request, "operator", null);
        assertFalse(result.isPresent());
    }
}
