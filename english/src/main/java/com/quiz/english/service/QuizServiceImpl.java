package com.quiz.english.service;

import com.quiz.english.model.LeaderboardEntry;
import com.quiz.english.model.LeaderboardUpdateMessage;
import com.quiz.english.redis.LeaderboardRepository;
import com.quiz.english.redis.QuizEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Production implementation of QuizService with Redis-backed leaderboard.
 * 
 * Performance Characteristics:
 * ===========================
 * - joinQuiz: O(log N) - Single ZADD operation
 * - submitAnswer: O(log N) - Single ZINCRBY operation
 * - getLeaderboard: O(log N + M) - Single ZREVRANGE operation
 * 
 * Concurrency Safety:
 * ==================
 * - All Redis operations are atomic
 * - ZINCRBY ensures concurrent score updates don't conflict
 * - No race conditions on score increments
 * 
 * Idempotency:
 * ===========
 * - Multiple joins don't affect score (ZADD NX)
 * - Score updates are additive (safe to retry)
 * 
 * Scoring Rules:
 * =============
 * - Correct answer: +10 points
 * - Incorrect answer: +0 points
 * - Initial score: 0 points
 * 
 * Event Publishing:
 * ================
 * - USER_JOINED: Published after successful join
 * - SCORE_UPDATED: Published after score increment
 * - Events trigger cross-instance leaderboard broadcasts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {

    private final LeaderboardRepository leaderboardRepository;
    private final QuizEventPublisher eventPublisher;
    private final QuestionBankService questionBankService;
    
    /**
     * Points awarded for a correct answer.
     */
    private static final int CORRECT_ANSWER_POINTS = 10;
    
    /**
     * Points awarded for an incorrect answer.
     */
    private static final int INCORRECT_ANSWER_POINTS = 0;
    
    /**
     * Handle a user joining a quiz.
     * 
     * Operations:
     * 1. Initialize user in leaderboard with score 0 (idempotent)
     * 2. Publish USER_JOINED event to Redis Pub/Sub
     * 
     * Time Complexity: O(log N)
     * Concurrency: Safe - ZADD NX is atomic
     * Idempotency: Safe - multiple joins don't change score
     * 
     * @param quizId the quiz ID
     * @param userId the user ID
     */
    @Override
    public void handleJoin(String quizId, String userId) {
        log.info("User {} joining quiz {}", userId, quizId);
        
        // Initialize user in leaderboard with score 0
        // If user already exists, score is not changed (idempotent)
        boolean isNewUser = leaderboardRepository.initializeUser(quizId, userId);
        
        if (isNewUser) {
            log.info("User {} initialized in quiz {} leaderboard with score 0", userId, quizId);
        } else {
            log.info("User {} already exists in quiz {} leaderboard, score unchanged", userId, quizId);
        }
        
        // Publish USER_JOINED event to notify all instances
        // All instances will broadcast updated leaderboard to their WebSocket clients
        eventPublisher.publishUserJoined(quizId, userId);
        
        log.info("User {} successfully joined quiz {}", userId, quizId);
    }
    
    /**
     * Handle an answer submission.
     *
     * Operations:
     * 1. Validate question number
     * 2. Calculate points based on question and correctness
     * 3. Atomically increment user's score in Redis
     * 4. Publish SCORE_UPDATED event to Redis Pub/Sub
     *
     * Time Complexity: O(log N)
     * Concurrency: Safe - ZINCRBY is atomic
     * Idempotency: Safe to retry (additive operation)
     *
     * @param quizId the quiz ID
     * @param userId the user ID
     * @param questionNumber the question number (1-10)
     * @param correct whether the answer was correct
     * @return the user's new total score
     */
    @Override
    public int handleAnswerSubmission(String quizId, String userId, Integer questionNumber, boolean correct) {
        log.info("User {} submitting answer for question {} (correct: {}) in quiz {}",
                 userId, questionNumber, correct, quizId);

        // Validate question number
        if (!questionBankService.isValidQuestionNumber(questionNumber)) {
            log.warn("Invalid question number {} for quiz {}, user {}", questionNumber, quizId, userId);
            throw new IllegalArgumentException("Invalid question number: " + questionNumber + ". Must be between 1 and 10.");
        }

        // Calculate points to award based on question
        // Question N awards N points for correct answer, 0 for incorrect
        int points = correct ? questionBankService.getQuestionPoints(quizId, questionNumber) : INCORRECT_ANSWER_POINTS;

        log.info("Question {} awards {} points (correct: {})", questionNumber, points, correct);

        // Atomically increment score in Redis
        // ZINCRBY is atomic - safe for concurrent updates
        // If user doesn't exist, creates with the points value
        double newScore = leaderboardRepository.incrementScore(quizId, userId, points);

        log.info("User {} score updated in quiz {}: +{} points (question {}), new score: {}",
                 userId, quizId, points, questionNumber, newScore);

        // Publish SCORE_UPDATED event to notify all instances
        // All instances will broadcast updated leaderboard to their WebSocket clients
        eventPublisher.publishScoreUpdated(quizId, userId, (int) newScore);

        log.info("User {} successfully submitted answer for question {} in quiz {}, new score: {}",
                 userId, questionNumber, quizId, newScore);

        return (int) newScore;
    }
    
    /**
     * Get the entire leaderboard for a quiz.
     * 
     * Operations:
     * 1. Fetch all entries from Redis Sorted Set
     * 2. Convert to LeaderboardUpdateMessage
     * 
     * Time Complexity: O(N log N + M) where M is total entries
     * 
     * Note: For large leaderboards, consider using getLeaderboard(quizId, topN)
     * 
     * @param quizId the quiz ID
     * @return leaderboard update message with all entries
     */
    @Override
    public LeaderboardUpdateMessage getLeaderboard(String quizId) {
        log.debug("Fetching entire leaderboard for quiz {}", quizId);
        
        List<LeaderboardEntry> entries = leaderboardRepository.getAll(quizId);
        
        log.debug("Retrieved {} entries from quiz {} leaderboard", entries.size(), quizId);
        
        return new LeaderboardUpdateMessage(quizId, entries);
    }
    
    /**
     * Get the top N users from the leaderboard.
     * 
     * Operations:
     * 1. Fetch top N entries from Redis Sorted Set (single ZREVRANGE call)
     * 2. Convert to LeaderboardUpdateMessage
     * 
     * Time Complexity: O(log N + M) where M is topN
     * 
     * Optimized for high-frequency reads.
     * 
     * @param quizId the quiz ID
     * @param topN number of top users to retrieve
     * @return leaderboard update message with top N entries
     */
    @Override
    public LeaderboardUpdateMessage getLeaderboard(String quizId, int topN) {
        log.debug("Fetching top {} entries from quiz {} leaderboard", topN, quizId);
        
        List<LeaderboardEntry> entries = leaderboardRepository.getTopN(quizId, topN);
        
        log.debug("Retrieved {} entries from quiz {} leaderboard", entries.size(), quizId);
        
        return new LeaderboardUpdateMessage(quizId, entries);
    }
}

