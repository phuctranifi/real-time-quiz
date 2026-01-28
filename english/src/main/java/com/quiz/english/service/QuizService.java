package com.quiz.english.service;

import com.quiz.english.model.LeaderboardUpdateMessage;

/**
 * Service interface for quiz business logic.
 * 
 * Implementations should be stateless and delegate storage to Redis.
 */
public interface QuizService {
    
    /**
     * Handle a user joining a quiz.
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     */
    void handleJoin(String quizId, String userId);
    
    /**
     * Handle an answer submission.
     *
     * @param quizId the quiz ID
     * @param userId the user ID
     * @param questionNumber the question number (1-10)
     * @param correct whether the answer was correct
     * @return the user's new score
     */
    int handleAnswerSubmission(String quizId, String userId, Integer questionNumber, boolean correct);
    
    /**
     * Get the current leaderboard for a quiz.
     *
     * @param quizId the quiz ID
     * @return leaderboard update message with all entries
     */
    LeaderboardUpdateMessage getLeaderboard(String quizId);

    /**
     * Get the top N users from the leaderboard.
     *
     * @param quizId the quiz ID
     * @param topN number of top users to retrieve
     * @return leaderboard update message with top N entries
     */
    LeaderboardUpdateMessage getLeaderboard(String quizId, int topN);
}

