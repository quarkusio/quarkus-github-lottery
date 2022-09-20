package io.quarkus.github.lottery.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.quarkus.github.lottery.draw.LotteryReport;

public class LotteryHistory {

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private final Map<String, Instant> lastNotificationInstantByUsername = new HashMap<>();

    private final Instant since;

    public LotteryHistory(Instant now) {
        Instant minSinceToResolveLastNotificationInstantForUsername = now.minus(2, ChronoUnit.DAYS);
        this.since = minSinceToResolveLastNotificationInstantForUsername;
    }

    Instant since() {
        return since;
    }

    void add(LotteryReport.Serialized report) {
        var instant = report.instant();
        lastNotificationInstantByUsername.merge(report.username(), instant, LotteryHistory::max);

        // TODO also extract information by bucket
    }

    public Optional<Instant> lastNotificationInstantForUsername(String username) {
        return Optional.ofNullable(lastNotificationInstantByUsername.get(username));
    }

}
