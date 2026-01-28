package com.quiz.english.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Leaderboard entry representing a user's score and rank in a quiz.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry {
    /**
     * User identifier.
     */
    private String userId;
    
    /**
     * User's total score.
     */
    private Integer score;
    
    /**
     * User's rank (1-based, 1 = highest score).
     */
    private Integer rank;
}

