package io.quarkus.github.lottery;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForLottery;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForNotification;
import static io.quarkus.github.lottery.util.MockHelper.mockPagedIterable;
import static io.quarkus.github.lottery.util.MockHelper.url;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.test.junit.QuarkusMock;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueBuilder;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedSearchIterable;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests that GitHubService correctly interacts with the GitHub clients.
 */
@QuarkusTest
@GitHubAppTest
@ExtendWith(MockitoExtension.class)
public class GitHubServiceTest {

    private final GitHubInstallationRef installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1234L);

    @Inject
    GitHubService gitHubService;

    @Test
    void listRepositories() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var applicationClient = mocks.applicationClient();
                    {
                        // Scope: application client
                        var appMock = mocks.ghObject(GHApp.class, 1);
                        when(applicationClient.getApp()).thenReturn(appMock);
                        when(appMock.getSlug()).thenReturn(installationRef.appSlug());

                        var installationMock = Mockito.mock(GHAppInstallation.class);
                        when(installationMock.getId()).thenReturn(installationRef.installationId());
                        var installationsMocks = mockPagedIterable(installationMock);
                        when(appMock.listInstallations()).thenReturn(installationsMocks);
                    }

                    var installationClient = mocks.installationClient(installationRef.installationId());
                    {
                        // Scope: installation client
                        var installationMock = Mockito.mock(GHAuthenticatedAppInstallation.class);
                        when(installationClient.getInstallation()).thenReturn(installationMock);

                        var installationRepositoryMock = Mockito.mock(GHRepository.class);
                        var installationRepositoryMocks = mockPagedIterable(installationRepositoryMock);
                        when(installationMock.listRepositories()).thenReturn(installationRepositoryMocks);
                        when(installationRepositoryMock.getFullName()).thenReturn(repoRef.repositoryName());
                    }
                })
                .when(() -> {
                    assertThat(gitHubService.listRepositories())
                            .containsExactlyInAnyOrder(repoRef);
                })
                .then().github(mocks -> {
                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void fetchLotteryConfig() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());
                    mocks.configFile(repositoryMock, "quarkus-github-lottery.yml")
                            .fromString("""
                                    notifications:
                                      createIssues:
                                        repository: "quarkusio/quarkus-lottery-reports"
                                    buckets:
                                      triage:
                                        needsTriageLabel: "triage/needs-triage"
                                        notificationExpiration: P3D
                                    participants:
                                      - username: "yrodiere"
                                        days: ["MONDAY"]
                                        triage:
                                          maxIssues: 3
                                      - username: "gsmet"
                                        days: ["MONDAY", "WEDNESDAY", "FRIDAY"]
                                        timezone: "Europe/Paris"
                                        triage:
                                          maxIssues: 10
                                    """);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.fetchLotteryConfig())
                            .isNotEmpty()
                            .get().usingRecursiveComparison().isEqualTo(new LotteryConfig(
                                    new LotteryConfig.NotificationsConfig(
                                            new LotteryConfig.NotificationsConfig.CreateIssuesConfig(
                                                    "quarkusio/quarkus-lottery-reports")),
                                    new LotteryConfig.BucketsConfig(
                                            new LotteryConfig.BucketsConfig.TriageBucketConfig("triage/needs-triage",
                                                    Duration.ofDays(3))),
                                    List.of(
                                            new LotteryConfig.ParticipantConfig(
                                                    "yrodiere",
                                                    Set.of(DayOfWeek.MONDAY),
                                                    Optional.empty(),
                                                    new LotteryConfig.ParticipationConfig(
                                                            3)),
                                            new LotteryConfig.ParticipantConfig(
                                                    "gsmet",
                                                    Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                                                    Optional.of(ZoneId.of("Europe/Paris")),
                                                    new LotteryConfig.ParticipationConfig(
                                                            10)))));
                })
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesWithLabel() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
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

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1, "Hibernate ORM works too well");
                    var issue2Mock = mockIssueForLottery(mocks, 3, "Hibernate Search needs Solr support");
                    var issue3Mock = mockIssueForLottery(mocks, 2, "Where can I find documentation?");
                    var issue4Mock = mockIssueForLottery(mocks, 4, "Hibernate ORM works too well");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesWithLabel("triage/needs-triage"))
                            .containsExactly(
                                    new Issue(1, "Hibernate ORM works too well", url(1)),
                                    new Issue(3, "Hibernate Search needs Solr support", url(3)),
                                    new Issue(2, "Where can I find documentation?", url(2)),
                                    new Issue(4, "Hibernate ORM works too well", url(4)));
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).state(GHIssueState.OPEN);
                    verify(queryIssuesBuilderMock).sort(GHIssueQueryBuilder.Sort.UPDATED);
                    verify(queryIssuesBuilderMock).direction(GHDirection.DESC);
                    verify(queryIssuesBuilderMock).label("triage/needs-triage");
                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void extractCommentsFromDedicatedIssue_dedicatedIssueDoesNotExist() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Another unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.extractCommentsFromDedicatedIssue(null,
                            "Lottery history for quarkusio/quarkus", since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void extractCommentsFromDedicatedIssue_dedicatedIssueExists_appCommentsDoNotExist() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2Comment1Mock = mocks.issueComment(201);
                    when(issue2Comment1Mock.getUser()).thenReturn(someoneElseMock);
                    var issue2Comment2Mock = mocks.issueComment(202);
                    when(issue2Comment2Mock.getUser()).thenReturn(someoneElseMock);
                    var issue2CommentMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock);
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.extractCommentsFromDedicatedIssue(null,
                            "Lottery history for quarkusio/quarkus", since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(queryIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void extractCommentsFromDedicatedIssue_dedicatedIssueExists_appCommentsExist_allTooOld() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    PagedSearchIterable<GHIssueComment> issue2CommentMocks = mockPagedIterable();
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.extractCommentsFromDedicatedIssue(null,
                            "Lottery history for quarkusio/quarkus", since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(queryIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void extractCommentsFromDedicatedIssue_dedicatedIssueExists_appCommentsExist() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2Comment1Mock = mocks.issueComment(202);
                    when(issue2Comment1Mock.getUser()).thenReturn(mySelfMock);
                    when(issue2Comment1Mock.getBody()).thenReturn("issue2Comment1Mock#body");
                    var issue2Comment2Mock = mocks.issueComment(203);
                    when(issue2Comment2Mock.getUser()).thenReturn(mySelfMock);
                    when(issue2Comment2Mock.getBody()).thenReturn("issue2Comment2Mock#body");
                    var issue2Comment3Mock = mocks.issueComment(204);
                    when(issue2Comment3Mock.getUser()).thenReturn(someoneElseMock);
                    var issue2CommentMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock, issue2Comment3Mock);
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.extractCommentsFromDedicatedIssue(null,
                            "Lottery history for quarkusio/quarkus", since))
                            .containsExactly("issue2Comment1Mock#body", "issue2Comment2Mock#body");
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(queryIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void commentOnDedicatedIssue_dedicatedIssueExists_open() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2,
                            "yrodiere's report for quarkusio/quarkus (updated 2017-11-05T06:00:00Z)");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.OPEN);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2Comment1Mock = mocks.issueComment(201);
                    when(issue2Comment1Mock.getUser()).thenReturn(mySelfMock);
                    var issue2Comment2Mock = mocks.issueComment(202);
                    when(issue2Comment2Mock.getUser()).thenReturn(mySelfMock);
                    var issue2Comment3Mock = mocks.issueComment(203);
                    when(issue2Comment3Mock.getUser()).thenReturn(someoneElseMock);
                    var issue2CommentMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock, issue2Comment3Mock);
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(issue2Comment2Mock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.commentOnDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus",
                            " (updated 2017-11-06T06:00:00Z)", "Some content");
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("yrodiere");

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).setTitle("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void commentOnDedicatedIssue_dedicatedIssueExists_noTopicSuffix() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.OPEN);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2Comment1Mock = mocks.issueComment(201);
                    when(issue2Comment1Mock.getUser()).thenReturn(mySelfMock);
                    var issue2Comment2Mock = mocks.issueComment(202);
                    when(issue2Comment2Mock.getUser()).thenReturn(mySelfMock);
                    var issue2Comment3Mock = mocks.issueComment(203);
                    when(issue2Comment3Mock.getUser()).thenReturn(someoneElseMock);
                    var issue2CommentMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock, issue2Comment3Mock);
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(issue2Comment2Mock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.commentOnDedicatedIssue("quarkus-github-lottery[bot]",
                            "Lottery history for quarkusio/quarkus", "", "Some content");
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("quarkus-github-lottery[bot]");

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void commentOnDedicatedIssue_dedicatedIssueExists_closed() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2,
                            "yrodiere's report for quarkusio/quarkus (updated 2017-11-05T06:00:00Z)");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.CLOSED);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2Comment1Mock = mocks.issueComment(201);
                    when(issue2Comment1Mock.getUser()).thenReturn(mySelfMock);
                    var issue2Comment2Mock = mocks.issueComment(202);
                    when(issue2Comment2Mock.getUser()).thenReturn(mySelfMock);
                    var issue2Comment3Mock = mocks.issueComment(203);
                    when(issue2Comment3Mock.getUser()).thenReturn(someoneElseMock);
                    var issue2CommentMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock, issue2Comment3Mock);
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(issue2Comment2Mock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.commentOnDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus",
                            " (updated 2017-11-06T06:00:00Z)", "Some content");
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("yrodiere");

                    verify(mocks.issue(2)).setTitle("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(mocks.issue(2)).reopen();

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @Test
    void commentOnDedicatedIssue_dedicatedIssueDoesNotExist() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issueBuilderMock = Mockito.mock(GHIssueBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(repositoryMock.createIssue(any())).thenReturn(issueBuilderMock);
                    var issue2Mock = mocks.issue(2);
                    when(issueBuilderMock.create()).thenReturn(issue2Mock);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.commentOnDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus",
                            " (updated 2017-11-06T06:00:00Z)", "Some content");
                })
                .then().github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("yrodiere");
                    verify(repositoryMock)
                            .createIssue("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(issueBuilderMock).assignee("yrodiere");
                    verify(issueBuilderMock).body("This issue is dedicated to yrodiere's report for quarkusio/quarkus.");
                    verifyNoMoreInteractions(queryIssuesBuilderMock, issueBuilderMock);
                    verify(mocks.issue(2)).comment("Some content");
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

};