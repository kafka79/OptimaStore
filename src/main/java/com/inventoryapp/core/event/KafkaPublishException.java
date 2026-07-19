package com.inventoryapp.core.event;

public class KafkaPublishException extends RuntimeException {
    public KafkaPublishException(String message) {
        super(message);
    }

    public KafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
