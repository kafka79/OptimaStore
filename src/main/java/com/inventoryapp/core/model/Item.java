package com.inventoryapp.core.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Item(
        Long id,
        String sku,
        String name,
        int quantity,
        BigDecimal unitPrice,
        String category,
        Instant updatedAt,
        boolean archived,
        int lowStockThreshold
) {
}
