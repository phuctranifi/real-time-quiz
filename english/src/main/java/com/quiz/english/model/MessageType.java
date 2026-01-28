package com.quiz.english.model;

/**
 * WebSocket message types for quiz communication.
 */
public enum MessageType {
    /**
     * Client joins a quiz room.
     */
    JOIN,
    
    /**
     * Client submits an answer.
     */
    SUBMIT_ANSWER,
    
    /**
     * Server confirms successful join.
     */
    JOIN_SUCCESS,
    
    /**
     * Server sends error message.
     */
    ERROR,
    
    /**
     * Server broadcasts leaderboard update.
     */
    LEADERBOARD_UPDATE,
    
    /**
     * Server sends answer result.
     */
    ANSWER_RESULT
}

