package com.quiz.english.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Client message to join a quiz room.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class JoinMessage extends WebSocketMessage {
    private String quizId;
    private String userId;
    
    public JoinMessage(String quizId, String userId) {
        super(MessageType.JOIN);
        this.quizId = quizId;
        this.userId = userId;
    }
}

