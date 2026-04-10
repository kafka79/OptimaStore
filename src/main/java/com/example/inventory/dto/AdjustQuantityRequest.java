package com.example.inventory.dto;

import jakarta.validation.constraints.NotNull;

public record AdjustQuantityRequest(
        @NotNull Integer delta
) {
}
