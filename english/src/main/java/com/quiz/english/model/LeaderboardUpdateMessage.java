package com.quiz.english.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Server broadcast of leaderboard update.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class LeaderboardUpdateMessage extends WebSocketMessage {
    private String quizId;
    private List<LeaderboardEntry> leaderboard;

    public LeaderboardUpdateMessage(String quizId, List<LeaderboardEntry> leaderboard) {
        super(MessageType.LEADERBOARD_UPDATE);
        this.quizId = quizId;
        this.leaderboard = leaderboard;
    }
}

