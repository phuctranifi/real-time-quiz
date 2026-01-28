package com.quiz.english.ws;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Production-Hardened WebSocket Lifecycle Event Listener.
 *
 * Responsibilities:
 * =================
 * 1. Connection tracking and cleanup
 * 2. Heartbeat monitoring registration
 * 3. Rate limiter cleanup
 * 4. Graceful error handling
 * 5. Metrics collection
 *
 * Graceful Disconnect Handling:
 * ==============================
 * - Normal disconnect: Client closes connection cleanly
 * - Abnormal disconnect: Network failure, browser crash, etc.
 * - Timeout disconnect: No heartbeat received
 * - All cases handled gracefully with proper cleanup
 *
 * Horizontal Scaling:
 * ===================
 * - Each instance manages its own connections
 * - No cross-instance coordination needed
 * - When instance dies, load balancer redirects to healthy instances
 * - Clients reconnect automatically (client-side responsibility)
 *
 * Performance:
 * ============
 * - O(1) cleanup operations (HashMap removals)
 * - No Redis calls on disconnect (instance-local only)
 * - Minimal overhead: just cleanup and metrics
 *
 * Failure Modes:
 * ==============
 * - If cleanup fails, session leaks (minor, bounded by max connections)
 * - Heartbeat monitor will eventually clean up stale sessions
 * - Metrics track cleanup failures for monitoring
 */
@Slf4j
@Component
public class WebSocketEventListener {

    private final WebSocketSessionRegistry sessionRegistry;
    private final QuizRoomManager roomManager;
    private final WebSocketHeartbeatMonitor heartbeatMonitor;
    private final RateLimiter rateLimiter;

    // Metrics
    private final Counter connectionsCounter;
    private final Counter disconnectionsCounter;
    private final Counter normalDisconnectsCounter;
    private final Counter abnormalDisconnectsCounter;
    private final Counter cleanupErrorsCounter;

    public WebSocketEventListener(
            WebSocketSessionRegistry sessionRegistry,
            QuizRoomManager roomManager,
            WebSocketHeartbeatMonitor heartbeatMonitor,
            RateLimiter rateLimiter,
            MeterRegistry meterRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.roomManager = roomManager;
        this.heartbeatMonitor = heartbeatMonitor;
        this.rateLimiter = rateLimiter;

        // Initialize metrics
        this.connectionsCounter = Counter.builder("quiz.websocket.connections")
                .description("Total number of WebSocket connections")
                .register(meterRegistry);

        this.disconnectionsCounter = Counter.builder("quiz.websocket.disconnections")
                .description("Total number of WebSocket disconnections")
                .register(meterRegistry);

        this.normalDisconnectsCounter = Counter.builder("quiz.websocket.disconnections.normal")
                .description("Number of normal disconnections")
                .register(meterRegistry);

        this.abnormalDisconnectsCounter = Counter.builder("quiz.websocket.disconnections.abnormal")
                .description("Number of abnormal disconnections")
                .register(meterRegistry);

        this.cleanupErrorsCounter = Counter.builder("quiz.websocket.cleanup.errors")
                .description("Number of errors during session cleanup")
                .register(meterRegistry);
    }

    /**
     * Handle WebSocket connection event.
     *
     * Production Considerations:
     * ==========================
     * - Register for heartbeat monitoring immediately
     * - Log connection for debugging
     * - Track metrics for monitoring
     * - Graceful error handling (don't reject connection on error)
     *
     * @param event the connection event
     */
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        String sessionId = null;
        try {
            StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
            sessionId = headerAccessor.getSessionId();

            log.info("WebSocket connected - sessionId: {}, user: {}",
                     sessionId, headerAccessor.getUser());

            // Register for heartbeat monitoring
            heartbeatMonitor.registerSession(sessionId);

            // Track metrics
            connectionsCounter.increment();

            log.debug("Session {} registered for monitoring", sessionId);

        } catch (Exception e) {
            log.error("Error handling WebSocket connection for session {}", sessionId, e);
            // Don't throw - allow connection to proceed
        }
    }

    /**
     * Handle WebSocket disconnection event.
     *
     * Production Considerations:
     * ==========================
     * - Distinguish normal vs abnormal disconnects
     * - Clean up ALL resources (registry, rooms, heartbeat, rate limiter)
     * - Log disconnect reason for debugging
     * - Track metrics for monitoring
     * - Graceful error handling (don't let cleanup errors propagate)
     *
     * Disconnect Types:
     * =================
     * - NORMAL: Client closed connection cleanly
     * - GOING_AWAY: Browser tab closed or navigated away
     * - PROTOCOL_ERROR: WebSocket protocol violation
     * - NO_STATUS_CODE: Connection lost without close frame
     * - ABNORMAL_CLOSURE: Network failure, timeout, etc.
     *
     * @param event the disconnection event
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = null;
        String userId = "unknown";
        String quizId = null;

        try {
            StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
            sessionId = headerAccessor.getSessionId();

            // Get user info before cleanup
            userId = sessionRegistry.getUserId(sessionId).orElse("unknown");
            quizId = roomManager.getQuizId(sessionId);

            // Determine disconnect type
            CloseStatus closeStatus = event.getCloseStatus();
            boolean isNormal = closeStatus != null && closeStatus.getCode() == CloseStatus.NORMAL.getCode();

            log.info("WebSocket disconnected - sessionId: {}, user: {}, quiz: {}, status: {}, reason: {}",
                     sessionId, userId, quizId,
                     closeStatus != null ? closeStatus.getCode() : "unknown",
                     closeStatus != null ? closeStatus.getReason() : "unknown");

            // Clean up all resources
            cleanupSession(sessionId);

            // Track metrics
            disconnectionsCounter.increment();
            if (isNormal) {
                normalDisconnectsCounter.increment();
            } else {
                abnormalDisconnectsCounter.increment();
            }

            log.info("Successfully cleaned up session {} (user: {}, quiz: {})", sessionId, userId, quizId);

        } catch (Exception e) {
            log.error("Error handling WebSocket disconnection for session {} (user: {}, quiz: {})",
                      sessionId, userId, quizId, e);
            cleanupErrorsCounter.increment();

            // Still try to clean up even if there was an error
            if (sessionId != null) {
                try {
                    cleanupSession(sessionId);
                } catch (Exception cleanupError) {
                    log.error("Failed to clean up session {} after error", sessionId, cleanupError);
                }
            }
        }
    }

    /**
     * Clean up all resources for a session.
     *
     * Centralized cleanup logic to ensure consistency.
     *
     * @param sessionId the session ID
     */
    private void cleanupSession(String sessionId) {
        // Remove from session registry
        sessionRegistry.unregisterSession(sessionId);

        // Remove from room manager
        roomManager.removeSession(sessionId);

        // Remove from heartbeat monitor
        heartbeatMonitor.unregisterSession(sessionId);

        // Remove from rate limiter
        rateLimiter.removeSession(sessionId);

        log.debug("Cleaned up all resources for session: {}", sessionId);
    }
}

