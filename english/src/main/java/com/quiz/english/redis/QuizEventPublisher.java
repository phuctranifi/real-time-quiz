package com.quiz.english.redis;

import com.quiz.english.model.QuizEvent;
import com.quiz.english.model.QuizEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Publisher for quiz events to Redis Pub/Sub.
 * 
 * Multi-Instance Broadcasting:
 * ============================
 * This service publishes events to Redis channels that ALL backend instances subscribe to.
 * When you call publishUserJoined() or publishScoreUpdated(), the event is sent to Redis,
 * and Redis broadcasts it to every instance listening on that channel.
 * 
 * Channel Pattern: quiz:{quizId}:events
 * 
 * Example:
 * - Instance A: User joins quiz123
 * - Instance A: Calls publishUserJoined("quiz123", "user456")
 * - Redis: Broadcasts to channel "quiz:quiz123:events"
 * - Instances A, B, C: All receive the event via QuizEventSubscriber
 * - Instances A, B, C: Each broadcasts leaderboard to their WebSocket clients
 * - Result: All users see the update, regardless of which instance they're on
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizEventPublisher {
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    /**
     * Unique identifier for this instance (e.g., hostname, pod name).
     * Used for debugging to track which instance published an event.
     */
    @Value("${spring.application.instance-id:unknown}")
    private String instanceId;
    
    /**
     * Publish a USER_JOINED event.
     * 
     * This notifies all instances that a user joined a quiz, so they can:
     * - Update their local state if needed
     * - Broadcast updated leaderboard to their WebSocket clients
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     */
    public void publishUserJoined(String quizId, String userId) {
        QuizEvent event = QuizEvent.userJoined(quizId, userId, instanceId);
        String channel = getChannelName(quizId);
        
        log.debug("Publishing USER_JOINED event - quizId: {}, userId: {}, channel: {}", 
                  quizId, userId, channel);
        
        redisTemplate.convertAndSend(channel, event);
        
        log.info("Published USER_JOINED event for user {} in quiz {} to channel {}", 
                 userId, quizId, channel);
    }
    
    /**
     * Publish a SCORE_UPDATED event.
     * 
     * This notifies all instances that a user's score changed, so they can:
     * - Fetch updated leaderboard from Redis
     * - Broadcast updated leaderboard to their WebSocket clients
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     * @param newScore the user's new score
     */
    public void publishScoreUpdated(String quizId, String userId, int newScore) {
        QuizEvent event = QuizEvent.scoreUpdated(quizId, userId, newScore, instanceId);
        String channel = getChannelName(quizId);
        
        log.debug("Publishing SCORE_UPDATED event - quizId: {}, userId: {}, score: {}, channel: {}", 
                  quizId, userId, newScore, channel);
        
        redisTemplate.convertAndSend(channel, event);
        
        log.info("Published SCORE_UPDATED event for user {} in quiz {} (score: {}) to channel {}", 
                 userId, quizId, newScore, channel);
    }
    
    /**
     * Get the Redis channel name for a quiz.
     * 
     * Pattern: quiz:{quizId}:events
     * 
     * @param quizId the quiz ID
     * @return the channel name
     */
    private String getChannelName(String quizId) {
        return String.format("quiz:%s:events", quizId);
    }
}

