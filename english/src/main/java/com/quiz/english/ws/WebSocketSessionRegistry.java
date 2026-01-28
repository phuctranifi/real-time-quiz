package com.quiz.english.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry for WebSocket sessions.
 * 
 * IMPORTANT: This is instance-local only. Each backend instance maintains its own registry.
 * For multi-instance deployments, session affinity (sticky sessions) should be configured
 * at the load balancer level, OR use a shared session store (Redis).
 * 
 * Thread-safe implementation using ConcurrentHashMap.
 */
@Slf4j
@Component
public class WebSocketSessionRegistry {
    
    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // userId -> sessionId (for reverse lookup)
    private final Map<String, String> userToSession = new ConcurrentHashMap<>();
    
    /**
     * Register a new WebSocket session.
     * 
     * @param session the WebSocket session
     */
    public void registerSession(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.debug("Registered session: {}", session.getId());
    }
    
    /**
     * Associate a user with their WebSocket session.
     * 
     * @param userId the user ID
     * @param sessionId the session ID
     */
    public void associateUser(String userId, String sessionId) {
        // Remove old session association if exists
        String oldSessionId = userToSession.put(userId, sessionId);
        if (oldSessionId != null && !oldSessionId.equals(sessionId)) {
            log.debug("User {} switched from session {} to {}", userId, oldSessionId, sessionId);
        }
        log.debug("Associated user {} with session {}", userId, sessionId);
    }
    
    /**
     * Unregister a WebSocket session.
     * 
     * @param sessionId the session ID
     */
    public void unregisterSession(String sessionId) {
        sessions.remove(sessionId);
        
        // Remove user association
        userToSession.entrySet().removeIf(entry -> entry.getValue().equals(sessionId));
        
        log.debug("Unregistered session: {}", sessionId);
    }
    
    /**
     * Get a session by ID.
     * 
     * @param sessionId the session ID
     * @return the WebSocket session, if present
     */
    public Optional<WebSocketSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
    
    /**
     * Get a session by user ID.
     * 
     * @param userId the user ID
     * @return the WebSocket session, if present
     */
    public Optional<WebSocketSession> getSessionByUserId(String userId) {
        String sessionId = userToSession.get(userId);
        return sessionId != null ? getSession(sessionId) : Optional.empty();
    }
    
    /**
     * Get the user ID associated with a session.
     * 
     * @param sessionId the session ID
     * @return the user ID, if present
     */
    public Optional<String> getUserId(String sessionId) {
        return userToSession.entrySet().stream()
                .filter(entry -> entry.getValue().equals(sessionId))
                .map(Map.Entry::getKey)
                .findFirst();
    }
    
    /**
     * Get total number of active sessions.
     * 
     * @return session count
     */
    public int getSessionCount() {
        return sessions.size();
    }
}

