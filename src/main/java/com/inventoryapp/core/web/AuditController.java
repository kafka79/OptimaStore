package com.inventoryapp.core.web;

import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.dto.StockTransactionResponse;
import com.inventoryapp.core.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final Logger logger = LoggerFactory.getLogger(AuditController.class);
    private final InventoryService inventoryService;

    public AuditController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/transactions")
    public CursorResponse<StockTransactionResponse> transactions(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long itemId
    ) {
        logger.info("REST request to list stock transactions: lastId={}, size={}, itemId={}", lastId, size, itemId);
        return inventoryService.getTransactions(lastId, size, itemId);
    }
}
