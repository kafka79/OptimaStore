package com.example.inventory.repository;

import com.example.inventory.dto.PageResponse;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class InventoryJdbcRepositoryTests {

    @Autowired
    private InventoryJdbcRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM items");
    }

    @Test
    void testInsertAndFindById() {
        Item inserted = repository.insert("SKU-100", "Item 100", 5, new BigDecimal("10.50"), "Electronics");
        assertNotNull(inserted.id());
        assertEquals("SKU-100", inserted.sku());

        Optional<Item> found = repository.findById(inserted.id());
        assertTrue(found.isPresent());
        assertEquals("Item 100", found.get().name());
    }

    @Test
    void testFindAllPaginated() {
        repository.insert("SKU-A", "Apple", 10, new BigDecimal("1.50"), "Fruit");
        repository.insert("SKU-B", "Banana", 20, new BigDecimal("0.80"), "Fruit");
        repository.insert("SKU-C", "Cherry", 5, new BigDecimal("3.00"), "Fruit");

        PageResponse<Item> page = repository.findAll(0, 2, null, "Fruit");
        assertEquals(3, page.totalElements());
        assertEquals(2, page.content().size());
        assertEquals("Apple", page.content().get(0).name());
        assertEquals("Banana", page.content().get(1).name());

        PageResponse<Item> page2 = repository.findAll(0, 2, "cherry", null);
        assertEquals(1, page2.totalElements());
        assertEquals(1, page2.content().size());
        assertEquals("Cherry", page2.content().get(0).name());
    }

    @Test
    void testAdjustQuantity() {
        Item inserted = repository.insert("SKU-100", "Item 100", 10, new BigDecimal("10.50"), "Electronics");
        
        Optional<Item> adjusted = repository.adjustQuantity(inserted.id(), 5);
        assertTrue(adjusted.isPresent());
        assertEquals(15, adjusted.get().quantity());

        Optional<Item> adjustedNegative = repository.adjustQuantity(inserted.id(), -20);
        assertFalse(adjustedNegative.isPresent()); // Quantity cannot go below zero
    }

    @Test
    void testBuildReport() {
        repository.insert("SKU-1", "Item 1", 2, new BigDecimal("10.00"), "Electronics");
        repository.insert("SKU-2", "Item 2", 10, new BigDecimal("5.00"), "Groceries");

        InventoryReport report = repository.buildReport(5);
        assertEquals(2, report.distinctItems());
        assertEquals(12, report.totalUnitsOnHand());
        assertEquals(0, new BigDecimal("70.00").compareTo(report.totalInventoryValue()));
        assertEquals(1, report.lowStockItemCount());
        assertEquals("Item 1", report.lowStockItems().get(0).name());
    }
}
