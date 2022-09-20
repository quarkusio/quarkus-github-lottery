package io.quarkus.github.lottery.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.LotteryReport;

public class LotteryHistory {

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private final Map<String, Instant> lastNotificationInstantByUsername = new HashMap<>();

    private final Instant since;

    private final Bucket triage;

    public LotteryHistory(Instant now, LotteryConfig.BucketsConfig config) {
        Instant minSinceToResolveLastNotificationInstantForUsername = now.minus(2, ChronoUnit.DAYS);
        Instant triageExpirationCutoff = now.minus(config.triage().notificationExpiration());
        this.since = min(minSinceToResolveLastNotificationInstantForUsername, triageExpirationCutoff);
        this.triage = new Bucket(triageExpirationCutoff);
    }

    Instant since() {
        return since;
    }

    void add(LotteryReport.Serialized report) {
        var instant = report.instant();
        lastNotificationInstantByUsername.merge(report.username(), instant, LotteryHistory::max);

        triage().add(instant, report.triage());
        // TODO also extract information for other buckets (when there *are* other buckets)
    }

    public Optional<Instant> lastNotificationInstantForUsername(String username) {
        return Optional.ofNullable(lastNotificationInstantByUsername.get(username));
    }

    public Bucket triage() {
        return triage;
    }

    public static class Bucket {
        private final Instant expirationCutoff;
        private final Map<Integer, Instant> lastNotificationInstantByIssueNumber = new HashMap<>();

        public Bucket(Instant expirationCutoff) {
            this.expirationCutoff = expirationCutoff;
        }

        public boolean lastNotificationExpiredForIssueNumber(int issueNumber) {
            return lastNotificationInstantByIssueNumber.getOrDefault(issueNumber, Instant.MIN)
                    .isBefore(expirationCutoff);
        }

        private void add(Instant instant, LotteryReport.Bucket.Serialized bucket) {
            for (int issueNumber : bucket.issueNumbers()) {
                lastNotificationInstantByIssueNumber.merge(issueNumber, instant, LotteryHistory::max);
            }
        }

    }

}
