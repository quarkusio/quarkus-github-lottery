package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

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

        Notifier notifier = notificationService.notifier(drawRef, config);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        when(messageFormatterMock.formatNotificationTopicText(drawRef, "yrodiere"))
                .thenReturn("yrodiere's report for quarkusio/quarkus");

        when(notificationRepoMock.hasClosedDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                .thenReturn(true);
        assertThat(notifier.hasClosedDedicatedIssue("yrodiere")).isTrue();
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        when(notificationRepoMock.hasClosedDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                .thenReturn(false);
        assertThat(notifier.hasClosedDedicatedIssue("yrodiere")).isFalse();
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);
    }

    @Test
    void send() throws IOException {
        var config = new LotteryConfig.Notifications(
                new LotteryConfig.Notifications.CreateIssuesConfig("quarkusio/quarkus-lottery-reports"));

        var notificationRepoRef = new GitHubRepositoryRef(installationRef, config.createIssues().repository());
        when(gitHubServiceMock.repository(notificationRepoRef)).thenReturn(notificationRepoMock);
        when(notificationRepoMock.ref()).thenReturn(notificationRepoRef);

        Notifier notifier = notificationService.notifier(drawRef, config);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport1 = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
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
        verify(notificationRepoMock).commentOnDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus",
                " (updated 2017-11-06T06:00:00Z)", markdownNotification1);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport2 = new LotteryReport(drawRef, "gsmet", Optional.empty(),
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
        verify(notificationRepoMock).commentOnDedicatedIssue("gsmet", "gsmet's report for quarkusio/quarkus",
                " (updated 2017-11-06T06:00:00Z)", markdownNotification2);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);

        var lotteryReport3 = new LotteryReport(drawRef, "geoand", Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(11, 12))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(13, 14))));
        var markdownNotification3 = "Notif 2";
        when(messageFormatterMock.formatNotificationTopicText(drawRef, "geoand"))
                .thenReturn("geoand's report for quarkusio/quarkus");
        when(messageFormatterMock.formatNotificationTopicSuffixText(lotteryReport3))
                .thenReturn(" (updated 2017-11-06T06:00:00Z)");
        when(messageFormatterMock.formatNotificationBodyMarkdown(lotteryReport3, notificationRepoRef))
                .thenReturn(markdownNotification3);
        notifier.send(lotteryReport3);
        verify(notificationRepoMock).commentOnDedicatedIssue("geoand", "geoand's report for quarkusio/quarkus",
                " (updated 2017-11-06T06:00:00Z)", markdownNotification3);
        verifyNoMoreInteractions(gitHubServiceMock, notificationRepoMock, messageFormatterMock);
    }

}