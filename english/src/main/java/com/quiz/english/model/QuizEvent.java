package com.quiz.english.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event published to Redis Pub/Sub for cross-instance communication.
 * 
 * Multi-Instance Broadcasting:
 * ============================
 * When a user action occurs on Instance A (e.g., joins quiz, submits answer):
 * 1. Instance A processes the action and updates Redis (leaderboard, scores)
 * 2. Instance A publishes a QuizEvent to Redis channel: quiz:{quizId}:events
 * 3. ALL instances (A, B, C, ...) subscribed to that channel receive the event
 * 4. Each instance broadcasts the update to its connected WebSocket clients
 * 5. Result: All users across all instances see real-time updates
 * 
 * This pattern ensures:
 * - No sticky sessions required (users can connect to any instance)
 * - Real-time sync across all instances
 * - Single source of truth (Redis)
 * - Horizontal scalability
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizEvent {
    
    /**
     * Type of event (USER_JOINED, SCORE_UPDATED).
     */
    private QuizEventType type;
    
    /**
     * Quiz ID this event relates to.
     */
    private String quizId;
    
    /**
     * User ID who triggered the event.
     */
    private String userId;
    
    /**
     * New score (for SCORE_UPDATED events).
     * Null for USER_JOINED events.
     */
    private Integer score;
    
    /**
     * Timestamp when event was created.
     */
    private Instant timestamp;
    
    /**
     * Instance ID that published this event (for debugging).
     * Helps identify which server instance originated the event.
     */
    private String sourceInstanceId;
    
    /**
     * Create a USER_JOINED event.
     */
    public static QuizEvent userJoined(String quizId, String userId, String instanceId) {
        return new QuizEvent(
            QuizEventType.USER_JOINED,
            quizId,
            userId,
            null,
            Instant.now(),
            instanceId
        );
    }
    
    /**
     * Create a SCORE_UPDATED event.
     */
    public static QuizEvent scoreUpdated(String quizId, String userId, int score, String instanceId) {
        return new QuizEvent(
            QuizEventType.SCORE_UPDATED,
            quizId,
            userId,
            score,
            Instant.now(),
            instanceId
        );
    }
}

