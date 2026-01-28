package com.quiz.english.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Base class for all WebSocket messages.
 * Uses Jackson polymorphic deserialization to handle different message types.
 */
@Data
@NoArgsConstructor
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = JoinMessage.class, name = "JOIN"),
    @JsonSubTypes.Type(value = SubmitAnswerMessage.class, name = "SUBMIT_ANSWER"),
    @JsonSubTypes.Type(value = JoinSuccessMessage.class, name = "JOIN_SUCCESS"),
    @JsonSubTypes.Type(value = ErrorMessage.class, name = "ERROR"),
    @JsonSubTypes.Type(value = LeaderboardUpdateMessage.class, name = "LEADERBOARD_UPDATE"),
    @JsonSubTypes.Type(value = AnswerResultMessage.class, name = "ANSWER_RESULT")
})
public abstract class WebSocketMessage {
    private MessageType type;
    
    protected WebSocketMessage(MessageType type) {
        this.type = type;
    }
}

