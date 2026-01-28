package com.quiz.english.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server response confirming successful join.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class JoinSuccessMessage extends WebSocketMessage {
    private String quizId;
    private String userId;
    private String message;
    
    public JoinSuccessMessage(String quizId, String userId, String message) {
        super(MessageType.JOIN_SUCCESS);
        this.quizId = quizId;
        this.userId = userId;
        this.message = message;
    }
}

