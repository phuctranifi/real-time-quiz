package com.quiz.english.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server error message.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ErrorMessage extends WebSocketMessage {
    private String error;
    private String details;
    
    public ErrorMessage(String error, String details) {
        super(MessageType.ERROR);
        this.error = error;
        this.details = details;
    }
}

