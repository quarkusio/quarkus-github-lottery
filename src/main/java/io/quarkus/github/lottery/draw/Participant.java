package io.quarkus.github.lottery.draw;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.logging.Log;

/**
 * A participant in the {@link Lottery}.
 */
public final class Participant {

    public static Optional<Participant> create(DrawRef drawRef, LotteryConfig.Participant config) {
        var username = config.username();
        var timezone = config.timezone().orElse(ZoneOffset.UTC);

        LocalDate drawDate = drawRef.instant().atZone(timezone).toLocalDate();
        DayOfWeek dayOfWeek = drawDate.getDayOfWeek();
        var triage = config.triage()
                .filter(c -> isDay(c.days(), dayOfWeek, username, "triage"))
                .map(LotteryConfig.Participant.Triage::participation)
                .flatMap(c -> Participation.create(username, c));
        var maintenance = config.maintenance()
                .filter(c -> isDay(c.days(), dayOfWeek, username, "maintenance"))
                .flatMap(c -> Maintenance.create(username, c));
        var stewardship = config.stewardship()
                .filter(c -> isDay(c.days(), dayOfWeek, username, "stewardship"))
                .map(LotteryConfig.Participant.Stewardship::participation)
                .flatMap(c -> Participation.create(username, c));

        if (triage.isEmpty() && maintenance.isEmpty() && stewardship.isEmpty()) {
            Log.debugf("Skipping user %s because they participate in neither triage, maintenance nor stewardship", username);
            return Optional.empty();
        }

        return Optional.of(new Participant(drawRef, username, config.timezone(), triage, maintenance, stewardship));
    }

    private static boolean isDay(Set<DayOfWeek> acceptedDays, DayOfWeek testedDay,
            String username, String participationName) {
        if (!acceptedDays.contains(testedDay)) {
            Log.debugf("Skipping %s for user %s who wants to be notified on %s, because today is %s",
                    participationName, username, acceptedDays, testedDay);
            return false;
        }
        return true;
    }

    private final DrawRef drawRef;
    private final String username;
    private final Optional<ZoneId> timezone;

    private final Optional<Participation> triage;
    private final Optional<Maintenance> maintenance;
    private final Optional<Participation> stewardship;

    private Participant(DrawRef drawRef, String username, Optional<ZoneId> timezone, Optional<Participation> triage,
            Optional<Maintenance> maintenance, Optional<Participation> stewardship) {
        this.drawRef = drawRef;
        this.username = username;
        this.timezone = timezone;
        this.triage = triage;
        this.maintenance = maintenance;
        this.stewardship = stewardship;
    }

    public void participate(Lottery lottery) {
        triage.ifPresent(lottery.triage()::participate);
        maintenance.ifPresent(m -> m.participate(lottery));
        stewardship.ifPresent(lottery.stewardship()::participate);
    }

    public LotteryReport report(String triageLabel, Set<String> feedbackLabels) {
        return new LotteryReport(drawRef, username, timezone,
                new LotteryReport.Config(
                        triageLabel,
                        feedbackLabels,
                        maintenance.map(m -> m.labels).orElseGet(Set::of)),
                triage.map(Participation::issues).map(LotteryReport.Bucket::new),
                maintenance.flatMap(m -> m.created).map(Participation::issues).map(LotteryReport.Bucket::new),
                maintenance.flatMap(m -> m.feedbackNeeded).map(Participation::issues).map(LotteryReport.Bucket::new),
                maintenance.flatMap(m -> m.feedbackProvided).map(Participation::issues).map(LotteryReport.Bucket::new),
                maintenance.flatMap(m -> m.stale).map(Participation::issues).map(LotteryReport.Bucket::new),
                stewardship.map(Participation::issues).map(LotteryReport.Bucket::new));
    }

    private static final class Maintenance {
        public static Optional<Maintenance> create(String username, LotteryConfig.Participant.Maintenance config) {
            var created = config.created()
                    .flatMap(p -> Participation.create(username, p));
            var feedbackNeeded = config.feedback().map(LotteryConfig.Participant.Maintenance.Feedback::needed)
                    .flatMap(p -> Participation.create(username, p));
            var feedbackProvided = config.feedback().map(LotteryConfig.Participant.Maintenance.Feedback::provided)
                    .flatMap(p -> Participation.create(username, p));
            var stale = config.stale()
                    .flatMap(p -> Participation.create(username, p));

            if (created.isEmpty() && feedbackNeeded.isEmpty() && feedbackProvided.isEmpty() && stale.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new Maintenance(username, config.labels(), created, feedbackNeeded, feedbackProvided, stale));
        }

        private final String username;
        private final Set<String> labels;

        private final Optional<Participation> created;
        private final Optional<Participation> feedbackNeeded;
        private final Optional<Participation> feedbackProvided;
        private final Optional<Participation> stale;

        private Maintenance(String username, List<String> labels,
                Optional<Participation> created,
                Optional<Participation> feedbackNeeded,
                Optional<Participation> feedbackProvided,
                Optional<Participation> stale) {
            this.username = username;
            // Remove duplicates, but preserve order
            this.labels = new LinkedHashSet<>(labels);
            this.created = created;
            this.feedbackNeeded = feedbackNeeded;
            this.feedbackProvided = feedbackProvided;
            this.stale = stale;
        }

        public void participate(Lottery lottery) {
            for (String label : labels) {
                Lottery.Maintenance maintenance = lottery.maintenance(label);
                created.ifPresent(maintenance.created()::participate);
                feedbackNeeded.ifPresent(maintenance.feedbackNeeded()::participate);
                feedbackProvided.ifPresent(maintenance.feedbackProvided()::participate);
                stale.ifPresent(maintenance.stale()::participate);
            }
        }
    }
}
