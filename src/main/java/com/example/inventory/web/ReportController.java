package com.example.inventory.web;

import com.example.inventory.model.InventoryReport;
import com.example.inventory.service.InventoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {

    private final InventoryService inventoryService;

    public ReportController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/summary")
    public InventoryReport summary(
            @RequestParam(defaultValue = "5") @Min(0) @Max(1_000_000) int lowStockThreshold
    ) {
        return inventoryService.report(lowStockThreshold);
    }
}
