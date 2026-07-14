package com.example.inventory.repository;

import com.example.inventory.dto.CursorResponse;
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
        Item inserted = repository.insert("SKU-100", "Item 100", 5, new BigDecimal("10.50"), "Electronics", 5, "test-operator");
        assertNotNull(inserted.id());
        assertEquals("SKU-100", inserted.sku());

        Optional<Item> found = repository.findById(inserted.id());
        assertTrue(found.isPresent());
        assertEquals("Item 100", found.get().name());
        assertFalse(found.get().archived());
    }

    @Test
    void testFindAllPaginated() {
        repository.insert("SKU-A", "Apple", 10, new BigDecimal("1.50"), "Fruit", 5, "test-operator");
        repository.insert("SKU-B", "Banana", 20, new BigDecimal("0.80"), "Fruit", 5, "test-operator");
        repository.insert("SKU-C", "Cherry", 5, new BigDecimal("3.00"), "Fruit", 5, "test-operator");

        CursorResponse<Item> page = repository.findAll(null, 2, null, "Fruit");
        assertEquals(2, page.items().size());
        assertEquals("Apple", page.items().get(0).name());
        assertEquals("Banana", page.items().get(1).name());

        CursorResponse<Item> page2 = repository.findAll(null, 2, "cherry", null);
        assertEquals(1, page2.items().size());
        assertEquals("Cherry", page2.items().get(0).name());
    }

    @Test
    void testAdjustQuantity() {
        Item inserted = repository.insert("SKU-100", "Item 100", 10, new BigDecimal("10.50"), "Electronics", 5, "test-operator");
        
        Optional<Item> adjusted = repository.adjustQuantity(inserted.id(), 5, "test-operator");
        assertTrue(adjusted.isPresent());
        assertEquals(15, adjusted.get().quantity());

        assertThrows(IllegalArgumentException.class, () -> {
            repository.adjustQuantity(inserted.id(), -20, "test-operator"); // Quantity cannot go below zero
        });
    }

    @Test
    void testBuildReport() {
        repository.insert("SKU-1", "Item 1", 2, new BigDecimal("10.00"), "Electronics", 5, "test-operator");
        repository.insert("SKU-2", "Item 2", 10, new BigDecimal("5.00"), "Groceries", 5, "test-operator");

        InventoryReport report = repository.buildReport(5);
        assertEquals(2, report.distinctItems());
        assertEquals(12, report.totalUnitsOnHand());
        assertEquals(0, new BigDecimal("70.00").compareTo(report.totalInventoryValue()));
        assertEquals(1, report.lowStockItemCount());
        assertEquals("Item 1", report.lowStockItems().get(0).name());
    }

    @Test
    void testSoftDelete() {
        Item inserted = repository.insert("SKU-DEL", "To Delete", 5, new BigDecimal("4.00"), "General", 5, "test-operator");
        assertNotNull(inserted.id());

        // Delete the item (soft delete)
        assertTrue(repository.deleteById(inserted.id(), "test-operator"));

        // Verify it is not found by regular query
        Optional<Item> found = repository.findById(inserted.id());
        assertFalse(found.isPresent());

        // Verify it is found in the database by direct JDBC check as archived
        Boolean isArchived = jdbcTemplate.queryForObject(
                "SELECT archived FROM items WHERE id = ?",
                Boolean.class,
                inserted.id()
        );
        assertTrue(isArchived);

        // Verify that trying to insert the same SKU un-archives and updates it
        Item readded = repository.insert("SKU-DEL", "Reactivated Name", 20, new BigDecimal("5.00"), "Fruit", 5, "test-operator");
        assertEquals(inserted.id(), readded.id());
        assertEquals("Reactivated Name", readded.name());
        assertEquals(20, readded.quantity());
        assertFalse(readded.archived());
    }

    @Test
    void testAuditLogging() {
        Item item = repository.insert("SKU-LOG", "Logger Item", 10, new BigDecimal("2.50"), "General", 5, "test-operator");
        
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE sku = 'SKU-LOG'",
                Integer.class
        );
        assertEquals(1, count);
        
        java.util.Map<String, Object> log = jdbcTemplate.queryForMap(
                "SELECT * FROM stock_transactions WHERE sku = 'SKU-LOG' AND reason = 'INITIAL_STOCK'"
        );
        assertEquals(10, ((Number) log.get("delta")).intValue());
        assertEquals(0, ((Number) log.get("previous_quantity")).intValue());
        assertEquals(10, ((Number) log.get("new_quantity")).intValue());

        repository.adjustQuantity(item.id(), 5, "test-operator");
        count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE sku = 'SKU-LOG'",
                Integer.class
        );
        assertEquals(2, count);
        
        log = jdbcTemplate.queryForMap(
                "SELECT * FROM stock_transactions WHERE sku = 'SKU-LOG' AND reason = 'RESTOCK'"
        );
        assertEquals(5, ((Number) log.get("delta")).intValue());
        assertEquals(10, ((Number) log.get("previous_quantity")).intValue());
        assertEquals(15, ((Number) log.get("new_quantity")).intValue());

        repository.deleteById(item.id(), "test-operator");
        count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE sku = 'SKU-LOG'",
                Integer.class
        );
        assertEquals(3, count);
        
        log = jdbcTemplate.queryForMap(
                "SELECT * FROM stock_transactions WHERE sku = 'SKU-LOG' AND reason = 'ARCHIVED'"
        );
        assertEquals(-15, ((Number) log.get("delta")).intValue());
        assertEquals(15, ((Number) log.get("previous_quantity")).intValue());
        assertEquals(0, ((Number) log.get("new_quantity")).intValue());

        repository.insert("SKU-LOG", "Reactivated Logger", 25, new BigDecimal("2.50"), "General", 5, "test-operator");
        count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE sku = 'SKU-LOG'",
                Integer.class
        );
        assertEquals(4, count);
        
        log = jdbcTemplate.queryForMap(
                "SELECT * FROM stock_transactions WHERE sku = 'SKU-LOG' AND reason = 'REACTIVATE'"
        );
        assertEquals(25, ((Number) log.get("delta")).intValue());
        assertEquals(0, ((Number) log.get("previous_quantity")).intValue());
        assertEquals(25, ((Number) log.get("new_quantity")).intValue());
    }

    @Test
    void testOutboxRetryAndDlq() {
        repository.insertOutboxEvent("aggregate", "123", "event-type", "payload");
        java.util.List<com.example.inventory.model.OutboxEvent> events = repository.findPendingOutboxEvents(java.time.Instant.now().plusSeconds(60));
        assertEquals(1, events.size());
        assertEquals(0, events.get(0).retryCount());
        assertEquals("PENDING", events.get(0).status());

        repository.incrementOutboxEventRetry(events.get(0).id(), 1, "PENDING");
        events = repository.findPendingOutboxEvents(java.time.Instant.now().plusSeconds(60));
        assertEquals(1, events.get(0).retryCount());
        assertEquals("PENDING", events.get(0).status());

        repository.incrementOutboxEventRetry(events.get(0).id(), 5, "FAILED");
        events = repository.findPendingOutboxEvents(java.time.Instant.now().plusSeconds(60));
        assertEquals(0, events.size());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = '123'",
                String.class
        );
        assertEquals("FAILED", status);
    }
}
