package com.example.inventory.service;

import com.example.inventory.dto.AdjustQuantityRequest;
import com.example.inventory.dto.CreateItemRequest;
import com.example.inventory.dto.CursorResponse;
import com.example.inventory.event.LowStockEvent;
import com.example.inventory.exception.IdempotencyException;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import com.example.inventory.repository.InventoryJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
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
    public CursorResponse<Item> listItems(Long lastId, int size, String search, String category) {
        logger.info("Fetching items lastId={}, size={}, search={}, category={}", lastId, size, search, category);
        return repository.findAll(lastId, size, search, category);
    }

    public Item addItem(CreateItemRequest request, String operator) {
        logger.info("Adding new item with SKU: {}", request.sku());
        Item item = repository.insert(
                request.sku(),
                request.name(),
                request.quantity(),
                request.unitPrice(),
                request.category(),
                request.lowStockThreshold(),
                operator
        );
        if (item.quantity() < item.lowStockThreshold()) {
            logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", item.sku(), item.quantity());
            eventPublisher.publishEvent(new LowStockEvent(this, item));
        }
        return item;
    }

    public boolean removeItem(long id, String operator) {
        logger.info("Removing item with ID: {}", id);
        return repository.deleteById(id, operator);
    }

    public Optional<Item> adjustStock(long id, AdjustQuantityRequest request, String operator, String idempotencyKey) {
        logger.info("Adjusting stock for ID {}: delta={}, key={}", id, request.delta(), idempotencyKey);
        
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            boolean isNew = repository.insertIdempotencyKey(idempotencyKey);
            if (!isNew) {
                logger.warn("Idempotency key already processed: {}", idempotencyKey);
                throw new IdempotencyException("Request already processed for idempotency key: " + idempotencyKey);
            }
        }

        Optional<Item> result = repository.adjustQuantity(id, request.delta(), operator);
        if (result.isPresent()) {
            Item after = result.get();
            int beforeQty = after.quantity() - request.delta();
            if (beforeQty >= after.lowStockThreshold() && after.quantity() < after.lowStockThreshold()) {
                logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", after.sku(), after.quantity());
                eventPublisher.publishEvent(new LowStockEvent(this, after));
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
    public List<String> getCategories() {
        logger.info("Fetching distinct active categories");
        return repository.findDistinctCategories();
    }

    @Transactional(readOnly = true)
    public void exportToWriter(PrintWriter writer, String search, String category) {
        logger.info("Streaming inventory export directly to writer");
        repository.streamAll(writer, search, category);
    }
}
