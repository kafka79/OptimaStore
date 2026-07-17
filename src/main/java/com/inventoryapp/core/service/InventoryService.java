package com.inventoryapp.core.service;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.CursorResponse;
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
        
        if (item.quantity() < item.lowStockThreshold()) {
            logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", item.sku(), item.quantity());
            eventPublisher.publishEvent(new LowStockEvent(this, item));
        }
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
        
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            boolean isNew = idempotencyRepository.insertIdempotencyKey(idempotencyKey);
            if (!isNew) {
                Optional<String> cachedOpt = idempotencyRepository.getIdempotencyResponse(idempotencyKey);
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

        Optional<Item> result;
        try {
            // Use TransactionTemplate to handle the transaction programmatically instead of @Lazy proxy hack
            result = transactionTemplate.execute(status -> {
                Optional<Item> currentOpt = itemRepository.findByIdForUpdate(id);
                if (currentOpt.isEmpty()) {
                    return Optional.empty();
                }
                
                Item current = currentOpt.get();
                int newQty = current.quantity() + request.delta();
                
                if (newQty < 0) {
                    throw new IllegalArgumentException("Insufficient stock for adjustment");
                }
                
                Item updated = itemRepository.updateQuantity(id, newQty);
                transactionRepository.logTransaction(id, updated.sku(), request.delta(), current.quantity(), newQty, request.delta() >= 0 ? "RESTOCK" : "DISPATCH", operator);
                
                if (current.quantity() >= updated.lowStockThreshold() && updated.quantity() < updated.lowStockThreshold()) {
                    logger.warn("Low stock detected for SKU: {} (quantity: {}). Publishing push alert event.", updated.sku(), updated.quantity());
                    eventPublisher.publishEvent(new LowStockEvent(this, updated));
                }
                return Optional.of(updated);
            });
        } catch (Exception ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                logger.warn("Transaction failed for idempotency key [{}]. Deleting key to allow retry.", idempotencyKey);
                idempotencyRepository.deleteIdempotencyKey(idempotencyKey);
            }
            throw ex;
        }
        
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String payload = result.isPresent() ? objectMapper.writeValueAsString(result.get()) : "NOT_FOUND";
                idempotencyRepository.updateIdempotencyResponse(idempotencyKey, payload);
            } catch (Exception e) {
                logger.error("Failed to serialize idempotency response", e);
            }
        }
        return result;
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
