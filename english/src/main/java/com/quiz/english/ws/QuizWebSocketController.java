package com.quiz.english.ws;

import com.quiz.english.model.*;
import com.quiz.english.service.QuizService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Production-Hardened WebSocket Controller for Quiz Operations.
 *
 * Handles incoming messages from clients and routes them to the service layer.
 * Uses STOMP protocol with message mapping.
 *
 * Production Features:
 * ====================
 * 1. Rate Limiting: Prevents abuse and DoS attacks
 * 2. Heartbeat Handling: Detects stale connections
 * 3. Structured Logging: Contextual logs for debugging
 * 4. Metrics: Track message counts, errors, rate limits
 * 5. Graceful Error Handling: Never crash on bad input
 *
 * Multi-Instance Flow with Redis Pub/Sub:
 * ========================================
 * 1. Client sends message to /app/quiz/join or /app/quiz/submit
 * 2. Controller validates and processes message
 * 3. Service layer handles business logic AND publishes event to Redis
 * 4. Redis broadcasts event to ALL instances (including this one)
 * 5. QuizEventSubscriber on each instance receives event
 * 6. Each instance broadcasts to its local WebSocket clients
 *
 * Note: This controller does NOT broadcast directly anymore.
 * Broadcasting is handled by QuizEventSubscriber to ensure ALL instances
 * (not just the one that received the request) broadcast to their clients.
 *
 * Rate Limiting Strategy:
 * =======================
 * - Per-session token bucket (10 tokens, refill 5/sec)
 * - Allows bursts while preventing sustained abuse
 * - Rate-limited requests return error, don't crash
 * - Metrics track rate limit violations
 *
 * Performance Considerations:
 * ===========================
 * - Rate limiter: O(1) per request
 * - Heartbeat: O(1) timestamp update
 * - Metrics: O(1) counter increment
 * - Total overhead: ~10-20 microseconds per message
 */
@Slf4j
@Controller
public class QuizWebSocketController {

    private final QuizService quizService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionRegistry sessionRegistry;
    private final QuizRoomManager roomManager;
    private final RateLimiter rateLimiter;
    private final WebSocketHeartbeatMonitor heartbeatMonitor;

    // Metrics
    private final Counter joinMessagesCounter;
    private final Counter submitMessagesCounter;
    private final Counter heartbeatMessagesCounter;
    private final Counter rateLimitedCounter;
    private final Counter errorMessagesCounter;

    public QuizWebSocketController(
            QuizService quizService,
            SimpMessagingTemplate messagingTemplate,
            WebSocketSessionRegistry sessionRegistry,
            QuizRoomManager roomManager,
            RateLimiter rateLimiter,
            WebSocketHeartbeatMonitor heartbeatMonitor,
            MeterRegistry meterRegistry) {
        this.quizService = quizService;
        this.messagingTemplate = messagingTemplate;
        this.sessionRegistry = sessionRegistry;
        this.roomManager = roomManager;
        this.rateLimiter = rateLimiter;
        this.heartbeatMonitor = heartbeatMonitor;

        // Initialize metrics
        this.joinMessagesCounter = Counter.builder("quiz.websocket.messages.join")
                .description("Number of JOIN messages received")
                .register(meterRegistry);

        this.submitMessagesCounter = Counter.builder("quiz.websocket.messages.submit")
                .description("Number of SUBMIT_ANSWER messages received")
                .register(meterRegistry);

        this.heartbeatMessagesCounter = Counter.builder("quiz.websocket.messages.heartbeat")
                .description("Number of HEARTBEAT messages received")
                .register(meterRegistry);

        this.rateLimitedCounter = Counter.builder("quiz.websocket.messages.rate_limited")
                .description("Number of messages rejected by rate limiter")
                .register(meterRegistry);

        this.errorMessagesCounter = Counter.builder("quiz.websocket.messages.errors")
                .description("Number of error messages sent")
                .register(meterRegistry);
    }
    
    /**
     * Handle HEARTBEAT message.
     *
     * Client sends: {"type": "HEARTBEAT"}
     * Server responds: (no response, just updates timestamp)
     *
     * Production Considerations:
     * ==========================
     * - Lightweight: Just timestamp update, no business logic
     * - No rate limiting: Heartbeats are essential for connection health
     * - No response: Reduces network traffic
     * - Metrics: Track heartbeat count for monitoring
     *
     * @param headerAccessor message headers containing session info
     */
    @MessageMapping("/heartbeat")
    public void handleHeartbeat(SimpMessageHeaderAccessor headerAccessor) {
        try {
            String sessionId = headerAccessor.getSessionId();

            log.trace("HEARTBEAT received - sessionId: {}", sessionId);

            // Update heartbeat timestamp
            heartbeatMonitor.recordHeartbeat(sessionId);

            // Track metrics
            heartbeatMessagesCounter.increment();

        } catch (Exception e) {
            log.error("Error handling heartbeat", e);
            // Don't send error response - heartbeat should be silent
        }
    }

    /**
     * Handle JOIN message.
     *
     * Client sends: {"type": "JOIN", "quizId": "quiz123", "userId": "user456"}
     * Server responds: {"type": "JOIN_SUCCESS", "quizId": "quiz123", "userId": "user456", "message": "..."}
     * Server broadcasts: {"type": "LEADERBOARD_UPDATE", "quizId": "quiz123", "leaderboard": [...]}
     *
     * Production Considerations:
     * ==========================
     * - Rate limiting: Prevents join spam
     * - Input validation: Reject invalid data
     * - Structured logging: Include context for debugging
     * - Metrics: Track join count
     * - Graceful errors: Return error message, don't crash
     *
     * @param message the join message
     * @param headerAccessor message headers containing session info
     */
    @MessageMapping("/quiz/join")
    public void handleJoin(@Payload JoinMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = null;
        String quizId = null;
        String userId = null;

        try {
            sessionId = headerAccessor.getSessionId();
            quizId = message.getQuizId();
            userId = message.getUserId();

            log.info("JOIN request - quizId: {}, userId: {}, sessionId: {}", quizId, userId, sessionId);

            // Rate limiting
            if (!rateLimiter.tryConsume(sessionId)) {
                log.warn("Rate limit exceeded for JOIN - sessionId: {}, userId: {}", sessionId, userId);
                sendError(sessionId, "Rate limit exceeded. Please slow down.");
                rateLimitedCounter.increment();
                return;
            }

            // Validate input
            if (quizId == null || quizId.isBlank()) {
                sendError(sessionId, "Invalid quiz ID");
                return;
            }
            if (userId == null || userId.isBlank()) {
                sendError(sessionId, "Invalid user ID");
                return;
            }

            // Associate user with session
            sessionRegistry.associateUser(userId, sessionId);

            // Add to room
            roomManager.joinRoom(quizId, sessionId);

            // Delegate to service layer
            // Service will publish USER_JOINED event to Redis
            // QuizEventSubscriber will receive it and broadcast to all instances
            quizService.handleJoin(quizId, userId);

            // Send success response to user
            JoinSuccessMessage response = new JoinSuccessMessage(
                quizId,
                userId,
                "Successfully joined quiz " + quizId
            );
            messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/reply",
                response
            );

            // Note: Leaderboard broadcast is handled by QuizEventSubscriber
            // after receiving the USER_JOINED event from Redis Pub/Sub

            log.info("User {} successfully joined quiz {}", userId, quizId);

            // Track metrics
            joinMessagesCounter.increment();

        } catch (Exception e) {
            log.error("Error handling JOIN message - quizId: {}, userId: {}, sessionId: {}",
                      quizId, userId, sessionId, e);
            sendError(sessionId != null ? sessionId : headerAccessor.getSessionId(),
                      "Failed to join quiz: " + e.getMessage());
        }
    }

    /**
     * Handle SUBMIT_ANSWER message.
     *
     * Client sends: {"type": "SUBMIT_ANSWER", "quizId": "quiz123", "userId": "user456", "correct": true}
     * Server responds: {"type": "ANSWER_RESULT", "quizId": "quiz123", "userId": "user456", "correct": true, "newScore": 10}
     * Server broadcasts: {"type": "LEADERBOARD_UPDATE", "quizId": "quiz123", "leaderboard": [...]}
     *
     * Production Considerations:
     * ==========================
     * - Rate limiting: Prevents answer spam
     * - Input validation: Reject invalid data
     * - Structured logging: Include context for debugging
     * - Metrics: Track submit count
     * - Graceful errors: Return error message, don't crash
     *
     * @param message the submit answer message
     * @param headerAccessor message headers containing session info
     */
    @MessageMapping("/quiz/submit")
    public void handleSubmitAnswer(@Payload SubmitAnswerMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = null;
        String quizId = null;
        String userId = null;
        Integer questionNumber = null;
        Boolean correct = null;

        try {
            sessionId = headerAccessor.getSessionId();
            quizId = message.getQuizId();
            userId = message.getUserId();
            questionNumber = message.getQuestionNumber();
            correct = message.getCorrect();

            log.info("SUBMIT_ANSWER request - quizId: {}, userId: {}, questionNumber: {}, correct: {}, sessionId: {}",
                     quizId, userId, questionNumber, correct, sessionId);

            // Rate limiting
            if (!rateLimiter.tryConsume(sessionId)) {
                log.warn("Rate limit exceeded for SUBMIT_ANSWER - sessionId: {}, userId: {}", sessionId, userId);
                sendError(sessionId, "Rate limit exceeded. Please slow down.");
                rateLimitedCounter.increment();
                return;
            }

            // Validate input
            if (quizId == null || quizId.isBlank()) {
                sendError(sessionId, "Invalid quiz ID");
                return;
            }
            if (userId == null || userId.isBlank()) {
                sendError(sessionId, "Invalid user ID");
                return;
            }
            if (questionNumber == null) {
                sendError(sessionId, "Question number is required");
                return;
            }
            if (correct == null) {
                sendError(sessionId, "Answer correctness not specified");
                return;
            }
            
            // Verify user is in the quiz room
            if (!roomManager.isInRoom(quizId, sessionId)) {
                sendError(sessionId, "You are not in quiz " + quizId);
                return;
            }
            
            // Delegate to service layer
            // Service will publish SCORE_UPDATED event to Redis
            // QuizEventSubscriber will receive it and broadcast to all instances
            int newScore = quizService.handleAnswerSubmission(quizId, userId, questionNumber, correct);

            // Calculate points earned for this answer
            int pointsEarned = correct ? questionNumber : 0;

            // Send result to user
            AnswerResultMessage response = new AnswerResultMessage(
                quizId,
                userId,
                questionNumber,
                correct,
                pointsEarned,
                newScore
            );
            messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/reply",
                response
            );

            // Note: Leaderboard broadcast is handled by QuizEventSubscriber
            // after receiving the SCORE_UPDATED event from Redis Pub/Sub

            log.info("User {} submitted answer for question {} (correct: {}) in quiz {}, new score: {}",
                     userId, questionNumber, correct, quizId, newScore);

            // Track metrics
            submitMessagesCounter.increment();

        } catch (Exception e) {
            log.error("Error handling SUBMIT_ANSWER message - quizId: {}, userId: {}, questionNumber: {}, correct: {}, sessionId: {}",
                      quizId, userId, questionNumber, correct, sessionId, e);
            sendError(sessionId != null ? sessionId : headerAccessor.getSessionId(),
                      "Failed to submit answer: " + e.getMessage());
        }
    }

    /**
     * Send error message to a specific session.
     *
     * Production Considerations:
     * ==========================
     * - Always send error response (don't leave client hanging)
     * - Log errors for debugging
     * - Track metrics for monitoring
     * - Graceful: Never throw exceptions
     *
     * @param sessionId the session ID
     * @param error the error message
     */
    private void sendError(String sessionId, String error) {
        try {
            ErrorMessage errorMessage = new ErrorMessage(error, null);
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/reply", errorMessage);
            log.warn("Sent error to session {}: {}", sessionId, error);
            errorMessagesCounter.increment();
        } catch (Exception e) {
            log.error("Failed to send error message to session {}: {}", sessionId, error, e);
        }
    }
}

