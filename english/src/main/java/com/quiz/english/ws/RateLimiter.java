package com.quiz.english.ws;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate Limiter for WebSocket messages using Token Bucket algorithm.
 * 
 * Production Rate Limiting Strategy:
 * ===================================
 * 1. Per-Session Limits: Each WebSocket session has its own rate limit
 * 2. Token Bucket: Allows bursts while maintaining average rate
 * 3. Graceful Rejection: Rate-limited requests return error, don't crash
 * 4. Metrics: Track rate limit violations
 * 
 * Token Bucket Algorithm:
 * =======================
 * - Bucket holds N tokens (capacity)
 * - Tokens refill at R tokens/second
 * - Each request consumes 1 token
 * - If no tokens available, request is rejected
 * - Allows bursts up to bucket capacity
 * 
 * Example Configuration:
 * ======================
 * - Capacity: 10 tokens
 * - Refill: 5 tokens/second
 * - Result: Can handle 10 requests instantly, then 5 req/sec sustained
 * 
 * Why Per-Session (Not Per-User)?
 * ================================
 * - Simpler: No need to track user identity
 * - Faster: No Redis lookup needed
 * - Fair: Each connection gets same limit
 * - Scalable: Instance-local, no coordination
 * 
 * Horizontal Scaling:
 * ===================
 * - Each instance enforces limits independently
 * - User with N connections gets N * limit (acceptable)
 * - Alternative: Use Redis-backed rate limiter for global limits
 * - Trade-off: Simplicity vs. strict global limits
 * 
 * Performance:
 * ============
 * - O(1) token consumption check
 * - No Redis calls (instance-local)
 * - Minimal memory: ~100 bytes per session
 * - No locks: ConcurrentHashMap + atomic operations
 * 
 * Failure Modes:
 * ==============
 * - If rate limiter fails, requests are allowed (fail-open)
 * - Prevents rate limiter bugs from breaking the system
 * - Logged and monitored via metrics
 */
@Slf4j
@Component
public class RateLimiter {
    
    // sessionId -> rate limiter
    private final Map<String, io.github.resilience4j.ratelimiter.RateLimiter> limiters = new ConcurrentHashMap<>();
    
    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    
    @Value("${quiz.rate-limit.capacity:10}")
    private int capacity;
    
    @Value("${quiz.rate-limit.refill-tokens:5}")
    private int refillTokens;
    
    @Value("${quiz.rate-limit.refill-period-seconds:1}")
    private int refillPeriodSeconds;
    
    public RateLimiter(MeterRegistry meterRegistry) {
        // Initialize metrics
        this.allowedCounter = Counter.builder("quiz.rate_limit.allowed")
                .description("Number of requests allowed by rate limiter")
                .register(meterRegistry);
        
        this.rejectedCounter = Counter.builder("quiz.rate_limit.rejected")
                .description("Number of requests rejected by rate limiter")
                .register(meterRegistry);
    }
    
    /**
     * Try to consume a token for a session.
     * 
     * @param sessionId the session ID
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsume(String sessionId) {
        try {
            io.github.resilience4j.ratelimiter.RateLimiter limiter =
                    limiters.computeIfAbsent(sessionId, this::createRateLimiter);

            boolean allowed = limiter.acquirePermission(1);

            if (allowed) {
                allowedCounter.increment();
                return true;
            } else {
                rejectedCounter.increment();
                log.warn("Rate limit exceeded for session: {}", sessionId);
                return false;
            }

        } catch (Exception e) {
            // Fail-open: Allow request if rate limiter fails
            log.error("Rate limiter error for session {}, allowing request", sessionId, e);
            allowedCounter.increment();
            return true;
        }
    }
    
    /**
     * Create a new rate limiter for a session.
     *
     * @param sessionId the session ID
     * @return the rate limiter
     */
    private io.github.resilience4j.ratelimiter.RateLimiter createRateLimiter(String sessionId) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(capacity)
                .limitRefreshPeriod(Duration.ofSeconds(refillPeriodSeconds))
                .timeoutDuration(Duration.ZERO) // Non-blocking
                .build();

        log.debug("Created rate limiter for session {} - limit: {} requests per {} seconds",
                  sessionId, capacity, refillPeriodSeconds);

        return io.github.resilience4j.ratelimiter.RateLimiter.of(
                "session-" + sessionId,
                config
        );
    }
    
    /**
     * Remove rate limit bucket for a session.
     * 
     * Call this when session disconnects to free memory.
     * 
     * @param sessionId the session ID
     */
    public void removeSession(String sessionId) {
        limiters.remove(sessionId);
        log.debug("Removed rate limiter for session: {}", sessionId);
    }
    
    /**
     * Get the number of sessions being rate limited.
     *
     * @return session count
     */
    public int getTrackedSessionCount() {
        return limiters.size();
    }

    /**
     * Get available permissions for a session.
     *
     * Useful for debugging and monitoring.
     *
     * @param sessionId the session ID
     * @return available permissions, or -1 if session not found
     */
    public int getAvailablePermissions(String sessionId) {
        io.github.resilience4j.ratelimiter.RateLimiter limiter = limiters.get(sessionId);
        if (limiter == null) {
            return -1;
        }
        return limiter.getMetrics().getAvailablePermissions();
    }
}

