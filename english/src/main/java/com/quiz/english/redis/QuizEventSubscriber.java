package com.quiz.english.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quiz.english.model.LeaderboardUpdateMessage;
import com.quiz.english.model.QuizEvent;
import com.quiz.english.model.QuizEventType;
import com.quiz.english.service.QuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Subscriber for quiz events from Redis Pub/Sub.
 * 
 * Multi-Instance Broadcasting:
 * ============================
 * This component receives events published by ANY instance (including itself) and
 * dispatches them to the WebSocket layer for broadcasting to connected clients.
 * 
 * Flow:
 * 1. Instance A publishes event to Redis channel: quiz:quiz123:events
 * 2. Redis broadcasts to ALL instances (A, B, C, ...)
 * 3. Each instance's QuizEventSubscriber receives the event
 * 4. Each instance broadcasts to its local WebSocket clients
 * 5. Result: All users across all instances see the update
 * 
 * Why this works:
 * - Each instance only knows about its own WebSocket connections
 * - Redis Pub/Sub ensures ALL instances receive the event
 * - Each instance broadcasts to its own clients
 * - No cross-instance WebSocket communication needed
 * - Horizontally scalable (add more instances anytime)
 * 
 * Example:
 * User on Instance A submits answer
 *   → Instance A publishes SCORE_UPDATED
 *   → Instances A, B, C all receive event
 *   → Instance A broadcasts to users connected to A
 *   → Instance B broadcasts to users connected to B
 *   → Instance C broadcasts to users connected to C
 *   → All users see the update
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final QuizService quizService;

    /**
     * Number of top users to broadcast in leaderboard updates.
     * Configurable via application.properties: quiz.leaderboard.top-n
     */
    @Value("${quiz.leaderboard.top-n:10}")
    private int leaderboardTopN;
    
    /**
     * Handle incoming Redis Pub/Sub messages.
     * 
     * This method is called by Spring Data Redis when a message arrives on
     * any channel matching the pattern: quiz:*:events
     * 
     * @param message the Redis message
     * @param pattern the pattern that matched (quiz:*:events)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // Deserialize the event
            QuizEvent event = objectMapper.readValue(message.getBody(), QuizEvent.class);
            
            String channel = new String(message.getChannel());
            log.debug("Received event from channel {}: type={}, quizId={}, userId={}, source={}", 
                      channel, event.getType(), event.getQuizId(), event.getUserId(), 
                      event.getSourceInstanceId());
            
            // Dispatch based on event type
            switch (event.getType()) {
                case USER_JOINED:
                    handleUserJoined(event);
                    break;
                case SCORE_UPDATED:
                    handleScoreUpdated(event);
                    break;
                default:
                    log.warn("Unknown event type: {}", event.getType());
            }
            
        } catch (Exception e) {
            log.error("Error processing Redis Pub/Sub message", e);
        }
    }
    
    /**
     * Handle USER_JOINED event.
     *
     * Flow:
     * 1. Receive USER_JOINED event from Redis Pub/Sub
     * 2. Fetch top N leaderboard from Redis (single source of truth)
     * 3. Broadcast to all WebSocket clients on THIS instance subscribed to /topic/quiz/{quizId}
     *
     * Performance: O(log N + M) where M = leaderboardTopN (default 10)
     *
     * @param event the event
     */
    private void handleUserJoined(QuizEvent event) {
        String quizId = event.getQuizId();
        String userId = event.getUserId();

        log.info("Handling USER_JOINED event - quizId: {}, userId: {}, source: {}",
                 quizId, userId, event.getSourceInstanceId());

        // Fetch top N leaderboard from Redis (single source of truth)
        // Optimized: Only fetch top N instead of all entries
        LeaderboardUpdateMessage leaderboard = quizService.getLeaderboard(quizId, leaderboardTopN);

        // Broadcast to all WebSocket clients on THIS instance subscribed to /topic/quiz/{quizId}
        // Note: Other instances do the same for their clients
        // Destination: /topic/quiz/{quizId}
        // Message format: {"type": "LEADERBOARD_UPDATE", "quizId": "...", "leaderboard": [...]}
        messagingTemplate.convertAndSend("/topic/quiz/" + quizId, leaderboard);

        log.info("Broadcasted top {} leaderboard entries for quiz {} after user {} joined",
                 leaderboardTopN, quizId, userId);
    }
    
    /**
     * Handle SCORE_UPDATED event.
     *
     * Flow:
     * 1. Receive SCORE_UPDATED event from Redis Pub/Sub
     * 2. Fetch top N leaderboard from Redis (single source of truth)
     * 3. Broadcast to all WebSocket clients on THIS instance subscribed to /topic/quiz/{quizId}
     *
     * Performance: O(log N + M) where M = leaderboardTopN (default 10)
     *
     * @param event the event
     */
    private void handleScoreUpdated(QuizEvent event) {
        String quizId = event.getQuizId();
        String userId = event.getUserId();
        Integer score = event.getScore();

        log.info("Handling SCORE_UPDATED event - quizId: {}, userId: {}, score: {}, source: {}",
                 quizId, userId, score, event.getSourceInstanceId());

        // Fetch top N leaderboard from Redis (single source of truth)
        // Optimized: Only fetch top N instead of all entries
        LeaderboardUpdateMessage leaderboard = quizService.getLeaderboard(quizId, leaderboardTopN);

        // Broadcast to all WebSocket clients on THIS instance subscribed to /topic/quiz/{quizId}
        // Note: Other instances do the same for their clients
        // Destination: /topic/quiz/{quizId}
        // Message format: {"type": "LEADERBOARD_UPDATE", "quizId": "...", "leaderboard": [...]}
        messagingTemplate.convertAndSend("/topic/quiz/" + quizId, leaderboard);

        log.info("Broadcasted top {} leaderboard entries for quiz {} after user {} score update to {}",
                 leaderboardTopN, quizId, userId, score);
    }
}

