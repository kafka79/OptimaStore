package com.inventoryapp.core.event;

public interface MessagePublisher {
    void publish(String topic, Object message);
}
