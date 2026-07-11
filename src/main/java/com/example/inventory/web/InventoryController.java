package com.example.inventory.web;

import com.example.inventory.dto.AdjustQuantityRequest;
import com.example.inventory.dto.CreateItemRequest;
import com.example.inventory.dto.PageResponse;
import com.example.inventory.exception.DuplicateSkuException;
import com.example.inventory.model.Item;
import com.example.inventory.service.InventoryService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public PageResponse<Item> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category
    ) {
        logger.info("REST request to list items: page={}, size={}, search={}, category={}", page, size, search, category);
        return inventoryService.listItems(page, size, search, category);
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

    @PatchMapping("/{id}/quantity")
    public ResponseEntity<Item> adjustQuantity(
            @PathVariable long id,
            @Valid @RequestBody AdjustQuantityRequest body
    ) {
        logger.info("REST request to adjust item quantity: id={}, delta={}", id, body.delta());
        return inventoryService.adjustStock(id, body, getOperator())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            HttpServletResponse response
    ) throws IOException {
        logger.info("REST request to export CSV via stream");
        
        List<Item> items = inventoryService.getItemsForExport(search, category);
        
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"inventory-export.csv\"");
        
        try (PrintWriter writer = response.getWriter()) {
            writer.println("id,sku,name,quantity,unitPrice,category,updatedAt,lowStockThreshold");
            for (Item item : items) {
                writer.println(String.format("%s,%s,%s,%d,%s,%s,%s,%d",
                        item.id() != null ? item.id().toString() : "",
                        csvEscape(item.sku()),
                        csvEscape(item.name()),
                        item.quantity(),
                        item.unitPrice().toString(),
                        csvEscape(item.category()),
                        item.updatedAt().toString(),
                        item.lowStockThreshold()
                ));
            }
        }
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    @GetMapping("/categories")
    public List<String> categories() {
        logger.info("REST request to list distinct categories");
        return inventoryService.getCategories();
    }

    private String getOperator() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "anonymous";
    }
}
