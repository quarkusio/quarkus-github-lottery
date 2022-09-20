package io.quarkus.github.lottery.draw;

import java.io.IOException;
import java.util.Random;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.history.LotteryHistory;

/**
 * A lottery, with a {@link LotteryBucket buckets} for each pool of issues.
 */
public final class Lottery {

    private final LotteryConfig.BucketsConfig buckets;
    private final LotteryBucket triageBucket;

    public Lottery(LotteryConfig.BucketsConfig buckets) {
        this.buckets = buckets;
        Random random = new Random();
        this.triageBucket = new LotteryBucket(random);
        // TODO add more buckets for maintenance, ...
    }

    LotteryBucket triage() {
        return triageBucket;
    }

    public void draw(GitHubRepository repo, LotteryHistory lotteryHistory) throws IOException {
        if (triageBucket.hasTickets()) {
            var triageHistory = lotteryHistory.triage();
            triageBucket.draw(repo.issuesWithLabel(buckets.triage().needsTriageLabel())
                    .filter(issue -> triageHistory.lastNotificationExpiredForIssueNumber(issue.number()))
                    .iterator());
        }
        // TODO draw for other buckets
    }
}
