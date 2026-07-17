package com.inventoryapp.core.web;

import com.inventoryapp.core.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;

    public RateLimitInterceptor(MeterRegistry meterRegistry, StringRedisTemplate redisTemplate) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }
        
        long currentSecond = System.currentTimeMillis() / 1000;
        String key = "rate_limit:" + ip + ":" + currentSecond;

        try {
            Long currentRequests = redisTemplate.opsForValue().increment(key);
            if (currentRequests != null) {
                if (currentRequests == 1) {
                    redisTemplate.expire(key, 2, TimeUnit.SECONDS);
                }
                if (currentRequests <= 50) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Fail open if Redis is unavailable
            return true;
        }

        meterRegistry.counter("rate.limit.rejected", "ip", ip).increment();
        throw new RateLimitException("Too many requests");
    }
}
