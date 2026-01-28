package com.quiz.english.service;

import com.quiz.english.model.Question;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to manage quiz questions.
 * Each quiz has a predefined set of questions (1-10).
 * Question N awards N points for correct answer.
 */
@Service
public class QuestionBankService {
    
    /**
     * Question bank per quiz.
     * Key: quizId
     * Value: Map of questionNumber -> Question
     */
    private final Map<String, Map<Integer, Question>> quizQuestions = new ConcurrentHashMap<>();
    
    /**
     * Initialize questions for a quiz.
     * Creates 10 questions where question N awards N points.
     *
     * @param quizId the quiz ID
     */
    public void initializeQuestions(String quizId) {
        Map<Integer, Question> questions = new HashMap<>();
        
        // Create 10 questions
        for (int i = 1; i <= 10; i++) {
            Question question = new Question(
                i,
                "Question " + i + ": What is the answer to question " + i + "?",
                i,  // Points = question number
                "answer" + i  // Correct answer (not used in current implementation)
            );
            questions.put(i, question);
        }
        
        quizQuestions.put(quizId, questions);
    }
    
    /**
     * Get a specific question.
     *
     * @param quizId the quiz ID
     * @param questionNumber the question number (1-10)
     * @return the question, or null if not found
     */
    public Question getQuestion(String quizId, Integer questionNumber) {
        // Auto-initialize if quiz doesn't exist
        if (!quizQuestions.containsKey(quizId)) {
            initializeQuestions(quizId);
        }
        
        Map<Integer, Question> questions = quizQuestions.get(quizId);
        return questions != null ? questions.get(questionNumber) : null;
    }
    
    /**
     * Get all questions for a quiz.
     *
     * @param quizId the quiz ID
     * @return list of all questions
     */
    public List<Question> getAllQuestions(String quizId) {
        // Auto-initialize if quiz doesn't exist
        if (!quizQuestions.containsKey(quizId)) {
            initializeQuestions(quizId);
        }
        
        Map<Integer, Question> questions = quizQuestions.get(quizId);
        if (questions == null) {
            return Collections.emptyList();
        }
        
        return new ArrayList<>(questions.values());
    }
    
    /**
     * Get points for a question.
     *
     * @param quizId the quiz ID
     * @param questionNumber the question number (1-10)
     * @return points for the question, or 0 if not found
     */
    public int getQuestionPoints(String quizId, Integer questionNumber) {
        Question question = getQuestion(quizId, questionNumber);
        return question != null ? question.getPoints() : 0;
    }
    
    /**
     * Validate question number.
     *
     * @param questionNumber the question number
     * @return true if valid (1-10), false otherwise
     */
    public boolean isValidQuestionNumber(Integer questionNumber) {
        return questionNumber != null && questionNumber >= 1 && questionNumber <= 10;
    }
}

