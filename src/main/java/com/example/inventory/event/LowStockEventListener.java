package com.example.inventory.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LowStockEventListener {

    private static final Logger logger = LoggerFactory.getLogger(LowStockEventListener.class);
    private final MessagePublisher messagePublisher;

    public LowStockEventListener(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    @EventListener
    public void handleLowStockEvent(LowStockEvent event) {
        logger.warn("PUSH ALERT: Local LowStockEvent caught for SKU [{}]. Forwarding to distributed message broker...",
                event.getItem().sku());
        // Simulating writing to Kafka so other cluster nodes consume the alert
        messagePublisher.publish("inventory.low-stock", event.getItem());
    }
}
