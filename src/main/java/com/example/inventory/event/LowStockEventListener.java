package com.example.inventory.event;

import com.example.inventory.repository.InventoryJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LowStockEventListener {

    private static final Logger logger = LoggerFactory.getLogger(LowStockEventListener.class);
    private final InventoryJdbcRepository repository;

    public LowStockEventListener(InventoryJdbcRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void handleLowStockEvent(LowStockEvent event) {
        logger.info("PUSH ALERT: Local LowStockEvent caught for SKU [{}]. Storing in outbox table within active transaction...",
                event.getItem().sku());
        
        repository.insertOutboxEvent(
                "Item",
                String.valueOf(event.getItem().id()),
                "inventory.low-stock",
                event.getItem().toString()
        );
    }
}
