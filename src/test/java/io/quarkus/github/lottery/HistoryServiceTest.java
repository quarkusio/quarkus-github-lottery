package io.quarkus.github.lottery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.github.lottery.github.GitHubInstallationRef;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.history.HistoryService;
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class HistoryServiceTest {
    GitHubService gitHubServiceMock;
    GitHubRepository persistenceRepoMock;

    MessageFormatter messageFormatterMock;

    GitHubInstallationRef installationRef;
    GitHubRepositoryRef repoRef;
    Instant now;
    DrawRef drawRef;

    @Inject
    HistoryService historyService;

    @BeforeEach
    void setup() {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1L);
        repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        persistenceRepoMock = Mockito.mock(GitHubRepository.class);

        now = LocalDateTime.of(2017, 11, 6, 8, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef, now);

        messageFormatterMock = Mockito.mock(MessageFormatter.class);
        QuarkusMock.installMockForType(messageFormatterMock, MessageFormatter.class);
    }

    @Test
    void lastNotificationInstantForUser_noHistory() throws Exception {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of());

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String selfUsername = "quarkus-lottery-bot";
        when(persistenceRepoMock.appLogin()).thenReturn(selfUsername);
        when(persistenceRepoMock.extractCommentsFromDedicatedIssue(eq(selfUsername), eq(topic), any()))
                .thenAnswer(ignored -> Stream.of());

        var history = historyService.fetch(drawRef, config);

        assertThat(history.lastNotificationInstantForUsername("yrodiere"))
                .isEmpty();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationInstantForUser_notNotified() throws Exception {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of());

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String selfUsername = "quarkus-lottery-bot";
        when(persistenceRepoMock.appLogin()).thenReturn(selfUsername);
        String historyBody = "Some content";
        when(persistenceRepoMock.extractCommentsFromDedicatedIssue(eq(selfUsername), eq(topic), any()))
                .thenAnswer(ignored -> Stream.of(historyBody));
        when(messageFormatterMock.extractPayloadFromHistoryBodyMarkdown(historyBody))
                .thenReturn(List.of(
                        new LotteryReport.Serialized(now.minus(1, ChronoUnit.DAYS), "gsmet",
                                new LotteryReport.Bucket.Serialized(List.of(1, 2)))));

        var history = historyService.fetch(drawRef, config);

        assertThat(history.lastNotificationInstantForUsername("yrodiere"))
                .isEmpty();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationInstantForUser_notifiedRecently() throws Exception {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of());

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String selfUsername = "quarkus-lottery-bot";
        when(persistenceRepoMock.appLogin()).thenReturn(selfUsername);
        String historyBody = "Some content";
        when(persistenceRepoMock.extractCommentsFromDedicatedIssue(eq(selfUsername), eq(topic), any()))
                .thenAnswer(ignored -> Stream.of(historyBody));
        when(messageFormatterMock.extractPayloadFromHistoryBodyMarkdown(historyBody))
                .thenReturn(List.of(
                        new LotteryReport.Serialized(now.minus(1, ChronoUnit.DAYS), "gsmet",
                                new LotteryReport.Bucket.Serialized(List.of(1, 2))),
                        new LotteryReport.Serialized(now.minus(2, ChronoUnit.DAYS), "yrodiere",
                                new LotteryReport.Bucket.Serialized(List.of(4, 5)))));

        var history = historyService.fetch(drawRef, config);

        assertThat(history.lastNotificationInstantForUsername("yrodiere"))
                .contains(now.minus(2, ChronoUnit.DAYS));

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationExpiredForIssueNumber_noHistory() throws Exception {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of());

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String selfUsername = "quarkus-lottery-bot";
        when(persistenceRepoMock.appLogin()).thenReturn(selfUsername);
        when(persistenceRepoMock.extractCommentsFromDedicatedIssue(eq(selfUsername), eq(topic), any()))
                .thenAnswer(ignored -> Stream.of());

        var history = historyService.fetch(drawRef, config);

        assertThat(history.triage().lastNotificationExpiredForIssueNumber(2))
                .isTrue();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationExpiredForIssueNumber() throws Exception {
        var config = new LotteryConfig(
                new LotteryConfig.NotificationsConfig(
                        new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.BucketsConfig(
                        new LotteryConfig.BucketsConfig.TriageBucketConfig("needs-triage", Duration.ofDays(3))),
                List.of());

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String selfUsername = "quarkus-lottery-bot";
        when(persistenceRepoMock.appLogin()).thenReturn(selfUsername);
        String historyBody = "Some content";
        when(persistenceRepoMock.extractCommentsFromDedicatedIssue(eq(selfUsername), eq(topic), any()))
                .thenAnswer(ignored -> Stream.of(historyBody));
        when(messageFormatterMock.extractPayloadFromHistoryBodyMarkdown(historyBody))
                .thenReturn(List.of(
                        new LotteryReport.Serialized(now.minus(1, ChronoUnit.DAYS), "gsmet",
                                new LotteryReport.Bucket.Serialized(List.of(1, 2))),
                        new LotteryReport.Serialized(now.minus(7, ChronoUnit.DAYS), "yrodiere",
                                new LotteryReport.Bucket.Serialized(List.of(42)))));

        var history = historyService.fetch(drawRef, config);

        // Notified recently
        assertThat(history.triage().lastNotificationExpiredForIssueNumber(2))
                .isFalse();
        // Not notified at all
        assertThat(history.triage().lastNotificationExpiredForIssueNumber(4))
                .isTrue();
        // Notified a long time ago (expired)
        assertThat(history.triage().lastNotificationExpiredForIssueNumber(42))
                .isTrue();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

}
