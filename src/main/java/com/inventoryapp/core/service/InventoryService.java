package com.inventoryapp.core.service;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.BatchCreateItemRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.dto.StockTransactionResponse;
import com.inventoryapp.core.dto.UpdateItemRequest;
import com.inventoryapp.core.event.LowStockEvent;
import com.inventoryapp.core.exception.IdempotencyException;
import com.inventoryapp.core.model.InventoryReport;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.repository.IdempotencyRepository;
import com.inventoryapp.core.repository.ItemRepository;
import com.inventoryapp.core.repository.StockTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

@Service
/**
 * CRITICAL DEADLOCK PREVENTION RULE:
 * When acquiring multiple locks in this service (e.g. Idempotency Key and Item),
 * you MUST always acquire them in the exact same order:
 * 1. Idempotency Key
 * 2. Item
 * Failing to follow this order can cause distributed deadlocks under heavy load.
 */
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private final ItemRepository itemRepository;
    private final StockTransactionRepository transactionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public InventoryService(ItemRepository itemRepository, 
                            StockTransactionRepository transactionRepository,
                            IdempotencyRepository idempotencyRepository,
                            ApplicationEventPublisher eventPublisher, 
                            ObjectMapper objectMapper,
                            TransactionTemplate transactionTemplate) {
        this.itemRepository = itemRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(readOnly = true)
    public CursorResponse<Item> listItems(Long lastId, int size, String search, String category) {
        logger.info("Fetching items lastId={}, size={}, search={}, category={}", lastId, size, search, category);
        return itemRepository.findAll(lastId, size, search, category);
    }

    @Transactional
    public Item addItem(CreateItemRequest request, String operator) {
        logger.info("Adding new item with SKU: {}", request.sku());
        Item item = itemRepository.insert(
                request.sku(),
                request.name(),
                request.quantity(),
                request.unitPrice(),
                request.category(),
                request.lowStockThreshold()
        );
        transactionRepository.logTransaction(item.id(), item.sku(), item.quantity(), 0, item.quantity(), "INITIAL_STOCK", operator);

        checkAndPublishLowStock(item);
        return item;
    }

    @Transactional
    public boolean removeItem(long id, String operator) {
        logger.info("Removing item with ID: {}", id);
        Optional<Item> currentOpt = itemRepository.findByIdForUpdate(id);
        if (currentOpt.isEmpty()) {
            return false;
        }
        Item current = currentOpt.get();
        boolean deleted = itemRepository.deleteById(id);
        if (deleted) {
            transactionRepository.logTransaction(id, current.sku(), -current.quantity(), current.quantity(), 0, "ARCHIVED", operator);
        }
        return deleted;
    }

    @Transactional
    public Optional<Item> updateItem(long id, UpdateItemRequest request) {
        logger.info("Updating item with ID: {}", id);
        Optional<Item> itemOpt = itemRepository.findByIdForUpdate(id);
        if (itemOpt.isEmpty()) {
            return Optional.empty();
        }
        Item updated = itemRepository.updateItem(id, request.name(), request.unitPrice(), request.category());
        return Optional.of(updated);
    }

    public Optional<Item> adjustStock(long id, AdjustQuantityRequest request, String operator, String idempotencyKey) {
        logger.info("Adjusting stock for ID {}: delta={}, key={}", id, request.delta(), idempotencyKey);

        return transactionTemplate.execute(status -> {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<Item> cached = resolveIdempotency(idempotencyKey);
                if (cached != null) {
                    return cached;
                }
            }

            Optional<Item> currentOpt = itemRepository.findByIdForUpdate(id);
            if (currentOpt.isEmpty()) {
                persistIdempotencyResponse(idempotencyKey, Optional.empty());
                return Optional.empty();
            }

            Item current = currentOpt.get();
            int newQty = current.quantity() + request.delta();

            if (newQty < 0) {
                throw new IllegalArgumentException("Insufficient stock for adjustment - available: " + current.quantity() + ", requested delta: " + request.delta());
            }

            Item updated = itemRepository.updateQuantity(id, newQty);
            transactionRepository.logTransaction(id, updated.sku(), request.delta(), current.quantity(), newQty,
                    request.delta() >= 0 ? "RESTOCK" : "DISPATCH", operator);

            checkLowStockCrossed(current, updated);

            Optional<Item> result = Optional.of(updated);
            persistIdempotencyResponse(idempotencyKey, result);
            return result;
        });
    }

    /**
     * Returns null if no cached response exists (proceed normally).
     * Returns Optional.empty() if cached as NOT_FOUND.
     * Returns Optional.of(item) if cached as found.
     * Throws IdempotencyException if the key is still processing.
     */
    private Optional<Item> resolveIdempotency(String idempotencyKey) {
        boolean isNew = idempotencyRepository.insertIdempotencyKey(idempotencyKey);
        if (isNew) {
            return null;
        }

        Optional<String> cachedOpt = idempotencyRepository.getIdempotencyResponseForUpdate(idempotencyKey);
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
                Item item = objectMapper.readValue(payload, Item.class);
                return Optional.of(item);
            } catch (Exception e) {
                logger.error("Failed to deserialize idempotency response", e);
                throw new RuntimeException("Failed to process idempotency response", e);
            }
        }

        logger.warn("Idempotency key is currently processing: {}", idempotencyKey);
        throw new IdempotencyException("Request is currently processing for idempotency key: " + idempotencyKey);
    }



    private void checkLowStockCrossed(Item before, Item after) {
        if (before.quantity() >= before.lowStockThreshold() && after.quantity() < after.lowStockThreshold()) {
            logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", after.sku(), after.quantity());
            eventPublisher.publishEvent(new LowStockEvent(this, after));
        }
    }



    private void persistIdempotencyResponse(String idempotencyKey, Optional<Item> result) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        try {
            String payload = result.isPresent() ? objectMapper.writeValueAsString(result.get()) : "NOT_FOUND";
            idempotencyRepository.updateIdempotencyResponse(idempotencyKey, payload);
        } catch (Exception e) {
            logger.error("Failed to serialize idempotency response", e);
        }
    }

    @Transactional
    public List<Item> batchCreate(BatchCreateItemRequest request, String operator) {
        logger.info("Batch creating {} items", request.items().size());
        return request.items().stream()
                .map(item -> addItem(item, operator))
                .toList();
    }

    @Transactional
    public boolean restoreItem(long id, String operator) {
        logger.info("Restoring item with ID: {}", id);
        Optional<Item> archivedOpt = itemRepository.findByIdForUpdate(id);
        if (archivedOpt.isEmpty()) {
            return false;
        }
        Item archived = archivedOpt.get();
        if (!archived.archived()) {
            logger.warn("Item {} is not archived, skipping restore", id);
            return false;
        }
        boolean restored = itemRepository.restoreById(id);
        if (restored) {
            Item restoredItem = itemRepository.findById(id).orElseThrow();
            transactionRepository.logTransaction(id, restoredItem.sku(), restoredItem.quantity(), 0, restoredItem.quantity(), "RESTORED", operator);
        }
        return restored;
    }

    @Transactional(readOnly = true)
    public CursorResponse<StockTransactionResponse> getTransactions(Long lastId, int size, Long itemId) {
        logger.info("Fetching stock transactions lastId={}, size={}, itemId={}", lastId, size, itemId);
        return transactionRepository.findTransactions(lastId, size, itemId);
    }

    private void checkAndPublishLowStock(Item item) {
        if (item.quantity() < item.lowStockThreshold()) {
            logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", item.sku(), item.quantity());
            eventPublisher.publishEvent(new LowStockEvent(this, item));
        }
    }

    @Transactional(readOnly = true)
    public InventoryReport report(int lowStockThreshold) {
        logger.info("Generating report with threshold: {}", lowStockThreshold);
        int threshold = Math.max(0, lowStockThreshold);
        return itemRepository.buildReport(threshold);
    }

    @Transactional(readOnly = true)
    public List<String> getCategories() {
        logger.info("Fetching distinct active categories");
        return itemRepository.findDistinctCategories();
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
                        com.inventoryapp.core.util.CsvUtils.escape(item.sku()),
                        com.inventoryapp.core.util.CsvUtils.escape(item.name()),
                        item.quantity(),
                        item.unitPrice().toString(),
                        com.inventoryapp.core.util.CsvUtils.escape(item.category()),
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
