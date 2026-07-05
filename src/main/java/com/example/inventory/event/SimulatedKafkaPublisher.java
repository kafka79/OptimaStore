package com.example.inventory.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimulatedKafkaPublisher implements MessagePublisher {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedKafkaPublisher.class);

    @Override
    public void publish(String topic, Object message) {
        // In a real environment, this would use a KafkaTemplate, RabbitTemplate, or Amazon SNS client.
        // We log the serializable data to simulate writing to a distributed event log.
        logger.info("DISTRIBUTED ALERT: Successfully published message to event broker topic [{}] -> Data: {}", topic, message);
    }
}
