package com.quiz.english.redis;

import com.quiz.english.model.LeaderboardEntry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production-Hardened Redis Repository for Leaderboard Operations.
 *
 * Performance Optimizations:
 * =========================
 * 1. Atomic Operations: All score updates use ZINCRBY (atomic increment)
 * 2. Sorted Sets: O(log N) for inserts/updates, O(1) for rank lookups
 * 3. Batch Fetching: Single ZREVRANGE call to get top N with scores
 * 4. Idempotent: Multiple joins don't affect score (ZADD NX)
 * 5. Concurrency-Safe: Redis operations are atomic by nature
 *
 * Production Resilience:
 * ======================
 * 1. Circuit Breaker: Fail fast when Redis is down
 * 2. In-Memory Fallback: Temporary leaderboard when Redis unavailable
 * 3. Metrics: Track operation latency and errors
 * 4. Structured Logging: Contextual logs for debugging
 *
 * Key Pattern: quiz:{quizId}:leaderboard
 * Member: userId (String)
 * Score: total points (Double)
 *
 * Redis Commands Used:
 * - ZADD NX: Add user with score 0 if not exists (idempotent join)
 * - ZINCRBY: Atomically increment score (concurrency-safe)
 * - ZREVRANGE WITHSCORES: Get top N users with scores (sorted desc)
 * - ZREVRANK: Get user's rank (0-based, 0 = highest)
 * - ZSCORE: Get user's current score
 *
 * Failure Modes & Recovery:
 * ==========================
 * 1. Redis Down: Circuit opens, fallback to in-memory leaderboard
 * 2. Network Timeout: Circuit breaker prevents cascading failures
 * 3. Redis Recovery: Circuit closes, sync from Redis (source of truth)
 * 4. Data Loss: In-memory data lost on instance restart (acceptable)
 *
 * Horizontal Scaling:
 * ===================
 * - Each instance has its own in-memory fallback
 * - Fallbacks may diverge during Redis outage (acceptable)
 * - When Redis recovers, all instances sync from Redis
 * - Eventual consistency restored
 */
@Slf4j
@Repository
public class LeaderboardRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisHealthMonitor healthMonitor;

    // In-memory fallback: quizId -> (userId -> score)
    private final Map<String, Map<String, Double>> inMemoryLeaderboards = new ConcurrentHashMap<>();

    // Metrics
    private final Timer redisOperationTimer;
    private final Counter redisErrorsCounter;
    private final Counter fallbackUsedCounter;

    public LeaderboardRepository(
            RedisTemplate<String, Object> redisTemplate,
            RedisHealthMonitor healthMonitor,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.healthMonitor = healthMonitor;

        // Initialize metrics
        this.redisOperationTimer = Timer.builder("quiz.redis.operation.duration")
                .description("Duration of Redis operations")
                .register(meterRegistry);

        this.redisErrorsCounter = Counter.builder("quiz.redis.operation.errors")
                .description("Number of Redis operation errors")
                .register(meterRegistry);

        this.fallbackUsedCounter = Counter.builder("quiz.redis.fallback.used")
                .description("Number of times in-memory fallback was used")
                .register(meterRegistry);
    }
    
    private static final String LEADERBOARD_KEY_PREFIX = "quiz:";
    private static final String LEADERBOARD_KEY_SUFFIX = ":leaderboard";
    
    /**
     * Initialize a user in the leaderboard with score 0.
     * Idempotent: If user already exists, score is not changed.
     *
     * Uses ZADD NX (add only if not exists).
     * Time Complexity: O(log N)
     *
     * Production: Uses circuit breaker with in-memory fallback.
     *
     * @param quizId the quiz ID
     * @param userId the user ID
     * @return true if user was added, false if already exists
     */
    public boolean initializeUser(String quizId, String userId) {
        return redisOperationTimer.record((java.util.function.Supplier<Boolean>) () -> {
            CircuitBreaker cb = healthMonitor.getCircuitBreaker();

            try {
                return cb.executeSupplier(() -> {
                    String key = getLeaderboardKey(quizId);

                    // ZADD key NX score member
                    Boolean added = redisTemplate.opsForZSet().addIfAbsent(key, userId, 0.0);

                    if (Boolean.TRUE.equals(added)) {
                        log.debug("Initialized user {} in quiz {} leaderboard with score 0", userId, quizId);
                    } else {
                        log.debug("User {} already exists in quiz {} leaderboard", userId, quizId);
                    }

                    return Boolean.TRUE.equals(added);
                });

            } catch (Exception e) {
                log.warn("Redis unavailable for initializeUser, using in-memory fallback - quizId: {}, userId: {}",
                         quizId, userId);
                redisErrorsCounter.increment();
                fallbackUsedCounter.increment();

                // Fallback: Initialize in memory
                return initializeUserInMemory(quizId, userId);
            }
        });
    }
    
    /**
     * Atomically increment a user's score.
     * Concurrency-safe: Uses ZINCRBY which is atomic.
     *
     * If user doesn't exist, creates with the increment value.
     * Time Complexity: O(log N)
     *
     * Production: Uses circuit breaker with in-memory fallback.
     *
     * @param quizId the quiz ID
     * @param userId the user ID
     * @param points points to add (can be negative)
     * @return the user's new total score
     */
    public double incrementScore(String quizId, String userId, double points) {
        return redisOperationTimer.record((java.util.function.Supplier<Double>) () -> {
            CircuitBreaker cb = healthMonitor.getCircuitBreaker();

            try {
                return cb.executeSupplier(() -> {
                    String key = getLeaderboardKey(quizId);

                    // ZINCRBY key increment member
                    Double newScore = redisTemplate.opsForZSet().incrementScore(key, userId, points);

                    if (newScore == null) {
                        log.error("Failed to increment score for user {} in quiz {}", userId, quizId);
                        return 0.0;
                    }

                    log.debug("Incremented score for user {} in quiz {} by {} points, new score: {}",
                              userId, quizId, points, newScore);

                    return newScore;
                });

            } catch (Exception e) {
                log.warn("Redis unavailable for incrementScore, using in-memory fallback - quizId: {}, userId: {}, points: {}",
                         quizId, userId, points);
                redisErrorsCounter.increment();
                fallbackUsedCounter.increment();

                // Fallback: Increment in memory
                return incrementScoreInMemory(quizId, userId, points);
            }
        });
    }
    
    /**
     * Get a user's current score.
     * 
     * Uses ZSCORE.
     * Time Complexity: O(1)
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     * @return the user's score, or 0 if not found
     */
    public double getScore(String quizId, String userId) {
        String key = getLeaderboardKey(quizId);
        
        // ZSCORE key member
        Double score = redisTemplate.opsForZSet().score(key, userId);
        
        return score != null ? score : 0.0;
    }
    
    /**
     * Get a user's rank (1-based, 1 = highest score).
     * 
     * Uses ZREVRANK (reverse rank, highest score = rank 0).
     * Time Complexity: O(log N)
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     * @return the user's rank (1-based), or null if not found
     */
    public Integer getRank(String quizId, String userId) {
        String key = getLeaderboardKey(quizId);
        
        // ZREVRANK key member (0-based, 0 = highest score)
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId);
        
        if (rank == null) {
            return null;
        }
        
        // Convert to 1-based rank
        return rank.intValue() + 1;
    }
    
    /**
     * Get top N users from the leaderboard.
     *
     * Uses ZREVRANGE WITHSCORES (single Redis call).
     * Time Complexity: O(log N + M) where M is the number of elements returned
     *
     * Production: Uses circuit breaker with in-memory fallback.
     *
     * @param quizId the quiz ID
     * @param topN number of top users to retrieve
     * @return list of leaderboard entries with userId, score, and rank
     */
    public List<LeaderboardEntry> getTopN(String quizId, int topN) {
        return redisOperationTimer.record(() -> {
            CircuitBreaker cb = healthMonitor.getCircuitBreaker();

            try {
                return cb.executeSupplier(() -> {
                    String key = getLeaderboardKey(quizId);

                    // ZREVRANGE key 0 (topN-1) WITHSCORES
                    Set<ZSetOperations.TypedTuple<Object>> entries =
                        redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, topN - 1);

                    if (entries == null || entries.isEmpty()) {
                        log.debug("No entries found in leaderboard for quiz {}", quizId);
                        return List.of();
                    }

                    List<LeaderboardEntry> leaderboard = new ArrayList<>();
                    int rank = 1;

                    for (ZSetOperations.TypedTuple<Object> entry : entries) {
                        String userId = (String) entry.getValue();
                        Double score = entry.getScore();

                        if (userId != null && score != null) {
                            leaderboard.add(new LeaderboardEntry(userId, score.intValue(), rank));
                            rank++;
                        }
                    }

                    log.debug("Retrieved top {} entries from quiz {} leaderboard", leaderboard.size(), quizId);

                    return leaderboard;
                });

            } catch (Exception e) {
                log.warn("Redis unavailable for getTopN, using in-memory fallback - quizId: {}, topN: {}",
                         quizId, topN);
                redisErrorsCounter.increment();
                fallbackUsedCounter.increment();

                // Fallback: Get from memory
                return getTopNInMemory(quizId, topN);
            }
        });
    }
    
    /**
     * Get the entire leaderboard (all users).
     * 
     * Uses ZREVRANGE WITHSCORES.
     * Time Complexity: O(N log N + M) where M is the total number of elements
     * 
     * Note: Use with caution for large leaderboards. Consider pagination.
     * 
     * @param quizId the quiz ID
     * @return list of all leaderboard entries
     */
    public List<LeaderboardEntry> getAll(String quizId) {
        String key = getLeaderboardKey(quizId);
        
        // ZREVRANGE key 0 -1 WITHSCORES (all entries)
        Set<ZSetOperations.TypedTuple<Object>> entries = 
            redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, -1);
        
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        
        List<LeaderboardEntry> leaderboard = new ArrayList<>();
        int rank = 1;
        
        for (ZSetOperations.TypedTuple<Object> entry : entries) {
            String userId = (String) entry.getValue();
            Double score = entry.getScore();
            
            if (userId != null && score != null) {
                leaderboard.add(new LeaderboardEntry(userId, score.intValue(), rank));
                rank++;
            }
        }
        
        return leaderboard;
    }
    
    /**
     * Get the total number of users in the leaderboard.
     * 
     * Uses ZCARD.
     * Time Complexity: O(1)
     * 
     * @param quizId the quiz ID
     * @return number of users
     */
    public long getSize(String quizId) {
        String key = getLeaderboardKey(quizId);
        Long size = redisTemplate.opsForZSet().size(key);
        return size != null ? size : 0;
    }
    
    /**
     * Remove a user from the leaderboard.
     * 
     * Uses ZREM.
     * Time Complexity: O(log N)
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     * @return true if user was removed, false if not found
     */
    public boolean removeUser(String quizId, String userId) {
        String key = getLeaderboardKey(quizId);
        Long removed = redisTemplate.opsForZSet().remove(key, userId);
        return removed != null && removed > 0;
    }
    
    /**
     * Delete the entire leaderboard for a quiz.
     * 
     * Uses DEL.
     * Time Complexity: O(N) where N is the number of members
     * 
     * @param quizId the quiz ID
     * @return true if leaderboard was deleted
     */
    public boolean deleteLeaderboard(String quizId) {
        String key = getLeaderboardKey(quizId);
        Boolean deleted = redisTemplate.delete(key);
        return Boolean.TRUE.equals(deleted);
    }
    
    /**
     * Get the Redis key for a quiz leaderboard.
     *
     * Pattern: quiz:{quizId}:leaderboard
     *
     * @param quizId the quiz ID
     * @return the Redis key
     */
    private String getLeaderboardKey(String quizId) {
        return LEADERBOARD_KEY_PREFIX + quizId + LEADERBOARD_KEY_SUFFIX;
    }

    // ========================================================================
    // In-Memory Fallback Methods
    // ========================================================================

    /**
     * Initialize user in in-memory leaderboard.
     *
     * Fallback when Redis is unavailable.
     *
     * @param quizId the quiz ID
     * @param userId the user ID
     * @return true if user was added, false if already exists
     */
    private boolean initializeUserInMemory(String quizId, String userId) {
        Map<String, Double> leaderboard = inMemoryLeaderboards.computeIfAbsent(quizId, k -> new ConcurrentHashMap<>());

        Double existing = leaderboard.putIfAbsent(userId, 0.0);
        boolean added = (existing == null);

        log.debug("[IN-MEMORY] Initialized user {} in quiz {} leaderboard, added: {}", userId, quizId, added);

        return added;
    }

    /**
     * Increment score in in-memory leaderboard.
     *
     * Fallback when Redis is unavailable.
     *
     * @param quizId the quiz ID
     * @param userId the user ID
     * @param points points to add
     * @return new score
     */
    private double incrementScoreInMemory(String quizId, String userId, double points) {
        Map<String, Double> leaderboard = inMemoryLeaderboards.computeIfAbsent(quizId, k -> new ConcurrentHashMap<>());

        double newScore = leaderboard.compute(userId, (k, v) -> (v == null ? 0.0 : v) + points);

        log.debug("[IN-MEMORY] Incremented score for user {} in quiz {} by {} points, new score: {}",
                  userId, quizId, points, newScore);

        return newScore;
    }

    /**
     * Get top N from in-memory leaderboard.
     *
     * Fallback when Redis is unavailable.
     *
     * @param quizId the quiz ID
     * @param topN number of entries
     * @return leaderboard entries
     */
    private List<LeaderboardEntry> getTopNInMemory(String quizId, int topN) {
        Map<String, Double> leaderboard = inMemoryLeaderboards.get(quizId);

        if (leaderboard == null || leaderboard.isEmpty()) {
            log.debug("[IN-MEMORY] No entries found in leaderboard for quiz {}", quizId);
            return List.of();
        }

        // Sort by score descending and take top N
        List<LeaderboardEntry> entries = leaderboard.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(topN)
                .map(entry -> {
                    int rank = 1; // Will be set correctly below
                    return new LeaderboardEntry(entry.getKey(), entry.getValue().intValue(), rank);
                })
                .toList();

        // Set correct ranks
        List<LeaderboardEntry> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            LeaderboardEntry entry = entries.get(i);
            result.add(new LeaderboardEntry(entry.getUserId(), entry.getScore(), i + 1));
        }

        log.debug("[IN-MEMORY] Retrieved top {} entries from quiz {} leaderboard", result.size(), quizId);

        return result;
    }
}

