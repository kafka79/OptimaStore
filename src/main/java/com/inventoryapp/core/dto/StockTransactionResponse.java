package com.inventoryapp.core.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record StockTransactionResponse(
        Long id,
        Long itemId,
        String sku,
        int delta,
        int previousQuantity,
        int newQuantity,
        String reason,
        String operator,
        Instant createdAt
) {
}
