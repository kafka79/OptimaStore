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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

@Service
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private final InventoryJdbcRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    
    @Autowired
    @Lazy
    private InventoryService self;

    public InventoryService(InventoryJdbcRepository repository, ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CursorResponse<Item> listItems(Long lastId, int size, String search, String category) {
        logger.info("Fetching items lastId={}, size={}, search={}, category={}", lastId, size, search, category);
        return repository.findAll(lastId, size, search, category);
    }

    @Transactional
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

    @Transactional
    public boolean removeItem(long id, String operator) {
        logger.info("Removing item with ID: {}", id);
        return repository.deleteById(id, operator);
    }

    public Optional<Item> adjustStock(long id, AdjustQuantityRequest request, String operator, String idempotencyKey) {
        logger.info("Adjusting stock for ID {}: delta={}, key={}", id, request.delta(), idempotencyKey);
        
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Runs outside main transaction with auto-commit. Eliminates REQUIRES_NEW deadlock.
            boolean isNew = repository.insertIdempotencyKey(idempotencyKey);
            if (!isNew) {
                Optional<String> cachedOpt = repository.getIdempotencyResponse(idempotencyKey);
                if (cachedOpt.isPresent()) {
                    String payload = cachedOpt.get();
                    if (payload == null) {
                        logger.warn("Idempotency key is currently processing: {}", idempotencyKey);
                        throw new IdempotencyException("Request is currently processing for idempotency key: " + idempotencyKey);
                    }
                    try {
                        if ("NOT_FOUND".equals(payload)) {
                            return Optional.empty();
                        }
                        return Optional.of(objectMapper.readValue(payload, Item.class));
                    } catch (Exception e) {
                        logger.error("Failed to deserialize idempotency response", e);
                        throw new RuntimeException("Failed to process idempotency response", e);
                    }
                } else {
                    logger.warn("Idempotency key is currently processing: {}", idempotencyKey);
                    throw new IdempotencyException("Request is currently processing for idempotency key: " + idempotencyKey);
                }
            }
        }

        // Delegate to transactional method
        Optional<Item> result = self.doAdjustStock(id, request, operator);
        
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String payload = result.isPresent() ? objectMapper.writeValueAsString(result.get()) : "NOT_FOUND";
                repository.updateIdempotencyResponse(idempotencyKey, payload);
            } catch (Exception e) {
                logger.error("Failed to serialize idempotency response", e);
            }
        }
        return result;
    }

    @Transactional
    public Optional<Item> doAdjustStock(long id, AdjustQuantityRequest request, String operator) {
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
        logger.info("Streaming inventory export directly to writer via pagination");
        Long lastId = null;
        int size = 500;
        boolean hasMore = true;
        
        while (hasMore) {
            CursorResponse<Item> page = listItems(lastId, size, search, category);
            for (Item item : page.items()) {
                writer.println(String.format("%s,%s,%s,%d,%s,%s,%s,%d",
                        item.id() != null ? item.id().toString() : "",
                        com.example.inventory.util.CsvUtils.escape(item.sku()),
                        com.example.inventory.util.CsvUtils.escape(item.name()),
                        item.quantity(),
                        item.unitPrice().toString(),
                        com.example.inventory.util.CsvUtils.escape(item.category()),
                        item.updatedAt().toString(),
                        item.lowStockThreshold()
                ));
            }
            if (page.nextCursor() != null) {
                lastId = page.nextCursor();
            } else {
                hasMore = false;
            }
        }
    }
}
