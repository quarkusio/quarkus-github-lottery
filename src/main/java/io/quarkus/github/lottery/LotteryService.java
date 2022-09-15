package io.quarkus.github.lottery;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
    @Scheduled(cron = "0 0 8 ? * *") // Every day at 8 AM
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
        List<Participant> participants = registerParticipants(drawRef, lottery, lotteryConfig.participants());

        lottery.draw(repo);

        try (var notifier = notificationService.notifier(repo, lotteryConfig.notifications())) {
            notifyParticipants(notifier, participants);
        }
    }

    private List<Participant> registerParticipants(DrawRef drawRef, Lottery lottery,
            List<LotteryConfig.ParticipantConfig> participantConfigs) {
        List<Participant> participants = new ArrayList<>();

        // TODO handle user timezones. That implies running the draw multiple times per day,
        // which implies persistence to remember whether a user was already notified (and thus must not be notified again that day).
        DayOfWeek dayOfWeek = drawRef.instant().atZone(ZoneOffset.UTC).getDayOfWeek();

        // Add participants to the lottery as necessary.
        for (LotteryConfig.ParticipantConfig participantConfig : participantConfigs) {
            if (!participantConfig.when().contains(dayOfWeek)) {
                // Not notifying this user today.
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
