package com.inventoryapp.core.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateItemRequest(
        @NotBlank String name,
        @NotNull @Min(0) BigDecimal unitPrice,
        @NotBlank String category
) {
}
