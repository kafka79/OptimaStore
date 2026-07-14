package com.example.inventory.dto;

import java.util.List;

public record CursorResponse<T>(
        List<T> items,
        Long nextCursor
) {}
