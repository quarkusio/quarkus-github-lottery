package io.quarkus.github.lottery.draw;

import java.io.IOException;
import java.util.Random;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.Installation;

/**
 * A lottery, with a {@link LotteryBucket buckets} for each pool of issues.
 */
public final class Lottery {

    private final LotteryConfig.LabelsConfig labels;
    private final LotteryBucket triageBucket;

    public Lottery(LotteryConfig.LabelsConfig labels) {
        this.labels = labels;
        Random random = new Random();
        this.triageBucket = new LotteryBucket(random);
        // TODO add more buckets for maintenance, ...
    }

    LotteryBucket triage() {
        return triageBucket;
    }

    public void draw(Installation installation) throws IOException {
        if (triageBucket.hasTickets()) {
            triageBucket.draw(installation.issuesWithLabel(labels.needsTriage()));
        }
        // TODO draw for other buckets
    }
}
