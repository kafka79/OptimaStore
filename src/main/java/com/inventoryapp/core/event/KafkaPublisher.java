package com.inventoryapp.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaPublisher implements MessagePublisher {

    private static final Logger logger = LoggerFactory.getLogger(KafkaPublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, Object message) {
        logger.info("Publishing message to Kafka topic [{}]: {}", topic, message);
        try {
            kafkaTemplate.send(topic, message).get(10, java.util.concurrent.TimeUnit.SECONDS);
            logger.info("DISTRIBUTED ALERT: Successfully published message to Kafka topic [{}]", topic);
        } catch (Exception ex) {
            logger.error("Failed to publish message to Kafka topic [{}]: {}", topic, ex.getMessage(), ex);
            throw new RuntimeException("Kafka publish failed", ex);
        }
    }
}
