package com.quiz.english.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a quiz question with its point value.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Question {
    /**
     * Question number (1-10).
     */
    private Integer questionNumber;
    
    /**
     * Question text.
     */
    private String questionText;
    
    /**
     * Points awarded for correct answer.
     * Question 1 = 1 point, Question 2 = 2 points, etc.
     */
    private Integer points;
    
    /**
     * Correct answer (for validation).
     */
    private String correctAnswer;
}

