package com.inventoryapp.core.web;

import com.inventoryapp.core.model.InventoryReport;
import com.inventoryapp.core.service.InventoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {

    private static final Logger logger = LoggerFactory.getLogger(ReportController.class);
    private final InventoryService inventoryService;

    public ReportController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/summary")
    public InventoryReport summary(
            @RequestParam(defaultValue = "5") @Min(0) @Max(1_000_000) int lowStockThreshold
    ) {
        logger.info("REST request to generate report summary: threshold={}", lowStockThreshold);
        return inventoryService.report(lowStockThreshold);
    }
}

