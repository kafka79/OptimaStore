package com.example.inventory.event;

import com.example.inventory.model.Item;
import org.springframework.context.ApplicationEvent;

public class LowStockEvent extends ApplicationEvent {
    private final Item item;

    public LowStockEvent(Object source, Item item) {
        super(source);
        this.item = item;
    }

    public Item getItem() {
        return item;
    }
}
