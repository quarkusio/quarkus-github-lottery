package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.MockHelper.url;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.notification.MarkdownNotification;
import io.quarkus.github.lottery.notification.NotificationFormatter;
import io.quarkus.github.lottery.notification.NotificationService;
import io.quarkus.github.lottery.notification.Notifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {
    GitHubService gitHubServiceMock;
    GitHubRepository sourceRepoMock;
    GitHubRepository notificationRepoMock;

    NotificationFormatter notificationFormatterMock;

    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @Inject
    NotificationService notificationService;

    @BeforeEach
    void setup() {
        gitHubServiceMock = Mockito.mock(GitHubService.class);
        QuarkusMock.installMockForType(gitHubServiceMock, GitHubService.class);
        repoRef = new GitHubRepositoryRef(1L, "quarkusio/quarkus");

        sourceRepoMock = Mockito.mock(GitHubRepository.class);
        when(sourceRepoMock.ref()).thenReturn(repoRef);
        notificationRepoMock = Mockito.mock(GitHubRepository.class);

        drawRef = new DrawRef(repoRef.repositoryName(), LocalDateTime.of(2017, 11, 6, 8, 0).toInstant(ZoneOffset.UTC));

        notificationFormatterMock = Mockito.mock(NotificationFormatter.class);
        QuarkusMock.installMockForType(notificationFormatterMock, NotificationFormatter.class);
    }

    @Test
    void simple() throws IOException {
        var config = new LotteryConfig.NotificationsConfig(
                new LotteryConfig.NotificationsConfig.CreateIssuesConfig("quarkusio/quarkus-lottery-reports"));

        var notificationRepoRef = new GitHubRepositoryRef(repoRef.installationId(), config.createIssues().repository());
        when(gitHubServiceMock.repository(notificationRepoRef)).thenReturn(notificationRepoMock);

        Notifier notifier = notificationService.notifier(sourceRepoMock, config);
        verifyNoMoreInteractions(gitHubServiceMock, sourceRepoMock, notificationRepoMock, notificationFormatterMock);

        var lotteryReport1 = new LotteryReport(drawRef, "yrodiere", List.of(
                new Issue(1, "Hibernate ORM works too well", url(1)),
                new Issue(3, "Hibernate Search needs Solr support", url(3))));
        var markdownNotification1 = new MarkdownNotification("yrodiere", "Notif 1");
        when(notificationFormatterMock.formatToMarkdown(lotteryReport1)).thenReturn(markdownNotification1);
        notifier.send(lotteryReport1);
        verify(notificationRepoMock).commentOnDedicatedNotificationIssue("yrodiere", markdownNotification1.body());
        verifyNoMoreInteractions(gitHubServiceMock, sourceRepoMock, notificationRepoMock, notificationFormatterMock);

        var lotteryReport2 = new LotteryReport(drawRef, "gsmet", List.of(
                new Issue(4, "Hibernate Search and Validator are on a boat", url(4)),
                new Issue(5, "Hibernate Validator needs Scala support", url(5))));
        var markdownNotification2 = new MarkdownNotification("gsmet", "Notif 2");
        when(notificationFormatterMock.formatToMarkdown(lotteryReport2)).thenReturn(markdownNotification2);
        notifier.send(lotteryReport2);
        verify(notificationRepoMock).commentOnDedicatedNotificationIssue("gsmet", markdownNotification2.body());
        verifyNoMoreInteractions(gitHubServiceMock, sourceRepoMock, notificationRepoMock, notificationFormatterMock);
    }

}