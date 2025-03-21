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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.common.primitives.Ints;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubInstallationRef;
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
public class LotteryRandomnessTest {

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
    void issue_randomness() throws IOException {
        var config = defaultConfig(List.of(
                new LotteryConfig.Participant("yrodiere",
                        Optional.empty(),
                        Optional.of(new LotteryConfig.Participant.Triage(
                                Set.of(DayOfWeek.MONDAY),
                                new LotteryConfig.Participant.Participation(10))),
                        Optional.empty(),
                        Optional.empty())));
        when(repoMock.fetchLotteryConfig()).thenReturn(Optional.of(config));

        int[] issueNumbersReturnedByGitHubInOrder = IntStream.range(1, 100).toArray();
        when(repoMock.issuesOrPullRequestsWithLabelLastUpdatedBefore("triage/needs-triage", Set.of(), now))
                .thenAnswer(ignored -> stubIssueList(issueNumbersReturnedByGitHubInOrder).stream());

        mockNotifiable("yrodiere", ZoneOffset.UTC);

        var historyTriageMock = mock(LotteryHistory.Bucket.class);
        when(historyMock.triage()).thenReturn(historyTriageMock);
        when(historyTriageMock.lastNotificationTimedOutForIssueNumber(anyInt())).thenReturn(true);

        // This should be enough to make it very unlikely that we "randomly"
        // get the exact same report every single time.
        int numberOfExecutions = 10;
        for (int i = 0; i < numberOfExecutions; i++) {
            lotteryService.draw();
        }

        var reportCaptor = ArgumentCaptor.forClass(LotteryReport.class);
        verify(notifierMock, Mockito.times(numberOfExecutions)).send(reportCaptor.capture());
        var reports = reportCaptor.getAllValues();

        verify(historyServiceMock, Mockito.times(numberOfExecutions)).append(eq(drawRef), eq(config), any());

        verify(notifierMock, Mockito.times(numberOfExecutions)).close();
        verify(repoMock, Mockito.times(numberOfExecutions)).close();

        verifyNoMoreInteractions(mainMocks);

        Set<List<Integer>> issueSelections = reports.stream()
                .map(r -> r.triage().get().issues().stream().map(Issue::number).toList())
                .collect(Collectors.toSet());

        System.out.printf("Issue selections:%n");
        for (List<Integer> issueSelection : issueSelections) {
            for (Integer issueNumber : issueSelection) {
                System.out.printf("%3d ", issueNumber);
            }
            System.out.printf("%n");
        }

        // If issue selection order is random, we should have at least 2 different selections for the same input.
        assertThat(issueSelections)
                .hasSizeGreaterThanOrEqualTo(2);

        // If issue selection order is random, we should have at least 1 selection that's not in the order returned by GitHub.
        assertThat(issueSelections)
                .anySatisfy(selection -> assertThat(selection)
                        .isNotEqualTo(Ints.asList(issueNumbersReturnedByGitHubInOrder)
                                .stream().limit(selection.size()).toList()));
    }
}