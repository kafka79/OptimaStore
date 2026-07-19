package com.inventoryapp.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BatchCreateItemRequest(
        @NotEmpty @Valid List<CreateItemRequest> items
) {
}
