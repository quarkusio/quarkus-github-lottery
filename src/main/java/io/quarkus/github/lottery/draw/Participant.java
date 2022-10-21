package io.quarkus.github.lottery.draw;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
        var triage = Optional.of(config.triage())
                .filter(c -> isDay(c.days(), dayOfWeek, username, "triage"))
                .map(LotteryConfig.Participant.Triage::participation)
                .flatMap(c -> Participation.create(username, c));

        if (triage.isEmpty()) {
            Log.debugf("Skipping user %s because they don't participate in triage", username);
            return Optional.empty();
        }

        return Optional.of(new Participant(drawRef, username, config.timezone(), triage.get()));
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

    private final Participation triage;

    private Participant(DrawRef drawRef, String username, Optional<ZoneId> timezone, Participation triage) {
        this.drawRef = drawRef;
        this.username = username;
        this.timezone = timezone;
        this.triage = triage;
    }

    public void participate(Lottery lottery) {
        lottery.triage().participate(triage);
    }

    public LotteryReport report() {
        return new LotteryReport(drawRef, username, timezone,
                new LotteryReport.Bucket(triage.issues));
    }
}
