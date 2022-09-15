package io.quarkus.github.lottery;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static io.quarkus.github.lottery.MockHelper.mockIssueForLottery;
import static io.quarkus.github.lottery.MockHelper.mockIssueForNotification;
import static io.quarkus.github.lottery.MockHelper.mockPagedIterable;
import static io.quarkus.github.lottery.MockHelper.url;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHIssueBuilder;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHRepository;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests that GitHubService correctly fetches data from the GitHub clients.
 */
@QuarkusTest
@GitHubAppTest
@ExtendWith(MockitoExtension.class)
public class GitHubServiceTest {

    @Inject
    GitHubService gitHubService;

    @Test
    void listRepositoriesAndIssues() throws IOException {
        GitHubRepositoryRef repoRef = new GitHubRepositoryRef(1234L, "quarkusio/quarkus");

        given()
                .github(mocks -> {
                    var applicationClient = mocks.applicationClient();
                    var appMock = mocks.ghObject(GHApp.class, repoRef.installationId());
                    when(applicationClient.getApp()).thenReturn(appMock);

                    var installationMock = mocks.ghObject(GHAppInstallation.class, repoRef.installationId());
                    var installationsMocks = mockPagedIterable(installationMock);
                    when(appMock.listInstallations()).thenReturn(installationsMocks);
                    when(installationMock.getId()).thenReturn(repoRef.installationId());
                    var installationRepositoryMock = Mockito.mock(GHRepository.class);
                    var installationRepositoryMocks = mockPagedIterable(installationRepositoryMock);
                    when(installationMock.listRepositories()).thenReturn(installationRepositoryMocks);
                    when(installationRepositoryMock.getFullName()).thenReturn(repoRef.repositoryName());

                    var repositoryMock = mocks.repository(repoRef.repositoryName());
                    mocks.configFile(repositoryMock, "quarkus-github-lottery.yaml")
                            .fromString("""
                                    notifications:
                                      createIssues:
                                        repository: "quarkusio/quarkus-lottery-reports"
                                    labels:
                                      needsTriage: "triage/needs-triage"
                                    participants:
                                      - username: "yrodiere"
                                        when: ["MONDAY"]
                                        triage:
                                          maxIssues: 3
                                      - username: "gsmet"
                                        when: ["MONDAY", "WEDNESDAY", "FRIDAY"]
                                        triage:
                                          maxIssues: 10
                                    """);

                    var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                            withSettings().defaultAnswer(Answers.RETURNS_SELF));
                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1, "Hibernate ORM works too well");
                    var issue2Mock = mockIssueForLottery(mocks, 3, "Hibernate Search needs Solr support");
                    var issue3Mock = mockIssueForLottery(mocks, 2, "Where can I find documentation?");
                    var issue4Mock = mockIssueForLottery(mocks, 4, "Hibernate ORM works too well");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    assertThat(gitHubService.listRepositories())
                            .containsExactlyInAnyOrder(repoRef);

                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.fetchLotteryConfig())
                            .isNotEmpty()
                            .get().usingRecursiveComparison().isEqualTo(new LotteryConfig(
                                    new LotteryConfig.NotificationsConfig(
                                            new LotteryConfig.NotificationsConfig.CreateIssuesConfig(
                                                    "quarkusio/quarkus-lottery-reports")),
                                    new LotteryConfig.LabelsConfig("triage/needs-triage"),
                                    List.of(
                                            new LotteryConfig.ParticipantConfig(
                                                    "yrodiere",
                                                    Set.of(DayOfWeek.MONDAY),
                                                    new LotteryConfig.ParticipationConfig(
                                                            3)),
                                            new LotteryConfig.ParticipantConfig(
                                                    "gsmet",
                                                    Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                                                    new LotteryConfig.ParticipationConfig(
                                                            10)))));

                    assertThat(repo.issuesWithLabel("triage/needs-triage"))
                            .toIterable().containsExactly(
                                    new Issue(1, "Hibernate ORM works too well", url(1)),
                                    new Issue(3, "Hibernate Search needs Solr support", url(3)),
                                    new Issue(2, "Where can I find documentation?", url(2)),
                                    new Issue(4, "Hibernate ORM works too well", url(4)));
                })
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void commentOnDedicatedNotificationIssue_dedicatedIssueExists() throws IOException {
        var repoRef = new GitHubRepositoryRef(1234L, "quarkusio/quarkus-lottery-reports");

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                            withSettings().defaultAnswer(Answers.RETURNS_SELF));
                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "yrodiere's notifications");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.commentOnDedicatedNotificationIssue("yrodiere", "Some content");
                })
                .then().github(mocks -> {
                    verify(mocks.issue(2)).comment("Some content");
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void commentOnDedicatedNotificationIssue_dedicatedIssueDoesNotExist() throws IOException {
        var repoRef = new GitHubRepositoryRef(1234L, "quarkusio/quarkus-lottery-reports");
        var issueBuilderMock = Mockito.mock(GHIssueBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                            withSettings().defaultAnswer(Answers.RETURNS_SELF));
                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(repositoryMock.createIssue("yrodiere's notifications")).thenReturn(issueBuilderMock);
                    var issue2Mock = mocks.issue(2);
                    when(issueBuilderMock.create()).thenReturn(issue2Mock);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.commentOnDedicatedNotificationIssue("yrodiere", "Some content");
                })
                .then().github(mocks -> {
                    verify(issueBuilderMock).assignee("yrodiere");
                    verify(issueBuilderMock).body("This is where @yrodiere will get periodically notified of issues.");
                    verifyNoMoreInteractions(issueBuilderMock);
                    verify(mocks.issue(2)).comment("Some content");
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

};