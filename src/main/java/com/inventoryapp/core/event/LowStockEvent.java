package com.inventoryapp.core.event;

import com.inventoryapp.core.model.Item;
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
