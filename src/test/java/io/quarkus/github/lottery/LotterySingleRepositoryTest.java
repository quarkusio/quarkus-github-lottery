package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.MockHelper.url;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.github.lottery.draw.DrawRef;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.github.lottery.notification.Notifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class LotterySingleRepositoryTest {
    GitHubService gitHubServiceMock;
    GitHubRepository repoMock;
    Clock clockMock;
    NotificationService notificationServiceMock;

    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @Inject
    LotteryService lotteryService;;

    @BeforeEach
    void setup() throws IOException {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        repoRef = new GitHubRepositoryRef(1L, "quarkusio/quarkus");
        when(gitHubServiceMock.listRepositories()).thenReturn(List.of(repoRef));

        repoMock = Mockito.mock(GitHubRepository.class);
        when(gitHubServiceMock.repository(repoRef)).thenReturn(repoMock);

        // Note tests below assume this is at least 1AM
        Instant now = LocalDateTime.of(2017, 11, 6, 8, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef.repositoryName(), now);
        clockMock = Clock.fixed(drawRef.instant(), ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        notificationServiceMock = Mockito.mock(NotificationService.class);
        QuarkusMock.installMockForType(notificationServiceMock, NotificationService.class);
    }

    @Test
    void noConfig() throws IOException {
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.empty());

        lotteryService.draw();

        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock);
    }

    @Test
    void participant_when_differentDay() throws IOException {
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.TUESDAY),
                        new LotteryConfig.ParticipationConfig(3))))));
        when(repoMock.ref()).thenReturn(repoRef);

        lotteryService.draw();

        verify(repoMock).close();

        // Today is Monday, but the participant wants notifications on Tuesday.
        // Nothing to do.
        verifyNoMoreInteractions(gitHubServiceMock, repoMock);
    }

    @Test
    void singleParticipant_neverNotified() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.iterator());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(repoMock, config.notifications())).thenReturn(notifierMock);
        when(notifierMock.lastNotificationInstant(drawRef, "yrodiere")).thenReturn(Optional.empty());

        lotteryService.draw();

        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere",
                issueNeedingTriage.subList(0, 3)));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock);
    }

    @Test
    void singleParticipant_notifiedYesterday() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.iterator());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(repoMock, config.notifications())).thenReturn(notifierMock);
        when(notifierMock.lastNotificationInstant(drawRef, "yrodiere"))
                .thenReturn(Optional.of(drawRef.instant().minus(1, ChronoUnit.DAYS)));

        lotteryService.draw();

        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere",
                issueNeedingTriage.subList(0, 3)));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock);
    }

    @Test
    void singleParticipant_alreadyNotifiedToday() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(repoMock, config.notifications())).thenReturn(notifierMock);
        when(notifierMock.lastNotificationInstant(drawRef, "yrodiere"))
                .thenReturn(Optional.of(drawRef.instant().minus(1, ChronoUnit.HOURS)));

        lotteryService.draw();

        verify(notifierMock).close();
        verify(repoMock).close();

        // The participant was already notified today.
        // Nothing to do.
        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock);
    }

    @RepeatedTest(10) // Just to be reasonably certain that issues are spread evenly
    void multiParticipants_evenSpread() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.LabelsConfig("needs-triage"),
                List.of(
                        new LotteryConfig.ParticipantConfig(
                                "yrodiere",
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.ParticipationConfig(10)),
                        new LotteryConfig.ParticipantConfig(
                                "gsmet",
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.ParticipationConfig(10))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.iterator());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(repoMock, config.notifications())).thenReturn(notifierMock);
        when(notifierMock.lastNotificationInstant(drawRef, "yrodiere")).thenReturn(Optional.empty());
        when(notifierMock.lastNotificationInstant(drawRef, "gsmet")).thenReturn(Optional.empty());

        lotteryService.draw();

        var reportCaptor = ArgumentCaptor.forClass(LotteryReport.class);
        verify(notifierMock, Mockito.times(2)).send(reportCaptor.capture());
        var reports = reportCaptor.getAllValues();

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock);

        for (var report : reports) {
            assertThat(report.issuesToTriage()).hasSize(2);
        }
    }

}