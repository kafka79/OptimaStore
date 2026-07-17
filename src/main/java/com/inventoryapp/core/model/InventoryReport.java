package com.inventoryapp.core.model;

import java.math.BigDecimal;
import java.util.List;

public record InventoryReport(
        long distinctItems,
        long totalUnitsOnHand,
        BigDecimal totalInventoryValue,
        int lowStockItemCount,
        List<Item> lowStockItems
) {
}
