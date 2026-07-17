package com.inventoryapp.core.web;

import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.CursorResponse;
import com.inventoryapp.core.exception.DuplicateSkuException;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.service.InventoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.inventoryapp.core.dto.UpdateItemRequest;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;


@RestController
@RequestMapping("/api/items")
@Validated
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public CursorResponse<Item> list(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category
    ) {
        logger.info("REST request to list items: lastId={}, size={}, search={}, category={}", lastId, size, search, category);
        return inventoryService.listItems(lastId, size, search, category);
    }

    @PostMapping
    public ResponseEntity<Item> create(
            @Valid @RequestBody CreateItemRequest body
    ) {
        logger.info("REST request to create item: SKU={}", body.sku());
        Item created = inventoryService.addItem(body, getOperator());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable long id
    ) {
        logger.info("REST request to delete item: id={}", id);
        if (inventoryService.removeItem(id, getOperator())) {
            return ResponseEntity.noContent().build();
        }
        logger.warn("Item to delete not found: id={}", id);
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<Item> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateItemRequest body
    ) {
        logger.info("REST request to update item: id={}", id);
        return inventoryService.updateItem(id, body)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/quantity")
    public ResponseEntity<Item> adjustQuantity(
            @PathVariable long id,
            @Valid @RequestBody AdjustQuantityRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        logger.info("REST request to adjust item quantity: id={}, delta={}", id, body.delta());
        return inventoryService.adjustStock(id, body, getOperator(), idempotencyKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            HttpServletResponse response
    ) throws IOException {
        logger.info("REST request to export CSV via stream");
        
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"inventory-export.csv\"");
        
        try (PrintWriter writer = response.getWriter()) {
            writer.println("id,sku,name,quantity,unitPrice,category,updatedAt,lowStockThreshold");
            inventoryService.exportToWriter(writer, search, category);
            response.flushBuffer();
        }
    }

    @GetMapping("/categories")
    public List<String> categories() {
        logger.info("REST request to list distinct categories");
        return inventoryService.getCategories();
    }

    private String getOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "anonymous";
    }
}
