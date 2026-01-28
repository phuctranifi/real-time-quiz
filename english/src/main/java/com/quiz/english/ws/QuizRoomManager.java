package com.quiz.english.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages quiz room memberships and session-to-room mappings.
 * 
 * IMPORTANT: This is instance-local only. Each backend instance maintains its own room state.
 * For multi-instance deployments:
 * - Use Redis Pub/Sub to broadcast room events across instances
 * - Use sticky sessions to ensure users stay on the same instance
 * - OR implement distributed room state in Redis
 * 
 * Thread-safe implementation using ConcurrentHashMap.
 */
@Slf4j
@Component
public class QuizRoomManager {
    
    // quizId -> Set of sessionIds
    private final Map<String, Set<String>> quizRooms = new ConcurrentHashMap<>();
    
    // sessionId -> quizId (for reverse lookup)
    private final Map<String, String> sessionToQuiz = new ConcurrentHashMap<>();
    
    /**
     * Add a session to a quiz room.
     * 
     * @param quizId the quiz ID
     * @param sessionId the session ID
     */
    public void joinRoom(String quizId, String sessionId) {
        // Remove from old room if exists
        String oldQuizId = sessionToQuiz.get(sessionId);
        if (oldQuizId != null && !oldQuizId.equals(quizId)) {
            leaveRoom(oldQuizId, sessionId);
        }
        
        // Add to new room
        quizRooms.computeIfAbsent(quizId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionToQuiz.put(sessionId, quizId);
        
        log.debug("Session {} joined quiz room {}", sessionId, quizId);
    }
    
    /**
     * Remove a session from a quiz room.
     * 
     * @param quizId the quiz ID
     * @param sessionId the session ID
     */
    public void leaveRoom(String quizId, String sessionId) {
        Set<String> room = quizRooms.get(quizId);
        if (room != null) {
            room.remove(sessionId);
            
            // Clean up empty rooms
            if (room.isEmpty()) {
                quizRooms.remove(quizId);
                log.debug("Quiz room {} is now empty and removed", quizId);
            }
        }
        
        sessionToQuiz.remove(sessionId);
        log.debug("Session {} left quiz room {}", sessionId, quizId);
    }
    
    /**
     * Remove a session from all rooms (called on disconnect).
     * 
     * @param sessionId the session ID
     */
    public void removeSession(String sessionId) {
        String quizId = sessionToQuiz.get(sessionId);
        if (quizId != null) {
            leaveRoom(quizId, sessionId);
        }
    }
    
    /**
     * Get all session IDs in a quiz room.
     * 
     * @param quizId the quiz ID
     * @return set of session IDs
     */
    public Set<String> getRoomSessions(String quizId) {
        Set<String> sessions = quizRooms.get(quizId);
        return sessions != null ? Set.copyOf(sessions) : Set.of();
    }
    
    /**
     * Get the quiz ID for a session.
     * 
     * @param sessionId the session ID
     * @return the quiz ID, or null if not in a room
     */
    public String getQuizId(String sessionId) {
        return sessionToQuiz.get(sessionId);
    }
    
    /**
     * Get the number of participants in a quiz room.
     * 
     * @param quizId the quiz ID
     * @return participant count
     */
    public int getRoomSize(String quizId) {
        Set<String> room = quizRooms.get(quizId);
        return room != null ? room.size() : 0;
    }
    
    /**
     * Get all active quiz IDs.
     * 
     * @return set of quiz IDs
     */
    public Set<String> getActiveQuizIds() {
        return Set.copyOf(quizRooms.keySet());
    }
    
    /**
     * Check if a session is in a specific quiz room.
     * 
     * @param quizId the quiz ID
     * @param sessionId the session ID
     * @return true if session is in the room
     */
    public boolean isInRoom(String quizId, String sessionId) {
        return quizId.equals(sessionToQuiz.get(sessionId));
    }
}

