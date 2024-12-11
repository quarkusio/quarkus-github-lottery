package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static io.quarkus.github.lottery.util.MockHelper.stubReportConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

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
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.github.lottery.notification.Notifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {
    GitHubService gitHubServiceMock;
    GitHubRepository notificationRepoMock;

    MessageFormatter messageFormatterMock;

    GitHubInstallationRef installationRef;
    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @Inject
    NotificationService notificationService;

    @BeforeEach
    void setup() {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1L);
        repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        notificationRepoMock = Mockito.mock(GitHubRepository.class);

        drawRef = new DrawRef(repoRef, LocalDateTime.of(2017, 11, 6, 8, 0).toInstant(ZoneOffset.UTC));

        messageFormatterMock = Mockito.mock(MessageFormatter.class);
        QuarkusMock.installMockForType(messageFormatterMock, MessageFormatter.class);
    }

    @Test
    void hasClosedDedicatedIssue() throws IOException {
        var config = new LotteryConfig.Notifications(
                new LotteryConfig.Notifications.CreateIssuesConfig("quarkusio/quarkus-lottery-reports"));

        var notificationRepoRef = new GitHubRepositoryRef(installationRef, config.createIssues().repository());
        when(gitHubServiceMock.repository(notificationRepoRef)).thenReturn(notificationRepoMock);
        GitHubRepository.Topic notificationTopicMock = Mockito.mock(GitHubRepository.Topic.class);
        when(notificationRepoMock.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus")))
                .thenReturn(notificationTopicMock);

        Notifier notifier = notificationService.notifier(drawRef, config);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        when(messageFormatterMock.formatNotificationTopicText(drawRef, "yrodiere"))
                .thenReturn("yrodiere's report for quarkusio/quarkus");

        when(notificationTopicMock.isClosed())
                .thenReturn(true);
        assertThat(notifier.isIgnoring("yrodiere")).isTrue();
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        when(notificationTopicMock.isClosed())
                .thenReturn(false);
        assertThat(notifier.isIgnoring("yrodiere")).isFalse();
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);
    }

    @Test
    void send() throws IOException {
        var config = new LotteryConfig.Notifications(
                new LotteryConfig.Notifications.CreateIssuesConfig("quarkusio/quarkus-lottery-reports"));

        var notificationRepoRef = new GitHubRepositoryRef(installationRef, config.createIssues().repository());
        when(gitHubServiceMock.repository(notificationRepoRef)).thenReturn(notificationRepoMock);
        when(notificationRepoMock.ref()).thenReturn(notificationRepoRef);
        GitHubRepository.Topic notificationTopicYrodiereMock = Mockito.mock(GitHubRepository.Topic.class);
        GitHubRepository.Topic notificationTopicGsmetMock = Mockito.mock(GitHubRepository.Topic.class);
        GitHubRepository.Topic notificationTopicGeoandMock = Mockito.mock(GitHubRepository.Topic.class);
        GitHubRepository.Topic notificationTopicJsmithMock = Mockito.mock(GitHubRepository.Topic.class);
        when(notificationRepoMock.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus")))
                .thenReturn(notificationTopicYrodiereMock);
        when(notificationRepoMock.topic(TopicRef.notification("gsmet", "gsmet's report for quarkusio/quarkus")))
                .thenReturn(notificationTopicGsmetMock);
        when(notificationRepoMock.topic(TopicRef.notification("geoand", "geoand's report for quarkusio/quarkus")))
                .thenReturn(notificationTopicGeoandMock);
        when(notificationRepoMock.topic(TopicRef.notification("jsmith", "jsmith's report for quarkusio/quarkus")))
                .thenReturn(notificationTopicJsmithMock);

        Notifier notifier = notificationService.notifier(drawRef, config);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport1 = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                stubReportConfig("area/hibernate-orm", "area/hibernate-search"),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 3))),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        var markdownNotification1 = "Notif 1";
        when(messageFormatterMock.formatNotificationTopicText(drawRef, "yrodiere"))
                .thenReturn("yrodiere's report for quarkusio/quarkus");
        when(messageFormatterMock.formatNotificationTopicSuffixText(lotteryReport1))
                .thenReturn(" (updated 2017-11-06T06:00:00Z)");
        when(messageFormatterMock.formatNotificationBodyMarkdown(lotteryReport1, notificationRepoRef))
                .thenReturn(markdownNotification1);
        notifier.send(lotteryReport1);
        verify(notificationTopicYrodiereMock)
                .update(" (updated 2017-11-06T06:00:00Z)", markdownNotification1, true);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport2 = new LotteryReport(drawRef, "gsmet", Optional.empty(),
                stubReportConfig("area/hibernate-validator"),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(4, 5))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(7, 8))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(9, 10))),
                Optional.empty());
        var markdownNotification2 = "Notif 2";
        when(messageFormatterMock.formatNotificationTopicText(drawRef, "gsmet"))
                .thenReturn("gsmet's report for quarkusio/quarkus");
        when(messageFormatterMock.formatNotificationTopicSuffixText(lotteryReport2))
                .thenReturn(" (updated 2017-11-06T06:00:00Z)");
        when(messageFormatterMock.formatNotificationBodyMarkdown(lotteryReport2, notificationRepoRef))
                .thenReturn(markdownNotification2);
        notifier.send(lotteryReport2);
        verify(notificationTopicGsmetMock)
                .update(" (updated 2017-11-06T06:00:00Z)", markdownNotification2, true);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport3 = new LotteryReport(drawRef, "geoand", Optional.empty(),
                stubReportConfig(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(11, 12))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(13, 14))));
        var markdownNotification3 = "Notif 3";
        when(messageFormatterMock.formatNotificationTopicText(drawRef, "geoand"))
                .thenReturn("geoand's report for quarkusio/quarkus");
        when(messageFormatterMock.formatNotificationTopicSuffixText(lotteryReport3))
                .thenReturn(" (updated 2017-11-06T06:00:00Z)");
        when(messageFormatterMock.formatNotificationBodyMarkdown(lotteryReport3, notificationRepoRef))
                .thenReturn(markdownNotification3);
        notifier.send(lotteryReport3);
        verify(notificationTopicGeoandMock)
                .update(" (updated 2017-11-06T06:00:00Z)", markdownNotification3, true);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport4 = new LotteryReport(drawRef, "jsmith", Optional.empty(),
                stubReportConfig(),
                Optional.of(new LotteryReport.Bucket(stubIssueList())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList())));
        var markdownNotification4 = "Notif 4";
        when(messageFormatterMock.formatNotificationTopicText(drawRef, "jsmith"))
                .thenReturn("jsmith's report for quarkusio/quarkus");
        when(messageFormatterMock.formatNotificationTopicSuffixText(lotteryReport4))
                .thenReturn(" (updated 2017-11-06T06:00:00Z)");
        when(messageFormatterMock.formatNotificationBodyMarkdown(lotteryReport4, notificationRepoRef))
                .thenReturn(markdownNotification4);
        notifier.send(lotteryReport4);
        verify(notificationTopicJsmithMock)
                .update(" (updated 2017-11-06T06:00:00Z)", markdownNotification4, false);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);
    }

}