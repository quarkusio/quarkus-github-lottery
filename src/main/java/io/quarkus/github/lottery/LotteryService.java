package io.quarkus.github.lottery;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.Lottery;
import io.quarkus.github.lottery.draw.Participant;
import io.quarkus.github.lottery.history.LotteryHistory;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.history.HistoryService;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.github.lottery.notification.Notifier;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class LotteryService {

    @Inject
    GitHubService gitHubService;

    @Inject
    HistoryService historyService;

    @Inject
    NotificationService notificationService;

    @Inject
    Clock clock;

    /**
     * Draws the lottery and sends lists of tickets to participants as necessary.
     */
    @Scheduled(every = "1H", concurrentExecution = Scheduled.ConcurrentExecution.SKIP) // Every hour
    public synchronized void draw() throws IOException {
        Log.info("Starting draw...");
        List<GitHubRepositoryRef> refs = gitHubService.listRepositories();
        Log.infof("Will draw for the following repositories: %s", refs);

        // TODO parallelize
        for (GitHubRepositoryRef ref : refs) {
            Log.infof("Starting draw for repository %s...", ref);
            try {
                drawForRepository(ref);
                Log.infof("End of draw for repository %s.", ref);
            } catch (Exception e) {
                Log.errorf(e, "Error drawing for repository %s", ref);
            }
        }
        Log.info("End of draw.");
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
        var now = Instant.now(clock);
        var drawRef = new DrawRef(repo.ref(), now);

        Lottery lottery = new Lottery(now, lotteryConfig.buckets());

        try (var notifier = notificationService.notifier(drawRef, lotteryConfig.notifications())) {
            var history = historyService.fetch(drawRef, lotteryConfig);
            List<Participant> participants = registerParticipants(drawRef, lottery, notifier, history,
                    lotteryConfig.participants());

            lottery.draw(repo, history);

            var sent = notifyParticipants(notifier, participants);
            if (!sent.isEmpty()) {
                try {
                    historyService.append(drawRef, lotteryConfig, sent);
                } catch (IOException | RuntimeException e) {
                    Log.errorf(e, "Failed to save the following lottery report to history: %s", sent);
                }
            }
        }
    }

    private List<Participant> registerParticipants(DrawRef drawRef, Lottery lottery, Notifier notifier,
            LotteryHistory history, List<LotteryConfig.Participant> participantConfigs) throws IOException {
        List<Participant> participants = new ArrayList<>();

        // Add participants to the lottery as necessary.
        for (LotteryConfig.Participant participantConfig : participantConfigs) {
            String username = participantConfig.username();
            ZoneId timezone = participantConfig.timezone().orElse(ZoneOffset.UTC);

            Optional<ZonedDateTime> lastNotificationToday = history.lastNotificationToday(username, timezone);
            if (lastNotificationToday.isPresent()) {
                Log.debugf("Skipping user %s who has already been notified today (on %s)",
                        username, lastNotificationToday.get());
                continue;
            }

            if (notifier.hasClosedDedicatedIssue(username)) {
                Log.debugf("Skipping user %s whose dedicated issue is closed", username);
                continue;
            }

            Participant.create(drawRef, participantConfig).ifPresent(p -> {
                participants.add(p);
                p.participate(lottery);
            });
        }

        return participants;
    }

    private List<LotteryReport.Serialized> notifyParticipants(Notifier notifier, List<Participant> participants) {
        List<LotteryReport.Serialized> sent = new ArrayList<>();
        for (var participant : participants) {
            var report = participant.report();
            try {
                Log.debugf("Sending report: %s", report);
                notifier.send(report);
                sent.add(report.serialized());
            } catch (IOException | RuntimeException e) {
                Log.errorf(e, "Failed to send lottery report with content %s", report);
            }
        }
        return sent;
    }

}
