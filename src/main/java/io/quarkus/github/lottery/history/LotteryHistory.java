package io.quarkus.github.lottery.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private static Instant min(Instant first, Instant... others) {
        Instant result = first;
        for (Instant other : others) {
            if (other.isBefore(result)) {
                result = other;
            }
        }
        return result;
    }

    private final Map<String, Instant> lastNotificationInstantByUsername = new HashMap<>();

    private final Instant now;
    private final Instant since;

    private final Bucket triage;
    private final Bucket reproducerNeeded;
    private final Bucket reproducerProvided;
    private final Bucket stale;
    private final Bucket stewardship;

    public LotteryHistory(Instant now, LotteryConfig.Buckets config) {
        this.now = now;
        // lastNotificationToday only needs history over the last 2 days,
        // because we only need to say if the last notification was today or not.
        Instant lastNotificationTodayCutoff = now.minus(2, ChronoUnit.DAYS);
        // Each bucket has a configurable "timeout" that gives the notified user some time to address
        // a notification, after which (if the notification hasn't been addressed)
        // a new notification will be sent for the same ticket.
        // For that mechanism, we need to retrieve all past notifications that did not time out.
        Instant triageNotificationTimeoutCutoff = now.minus(config.triage().notification().timeout());
        Instant reproducerNeededNotificationTimeoutCutoff = now
                .minus(config.maintenance().reproducer().needed().notification().timeout());
        Instant reproducerProvidedNotificationTimeoutCutoff = now
                .minus(config.maintenance().reproducer().provided().notification().timeout());
        Instant staleNotificationTimeoutCutoff = now.minus(config.maintenance().stale().notification().timeout());
        Instant stewardshipNotificationTimeoutCutoff = now.minus(config.stewardship().notification().timeout());
        this.since = min(lastNotificationTodayCutoff, triageNotificationTimeoutCutoff,
                reproducerNeededNotificationTimeoutCutoff, reproducerProvidedNotificationTimeoutCutoff,
                staleNotificationTimeoutCutoff,
                stewardshipNotificationTimeoutCutoff);
        this.triage = new Bucket(triageNotificationTimeoutCutoff);
        this.reproducerNeeded = new Bucket(reproducerNeededNotificationTimeoutCutoff);
        this.reproducerProvided = new Bucket(reproducerProvidedNotificationTimeoutCutoff);
        this.stale = new Bucket(staleNotificationTimeoutCutoff);
        this.stewardship = new Bucket(stewardshipNotificationTimeoutCutoff);
    }

    Instant since() {
        return since;
    }

    void add(LotteryReport.Serialized report) {
        var instant = report.instant();
        lastNotificationInstantByUsername.merge(report.username(), instant, LotteryHistory::max);

        report.triage().ifPresent(bucket -> triage().add(instant, bucket));
        report.reproducerNeeded().ifPresent(bucket -> reproducerNeeded().add(instant, bucket));
        report.reproducerProvided().ifPresent(bucket -> reproducerProvided().add(instant, bucket));
        report.stale().ifPresent(bucket -> stale().add(instant, bucket));
    }

    public Optional<ZonedDateTime> lastNotificationToday(String username, ZoneId timezone) {
        return Optional.ofNullable(lastNotificationInstantByUsername.get(username))
                .map(instant -> instant.atZone(timezone))
                .filter(lastNotificationDate -> now.atZone(timezone).toLocalDate() // Ignore time of day.
                        .equals(lastNotificationDate.toLocalDate()));
    }

    public Bucket triage() {
        return triage;
    }

    public Bucket reproducerNeeded() {
        return reproducerNeeded;
    }

    public Bucket reproducerProvided() {
        return reproducerProvided;
    }

    public Bucket stale() {
        return stale;
    }

    public Bucket stewardship() {
        return stewardship;
    }

    public static class Bucket {
        private final Instant notificationTimeoutCutoff;
        private final Map<Integer, Instant> lastNotificationInstantByIssueNumber = new HashMap<>();

        public Bucket(Instant notificationTimeoutCutoff) {
            this.notificationTimeoutCutoff = notificationTimeoutCutoff;
        }

        public boolean lastNotificationTimedOutForIssueNumber(int issueNumber) {
            return lastNotificationInstantByIssueNumber.getOrDefault(issueNumber, Instant.MIN)
                    .isBefore(notificationTimeoutCutoff);
        }

        private void add(Instant instant, LotteryReport.Bucket.Serialized bucket) {
            for (int issueNumber : bucket.issueNumbers()) {
                lastNotificationInstantByIssueNumber.merge(issueNumber, instant, LotteryHistory::max);
            }
        }

    }

}
