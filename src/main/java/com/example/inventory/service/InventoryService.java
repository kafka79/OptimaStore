package com.example.inventory.service;

import com.example.inventory.dto.AdjustQuantityRequest;
import com.example.inventory.dto.CreateItemRequest;
import com.example.inventory.dto.PageResponse;
import com.example.inventory.event.LowStockEvent;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import com.example.inventory.repository.InventoryJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private final InventoryJdbcRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public InventoryService(InventoryJdbcRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<Item> listItems() {
        logger.info("Fetching all items without pagination");
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public PageResponse<Item> listItems(int page, int size, String search, String category) {
        logger.info("Fetching items page={}, size={}, search={}, category={}", page, size, search, category);
        return repository.findAll(page, size, search, category);
    }

    public Item addItem(CreateItemRequest request) {
        logger.info("Adding new item with SKU: {}", request.sku());
        Item item = repository.insert(
                request.sku(),
                request.name(),
                request.quantity(),
                request.unitPrice(),
                request.category()
        );
        if (item.quantity() < 5) {
            logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", item.sku(), item.quantity());
            eventPublisher.publishEvent(new LowStockEvent(this, item));
        }
        return item;
    }

    public boolean removeItem(long id) {
        logger.info("Removing item with ID: {}", id);
        return repository.deleteById(id);
    }

    public Optional<Item> adjustStock(long id, AdjustQuantityRequest request) {
        logger.info("Adjusting stock for ID {}: delta={}", id, request.delta());
        Optional<Item> result = repository.adjustQuantity(id, request.delta());
        if (result.isPresent()) {
            Item item = result.get();
            if (item.quantity() < 5) {
                logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", item.sku(), item.quantity());
                eventPublisher.publishEvent(new LowStockEvent(this, item));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public InventoryReport report(int lowStockThreshold) {
        logger.info("Generating report with threshold: {}", lowStockThreshold);
        int threshold = Math.max(0, lowStockThreshold);
        return repository.buildReport(threshold);
    }

    @Transactional(readOnly = true)
    public void streamAllItems(PrintWriter writer) {
        logger.info("Streaming all items as CSV");
        repository.streamAll(writer);
    }

    @Transactional(readOnly = true)
    public List<String> getCategories() {
        logger.info("Fetching distinct active categories");
        return repository.findDistinctCategories();
    }
}
