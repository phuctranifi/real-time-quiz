package com.quiz.english.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server response to answer submission.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AnswerResultMessage extends WebSocketMessage {
    private String quizId;
    private String userId;
    private Integer questionNumber;  // Question number answered
    private Boolean correct;
    private Integer pointsEarned;    // Points earned for this answer
    private Integer newScore;        // Total score after this answer

    public AnswerResultMessage(String quizId, String userId, Integer questionNumber, Boolean correct, Integer pointsEarned, Integer newScore) {
        super(MessageType.ANSWER_RESULT);
        this.quizId = quizId;
        this.userId = userId;
        this.questionNumber = questionNumber;
        this.correct = correct;
        this.pointsEarned = pointsEarned;
        this.newScore = newScore;
    }
}

