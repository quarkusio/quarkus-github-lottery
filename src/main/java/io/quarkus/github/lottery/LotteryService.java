package io.quarkus.github.lottery;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.Lottery;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.draw.Participant;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Installation;
import io.quarkus.github.lottery.github.InstallationRef;
import io.quarkus.github.lottery.notification.NotificationService;
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
        List<InstallationRef> refs = gitHubService.listInstallations();

        // TODO parallelize
        for (InstallationRef ref : refs) {
            drawForInstallation(ref);
        }
    }

    private void drawForInstallation(InstallationRef ref) throws IOException {
        Installation installation = gitHubService.installation(ref);
        var optionalLotteryConfig = installation.fetchLotteryConfig();
        if (optionalLotteryConfig.isEmpty()) {
            Log.infof("No lottery configuration found for %s; not drawing lottery.", ref);
            return;
        }
        LotteryConfig lotteryConfig = optionalLotteryConfig.get();

        List<Participant> participants = new ArrayList<>();

        // TODO handle user timezones. That implies running the draw multiple times per day,
        // which implies persistence to remember whether a user was already notified (and thus must not be notified again that day).
        DayOfWeek dayOfWeek = LocalDate.now(clock).getDayOfWeek();

        Lottery lottery = new Lottery(lotteryConfig.labels());

        // Add participants to the lottery as necessary.
        for (LotteryConfig.ParticipantConfig participantConfig : lotteryConfig.participants()) {
            if (!participantConfig.when().contains(dayOfWeek)) {
                // Not notifying this user today.
                continue;
            }

            var participant = new Participant(participantConfig);
            participants.add(participant);

            participant.participate(lottery);

            // TODO also handle maintainers
        }

        lottery.draw(installation);

        for (Participant participant : participants) {
            LotteryReport report = participant.report();
            notificationService.notify(installation, participant.username, report);
        }
    }

}
