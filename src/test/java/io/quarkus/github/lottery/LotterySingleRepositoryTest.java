package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.stubIssue;
import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static io.quarkus.github.lottery.util.MockHelper.stubReport;
import static io.quarkus.github.lottery.util.MockHelper.stubReportConfig;
import static io.quarkus.github.lottery.util.MockHelper.stubReportCreated;
import static io.quarkus.github.lottery.util.MockHelper.stubReportMaintenance;
import static io.quarkus.github.lottery.util.MockHelper.stubReportStewardship;
import static io.quarkus.github.lottery.util.MockHelper.stubReportTriage;
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

import jakarta.inject.Inject;

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
import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.IssueActionSide;
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
                                "triage/needs-triage",
                                Duration.ZERO, Duration.ofDays(3)),
                        new LotteryConfig.Buckets.Maintenance(
                                new LotteryConfig.Buckets.Maintenance.Created(
                                        Duration.ZERO, Duration.ofDays(1), Duration.ofDays(14), List.of("triage/on-ice")),
                                new LotteryConfig.Buckets.Maintenance.Feedback(
                                        List.of("triage/needs-reproducer", "triage/needs-feedback"),
                                        new LotteryConfig.Buckets.Maintenance.Feedback.Needed(
                                                Duration.ofDays(21), Duration.ofDays(3)),
                                        new LotteryConfig.Buckets.Maintenance.Feedback.Provided(
                                                Duration.ofDays(7), Duration.ofDays(3))),
                                new LotteryConfig.Buckets.Maintenance.Stale(
                                        Duration.ofDays(60), Duration.ofDays(14), List.of("triage/on-ice"))),
                        new LotteryConfig.Buckets.Stewardship(
                                Duration.ofDays(60), Duration.ofDays(14), List.of("triage/on-ice"))),
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
    Instant createdMinCutoff;
    Instant createdMaxCutoff;
    Instant feedbackNeededCutoff;
    Instant feedbackProvidedCutoff;
    Instant staleCutoff;
    Instant stewardshipCutoff;
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
        createdMinCutoff = now.minus(14, ChronoUnit.DAYS);
        createdMaxCutoff = now.minus(0, ChronoUnit.DAYS);
        feedbackNeededCutoff = now.minus(21, ChronoUnit.DAYS);
        feedbackProvidedCutoff = now.minus(7, ChronoUnit.DAYS);
        staleCutoff = now.minus(60, ChronoUnit.DAYS);
        stewardshipCutoff = now.minus(60, ChronoUnit.DAYS);
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
        when(notifierMock.isIgnoring(username)).thenReturn(false);
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
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.TUESDAY),
                                new LotteryConfig.Participant.Participation(10))))));
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
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.TUESDAY),
                                new LotteryConfig.Participant.Participation(10))))));
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
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.TUESDAY),
                                new LotteryConfig.Participant.Participation(10))))));
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
    void ignoring() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm", "area/hibernate-search"),
                                Set.of(DayOfWeek.MONDAY),
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.TUESDAY),
                                new LotteryConfig.Participant.Participation(10))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));
        when(repoMock.ref()).thenReturn(repoRef);

        when(historyMock.lastNotificationToday("yrodiere", ZoneOffset.UTC)).thenReturn(Optional.empty());
        when(notifierMock.isIgnoring("yrodiere")).thenReturn(true);

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
                        Optional.empty(),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("triage/needs-triage", Set.of(), now))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(stubReportTriage(drawRef, "yrodiere", Optional.empty(),
                stubIssueList(1, 3, 2)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 3, 2))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty())));

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
                        Optional.empty(),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("triage/needs-triage", Set.of(), now))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(3)).thenReturn(false);

        lotteryService.draw();

        // Since the last notification for issue with number 3 didn't time out yet,
        // it will be skipped and we'll notify about another issue.
        verify(notifierMock).send(stubReportTriage(drawRef, "yrodiere", Optional.empty(),
                stubIssueList(1, 2, 4)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 2, 4))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty())));

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
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween("area/hibernate-orm",
                Set.of("triage/needs-reproducer", "triage/needs-feedback", "triage/on-ice"),
                Set.of("yrodiere"),
                createdMinCutoff, createdMaxCutoff))
                .thenAnswer(ignored -> stubIssueList(701, 702, 703, 704, 705, 706).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-orm", IssueActionSide.TEAM, feedbackNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(101, 102, 103, 104, 105).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-orm", IssueActionSide.OUTSIDER, feedbackProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(201, 202, 203).stream());
        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("area/hibernate-orm", Set.of("triage/on-ice"),
                staleCutoff))
                .thenAnswer(ignored -> stubIssueList(301, 302, 303, 304, 305, 306).stream());

        when(repoMock.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween("area/hibernate-search",
                Set.of("triage/needs-reproducer", "triage/needs-feedback", "triage/on-ice"),
                Set.of("yrodiere"),
                createdMinCutoff, createdMaxCutoff))
                .thenAnswer(ignored -> stubIssueList(801, 802, 803, 804, 805, 806).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-search", IssueActionSide.TEAM, feedbackNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(401, 402, 403, 404, 405).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-search", IssueActionSide.OUTSIDER, feedbackProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(501, 502, 503).stream());
        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("area/hibernate-search", Set.of("triage/on-ice"),
                staleCutoff))
                .thenAnswer(ignored -> stubIssueList(601, 602, 603, 604, 605, 606).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyCreatedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.created()).thenReturn(historyCreatedMock);
        when(historyCreatedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyFeedbackNeededMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.feedbackNeeded()).thenReturn(historyFeedbackNeededMock);
        when(historyFeedbackNeededMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyFeedbackProvidedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.feedbackProvided()).thenReturn(historyFeedbackProvidedMock);
        when(historyFeedbackProvidedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyStaleMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stale()).thenReturn(historyStaleMock);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(stubReportMaintenance(drawRef, "yrodiere", Optional.empty(),
                List.of("area/hibernate-orm", "area/hibernate-search"),
                stubIssueList(701, 801, 702, 802, 703),
                stubIssueList(101, 401, 102, 402),
                stubIssueList(201, 501),
                stubIssueList(301, 601, 302, 602, 303)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(701, 801, 702, 802, 703))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(101, 401, 102, 402))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(201, 501))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(301, 601, 302, 602, 303))),
                        Optional.empty())));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyFeedbackNeededMock, historyFeedbackProvidedMock, historyStaleMock);
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
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween("area/hibernate-orm",
                Set.of("triage/needs-reproducer", "triage/needs-feedback", "triage/on-ice"),
                Set.of("yrodiere"),
                createdMinCutoff, createdMaxCutoff))
                .thenAnswer(ignored -> stubIssueList(701, 702, 703, 704, 705, 706).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-orm", IssueActionSide.TEAM, feedbackNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(101, 102, 103, 104, 105).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-orm", IssueActionSide.OUTSIDER, feedbackProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(201, 202, 203).stream());
        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("area/hibernate-orm", Set.of("triage/on-ice"),
                staleCutoff))
                .thenAnswer(ignored -> stubIssueList(301, 302, 303, 304, 305, 306).stream());

        when(repoMock.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween("area/hibernate-search",
                Set.of("triage/needs-reproducer", "triage/needs-feedback", "triage/on-ice"),
                Set.of("yrodiere"),
                createdMinCutoff, createdMaxCutoff))
                .thenAnswer(ignored -> stubIssueList(801, 802, 803, 804, 805, 806).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-search", IssueActionSide.TEAM, feedbackNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(401, 402, 403, 404, 405).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-search", IssueActionSide.OUTSIDER, feedbackProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(501, 502, 503).stream());
        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("area/hibernate-search", Set.of("triage/on-ice"),
                staleCutoff))
                .thenAnswer(ignored -> stubIssueList(601, 602, 603, 604, 605, 606).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyCreatedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.created()).thenReturn(historyCreatedMock);
        when(historyCreatedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyCreatedMock.lastNotificationTimedOutForIssueNumber(702)).thenReturn(false);
        var historyFeedbackNeededMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.feedbackNeeded()).thenReturn(historyFeedbackNeededMock);
        when(historyFeedbackNeededMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyFeedbackNeededMock.lastNotificationTimedOutForIssueNumber(401)).thenReturn(false);
        var historyFeedbackProvidedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.feedbackProvided()).thenReturn(historyFeedbackProvidedMock);
        when(historyFeedbackProvidedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyFeedbackProvidedMock.lastNotificationTimedOutForIssueNumber(201)).thenReturn(false);
        var historyStaleMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stale()).thenReturn(historyStaleMock);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(302)).thenReturn(false);

        lotteryService.draw();

        // Since the last notification for issues with number 401, 201, 302, 702 didn't time out yet,
        // they will be skipped and we'll notify about the next issues instead.
        verify(notifierMock).send(stubReportMaintenance(drawRef, "yrodiere", Optional.empty(),
                List.of("area/hibernate-orm", "area/hibernate-search"),
                stubIssueList(701, 801, 703, 802, 704),
                stubIssueList(101, 402, 102, 403),
                stubIssueList(202, 501),
                stubIssueList(301, 601, 303, 602, 304)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(701, 801, 703, 802, 704))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(101, 402, 102, 403))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(202, 501))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(301, 601, 303, 602, 304))),
                        Optional.empty())));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyCreatedMock, historyFeedbackNeededMock, historyFeedbackProvidedMock, historyStaleMock);
    }

    @Test
    void maintenance_created_filterOutSubmittedByMaintainer() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-orm"),
                                Set.of(DayOfWeek.MONDAY),
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.empty(),
                                Optional.empty())),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween("area/hibernate-orm",
                Set.of("triage/needs-reproducer", "triage/needs-feedback", "triage/on-ice"),
                Set.of("yrodiere"),
                createdMinCutoff, createdMaxCutoff))
                .thenAnswer(ignored -> List.of(
                        stubIssue(701, "yrodiere"),
                        stubIssue(702),
                        stubIssue(703, "yrodiere"),
                        stubIssue(704),
                        stubIssue(705),
                        stubIssue(706)).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyCreatedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.created()).thenReturn(historyCreatedMock);
        when(historyCreatedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(stubReportCreated(drawRef, "yrodiere", Optional.empty(),
                List.of("area/hibernate-orm"),
                stubIssueList(702, 704, 705, 706)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(702, 704, 705, 706))),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyCreatedMock);
    }

    @Test
    void stewardship() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("geoand",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsLastUpdatedBefore(Set.of("triage/on-ice"), stewardshipCutoff))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("geoand", ZoneOffset.UTC);

        var historyStewardshipMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stewardship()).thenReturn(historyStewardshipMock);
        when(historyStewardshipMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(stubReportStewardship(drawRef, "geoand", Optional.empty(),
                stubIssueList(1, 3, 2)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "geoand",
                        Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 3, 2))))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyStewardshipMock);
    }

    @Test
    void stewardship_issueAlreadyHasNonTimedOutNotification() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("geoand",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(3))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsLastUpdatedBefore(Set.of("triage/on-ice"), stewardshipCutoff))
                .thenAnswer(ignored -> stubIssueList(1, 3, 2, 4).stream());

        mockNotifiable("geoand", ZoneOffset.UTC);

        var historyStewardshipMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stewardship()).thenReturn(historyStewardshipMock);
        when(historyStewardshipMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        when(historyStewardshipMock.lastNotificationTimedOutForIssueNumber(3)).thenReturn(false);

        lotteryService.draw();

        // Since the last notification for issue with number 3 didn't time out yet,
        // it will be skipped and we'll notify about another issue.
        verify(notifierMock).send(stubReportStewardship(drawRef, "geoand", Optional.empty(),
                stubIssueList(1, 2, 4)));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "geoand",
                        Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 2, 4))))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyStewardshipMock);
    }

    @Test
    void stewardship_doesNotAffectMaintenance() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Maintenance(
                                List.of("area/hibernate-search"),
                                Set.of(DayOfWeek.MONDAY),
                                Optional.of(new LotteryConfig.Participant.Participation(5)),
                                Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                        new LotteryConfig.Participant.Participation(4),
                                        new LotteryConfig.Participant.Participation(2))),
                                Optional.of(new LotteryConfig.Participant.Participation(5)))),
                        Optional.empty()),
                new LotteryConfig.Participant("gsmet",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Stewardship(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(10))))));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween("area/hibernate-search",
                Set.of("triage/needs-reproducer", "triage/needs-feedback", "triage/on-ice"),
                Set.of("yrodiere"),
                createdMinCutoff, createdMaxCutoff))
                .thenAnswer(ignored -> stubIssueList(801, 802, 803, 804, 805, 806).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-search", IssueActionSide.TEAM, feedbackNeededCutoff))
                .thenAnswer(ignored -> stubIssueList(401, 402, 403, 404, 405).stream());
        when(repoMock.issuesLastActedOnByAndLastUpdatedBefore(
                Set.of("triage/needs-reproducer", "triage/needs-feedback"),
                "area/hibernate-search", IssueActionSide.OUTSIDER, feedbackProvidedCutoff))
                .thenAnswer(ignored -> stubIssueList(501, 502, 503).stream());
        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("area/hibernate-search", Set.of("triage/on-ice"),
                staleCutoff))
                .thenAnswer(ignored -> stubIssueList(601, 602, 603, 604, 605, 606).stream());

        when(repoMock.issuesOrPullRequestsLastUpdatedBefore(Set.of("triage/on-ice"), stewardshipCutoff))
                .thenAnswer(ignored -> stubIssueList(401, 501, 601, 701).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);
        mockNotifiable("gsmet", ZoneOffset.UTC);

        var historyCreatedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.created()).thenReturn(historyCreatedMock);
        when(historyCreatedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyFeedbackNeededMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.feedbackNeeded()).thenReturn(historyFeedbackNeededMock);
        when(historyFeedbackNeededMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyFeedbackProvidedMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.feedbackProvided()).thenReturn(historyFeedbackProvidedMock);
        when(historyFeedbackProvidedMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyStaleMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stale()).thenReturn(historyStaleMock);
        when(historyStaleMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);
        var historyStewardshipMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.stewardship()).thenReturn(historyStewardshipMock);
        when(historyStewardshipMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        lotteryService.draw();

        verify(notifierMock).send(stubReport(drawRef, "yrodiere", Optional.empty(),
                stubReportConfig("area/hibernate-search"),
                Optional.empty(),
                Optional.of(stubIssueList(801, 802, 803, 804, 805)),
                Optional.of(stubIssueList(401, 402, 403, 404)),
                Optional.of(stubIssueList(501, 502)),
                // Notifications to stewards don't prevent notifications to maintainers
                Optional.of(stubIssueList(601, 602, 603, 604, 605)),
                Optional.empty()));

        verify(notifierMock).send(stubReport(drawRef, "gsmet", Optional.empty(),
                stubReportConfig(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                // Notifications to maintainers don't prevent notifications to stewards
                Optional.of(stubIssueList(401, 501, 601, 701))));

        verify(historyServiceMock).append(drawRef, config, List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(801, 802, 803, 804, 805))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(401, 402, 403, 404))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(501, 502))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(601, 602, 603, 604, 605))),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "gsmet",
                        Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(401, 501, 601, 701))))));

        verify(notifierMock).close();
        verify(repoMock).close();

        verifyNoMoreInteractions(mainMocks);
        verifyNoMoreInteractions(historyFeedbackNeededMock, historyFeedbackProvidedMock, historyStaleMock);
    }

    @RepeatedTest(10) // Just to be reasonably certain that issues are spread evenly
    void multiParticipants_evenSpread() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(10))),
                        Optional.empty(),
                        Optional.empty()),
                new LotteryConfig.Participant("gsmet",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(10))),
                        Optional.empty(),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("triage/needs-triage", Set.of(), now))
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