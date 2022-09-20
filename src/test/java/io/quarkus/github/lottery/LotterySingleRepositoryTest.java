package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.MockHelper.url;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.history.HistoryService;
import io.quarkus.github.lottery.history.LotteryHistory;
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
    HistoryService historyServiceMock;

    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @Inject
    LotteryService lotteryService;

    @BeforeEach
    void setup() throws IOException {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        repoRef = new GitHubRepositoryRef(1L, "quarkusio/quarkus");
        when(gitHubServiceMock.listRepositories()).thenReturn(List.of(repoRef));

        repoMock = Mockito.mock(GitHubRepository.class);
        when(gitHubServiceMock.repository(repoRef)).thenReturn(repoMock);

        // Note tests below assume this is at least 1AM
        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef, now);
        clockMock = Clock.fixed(drawRef.instant(), ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        notificationServiceMock = Mockito.mock(NotificationService.class);
        QuarkusMock.installMockForType(notificationServiceMock, NotificationService.class);

        historyServiceMock = Mockito.mock(HistoryService.class);
        QuarkusMock.installMockForType(historyServiceMock, HistoryService.class);
    }

    @Test
    void noConfig() throws IOException {
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.empty());

        lotteryService.draw();

        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, historyServiceMock);
    }

    @Test
    void participant_days_differentDay_defaultTimezone() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.TUESDAY), Optional.empty(),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);

        lotteryService.draw();

        verify(repoMock).close();

        // The participant wants notifications on Tuesday, but we're Monday in UTC.
        // Nothing to do.
        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, historyServiceMock);
    }

    @Test
    void participant_days_differentDay_explicitTimezone() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY), Optional.of(ZoneId.of("America/Los_Angeles")),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);

        lotteryService.draw();

        verify(repoMock).close();

        // The participant wants notifications on Monday, and we're Monday in UTC,
        // but still Sunday in Los Angeles.
        // Nothing to do.
        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, historyServiceMock);
    }

    @Test
    void singleParticipant_neverNotified() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY), Optional.empty(),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.stream());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);
        when(historyMock.lastNotificationInstantForUsername("yrodiere")).thenReturn(Optional.empty());
        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationExpiredForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(issueNeedingTriage.subList(0, 3))));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        new LotteryReport.Bucket.Serialized(List.of(1, 3, 2)))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock,
                historyServiceMock, historyMock);
    }

    @Test
    void singleParticipant_notifiedYesterday() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY), Optional.empty(),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.stream());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);
        when(historyMock.lastNotificationInstantForUsername("yrodiere"))
                .thenReturn(Optional.of(drawRef.instant().minus(1, ChronoUnit.DAYS)));
        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationExpiredForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(issueNeedingTriage.subList(0, 3))));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        new LotteryReport.Bucket.Serialized(List.of(1, 3, 2)))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock,
                historyServiceMock, historyMock, historyTriageMock);
    }

    @Test
    void singleParticipant_notifiedYesterday_issueAlreadyHasNonExpiredNotification() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY), Optional.empty(),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.stream());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);
        when(historyMock.lastNotificationInstantForUsername("yrodiere"))
                .thenReturn(Optional.of(drawRef.instant().minus(1, ChronoUnit.DAYS)));
        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationExpiredForIssueNumber(anyInt())).thenReturn(true);
        when(historyTriageMock.lastNotificationExpiredForIssueNumber(3)).thenReturn(false);

        lotteryService.draw();

        // Since the last notification for issue with number 3 didn't expire yet,
        // it will be skipped and we'll notify about another issue.
        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(
                        List.of(issueNeedingTriage.get(0), issueNeedingTriage.get(2), issueNeedingTriage.get(3)))));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        new LotteryReport.Bucket.Serialized(List.of(1, 2, 4)))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock,
                historyServiceMock, historyMock, historyTriageMock);
    }

    @Test
    void singleParticipant_alreadyNotifiedToday() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(new LotteryConfig.ParticipantConfig(
                        "yrodiere",
                        Set.of(DayOfWeek.MONDAY), Optional.empty(),
                        new LotteryConfig.ParticipationConfig(3))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);
        when(historyMock.lastNotificationInstantForUsername("yrodiere"))
                .thenReturn(Optional.of(drawRef.instant().minus(1, ChronoUnit.HOURS)));

        lotteryService.draw();

        verify(notifierMock).close();
        verify(repoMock).close();

        // The participant was already notified today.
        // Nothing to do.
        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock,
                historyServiceMock, historyMock);
    }

    @RepeatedTest(10) // Just to be reasonably certain that issues are spread evenly
    void multiParticipants_evenSpread() throws IOException {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of(
                        new LotteryConfig.ParticipantConfig(
                                "yrodiere",
                                Set.of(DayOfWeek.MONDAY), Optional.empty(),
                                new LotteryConfig.ParticipationConfig(10)),
                        new LotteryConfig.ParticipantConfig(
                                "gsmet",
                                Set.of(DayOfWeek.MONDAY), Optional.empty(),
                                new LotteryConfig.ParticipationConfig(10))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        List<Issue> issueNeedingTriage = List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3)),
                new Issue(2, "Where can I find documentation?", url(2)),
                new Issue(4, "Hibernate ORM works too well", url(4)));
        when(repoMock.issuesWithLabel("needs-triage"))
                .thenAnswer(ignored -> issueNeedingTriage.stream());

        var notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(drawRef, config.notifications())).thenReturn(notifierMock);
        var historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(drawRef, config)).thenReturn(historyMock);
        when(historyMock.lastNotificationInstantForUsername("yrodiere")).thenReturn(Optional.empty());
        when(historyMock.lastNotificationInstantForUsername("gsmet")).thenReturn(Optional.empty());
        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationExpiredForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        var reportCaptor = ArgumentCaptor.forClass(LotteryReport.class);
        verify(notifierMock, Mockito.times(2)).send(reportCaptor.capture());
        var reports = reportCaptor.getAllValues();

        verify(historyServiceMock).append(eq(drawRef), eq(config), any());

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(gitHubServiceMock, repoMock, notificationServiceMock, notifierMock,
                historyServiceMock, historyMock);

        for (var report : reports) {
            assertThat(report.triage().issues()).hasSize(2);
        }
    }

}