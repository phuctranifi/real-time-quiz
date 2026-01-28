package com.quiz.english.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Client message to submit an answer.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SubmitAnswerMessage extends WebSocketMessage {
    private String quizId;
    private String userId;
    private Integer questionNumber;  // Question number (1-10)
    private Boolean correct;

    public SubmitAnswerMessage(String quizId, String userId, Integer questionNumber, Boolean correct) {
        super(MessageType.SUBMIT_ANSWER);
        this.quizId = quizId;
        this.userId = userId;
        this.questionNumber = questionNumber;
        this.correct = correct;
    }
}

