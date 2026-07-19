package com.inventoryapp.core.web;

import com.inventoryapp.core.event.StockEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final Logger logger = LoggerFactory.getLogger(EventController.class);
    private final StockEventPublisher stockEventPublisher;

    public EventController(StockEventPublisher stockEventPublisher) {
        this.stockEventPublisher = stockEventPublisher;
    }

    @GetMapping("/subscribe")
    public SseEmitter subscribe() {
        logger.info("New SSE subscriber connected");
        return stockEventPublisher.createEmitter();
    }
}
