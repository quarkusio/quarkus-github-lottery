package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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

import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.IssueActionSide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.history.HistoryService;
import io.quarkus.github.lottery.history.LotteryHistory;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.github.lottery.notification.Notifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class LotterySingleRepositoryTest {

    private static LotteryConfig defaultConfig(List<LotteryConfig.Participant> participants) {
        return new LotteryConfig(
                new LotteryConfig.Notifications(
                        new LotteryConfig.Notifications.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.Buckets(
                        new LotteryConfig.Buckets.Triage(
                                "needs-triage",
                                Duration.ZERO, Duration.ofDays(3)),
                        new LotteryConfig.Buckets.Maintenance(
                                new LotteryConfig.Buckets.Maintenance.Reproducer(
                                        "needs-reproducer",
                                        new LotteryConfig.Buckets.Maintenance.Reproducer.Needed(
                                                Duration.ofDays(21), Duration.ofDays(3)),
                                        new LotteryConfig.Buckets.Maintenance.Reproducer.Provided(
                                                Duration.ofDays(7), Duration.ofDays(3))),
                                new LotteryConfig.Buckets.Maintenance.Stale(
                                        Duration.ofDays(60), Duration.ofDays(14)))),
                participants);
    }

    GitHubService gitHubServiceMock;
    GitHubRepository repoMock;
    Clock clockMock;
    NotificationService notificationServiceMock;
    Notifier notifierMock;
    HistoryService historyServiceMock;
    LotteryHistory historyMock;

    GitHubInstallationRef installationRef;
    GitHubRepositoryRef repoRef;
    Instant now;
    Instant reproducerNeededCutoff;
    Instant reproducerProvidedCutoff;
    Instant staleCutoff;
    DrawRef drawRef;

    private Object[] mainMocks;

    @Inject
    LotteryService lotteryService;

    @BeforeEach
    void setup() throws IOException {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1L);
        repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");
        when(gitHubServiceMock.listRepositories()).thenReturn(List.of(repoRef));

        repoMock = Mockito.mock(GitHubRepository.class);
        when(gitHubServiceMock.repository(repoRef)).thenReturn(repoMock);
        doNothing().when(repoMock).close();
        when(repoMock.ref()).thenReturn(repoRef);

        // Note tests below assume this is at least 1AM
        now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        reproducerNeededCutoff = now.minus(21, ChronoUnit.DAYS);
        reproducerProvidedCutoff = now.minus(7, ChronoUnit.DAYS);
        staleCutoff = now.minus(60, ChronoUnit.DAYS);
        drawRef = new DrawRef(repoRef, now);
        clockMock = Clock.fixed(drawRef.instant(), ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        notificationServiceMock = Mockito.mock(NotificationService.class);
        QuarkusMock.installMockForType(notificationServiceMock, NotificationService.class);
        notifierMock = mock(Notifier.class);
        when(notificationServiceMock.notifier(eq(drawRef), any())).thenReturn(notifierMock);
        doNothing().when(notifierMock).close();

        historyServiceMock = Mockito.mock(HistoryService.class);
        QuarkusMock.installMockForType(historyServiceMock, HistoryService.class);
        historyMock = mock(LotteryHistory.class);
        when(historyServiceMock.fetch(eq(drawRef), any())).thenReturn(historyMock);

        mainMocks = new Object[] {
                gitHubServiceMock, repoMock,
                notificationServiceMock, notifierMock,
                historyServiceMock, historyMock
        };
    }

    private void mockNotifiable(String username, ZoneId timezone) throws IOException {
        when(historyMock.lastNotificationToday(username, timezone)).thenReturn(Optional.empty());
        when(notifierMock.hasClosedDedicatedIssue(username)).thenReturn(false);
    }

    @Test
    void days_differentDay_defaultTimezone() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.TUESDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm", "area/hibernate-search"),
                                Set.of(DayOfWeek.TUESDAY),
                                new LotteryConfig.Participant.Maintenance.Reproducer(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2)),
                                new LotteryConfig.Participant.Participation(5))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        lotteryService.draw();

        // The participant wants notifications on Tuesday, but we're Monday in UTC.
        // Nothing to do.
        verifyNoMoreInteractions(mainMocks);
    }

    @Test
    void days_differentDay_explicitTimezone() throws IOException {
        var timezone = ZoneId.of("America/Los_Angeles");
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.of(timezone),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm", "area/hibernate-search"),
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Maintenance.Reproducer(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2)),
                                new LotteryConfig.Participant.Participation(5))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        mockNotifiable("yrodiere", timezone);

        lotteryService.draw();

        verify(repoMock).close();

        // The participant wants notifications on Monday, and we're Monday in UTC,
        // but still Sunday in Los Angeles.
        // Nothing to do.
        verifyNoMoreInteractions(mainMocks);
    }

    @Test
    void alreadyNotifiedToday() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm", "area/hibernate-search"),
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Maintenance.Reproducer(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2)),
                                new LotteryConfig.Participant.Participation(5))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(historyMock.lastNotificationToday("yrodiere", ZoneOffset.UTC))
                .thenReturn(Optional.of(drawRef.instant().minus(1, ChronoUnit.HOURS).atZone(ZoneOffset.UTC)));

        lotteryService.draw();

        verify(notifierMock).close();
        verify(repoMock).close();

        // The participant was already notified today.
        // Nothing to do.
        verifyNoMoreInteractions(mainMocks);
    }

    @Test
    void closedDedicatedIssue() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        when(historyMock.lastNotificationToday("yrodiere", ZoneOffset.UTC)).thenReturn(Optional.empty());
        when(notifierMock.hasClosedDedicatedIssue("yrodiere")).thenReturn(true);

        lotteryService.draw();

        verify(repoMock).close();

        // The participant closed their dedicated issues in the "notification" repository.
        // Nothing to do.
        verifyNoMoreInteractions(mainMocks);
    }

    @Test
    void triage() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesWithLabelLastUpdatedBefore("needs-triage", now))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 3, 2))),
                Optional.empty(), Optional.empty(), Optional.empty()));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 3, 2))),
                        Optional.empty(), Optional.empty(), Optional.empty())));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyTriageMock);
    }

    @Test
    void triage_issueAlreadyHasNonTimedOutNotification() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesWithLabelLastUpdatedBefore("needs-triage", now))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(3)).thenReturn(false);

        lotteryService.draw();

        // Since the last notification for issue with number 3 didn't time out yet,
        // it will be skipped and we'll notify about another issue.
        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 2, 4))),
                Optional.empty(), Optional.empty(), Optional.empty()));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 2, 4))),
                        Optional.empty(), Optional.empty(), Optional.empty())));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyTriageMock);
    }

    @Test
    void maintenance() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm", "area/hibernate-search"),
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Maintenance.Reproducer(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2)),
                                new LotteryConfig.Participant.Participation(5))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-orm", IssueActionSide.TEAM, reproducerNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(101, 102, 103, 104, 105).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-orm", IssueActionSide.OUTSIDER, reproducerProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(201, 202, 203).stream());
        when(repoMock.issuesWithLabelLastUpdatedBefore("area/hibernate-orm", staleCutoff))
                .thenAnswer(ignored -> stubIssueList(301, 302, 303, 304, 305, 306).stream());

        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-search", IssueActionSide.TEAM, reproducerNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(401, 402, 403, 404, 405).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-search", IssueActionSide.OUTSIDER, reproducerProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(501, 502, 503).stream());
        when(repoMock.issuesWithLabelLastUpdatedBefore("area/hibernate-search", staleCutoff))
                .thenAnswer(ignored -> stubIssueList(601, 602, 603, 604, 605, 606).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyReproducerNeededMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.reproducerNeeded()).thenReturn(historyReproducerNeededMock);
        when(historyReproducerNeededMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyReproducerProvidedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.reproducerProvided()).thenReturn(historyReproducerProvidedMock);
        when(historyReproducerProvidedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyStaleMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stale()).thenReturn(historyStaleMock);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(101, 401, 102, 402))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(201, 501))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(301, 601, 302, 602, 303)))));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(101, 401, 102, 402))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(201, 501))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(301, 601, 302, 602, 303))))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyReproducerNeededMock, historyReproducerProvidedMock, historyStaleMock);
    }

    @Test
    void maintenance_issueAlreadyHasTimedOutNotification() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm", "area/hibernate-search"),
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Maintenance.Reproducer(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2)),
                                new LotteryConfig.Participant.Participation(5))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-orm", IssueActionSide.TEAM, reproducerNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(101, 102, 103, 104, 105).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-orm", IssueActionSide.OUTSIDER, reproducerProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(201, 202, 203).stream());
        when(repoMock.issuesWithLabelLastUpdatedBefore("area/hibernate-orm", staleCutoff))
                .thenAnswer(ignored -> stubIssueList(301, 302, 303, 304, 305, 306).stream());

        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-search", IssueActionSide.TEAM, reproducerNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(401, 402, 403, 404, 405).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                "area/hibernate-search", IssueActionSide.OUTSIDER, reproducerProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(501, 502, 503).stream());
        when(repoMock.issuesWithLabelLastUpdatedBefore("area/hibernate-search", staleCutoff))
                .thenAnswer(ignored -> stubIssueList(601, 602, 603, 604, 605, 606).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyReproducerNeededMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.reproducerNeeded()).thenReturn(historyReproducerNeededMock);
        when(historyReproducerNeededMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyReproducerNeededMock.lastNotificationTimedOutForIssueNumber(401)).thenReturn(false);
        var historyReproducerProvidedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.reproducerProvided()).thenReturn(historyReproducerProvidedMock);
        when(historyReproducerProvidedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyReproducerProvidedMock.lastNotificationTimedOutForIssueNumber(201)).thenReturn(false);
        var historyStaleMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stale()).thenReturn(historyStaleMock);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(302)).thenReturn(false);

        lotteryService.draw();

        // Since the last notification for issues with number 401, 201, 302 didn't time out yet,
        // they will be skipped and we'll notify about the next issues instead.
        verify(notifierMock).send(new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(101, 402, 102, 403))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(202, 501))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(301, 601, 303, 602, 304)))));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(101, 402, 102, 403))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(202, 501))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(301, 601, 303, 602, 304))))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyReproducerNeededMock, historyReproducerProvidedMock, historyStaleMock);
    }

    @RepeatedTest(10) // Just to be reasonably certain that issues are spread evenly
    void multiParticipants_evenSpread() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(10))),
                        Optional.empty()),
                new LotteryConfig.Participant("gsmet",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(10))),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesWithLabelLastUpdatedBefore("needs-triage", now))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);
        mockNotifiable("gsmet", ZoneOffset.UTC);

        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        var reportCaptor = ArgumentCaptor.forClass(LotteryReport.class);
        verify(notifierMock, Mockito.times(2)).send(reportCaptor.capture());
        var reports = reportCaptor.getAllValues();

        verify(historyServiceMock).append(eq(drawRef), eq(config), any());

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);

        for (var report : reports) {
            assertThat(report.triage()).isNotEmpty();
            assertThat(report.triage().get().issues()).hasSize(2);
        }
    }

}