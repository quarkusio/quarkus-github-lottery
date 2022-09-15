package io.quarkus.github.lottery;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.Lottery;
import io.quarkus.github.lottery.draw.Participant;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.github.lottery.notification.Notifier;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class LotteryService {

    @Inject
    GitHubService gitHubService;

    @Inject
    NotificationService notificationService;

    @Inject
    Clock clock;

    /**
     * Draws the lottery and sends lists of tickets to participants as necessary.
     */
    @Scheduled(cron = "0 0 * ? * *") // Every hour
    public void draw() throws IOException {
        List<GitHubRepositoryRef> refs = gitHubService.listRepositories();

        // TODO parallelize
        for (GitHubRepositoryRef ref : refs) {
            drawForRepository(ref);
        }
    }

    private void drawForRepository(GitHubRepositoryRef repoRef) throws IOException {
        try (GitHubRepository repo = gitHubService.repository(repoRef)) {
            var optionalLotteryConfig = repo.fetchLotteryConfig();
            if (optionalLotteryConfig.isEmpty()) {
                Log.infof("No lottery configuration found for %s; not drawing lottery.", repoRef);
                return;
            }
            doDrawForRepository(repo, optionalLotteryConfig.get());
        }
    }

    private void doDrawForRepository(GitHubRepository repo, LotteryConfig lotteryConfig) throws IOException {
        Lottery lottery = new Lottery(lotteryConfig.labels());

        var drawRef = new DrawRef(repo.ref().repositoryName(), Instant.now(clock));
        try (var notifier = notificationService.notifier(repo, lotteryConfig.notifications())) {
            List<Participant> participants = registerParticipants(drawRef, lottery, notifier, lotteryConfig.participants());

            lottery.draw(repo);

            notifyParticipants(notifier, participants);
        }
    }

    private List<Participant> registerParticipants(DrawRef drawRef, Lottery lottery,
            Notifier notifier, List<LotteryConfig.ParticipantConfig> participantConfigs) throws IOException {
        List<Participant> participants = new ArrayList<>();

        // TODO handle (configurable) user timezones
        ZoneOffset zone = ZoneOffset.UTC;

        // Add participants to the lottery as necessary.
        for (LotteryConfig.ParticipantConfig participantConfig : participantConfigs) {
            LocalDate drawDate = drawRef.instant().atZone(zone).toLocalDate();
            if (!participantConfig.when().contains(drawDate.getDayOfWeek())) {
                // This user does not participate to the draw on this day of the week.
                continue;
            }
            Optional<LocalDate> lastNotificationDate = notifier.lastNotificationInstant(drawRef, participantConfig.username())
                    .map(instant -> instant.atZone(zone).toLocalDate());
            if (lastNotificationDate.isPresent() && lastNotificationDate.get().equals(drawDate)) {
                // This user already participated in a draw today.
                continue;
            }

            var participant = new Participant(drawRef, participantConfig);
            participants.add(participant);

            participant.participate(lottery);

            // TODO also handle maintainers
        }

        return participants;
    }

    private void notifyParticipants(Notifier notifier, List<Participant> participants) {
        for (var participant : participants) {
            var report = participant.report();
            try {
                notifier.send(report);
            } catch (IOException | RuntimeException e) {
                Log.errorf(e, "Failed to send lottery report with content %s", report);
            }
            // TODO persist the information "these issues were notified on that day to that person"
        }
    }

}
