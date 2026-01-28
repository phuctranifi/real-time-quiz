package com.quiz.english.redis;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Health Monitor with Circuit Breaker.
 * 
 * Production Resilience Strategy:
 * ================================
 * 1. Circuit Breaker: Prevents cascading failures when Redis is down
 * 2. Health Checks: Periodic ping to detect Redis availability
 * 3. Graceful Degradation: System continues with reduced functionality
 * 4. Automatic Recovery: Circuit closes when Redis comes back
 * 
 * Circuit Breaker States:
 * =======================
 * - CLOSED: Normal operation, all requests go through
 * - OPEN: Redis is down, requests fail fast (no waiting)
 * - HALF_OPEN: Testing if Redis recovered, limited requests allowed
 * 
 * Failure Modes & Recovery:
 * =========================
 * 
 * Scenario 1: Redis Temporarily Down (< 1 minute)
 * ------------------------------------------------
 * - Circuit opens after 5 failures
 * - New requests fail fast (no timeout waiting)
 * - Health check detects recovery
 * - Circuit closes, normal operation resumes
 * - Impact: Brief degradation, no data loss (in-memory fallback)
 * 
 * Scenario 2: Redis Down for Extended Period
 * -------------------------------------------
 * - Circuit stays open
 * - Leaderboard operations use in-memory fallback
 * - Pub/Sub events are lost (acceptable for real-time updates)
 * - When Redis recovers, leaderboard syncs from Redis
 * - Impact: Temporary inconsistency, eventual consistency restored
 * 
 * Scenario 3: Network Partition
 * ------------------------------
 * - Each instance operates independently
 * - In-memory leaderboards diverge
 * - When partition heals, Redis becomes source of truth
 * - Impact: Temporary split-brain, resolved on recovery
 * 
 * Horizontal Scaling Considerations:
 * ===================================
 * - Each instance has its own circuit breaker
 * - Instances may have different circuit states (acceptable)
 * - Health checks are instance-local
 * - No coordination needed between instances
 * 
 * Performance Impact:
 * ===================
 * - Circuit breaker overhead: ~1-2 microseconds per call
 * - Health check: 1 Redis PING every 10 seconds
 * - Fail-fast: No timeout waiting when circuit is open
 * - Memory: Minimal (circuit breaker state only)
 */
@Slf4j
@Component
public class RedisHealthMonitor {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final CircuitBreaker circuitBreaker;
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    
    // Metrics
    private final Counter healthCheckSuccessCounter;
    private final Counter healthCheckFailureCounter;
    private final Counter circuitOpenCounter;
    
    public RedisHealthMonitor(
            RedisTemplate<String, Object> redisTemplate,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        
        // Configure circuit breaker
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open circuit if 50% of calls fail
                .waitDurationInOpenState(Duration.ofSeconds(30)) // Wait 30s before trying again
                .slidingWindowSize(10) // Consider last 10 calls
                .minimumNumberOfCalls(5) // Need at least 5 calls before calculating failure rate
                .permittedNumberOfCallsInHalfOpenState(3) // Allow 3 test calls in half-open state
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.circuitBreaker = registry.circuitBreaker("redis");

        // Initialize metrics BEFORE registering event listeners
        this.healthCheckSuccessCounter = Counter.builder("quiz.redis.health_check.success")
                .description("Number of successful Redis health checks")
                .register(meterRegistry);

        this.healthCheckFailureCounter = Counter.builder("quiz.redis.health_check.failure")
                .description("Number of failed Redis health checks")
                .register(meterRegistry);

        this.circuitOpenCounter = Counter.builder("quiz.redis.circuit_breaker.open")
                .description("Number of times circuit breaker opened")
                .register(meterRegistry);

        // Register circuit breaker event listeners (after metrics are initialized)
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    log.warn("Redis circuit breaker state transition: {} -> {}",
                             event.getStateTransition().getFromState(),
                             event.getStateTransition().getToState());

                    if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                        circuitOpenCounter.increment();
                    }
                })
                .onError(event -> {
                    log.error("Redis circuit breaker error: {}", event.getThrowable().getMessage());
                });
        
        // Register health status gauge
        Gauge.builder("quiz.redis.healthy", isHealthy, value -> value.get() ? 1.0 : 0.0)
                .description("Redis health status (1 = healthy, 0 = unhealthy)")
                .register(meterRegistry);
        
        // Register circuit breaker state gauge
        Gauge.builder("quiz.redis.circuit_breaker.state", circuitBreaker, cb -> {
            switch (cb.getState()) {
                case CLOSED: return 0.0;
                case OPEN: return 1.0;
                case HALF_OPEN: return 0.5;
                default: return -1.0;
            }
        })
                .description("Circuit breaker state (0 = closed, 0.5 = half-open, 1 = open)")
                .register(meterRegistry);
    }
    
    /**
     * Periodic health check for Redis.
     * 
     * Runs every 10 seconds.
     * 
     * @return true if Redis is healthy
     */
    @Scheduled(fixedRateString = "${quiz.redis.health-check-interval-ms:10000}")
    public boolean checkHealth() {
        try {
            // Use circuit breaker for health check
            circuitBreaker.executeSupplier(() -> {
                // Simple PING command
                String response = redisTemplate.getConnectionFactory()
                        .getConnection()
                        .ping();
                
                if ("PONG".equals(response)) {
                    return true;
                } else {
                    throw new RuntimeException("Unexpected Redis PING response: " + response);
                }
            });
            
            // Health check succeeded
            if (!isHealthy.get()) {
                log.info("Redis health restored");
            }
            isHealthy.set(true);
            healthCheckSuccessCounter.increment();
            
            return true;
            
        } catch (Exception e) {
            // Health check failed
            if (isHealthy.get()) {
                log.error("Redis health check failed - entering degraded mode", e);
            }
            isHealthy.set(false);
            healthCheckFailureCounter.increment();
            
            return false;
        }
    }
    
    /**
     * Check if Redis is currently healthy.
     * 
     * @return true if healthy
     */
    public boolean isHealthy() {
        return isHealthy.get();
    }
    
    /**
     * Get the circuit breaker instance.
     * 
     * Use this to wrap Redis operations:
     * ```java
     * circuitBreaker.executeSupplier(() -> redisTemplate.opsForValue().get(key));
     * ```
     * 
     * @return the circuit breaker
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
    
    /**
     * Get the current circuit breaker state.
     * 
     * @return the state (CLOSED, OPEN, HALF_OPEN)
     */
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }
    
    /**
     * Check if circuit breaker is open (Redis is down).
     * 
     * @return true if circuit is open
     */
    public boolean isCircuitOpen() {
        return circuitBreaker.getState() == CircuitBreaker.State.OPEN;
    }
}

