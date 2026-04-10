package com.example.inventory.service;

import com.example.inventory.dto.AdjustQuantityRequest;
import com.example.inventory.dto.CreateItemRequest;
import com.example.inventory.model.InventoryReport;
import com.example.inventory.model.Item;
import com.example.inventory.repository.InventoryJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class InventoryService {

    private final InventoryJdbcRepository repository;

    public InventoryService(InventoryJdbcRepository repository) {
        this.repository = repository;
    }

    public List<Item> listItems() {
        return repository.findAll();
    }

    public Item addItem(CreateItemRequest request) {
        return repository.insert(
                request.sku(),
                request.name(),
                request.quantity(),
                request.unitPrice(),
                request.category()
        );
    }

    public boolean removeItem(long id) {
        return repository.deleteById(id);
    }

    public Optional<Item> adjustStock(long id, AdjustQuantityRequest request) {
        return repository.adjustQuantity(id, request.delta());
    }

    public InventoryReport report(int lowStockThreshold) {
        int threshold = Math.max(0, lowStockThreshold);
        return repository.buildReport(threshold);
    }
}
