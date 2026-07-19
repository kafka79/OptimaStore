package com.inventoryapp.core.web;

import com.inventoryapp.core.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;

    private static final int PER_ENDPOINT_LIMIT = 20;
    private static final int WINDOW_SECONDS = 1;
    
    private final DefaultRedisScript<Long> rateLimitScript;

    public RateLimitInterceptor(MeterRegistry meterRegistry, StringRedisTemplate redisTemplate) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setLocation(new org.springframework.core.io.ClassPathResource("lua/rate_limit.lua"));
        this.rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }

        String method = request.getMethod();
        String path = request.getRequestURI();
        String endpoint = method + ":" + path.replaceAll("/\\d+", "/{id}");

        // Sliding window: store sorted set members with score = timestamp
        // Count members within the last WINDOW_SECONDS
        String key = "rate_limit:" + endpoint + ":" + ip;
        long now = System.currentTimeMillis();
        long windowStart = now - (WINDOW_SECONDS * 1000L);

        try {
            String member = now + "-" + UUID.randomUUID().toString();
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowStart),
                    String.valueOf(PER_ENDPOINT_LIMIT),
                    member,
                    "2"
            );

            if (result == null || result == 0L) {
                var counter = meterRegistry.counter("rate.limit.rejected", "ip", ip, "endpoint", endpoint);
                if (counter != null) {
                    counter.increment();
                }
                throw new RateLimitException("Too many requests - limit " + PER_ENDPOINT_LIMIT + " per " + WINDOW_SECONDS + "s per endpoint");
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            // Fail open if Redis is unavailable
            // Log the failure for monitoring
            logger.warn("Redis unavailable for rate limiting for IP {}. Request accepted for availability. Error: {}", ip, e.getMessage());
            // Metrics for unability alerts
            try {
                var counter = meterRegistry != null ? meterRegistry.counter("rate.limit.redis.unavailable", "ip", ip, "endpoint", endpoint) : null;
                if (counter != null) {
                    counter.increment();
                }
            } catch (Exception counterError) {
                logger.error("Failed to increment Redis unavailable counter", counterError);
            }
            return true;
        }

        return true;
    }
}
