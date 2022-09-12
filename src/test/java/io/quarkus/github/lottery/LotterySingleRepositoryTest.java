package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.MockHelper.url;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Installation;
import io.quarkus.github.lottery.github.InstallationRef;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class LotterySingleRepositoryTest {
    GitHubService gitHubServiceMock;
    Installation installationMock;
    Clock clockMock;
    NotificationService notificationServiceMock;

    @Inject
    LotteryService lotteryService;

    @BeforeEach
    void setup() throws IOException {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        var ref1 = new InstallationRef(1L, "quarkusio/quarkus");
        when(gitHubServiceMock.listInstallations()).thenReturn(List.of(ref1));

        installationMock = Mockito.mock(Installation.class);
        when(gitHubServiceMock.installation(ref1)).thenReturn(installationMock);

        clockMock = Clock.fixed(LocalDateTime.of(2017, 11, 6, 8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        notificationServiceMock = Mockito.mock(NotificationService.class);
        QuarkusMock.installMockForType(notificationServiceMock, NotificationService.class);
    }

    @Test
    void noConfig() throws IOException {
        when(installationMock.fetchLotteryConfig()).thenReturn(Optional.empty());

        lotteryService.draw();

        verifyNoMoreInteractions(gitHubServiceMock, installationMock);
    }

    @Test
    void participant_when_differentDay() throws IOException {
        when(installationMock.fetchLotteryConfig()).thenReturn(Optional.of(new LotteryConfig(
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.TUESDAY),
                        new LotteryConfig.ParticipationConfig(3))))));

        lotteryService.draw();

        // Today is Monday, but the subscription requests notifications on Tuesday.
        // Nothing to do.
        verifyNoMoreInteractions(gitHubServiceMock, installationMock);
    }

    @Test
    void singleParticipant() throws IOException {
        when(installationMock.fetchLotteryConfig()).thenReturn(Optional.of(new LotteryConfig(
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY),
                        new LotteryConfig.ParticipationConfig(3))))));

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(installationMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.iterator());

        lotteryService.draw();

        verify(notificationServiceMock).notify(installationMock, "yrodiere", new LotteryReport(
                issueNeedingTriage.subList(0, 3)));

        verifyNoMoreInteractions(gitHubServiceMock, installationMock);
    }

}