package com.example.inventory.event;

public interface MessagePublisher {
    void publish(String topic, Object message);
}
