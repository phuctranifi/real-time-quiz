package com.quiz.english.ws;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Heartbeat Monitor for detecting stale connections.
 * 
 * Production Considerations:
 * ==========================
 * 1. Stale Connection Detection: Clients must send heartbeat messages periodically
 * 2. Automatic Cleanup: Sessions that don't send heartbeats are considered dead
 * 3. Graceful Degradation: Heartbeat failures don't crash the system
 * 4. Metrics: Track heartbeat success/failure rates
 * 
 * How It Works:
 * =============
 * 1. Client sends heartbeat message every N seconds (e.g., 30s)
 * 2. Server updates lastHeartbeat timestamp
 * 3. Background task checks for stale sessions (no heartbeat for 2x interval)
 * 4. Stale sessions are cleaned up automatically
 * 
 * Client Implementation:
 * ======================
 * ```javascript
 * setInterval(() => {
 *   client.publish({
 *     destination: '/app/heartbeat',
 *     body: JSON.stringify({type: 'HEARTBEAT'})
 *   });
 * }, 30000); // Every 30 seconds
 * ```
 * 
 * Horizontal Scaling:
 * ===================
 * - Each instance monitors its own WebSocket connections
 * - No cross-instance coordination needed
 * - Heartbeat state is instance-local (not in Redis)
 * - When instance dies, load balancer redirects clients to healthy instances
 * - Clients reconnect and re-establish heartbeat
 * 
 * Performance:
 * ============
 * - O(N) scan every minute (N = number of sessions)
 * - Minimal overhead: just timestamp updates
 * - No Redis calls (instance-local only)
 */
@Slf4j
@Component
public class WebSocketHeartbeatMonitor {
    
    private final WebSocketSessionRegistry sessionRegistry;
    private final QuizRoomManager roomManager;
    private final SimpMessagingTemplate messagingTemplate;
    
    // sessionId -> last heartbeat timestamp
    private final Map<String, Instant> lastHeartbeat = new ConcurrentHashMap<>();
    
    // Metrics
    private final Counter heartbeatReceivedCounter;
    private final Counter staleSessionsCleanedCounter;
    
    @Value("${quiz.websocket.heartbeat.interval-seconds:30}")
    private int heartbeatIntervalSeconds;
    
    @Value("${quiz.websocket.heartbeat.timeout-multiplier:2}")
    private int timeoutMultiplier;
    
    public WebSocketHeartbeatMonitor(
            WebSocketSessionRegistry sessionRegistry,
            QuizRoomManager roomManager,
            SimpMessagingTemplate messagingTemplate,
            MeterRegistry meterRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.roomManager = roomManager;
        this.messagingTemplate = messagingTemplate;
        
        // Initialize metrics
        this.heartbeatReceivedCounter = Counter.builder("quiz.websocket.heartbeat.received")
                .description("Number of heartbeat messages received")
                .register(meterRegistry);
        
        this.staleSessionsCleanedCounter = Counter.builder("quiz.websocket.stale_sessions.cleaned")
                .description("Number of stale sessions cleaned up")
                .register(meterRegistry);
    }
    
    /**
     * Record a heartbeat from a client.
     * 
     * Called when client sends heartbeat message.
     * 
     * @param sessionId the session ID
     */
    public void recordHeartbeat(String sessionId) {
        lastHeartbeat.put(sessionId, Instant.now());
        heartbeatReceivedCounter.increment();
        
        log.trace("Heartbeat received from session: {}", sessionId);
    }
    
    /**
     * Register a new session for heartbeat monitoring.
     * 
     * @param sessionId the session ID
     */
    public void registerSession(String sessionId) {
        lastHeartbeat.put(sessionId, Instant.now());
        log.debug("Registered session {} for heartbeat monitoring", sessionId);
    }
    
    /**
     * Unregister a session from heartbeat monitoring.
     * 
     * @param sessionId the session ID
     */
    public void unregisterSession(String sessionId) {
        lastHeartbeat.remove(sessionId);
        log.debug("Unregistered session {} from heartbeat monitoring", sessionId);
    }
    
    /**
     * Check for stale sessions and clean them up.
     * 
     * Runs every minute.
     * 
     * Failure Mode:
     * =============
     * - If this task fails, stale sessions accumulate
     * - Recovery: Next scheduled run will clean them up
     * - Impact: Memory leak (minor, bounded by max connections)
     * - Mitigation: Monitor stale session count metric
     */
    @Scheduled(fixedRateString = "${quiz.websocket.heartbeat.check-interval-ms:60000}")
    public void checkStaleConnections() {
        try {
            Instant now = Instant.now();
            int timeoutSeconds = heartbeatIntervalSeconds * timeoutMultiplier;
            Instant threshold = now.minusSeconds(timeoutSeconds);
            
            int cleanedCount = 0;
            
            for (Map.Entry<String, Instant> entry : lastHeartbeat.entrySet()) {
                String sessionId = entry.getKey();
                Instant lastBeat = entry.getValue();
                
                if (lastBeat.isBefore(threshold)) {
                    // Session is stale - no heartbeat for too long
                    log.warn("Detected stale session {} - last heartbeat: {} (threshold: {})", 
                             sessionId, lastBeat, threshold);
                    
                    cleanupStaleSession(sessionId);
                    cleanedCount++;
                }
            }
            
            if (cleanedCount > 0) {
                log.info("Cleaned up {} stale WebSocket sessions", cleanedCount);
                staleSessionsCleanedCounter.increment(cleanedCount);
            }
            
        } catch (Exception e) {
            // Don't let exceptions kill the scheduled task
            log.error("Error checking for stale connections", e);
        }
    }
    
    /**
     * Clean up a stale session.
     * 
     * @param sessionId the session ID
     */
    private void cleanupStaleSession(String sessionId) {
        try {
            // Get user info before cleanup
            String userId = sessionRegistry.getUserId(sessionId).orElse("unknown");
            String quizId = roomManager.getQuizId(sessionId);
            
            // Remove from all registries
            lastHeartbeat.remove(sessionId);
            sessionRegistry.unregisterSession(sessionId);
            roomManager.removeSession(sessionId);
            
            log.info("Cleaned up stale session {} (user: {}, quiz: {})", sessionId, userId, quizId);
            
            // Note: In production, you might want to:
            // 1. Publish a USER_LEFT event to Redis
            // 2. Update leaderboard or other shared state
            // 3. Notify other users in the quiz
            
        } catch (Exception e) {
            log.error("Error cleaning up stale session {}", sessionId, e);
        }
    }
    
    /**
     * Get the number of active sessions being monitored.
     * 
     * @return session count
     */
    public int getMonitoredSessionCount() {
        return lastHeartbeat.size();
    }
    
    /**
     * Check if a session is alive (has recent heartbeat).
     * 
     * @param sessionId the session ID
     * @return true if session is alive
     */
    public boolean isSessionAlive(String sessionId) {
        Instant lastBeat = lastHeartbeat.get(sessionId);
        if (lastBeat == null) {
            return false;
        }
        
        int timeoutSeconds = heartbeatIntervalSeconds * timeoutMultiplier;
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);
        
        return lastBeat.isAfter(threshold);
    }
}

