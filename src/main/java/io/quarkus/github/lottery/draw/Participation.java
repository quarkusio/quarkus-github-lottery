package io.quarkus.github.lottery.draw;

import java.util.List;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.Issue;

/**
 * A participation of one {@link Participant} to one {@link LotteryBucket}.
 */
final class Participation {
    private final LotteryConfig.ParticipationConfig config;

    private LotteryTicket ticket;

    Participation(LotteryConfig.ParticipationConfig config) {
        this.config = config;
    }

    void participate(LotteryBucket bucket) {
        if (config != null && config.maxIssues() > 0) {
            ticket = bucket.ticket(config.maxIssues());
        }
    }

    List<Issue> result() {
        if (ticket == null) {
            return List.of();
        }
        return ticket.winnings;
    }
}
