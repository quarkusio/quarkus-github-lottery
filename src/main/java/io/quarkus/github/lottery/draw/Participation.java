package io.quarkus.github.lottery.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.Issue;

/**
 * A participation of one {@link Participant} to one {@link Lottery.Bucket}.
 */
final class Participation {
    public static Optional<Participation> create(String username, LotteryConfig.Participant.Participation config) {
        if (config.maxIssues() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Participation(username, config.maxIssues()));
    }

    private final String username;
    final int maxIssues;

    final List<Issue> issues = new ArrayList<>();

    private Participation(String username, int maxIssues) {
        this.username = username;
        this.maxIssues = maxIssues;
    }

    @Override
    public String toString() {
        return "Participation[username=" + username + ", maxIssues=" + maxIssues + "]";
    }

    String username() {
        return username;
    }

    List<Issue> issues() {
        return issues;
    }
}
