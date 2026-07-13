package com.example.inventory.service;

import com.example.inventory.dto.AdjustQuantityRequest;
import com.example.inventory.dto.CreateItemRequest;
import com.example.inventory.dto.PageResponse;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import com.example.inventory.repository.InventoryJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryServiceTests {

    @Mock
    private InventoryJdbcRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private InventoryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testListItemsAll() {
        Item item = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(repository.findAll()).thenReturn(List.of(item));

        List<Item> result = service.listItems();
        assertEquals(1, result.size());
        assertEquals("SKU1", result.get(0).sku());
    }

    @Test
    void testListItemsPaginated() {
        Item item = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        PageResponse<Item> pageResponse = new PageResponse<>(List.of(item), 0, 10, 1);
        when(repository.findAll(0, 10, "SKU1", "Category")).thenReturn(pageResponse);

        PageResponse<Item> result = service.listItems(0, 10, "SKU1", "Category");
        assertEquals(1, result.totalElements());
        assertEquals(1, result.content().size());
        assertEquals("SKU1", result.content().get(0).sku());
    }

    @Test
    void testAddItem() {
        CreateItemRequest request = new CreateItemRequest("SKU1", "Name1", 10, BigDecimal.TEN, "Category", 5);
        Item item = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(repository.insert(eq("SKU1"), eq("Name1"), eq(10), eq(BigDecimal.TEN), eq("Category"), eq(5), anyString())).thenReturn(item);

        Item result = service.addItem(request, "operator");
        assertNotNull(result);
        assertEquals("SKU1", result.sku());
    }

    @Test
    void testRemoveItem() {
        when(repository.deleteById(eq(1L), anyString())).thenReturn(true);
        assertTrue(service.removeItem(1L, "operator"));

        when(repository.deleteById(eq(2L), anyString())).thenReturn(false);
        assertFalse(service.removeItem(2L, "operator"));
    }

    @Test
    void testAdjustStock() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 10, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 15, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(repository.adjustQuantity(eq(1L), eq(5), anyString())).thenReturn(Optional.of(afterItem));

        Optional<Item> result = service.adjustStock(1L, request, "operator");
        assertTrue(result.isPresent());
        assertEquals(15, result.get().quantity());
    }

    @Test
    void testAdjustStockNoEventIfAlreadyBelowThreshold() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-1);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 3, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 2, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(repository.adjustQuantity(eq(1L), eq(-1), anyString())).thenReturn(Optional.of(afterItem));

        service.adjustStock(1L, request, "operator");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testAdjustStockFiresEventOnThresholdCross() {
        AdjustQuantityRequest request = new AdjustQuantityRequest(-2);
        Item beforeItem = new Item(1L, "SKU1", "Name1", 6, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        Item afterItem = new Item(1L, "SKU1", "Name1", 4, BigDecimal.TEN, "Category", Instant.now(), false, 5);
        when(repository.adjustQuantity(eq(1L), eq(-2), anyString())).thenReturn(Optional.of(afterItem));

        service.adjustStock(1L, request, "operator");
        verify(eventPublisher, times(1)).publishEvent(any(com.example.inventory.event.LowStockEvent.class));
    }

    @Test
    void testReport() {
        InventoryReport report = new InventoryReport(1, 10, BigDecimal.TEN, 0, Collections.emptyList());
        when(repository.buildReport(5)).thenReturn(report);

        InventoryReport result = service.report(5);
        assertNotNull(result);
        assertEquals(1, result.distinctItems());
        assertEquals(10, result.totalUnitsOnHand());
    }
}
