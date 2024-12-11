package io.quarkus.github.lottery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.TopicRef;
import io.quarkus.github.lottery.history.HistoryService;
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class HistoryServiceTest {

    private static LotteryConfig defaultConfig() {
        return new LotteryConfig(
                new LotteryConfig.Notifications(
                        new LotteryConfig.Notifications.CreateIssuesConfig("quarkusio/quarkus-lottery-reports")),
                new LotteryConfig.Buckets(
                        new LotteryConfig.Buckets.Triage(
                                "triage/needs-triage",
                                Duration.ZERO, Duration.ofDays(3)),
                        new LotteryConfig.Buckets.Maintenance(
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
                List.of());
    }

    GitHubService gitHubServiceMock;
    GitHubRepository persistenceRepoMock;
    GitHubRepository.Topic topicMock;

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
        topicMock = Mockito.mock(GitHubRepository.Topic.class);

        now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef, now);

        messageFormatterMock = Mockito.mock(MessageFormatter.class);
        QuarkusMock.installMockForType(messageFormatterMock, MessageFormatter.class);
    }

    @Test
    void lastNotificationToday_noHistory() throws Exception {
        var config = defaultConfig();

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        when(persistenceRepoMock.topic(TopicRef.history(topic)))
                .thenReturn(topicMock);
        when(topicMock.extractComments(any()))
                .thenAnswer(ignored -> Stream.of());

        var history = historyService.fetch(drawRef, config);

        ZoneId timezone = ZoneId.of("Europe/Paris");
        assertThat(history.lastNotificationToday("yrodiere", timezone))
                .isEmpty();
        timezone = ZoneId.of("America/Los_Angeles");
        assertThat(history.lastNotificationToday("yrodiere", timezone))
                .isEmpty();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationToday_notNotified() throws Exception {
        var config = defaultConfig();

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String historyBody = "Some content";
        when(persistenceRepoMock.topic(TopicRef.history(topic)))
                .thenReturn(topicMock);
        when(topicMock.extractComments(any()))
                .thenAnswer(ignored -> Stream.of(historyBody));
        when(messageFormatterMock.extractPayloadFromHistoryBodyMarkdown(historyBody))
                .thenReturn(List.of(
                        new LotteryReport.Serialized(now.minus(2, ChronoUnit.DAYS), "jane",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(6, 7))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty()),
                        new LotteryReport.Serialized(now.minus(1, ChronoUnit.HOURS), "gsmet",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 2))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty())));

        var history = historyService.fetch(drawRef, config);

        ZoneId timezone = ZoneId.of("Europe/Paris");
        assertThat(history.lastNotificationToday("yrodiere", timezone))
                .isEmpty();
        timezone = ZoneId.of("America/Los_Angeles");
        assertThat(history.lastNotificationToday("yrodiere", timezone))
                .isEmpty();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationToday_notifiedRecently() throws Exception {
        var config = defaultConfig();

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String historyBody = "Some content";
        when(persistenceRepoMock.topic(TopicRef.history(topic)))
                .thenReturn(topicMock);
        when(topicMock.extractComments(any()))
                .thenAnswer(ignored -> Stream.of(historyBody));
        when(messageFormatterMock.extractPayloadFromHistoryBodyMarkdown(historyBody))
                .thenReturn(List.of(
                        new LotteryReport.Serialized(now.minus(2, ChronoUnit.DAYS), "jane",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(6, 7))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty()),
                        new LotteryReport.Serialized(now.minus(1, ChronoUnit.HOURS), "gsmet",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 2))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty()),
                        new LotteryReport.Serialized(now.minus(9, ChronoUnit.HOURS), "yrodiere",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(4, 5))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty())));

        var history = historyService.fetch(drawRef, config);

        // 9 hours ago was yesterday in Paris
        ZoneId timezone = ZoneId.of("Europe/Paris");
        assertThat(history.lastNotificationToday("yrodiere", timezone))
                .isEmpty();

        // 9 hours ago was still today in Los Angeles
        timezone = ZoneId.of("America/Los_Angeles");
        assertThat(history.lastNotificationToday("yrodiere", timezone))
                .contains(now.minus(9, ChronoUnit.HOURS).atZone(timezone));

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationTimedOutForIssueNumber_noHistory() throws Exception {
        var config = defaultConfig();

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        when(persistenceRepoMock.topic(TopicRef.history(topic)))
                .thenReturn(topicMock);
        when(topicMock.extractComments(any()))
                .thenAnswer(ignored -> Stream.of());

        var history = historyService.fetch(drawRef, config);

        assertThat(history.triage().lastNotificationTimedOutForIssueNumber(2))
                .isTrue();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

    @Test
    void lastNotificationTimedOutForIssueNumber() throws Exception {
        var config = defaultConfig();

        var persistenceRepoRef = new GitHubRepositoryRef(installationRef,
                config.notifications().createIssues().repository());
        when(gitHubServiceMock.repository(persistenceRepoRef)).thenReturn(persistenceRepoMock);

        String topic = "Lottery history for quarkusio/quarkus";
        when(messageFormatterMock.formatHistoryTopicText(drawRef)).thenReturn(topic);
        String historyBody = "Some content";
        when(persistenceRepoMock.topic(TopicRef.history(topic)))
                .thenReturn(topicMock);
        when(topicMock.extractComments(any()))
                .thenAnswer(ignored -> Stream.of(historyBody));
        when(messageFormatterMock.extractPayloadFromHistoryBodyMarkdown(historyBody))
                .thenReturn(List.of(
                        new LotteryReport.Serialized(now.minus(1, ChronoUnit.DAYS), "gsmet",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 2))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty()),
                        new LotteryReport.Serialized(now.minus(7, ChronoUnit.DAYS), "yrodiere",
                                Optional.of(new LotteryReport.Bucket.Serialized(List.of(42))),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty())));

        var history = historyService.fetch(drawRef, config);

        // Notified recently
        assertThat(history.triage().lastNotificationTimedOutForIssueNumber(2))
                .isFalse();
        // Not notified at all
        assertThat(history.triage().lastNotificationTimedOutForIssueNumber(4))
                .isTrue();
        // Notified a long time ago (expired)
        assertThat(history.triage().lastNotificationTimedOutForIssueNumber(42))
                .isTrue();

        verifyNoMoreInteractions(gitHubServiceMock, persistenceRepoMock, messageFormatterMock);
    }

}
