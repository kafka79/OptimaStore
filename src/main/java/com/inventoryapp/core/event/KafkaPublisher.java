package com.inventoryapp.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class KafkaPublisher implements MessagePublisher {

    private static final Logger logger = LoggerFactory.getLogger(KafkaPublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final int TIMEOUT_SECONDS = 5;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String CB_PREFIX = "circuit_breaker:kafka:";
    private static final int CB_TTL_SECONDS = 60;

    public KafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate, StringRedisTemplate redisTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(String topic, Object message) {
        String cbKey = CB_PREFIX + topic;
        String failuresStr = redisTemplate.opsForValue().get(cbKey);
        int failures = 0;
        if (failuresStr != null) {
            try {
                failures = Integer.parseInt(failuresStr);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            logger.warn("Circuit breaker open for Kafka topic [{}]. {} consecutive failures. Skipping publish.", topic, failures);
            throw new KafkaPublishException("Kafka circuit breaker open after " + failures + " consecutive failures. Key: " + cbKey);
        }

        logger.info("Publishing message to Kafka topic [{}]: {}", topic, message);
        try {
            kafkaTemplate.send(topic, message).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            redisTemplate.delete(cbKey);
            logger.info("Successfully published message to Kafka topic [{}]", topic);
        } catch (Exception ex) {
            long incremented = redisTemplate.opsForValue().increment(cbKey);
            redisTemplate.expire(cbKey, CB_TTL_SECONDS, TimeUnit.SECONDS);
            logger.error("Failed to publish message to Kafka topic [{}] (failure #{})", topic, incremented, ex);
            throw new KafkaPublishException("Kafka publish failed after " + TIMEOUT_SECONDS + "s timeout", ex);
        }
    }
}
