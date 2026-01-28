package com.quiz.english.model;

/**
 * Types of quiz events published to Redis Pub/Sub.
 * 
 * These events enable cross-instance communication in a multi-server deployment.
 */
public enum QuizEventType {
    /**
     * User joined a quiz.
     * Triggers leaderboard refresh on all instances.
     */
    USER_JOINED,
    
    /**
     * User's score was updated.
     * Triggers leaderboard broadcast on all instances.
     */
    SCORE_UPDATED
}

