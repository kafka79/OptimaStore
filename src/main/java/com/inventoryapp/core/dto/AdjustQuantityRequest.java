package com.inventoryapp.core.dto;

import jakarta.validation.constraints.NotNull;

public record AdjustQuantityRequest(
        @NotNull Integer delta
) {
}
