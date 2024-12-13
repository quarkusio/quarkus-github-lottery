package io.quarkus.github.lottery;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueComment;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueEvent;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForLottery;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForLotteryFilteredOutByRepository;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForNotification;
import static io.quarkus.github.lottery.util.MockHelper.mockLabel;
import static io.quarkus.github.lottery.util.MockHelper.mockPagedIterable;
import static io.quarkus.github.lottery.util.MockHelper.mockUserForInspectedComments;
import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueBuilder;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHIssueEvent;
import org.kohsuke.github.GHIssueSearchBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedSearchIterable;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.github.IssueActionSide;
import io.quarkus.github.lottery.github.TopicRef;
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.test.junit.QuarkusMock;
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

    MessageFormatter messageFormatterMock;

    @BeforeEach
    void setup() {
        messageFormatterMock = Mockito.mock(MessageFormatter.class);
        QuarkusMock.installMockForType(messageFormatterMock, MessageFormatter.class);
    }

    @Test
    void listRepositories() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
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
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
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
                                        label: "triage/needs-triage"
                                        delay: PT0S
                                        timeout: P3D
                                      maintenance:
                                        created:
                                          delay: PT0S
                                          timeout: P1D
                                          expiry: P14D
                                          ignoreLabels: ["triage/on-ice"]
                                        feedback:
                                          labels: ["triage/needs-feedback", "triage/needs-reproducer"]
                                          needed:
                                            delay: P21D
                                            timeout: P3D
                                          provided:
                                            delay: P7D
                                            timeout: P3D
                                        stale:
                                          delay: P60D
                                          timeout: P14D
                                          ignoreLabels: ["triage/on-ice"]
                                      stewardship:
                                        delay: P60D
                                        timeout: P14D
                                        ignoreLabels: ["triage/on-ice"]
                                    participants:
                                      - username: "yrodiere"
                                        triage:
                                          days: ["MONDAY", "TUESDAY", "FRIDAY"]
                                          maxIssues: 3
                                        maintenance:
                                          labels: ["area/hibernate-orm", "area/hibernate-search"]
                                          days: ["MONDAY"]
                                          created:
                                            maxIssues: 5
                                          feedback:
                                            needed:
                                              maxIssues: 4
                                            provided:
                                              maxIssues: 2
                                          stale:
                                            maxIssues: 5
                                      - username: "gsmet"
                                        timezone: "Europe/Paris"
                                        triage:
                                          days: ["MONDAY", "WEDNESDAY", "FRIDAY"]
                                          maxIssues: 10
                                      - username: "jsmith"
                                        maintenance:
                                          labels: ["area/someobscurelibrary"]
                                          days: ["MONDAY"]
                                          feedback:
                                            needed:
                                              maxIssues: 1
                                            provided:
                                              maxIssues: 1
                                          stale:
                                            maxIssues: 5
                                      - username: "geoand"
                                        stewardship:
                                          days: ["MONDAY"]
                                          maxIssues: 10
                                      - username: "jblack"
                                        maintenance:
                                          labels: ["area/someotherobscurelibrary"]
                                          days: ["MONDAY"]
                                          feedback:
                                            needed:
                                              maxIssues: 1
                                            provided:
                                              maxIssues: 1
                                    """);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.fetchLotteryConfig())
                            .isNotEmpty()
                            .get().usingRecursiveComparison().isEqualTo(new LotteryConfig(
                                    new LotteryConfig.Notifications(
                                            new LotteryConfig.Notifications.CreateIssuesConfig(
                                                    "quarkusio/quarkus-lottery-reports")),
                                    new LotteryConfig.Buckets(
                                            new LotteryConfig.Buckets.Triage(
                                                    "triage/needs-triage",
                                                    Duration.ZERO, Duration.ofDays(3)),
                                            new LotteryConfig.Buckets.Maintenance(
                                                    new LotteryConfig.Buckets.Maintenance.Created(
                                                            Duration.ZERO, Duration.ofDays(1), Duration.ofDays(14),
                                                            List.of("triage/on-ice")),
                                                    new LotteryConfig.Buckets.Maintenance.Feedback(
                                                            List.of("triage/needs-feedback", "triage/needs-reproducer"),
                                                            new LotteryConfig.Buckets.Maintenance.Feedback.Needed(
                                                                    Duration.ofDays(21), Duration.ofDays(3)),
                                                            new LotteryConfig.Buckets.Maintenance.Feedback.Provided(
                                                                    Duration.ofDays(7), Duration.ofDays(3))),
                                                    new LotteryConfig.Buckets.Maintenance.Stale(
                                                            Duration.ofDays(60), Duration.ofDays(14),
                                                            List.of("triage/on-ice"))),
                                            new LotteryConfig.Buckets.Stewardship(
                                                    Duration.ofDays(60), Duration.ofDays(14), List.of("triage/on-ice"))),
                                    List.of(
                                            new LotteryConfig.Participant("yrodiere",
                                                    Optional.empty(),
                                                    Optional.of(new LotteryConfig.Participant.Triage(
                                                            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY),
                                                            new LotteryConfig.Participant.Participation(3))),
                                                    Optional.of(new LotteryConfig.Participant.Maintenance(
                                                            List.of("area/hibernate-orm", "area/hibernate-search"),
                                                            Set.of(DayOfWeek.MONDAY),
                                                            Optional.of(new LotteryConfig.Participant.Participation(5)),
                                                            Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                                                    new LotteryConfig.Participant.Participation(4),
                                                                    new LotteryConfig.Participant.Participation(2))),
                                                            Optional.of(new LotteryConfig.Participant.Participation(5)))),
                                                    Optional.empty()),
                                            new LotteryConfig.Participant("gsmet",
                                                    Optional.of(ZoneId.of("Europe/Paris")),
                                                    Optional.of(new LotteryConfig.Participant.Triage(
                                                            Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                                                            new LotteryConfig.Participant.Participation(10))),
                                                    Optional.empty(),
                                                    Optional.empty()),
                                            new LotteryConfig.Participant("jsmith",
                                                    Optional.empty(),
                                                    Optional.empty(),
                                                    Optional.of(new LotteryConfig.Participant.Maintenance(
                                                            List.of("area/someobscurelibrary"),
                                                            Set.of(DayOfWeek.MONDAY),
                                                            Optional.empty(),
                                                            Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                                                    new LotteryConfig.Participant.Participation(1),
                                                                    new LotteryConfig.Participant.Participation(1))),
                                                            Optional.of(new LotteryConfig.Participant.Participation(5)))),
                                                    Optional.empty()),
                                            new LotteryConfig.Participant("geoand",
                                                    Optional.empty(),
                                                    Optional.empty(),
                                                    Optional.empty(),
                                                    Optional.of(new LotteryConfig.Participant.Stewardship(
                                                            Set.of(DayOfWeek.MONDAY),
                                                            new LotteryConfig.Participant.Participation(10)))),
                                            new LotteryConfig.Participant("jblack",
                                                    Optional.empty(),
                                                    Optional.empty(),
                                                    Optional.of(new LotteryConfig.Participant.Maintenance(
                                                            List.of("area/someotherobscurelibrary"),
                                                            Set.of(DayOfWeek.MONDAY),
                                                            Optional.empty(),
                                                            Optional.of(new LotteryConfig.Participant.Maintenance.Feedback(
                                                                    new LotteryConfig.Participant.Participation(1),
                                                                    new LotteryConfig.Participant.Participation(1))),
                                                            Optional.empty())),
                                                    Optional.empty()))));
                })
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void fetchLotteryConfig_minimal() throws IOException {
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
                                        label: "triage/needs-triage"
                                        delay: PT0S
                                        timeout: P3D
                                      maintenance:
                                        created:
                                          delay: PT0S
                                          timeout: P1D
                                          expiry: P14D
                                        feedback:
                                          labels: ["triage/needs-feedback"]
                                          needed:
                                            delay: P21D
                                            timeout: P3D
                                          provided:
                                            delay: P7D
                                            timeout: P3D
                                        stale:
                                          delay: P60D
                                          timeout: P14D
                                      stewardship:
                                        delay: P60D
                                        timeout: P14D
                                    participants:
                                    """);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.fetchLotteryConfig())
                            .isNotEmpty()
                            .get().usingRecursiveComparison().isEqualTo(new LotteryConfig(
                                    new LotteryConfig.Notifications(
                                            new LotteryConfig.Notifications.CreateIssuesConfig(
                                                    "quarkusio/quarkus-lottery-reports")),
                                    new LotteryConfig.Buckets(
                                            new LotteryConfig.Buckets.Triage(
                                                    "triage/needs-triage",
                                                    Duration.ZERO, Duration.ofDays(3)),
                                            new LotteryConfig.Buckets.Maintenance(
                                                    new LotteryConfig.Buckets.Maintenance.Created(
                                                            Duration.ofDays(0), Duration.ofDays(1), Duration.ofDays(14),
                                                            List.of()),
                                                    new LotteryConfig.Buckets.Maintenance.Feedback(
                                                            List.of("triage/needs-feedback"),
                                                            new LotteryConfig.Buckets.Maintenance.Feedback.Needed(
                                                                    Duration.ofDays(21), Duration.ofDays(3)),
                                                            new LotteryConfig.Buckets.Maintenance.Feedback.Provided(
                                                                    Duration.ofDays(7), Duration.ofDays(3))),
                                                    new LotteryConfig.Buckets.Maintenance.Stale(
                                                            Duration.ofDays(60), Duration.ofDays(14), List.of())),
                                            new LotteryConfig.Buckets.Stewardship(
                                                    Duration.ofDays(60), Duration.ofDays(14), List.of())),
                                    List.of()));
                })
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesOrPullRequestsLastUpdatedBefore() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1);
                    var issue2Mock = mockIssueForLottery(mocks, 3);
                    var issue3Mock = mockIssueForLottery(mocks, 2);
                    var issue4Mock = mockIssueForLottery(mocks, 4);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesOrPullRequestsLastUpdatedBefore(Set.of(), cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 3, 2, 4));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).q("updated:<2017-11-05T06:00");
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.UPDATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.DESC);
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesOrPullRequestsLastUpdatedBefore_ignoreLabels() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1);
                    var issue2Mock = mockIssueForLottery(mocks, 3);
                    var issue3Mock = mockIssueForLottery(mocks, 2);
                    var issue4Mock = mockIssueForLottery(mocks, 4);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesOrPullRequestsLastUpdatedBefore(Set.of("triage/on-ice"), cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 3, 2, 4));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).q("updated:<2017-11-05T06:00");
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.UPDATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.DESC);
                    verify(searchIssuesBuilderMock).q("-label:triage/on-ice");
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesOrPullRequestsWithLabelLastUpdatedBefore() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1);
                    var issue2Mock = mockIssueForLottery(mocks, 3);
                    var issue3Mock = mockIssueForLottery(mocks, 2);
                    var issue4Mock = mockIssueForLottery(mocks, 4);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesOrPullRequestsWithLabelLastUpdatedBefore("triage/needs-triage", Set.of(), cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 3, 2, 4));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.UPDATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.DESC);
                    verify(searchIssuesBuilderMock).q("label:triage/needs-triage");
                    verify(searchIssuesBuilderMock).q("updated:<2017-11-05T06:00");
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesOrPullRequestsWithLabelLastUpdatedBefore_ignoreLabels() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1);
                    var issue2Mock = mockIssueForLottery(mocks, 3);
                    var issue3Mock = mockIssueForLottery(mocks, 2);
                    var issue4Mock = mockIssueForLottery(mocks, 4);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesOrPullRequestsWithLabelLastUpdatedBefore("triage/needs-triage",
                            Set.of("triage/on-ice"), cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 3, 2, 4));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.UPDATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.DESC);
                    verify(searchIssuesBuilderMock).q("label:triage/needs-triage");
                    verify(searchIssuesBuilderMock).q("updated:<2017-11-05T06:00");
                    verify(searchIssuesBuilderMock).q("-label:triage/on-ice");
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesLastActedOnByAndLastUpdatedBefore_team() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        Date issue1ActionLabelEvent = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date issue2ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));
        Date issue7ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));
        Date issue8ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue1QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue2QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue3QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue4QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue5QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue7QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue8QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    var adminUser = mockUserForInspectedComments(mocks, repositoryMock, 1L, "someadmin",
                            GHPermissionType.ADMIN);
                    var writeUser = mockUserForInspectedComments(mocks, repositoryMock, 2L, "somewriter",
                            GHPermissionType.WRITE);
                    var readUser = mockUserForInspectedComments(mocks, repositoryMock, 3L, "somereader", GHPermissionType.READ);
                    var noneUser = mockUserForInspectedComments(mocks, repositoryMock, 4L, "somestranger",
                            GHPermissionType.NONE);
                    var botUser = mockUserForInspectedComments(mocks, repositoryMock, 5L, "somebot[bot]");
                    var randomReporterUser = mockUserForInspectedComments(mocks, repositoryMock, 6L, "somereporter");

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock)
                            .thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1, randomReporterUser);
                    var issue2Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 2, randomReporterUser);
                    var issue3Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 3, randomReporterUser);
                    var issue4Mock = mockIssueForLottery(mocks, 4);
                    var issue5Mock = mockIssueForLottery(mocks, 5, randomReporterUser);
                    // issue6 used to be one after the cutoff, filtered out on the client side,
                    // but that's handled server-side now.
                    // Keeping this gap in numbering to avoid an even worse diff.
                    var issue7Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 7, randomReporterUser);
                    var issue8Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 8, writeUser);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock, issue5Mock,
                            issue7Mock, issue8Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var needsReproducerLabelMock = mockLabel("triage/needs-reproducer");
                    var areaHibernateSearchLabelMock = mockLabel("area/hibernate-search");

                    var issue1Event1Mock = mockIssueEvent("created");
                    var issue1Event2Mock = mockIssueEvent("labeled");
                    when(issue1Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue1Event2Mock.getCreatedAt()).thenReturn(issue1ActionLabelEvent);
                    var issue1Event3Mock = mockIssueEvent("labeled");
                    when(issue1Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue1Event4Mock = mockIssueEvent("locked");
                    var issue1EventsMocks = mockPagedIterable(issue1Event1Mock,
                            issue1Event2Mock, issue1Event3Mock, issue1Event4Mock);
                    when(issue1Mock.listEvents()).thenReturn(issue1EventsMocks);
                    var issue1CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 101, noneUser),
                            mockIssueComment(mocks, 102, adminUser));
                    when(issue1Mock.queryComments()).thenReturn(issue1QueryCommentsBuilderMock);
                    when(issue1QueryCommentsBuilderMock.list()).thenReturn(issue1CommentsMocks);

                    var issue2Event1Mock = mockIssueEvent("created");
                    var issue2Event2Mock = mockIssueEvent("labeled");
                    when(issue2Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue2Event2Mock.getCreatedAt()).thenReturn(issue2ActionLabelEvent);
                    var issue2Event3Mock = mockIssueEvent("labeled");
                    when(issue2Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue2Event4Mock = mockIssueEvent("locked");
                    var issue2EventsMocks = mockPagedIterable(issue2Event1Mock,
                            issue2Event2Mock, issue2Event3Mock, issue2Event4Mock);
                    when(issue2Mock.listEvents()).thenReturn(issue2EventsMocks);
                    var issue2CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 201, adminUser),
                            mockIssueComment(mocks, 202, readUser));
                    when(issue2Mock.queryComments()).thenReturn(issue2QueryCommentsBuilderMock);
                    when(issue2QueryCommentsBuilderMock.list()).thenReturn(issue2CommentsMocks);

                    PagedSearchIterable<GHIssueEvent> issue3EventsMocks = mockPagedIterable();
                    when(issue3Mock.listEvents()).thenReturn(issue3EventsMocks);
                    var issue3CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 301, adminUser),
                            mockIssueComment(mocks, 302, noneUser));
                    when(issue3Mock.queryComments()).thenReturn(issue3QueryCommentsBuilderMock);
                    when(issue3QueryCommentsBuilderMock.list()).thenReturn(issue3CommentsMocks);

                    PagedSearchIterable<GHIssueEvent> issue4EventsMocks = mockPagedIterable();
                    when(issue4Mock.listEvents()).thenReturn(issue4EventsMocks);
                    PagedSearchIterable<GHIssueComment> issue4CommentsMocks = mockPagedIterable();
                    when(issue4Mock.queryComments()).thenReturn(issue4QueryCommentsBuilderMock);
                    when(issue4QueryCommentsBuilderMock.list()).thenReturn(issue4CommentsMocks);

                    var issue5Event1Mock = mockIssueEvent("created");
                    var issue5Event2Mock = mockIssueEvent("locked");
                    var issue5EventsMocks = mockPagedIterable(issue5Event1Mock, issue5Event2Mock);
                    when(issue5Mock.listEvents()).thenReturn(issue5EventsMocks);
                    var issue5CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 501, noneUser),
                            mockIssueComment(mocks, 502, writeUser));
                    when(issue5Mock.queryComments()).thenReturn(issue5QueryCommentsBuilderMock);
                    when(issue5QueryCommentsBuilderMock.list()).thenReturn(issue5CommentsMocks);

                    // This is like issue 2, but a bot commented after the user -- which should be ignored.
                    var issue7Event1Mock = mockIssueEvent("created");
                    var issue7Event2Mock = mockIssueEvent("labeled");
                    when(issue7Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue7Event2Mock.getCreatedAt()).thenReturn(issue7ActionLabelEvent);
                    var issue7Event3Mock = mockIssueEvent("labeled");
                    when(issue7Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue7Event4Mock = mockIssueEvent("locked");
                    var issue7EventsMocks = mockPagedIterable(issue7Event1Mock,
                            issue7Event2Mock, issue7Event3Mock, issue7Event4Mock);
                    when(issue7Mock.listEvents()).thenReturn(issue7EventsMocks);
                    var issue7CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 701, noneUser),
                            mockIssueComment(mocks, 702, readUser),
                            mockIssueComment(mocks, 703, botUser));
                    when(issue7Mock.queryComments()).thenReturn(issue7QueryCommentsBuilderMock);
                    when(issue7QueryCommentsBuilderMock.list()).thenReturn(issue7CommentsMocks);

                    // This is like issue 2, but the reporter is a team member -- so should be considered as an outsider.
                    var issue8Event1Mock = mockIssueEvent("created");
                    var issue8Event2Mock = mockIssueEvent("labeled");
                    when(issue8Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue8Event2Mock.getCreatedAt()).thenReturn(issue8ActionLabelEvent);
                    var issue8Event3Mock = mockIssueEvent("labeled");
                    when(issue8Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue8Event4Mock = mockIssueEvent("locked");
                    var issue8EventsMocks = mockPagedIterable(issue8Event1Mock,
                            issue8Event2Mock, issue8Event3Mock, issue8Event4Mock);
                    when(issue8Mock.listEvents()).thenReturn(issue8EventsMocks);
                    var issue8CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 801, noneUser),
                            mockIssueComment(mocks, 802, writeUser));
                    when(issue8Mock.queryComments()).thenReturn(issue8QueryCommentsBuilderMock);
                    when(issue8QueryCommentsBuilderMock.list()).thenReturn(issue8CommentsMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesLastActedOnByAndLastUpdatedBefore(
                            new LinkedHashSet<>(List.of("triage/needs-feedback", "triage/needs-reproducer")),
                            "area/hibernate-search", IssueActionSide.TEAM, cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 4, 5));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).q("updated:<2017-11-05T06:00");
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.UPDATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.DESC);
                    verify(searchIssuesBuilderMock).q("label:triage/needs-feedback,triage/needs-reproducer");
                    verify(searchIssuesBuilderMock).q("label:area/hibernate-search");
                    verifyNoMoreInteractions(searchIssuesBuilderMock);

                    verify(issue1QueryCommentsBuilderMock).since(issue1ActionLabelEvent);
                    verify(issue2QueryCommentsBuilderMock).since(issue2ActionLabelEvent);
                    verify(issue7QueryCommentsBuilderMock).since(issue7ActionLabelEvent);
                    verify(issue8QueryCommentsBuilderMock).since(issue8ActionLabelEvent);

                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesLastActedOnByAndLastUpdatedBefore_outsider() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        Date issue1ActionLabelEvent = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date issue2ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));
        Date issue7ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));
        Date issue8ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue1QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue2QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue3QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue4QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue5QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue7QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue8QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    var adminUser = mockUserForInspectedComments(mocks, repositoryMock, 1L, "someadmin",
                            GHPermissionType.ADMIN);
                    var writeUser = mockUserForInspectedComments(mocks, repositoryMock, 2L, "somewriter",
                            GHPermissionType.WRITE);
                    var readUser = mockUserForInspectedComments(mocks, repositoryMock, 3L, "somereader", GHPermissionType.READ);
                    var noneUser = mockUserForInspectedComments(mocks, repositoryMock, 4L, "somestranger",
                            GHPermissionType.NONE);
                    var botUser = mockUserForInspectedComments(mocks, repositoryMock, 5L, "somebot[bot]");
                    var randomReporterUser = mockUserForInspectedComments(mocks, repositoryMock, 6L, "somereporter");

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    // Pull requests should always be filtered out
                    var issue1Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 1, randomReporterUser);
                    var issue2Mock = mockIssueForLottery(mocks, 2, randomReporterUser);
                    var issue3Mock = mockIssueForLottery(mocks, 3, randomReporterUser);
                    var issue4Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 4);
                    var issue5Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 5, randomReporterUser);
                    // issue6 used to be one after the cutoff, filtered out on the client side,
                    // but that's handled server-side now.
                    // Keeping this gap in numbering to avoid an even worse diff.
                    var issue7Mock = mockIssueForLottery(mocks, 7, randomReporterUser);
                    var issue8Mock = mockIssueForLottery(mocks, 8, writeUser);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock, issue5Mock,
                            issue7Mock, issue8Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var needsReproducerLabelMock = mockLabel("triage/needs-reproducer");
                    var areaHibernateSearchLabelMock = mockLabel("area/hibernate-search");

                    var issue1Event1Mock = mockIssueEvent("created");
                    var issue1Event2Mock = mockIssueEvent("labeled");
                    when(issue1Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue1Event2Mock.getCreatedAt()).thenReturn(issue1ActionLabelEvent);
                    var issue1Event3Mock = mockIssueEvent("labeled");
                    when(issue1Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue1Event4Mock = mockIssueEvent("locked");
                    var issue1EventsMocks = mockPagedIterable(issue1Event1Mock,
                            issue1Event2Mock, issue1Event3Mock, issue1Event4Mock);
                    when(issue1Mock.listEvents()).thenReturn(issue1EventsMocks);
                    var issue1CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 101, noneUser),
                            mockIssueComment(mocks, 102, adminUser));
                    when(issue1Mock.queryComments()).thenReturn(issue1QueryCommentsBuilderMock);
                    when(issue1QueryCommentsBuilderMock.list()).thenReturn(issue1CommentsMocks);

                    var issue2Event1Mock = mockIssueEvent("created");
                    var issue2Event2Mock = mockIssueEvent("labeled");
                    when(issue2Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue2Event2Mock.getCreatedAt()).thenReturn(issue2ActionLabelEvent);
                    var issue2Event3Mock = mockIssueEvent("labeled");
                    when(issue2Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue2Event4Mock = mockIssueEvent("locked");
                    var issue2EventsMocks = mockPagedIterable(issue2Event1Mock,
                            issue2Event2Mock, issue2Event3Mock, issue2Event4Mock);
                    when(issue2Mock.listEvents()).thenReturn(issue2EventsMocks);
                    var issue2CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 201, adminUser),
                            mockIssueComment(mocks, 202, readUser));
                    when(issue2Mock.queryComments()).thenReturn(issue2QueryCommentsBuilderMock);
                    when(issue2QueryCommentsBuilderMock.list()).thenReturn(issue2CommentsMocks);

                    PagedSearchIterable<GHIssueEvent> issue3EventsMocks = mockPagedIterable();
                    when(issue3Mock.listEvents()).thenReturn(issue3EventsMocks);
                    var issue3CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 301, adminUser),
                            mockIssueComment(mocks, 302, noneUser));
                    when(issue3Mock.queryComments()).thenReturn(issue3QueryCommentsBuilderMock);
                    when(issue3QueryCommentsBuilderMock.list()).thenReturn(issue3CommentsMocks);

                    PagedSearchIterable<GHIssueEvent> issue4EventsMocks = mockPagedIterable();
                    when(issue4Mock.listEvents()).thenReturn(issue4EventsMocks);
                    PagedSearchIterable<GHIssueComment> issue4CommentsMocks = mockPagedIterable();
                    when(issue4Mock.queryComments()).thenReturn(issue4QueryCommentsBuilderMock);
                    when(issue4QueryCommentsBuilderMock.list()).thenReturn(issue4CommentsMocks);

                    var issue5Event1Mock = mockIssueEvent("created");
                    var issue5Event2Mock = mockIssueEvent("locked");
                    var issue5EventsMocks = mockPagedIterable(issue5Event1Mock, issue5Event2Mock);
                    when(issue5Mock.listEvents()).thenReturn(issue5EventsMocks);
                    var issue5CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 501, noneUser),
                            mockIssueComment(mocks, 502, writeUser));
                    when(issue5Mock.queryComments()).thenReturn(issue5QueryCommentsBuilderMock);
                    when(issue5QueryCommentsBuilderMock.list()).thenReturn(issue5CommentsMocks);

                    // This is like issue 2, but a bot commented after the user -- which should be ignored.
                    var issue7Event1Mock = mockIssueEvent("created");
                    var issue7Event2Mock = mockIssueEvent("labeled");
                    when(issue7Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue7Event2Mock.getCreatedAt()).thenReturn(issue7ActionLabelEvent);
                    var issue7Event3Mock = mockIssueEvent("labeled");
                    when(issue7Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue7Event4Mock = mockIssueEvent("locked");
                    var issue7EventsMocks = mockPagedIterable(issue7Event1Mock,
                            issue7Event2Mock, issue7Event3Mock, issue7Event4Mock);
                    when(issue7Mock.listEvents()).thenReturn(issue7EventsMocks);
                    var issue7CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 701, adminUser),
                            mockIssueComment(mocks, 702, readUser),
                            mockIssueComment(mocks, 703, botUser));
                    when(issue7Mock.queryComments()).thenReturn(issue7QueryCommentsBuilderMock);
                    when(issue7QueryCommentsBuilderMock.list()).thenReturn(issue7CommentsMocks);

                    // This is like issue 2, but the reporter is a team member -- so should be considered as an outsider.
                    var issue8Event1Mock = mockIssueEvent("created");
                    var issue8Event2Mock = mockIssueEvent("labeled");
                    when(issue8Event2Mock.getLabel()).thenReturn(needsReproducerLabelMock);
                    when(issue8Event2Mock.getCreatedAt()).thenReturn(issue8ActionLabelEvent);
                    var issue8Event3Mock = mockIssueEvent("labeled");
                    when(issue8Event3Mock.getLabel()).thenReturn(areaHibernateSearchLabelMock);
                    var issue8Event4Mock = mockIssueEvent("locked");
                    var issue8EventsMocks = mockPagedIterable(issue8Event1Mock,
                            issue8Event2Mock, issue8Event3Mock, issue8Event4Mock);
                    when(issue8Mock.listEvents()).thenReturn(issue8EventsMocks);
                    var issue8CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 801, noneUser),
                            mockIssueComment(mocks, 802, writeUser));
                    when(issue8Mock.queryComments()).thenReturn(issue8QueryCommentsBuilderMock);
                    when(issue8QueryCommentsBuilderMock.list()).thenReturn(issue8CommentsMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesLastActedOnByAndLastUpdatedBefore(
                            new LinkedHashSet<>(List.of("triage/needs-feedback", "triage/needs-reproducer")),
                            "area/hibernate-search", IssueActionSide.OUTSIDER, cutoff))
                            .containsExactlyElementsOf(stubIssueList(2, 3, 7, 8));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).q("updated:<2017-11-05T06:00");
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.UPDATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.DESC);
                    verify(searchIssuesBuilderMock).q("label:triage/needs-feedback,triage/needs-reproducer");
                    verify(searchIssuesBuilderMock).q("label:area/hibernate-search");
                    verifyNoMoreInteractions(searchIssuesBuilderMock);

                    verify(issue1QueryCommentsBuilderMock).since(issue1ActionLabelEvent);
                    verify(issue2QueryCommentsBuilderMock).since(issue2ActionLabelEvent);
                    verify(issue7QueryCommentsBuilderMock).since(issue7ActionLabelEvent);
                    verify(issue8QueryCommentsBuilderMock).since(issue8ActionLabelEvent);

                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant minCutoff = now.minus(14, ChronoUnit.DAYS);
        Instant maxCutoff = now.minus(0, ChronoUnit.DAYS);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue1QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue2QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue3QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue4QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue5QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue7QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issue8QueryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    var adminUser = mockUserForInspectedComments(mocks, repositoryMock, 1L, "someadmin",
                            GHPermissionType.ADMIN);
                    var writeUser = mockUserForInspectedComments(mocks, repositoryMock, 2L, "somewriter",
                            GHPermissionType.WRITE);
                    var readUser = mockUserForInspectedComments(mocks, repositoryMock, 3L, "somereader", GHPermissionType.READ);
                    var noneUser = mockUserForInspectedComments(mocks, repositoryMock, 4L, "somestranger",
                            GHPermissionType.NONE);
                    var botUser = mockUserForInspectedComments(mocks, repositoryMock, 5L, "somebot[bot]");
                    var randomReporterUser = mockUserForInspectedComments(mocks, repositoryMock, 6L, "somereporter");

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    // Pull requests should always be filtered out
                    var issue1Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 1, randomReporterUser);
                    var issue2Mock = mockIssueForLottery(mocks, 2, randomReporterUser);
                    var issue3Mock = mockIssueForLottery(mocks, 3, randomReporterUser);
                    var issue4Mock = mockIssueForLottery(mocks, 4);
                    var issue5Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 5, randomReporterUser);
                    var issue7Mock = mockIssueForLottery(mocks, 7, randomReporterUser);
                    var issue8Mock = mockIssueForLottery(mocks, 8, writeUser);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock, issue5Mock,
                            issue7Mock, issue8Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var issue1CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 101, noneUser),
                            mockIssueComment(mocks, 102, readUser),
                            mockIssueComment(mocks, 103, adminUser));
                    when(issue1Mock.queryComments()).thenReturn(issue1QueryCommentsBuilderMock);
                    when(issue1QueryCommentsBuilderMock.list()).thenReturn(issue1CommentsMocks);

                    var issue2CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 202, readUser));
                    when(issue2Mock.queryComments()).thenReturn(issue2QueryCommentsBuilderMock);
                    when(issue2QueryCommentsBuilderMock.list()).thenReturn(issue2CommentsMocks);

                    var issue3CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 302, noneUser));
                    when(issue3Mock.queryComments()).thenReturn(issue3QueryCommentsBuilderMock);
                    when(issue3QueryCommentsBuilderMock.list()).thenReturn(issue3CommentsMocks);

                    PagedSearchIterable<GHIssueComment> issue4CommentsMocks = mockPagedIterable();
                    when(issue4Mock.queryComments()).thenReturn(issue4QueryCommentsBuilderMock);
                    when(issue4QueryCommentsBuilderMock.list()).thenReturn(issue4CommentsMocks);

                    var issue5CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 501, noneUser),
                            mockIssueComment(mocks, 502, writeUser));
                    when(issue5Mock.queryComments()).thenReturn(issue5QueryCommentsBuilderMock);
                    when(issue5QueryCommentsBuilderMock.list()).thenReturn(issue5CommentsMocks);

                    // This is like issue 2, but a bot commented after the user -- which should be ignored.
                    var issue7CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 701, readUser),
                            mockIssueComment(mocks, 702, botUser));
                    when(issue7Mock.queryComments()).thenReturn(issue7QueryCommentsBuilderMock);
                    when(issue7QueryCommentsBuilderMock.list()).thenReturn(issue7CommentsMocks);

                    // This is like issue 2, but the reporter is a team member -- so should be considered as an outsider.
                    var issue8CommentsMocks = mockPagedIterable(mockIssueComment(mocks, 801, noneUser),
                            mockIssueComment(mocks, 802, writeUser));
                    when(issue8Mock.queryComments()).thenReturn(issue8QueryCommentsBuilderMock);
                    when(issue8QueryCommentsBuilderMock.list()).thenReturn(issue8CommentsMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween(
                            "area/hibernate-search",
                            new LinkedHashSet<>(List.of("triage/needs-feedback", "triage/needs-reproducer", "triage/on-ice")),
                            Set.of("yrodiere"),
                            minCutoff, maxCutoff))
                            .containsExactlyElementsOf(stubIssueList(2, 3, 4, 7, 8));
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).isOpen();
                    verify(searchIssuesBuilderMock).sort(GHIssueSearchBuilder.Sort.CREATED);
                    verify(searchIssuesBuilderMock).order(GHDirection.ASC);
                    verify(searchIssuesBuilderMock).q("label:area/hibernate-search");
                    verify(searchIssuesBuilderMock).q("created:2017-10-23T06:00..2017-11-06T06:00");
                    verify(searchIssuesBuilderMock).q("-label:triage/needs-feedback,triage/needs-reproducer,triage/on-ice");
                    verify(searchIssuesBuilderMock).q("-commenter:yrodiere");
                    verifyNoMoreInteractions(searchIssuesBuilderMock);

                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_extractComments_dedicatedIssueDoesNotExist() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Another unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .extractComments(since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_extractComments_dedicatedIssueDoesNotExist_withConfusingOther() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues())
                            .thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "Lottery history for quarkusio/quarkusio.github.io");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Another unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .extractComments(since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_extractComments_dedicatedIssueExists_appCommentsDoNotExist() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2CommentMocks = mockPagedIterable(mockIssueComment(mocks, 201, someoneElseMock),
                            mockIssueComment(mocks, 202, someoneElseMock));
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .extractComments(since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(searchIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_extractComments_dedicatedIssueExists_appCommentsExist_allTooOld() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    PagedSearchIterable<GHIssueComment> issue2CommentMocks = mockPagedIterable();
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .extractComments(since))
                            .isEmpty();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(searchIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_extractComments_dedicatedIssueExists_appCommentsExist() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2CommentMocks = mockPagedIterable(
                            mockIssueComment(mocks, 201, mySelfMock, "issue2Comment1Mock#body"),
                            mockIssueComment(mocks, 202, mySelfMock, "issue2Comment2Mock#body"),
                            mockIssueComment(mocks, 203, someoneElseMock));
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .extractComments(since))
                            .containsExactly("issue2Comment1Mock#body", "issue2Comment2Mock#body");
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(searchIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_extractComments_dedicatedIssueExists_appCommentsExist_withConfusingOther() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var since = LocalDateTime.of(2017, 11, 6, 19, 0).toInstant(ZoneOffset.UTC);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "Lottery history for quarkusio/quarkusio.github.io");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var issue2Comment1Mock = mockIssueComment(mocks, 202, mySelfMock, "issue2Comment1Mock#body");
                    var issue2Comment2Mock = mockIssueComment(mocks, 203, mySelfMock, "issue2Comment2Mock#body");
                    var issue2Comment3Mock = mockIssueComment(mocks, 204, someoneElseMock);
                    var issue2CommentMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock, issue2Comment3Mock);
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .extractComments(since))
                            .containsExactly("issue2Comment1Mock#body", "issue2Comment2Mock#body");
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(queryCommentsBuilderMock).since(Date.from(since));

                    verifyNoMoreInteractions(searchIssuesBuilderMock, queryCommentsBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void topic_update_dedicatedIssueExists_open() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2,
                            "yrodiere's report for quarkusio/quarkus (updated 2017-11-05T06:00:00Z)");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.OPEN);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var commentToMinimizeMock = mockIssueComment(mocks, 202, mySelfMock);
                    when(commentToMinimizeMock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                    var issue2CommentMocks = mockPagedIterable(mockIssueComment(mocks, 201, mySelfMock),
                            commentToMinimizeMock,
                            mockIssueComment(mocks, 203, someoneElseMock));
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(messageFormatterMock.formatDedicatedIssueBodyMarkdown("yrodiere's report for quarkusio/quarkus",
                            "Some content"))
                            .thenReturn("Dedicated issue body");
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .update(" (updated 2017-11-06T06:00:00Z)", "Some content", true);
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).setTitle("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(mocks.issue(2)).setBody("Dedicated issue body");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void topic_update_dedicatedIssueExists_noTopicSuffix() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.OPEN);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var commentToMinimizeMock = mockIssueComment(mocks, 202, mySelfMock);
                    when(commentToMinimizeMock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                    var issue2CommentMocks = mockPagedIterable(mockIssueComment(mocks, 201, mySelfMock),
                            mockIssueComment(mocks, 202, mySelfMock),
                            mockIssueComment(mocks, 203, someoneElseMock));
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(messageFormatterMock.formatDedicatedIssueBodyMarkdown("Lottery history for quarkusio/quarkus",
                            "Some content"))
                            .thenReturn("Dedicated issue body");
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .update("", "Some content", true);
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).setBody("Dedicated issue body");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(messageFormatterMock, searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void topic_update_dedicatedIssueExists_withConfusingOther() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "Lottery history for quarkusio/quarkusio.github.io");
                    var issue2Mock = mockIssueForNotification(mocks, 2, "Lottery history for quarkusio/quarkus");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.OPEN);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var commentToMinimizeMock = mockIssueComment(mocks, 202, mySelfMock);
                    when(commentToMinimizeMock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                    var issue2CommentMocks = mockPagedIterable(mockIssueComment(mocks, 201, mySelfMock),
                            mockIssueComment(mocks, 202, mySelfMock),
                            mockIssueComment(mocks, 203, someoneElseMock));
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(messageFormatterMock.formatDedicatedIssueBodyMarkdown("Lottery history for quarkusio/quarkus",
                            "Some content"))
                            .thenReturn("Dedicated issue body");
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.topic(TopicRef.history("Lottery history for quarkusio/quarkus"))
                            .update("", "Some content", true);
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).setBody("Dedicated issue body");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(messageFormatterMock, searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    void topic_update_dedicatedIssueExists_closed() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var commentToMinimizeNodeId = "MDM6Qm90NzUwNjg0Mzg=";

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var queryCommentsBuilderMock = Mockito.mock(GHIssueCommentQueryBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2,
                            "yrodiere's report for quarkusio/quarkus (updated 2017-11-05T06:00:00Z)");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.CLOSED);

                    var mySelfMock = mocks.ghObject(GHUser.class, 1L);
                    when(mySelfMock.getLogin()).thenReturn(installationRef.appLogin());
                    var someoneElseMock = mocks.ghObject(GHUser.class, 2L);
                    when(someoneElseMock.getLogin()).thenReturn("yrodiere");

                    when(issue2Mock.queryComments()).thenReturn(queryCommentsBuilderMock);
                    var commentToMinimizeMock = mockIssueComment(mocks, 202, mySelfMock);
                    when(commentToMinimizeMock.getNodeId()).thenReturn(commentToMinimizeNodeId);
                    var issue2CommentMocks = mockPagedIterable(mockIssueComment(mocks, 201, mySelfMock),
                            mockIssueComment(mocks, 202, mySelfMock),
                            mockIssueComment(mocks, 203, someoneElseMock));
                    when(queryCommentsBuilderMock.list()).thenReturn(issue2CommentMocks);

                    when(messageFormatterMock.formatDedicatedIssueBodyMarkdown("yrodiere's report for quarkusio/quarkus",
                            "Some content"))
                            .thenReturn("Dedicated issue body");
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .update(" (updated 2017-11-06T06:00:00Z)", "Some content", true);
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");

                    verify(mocks.issue(2)).setTitle("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(mocks.issue(2)).reopen();

                    verify(queryCommentsBuilderMock).since(Date.from(now.minus(21, ChronoUnit.DAYS)));
                    var mapCaptor = ArgumentCaptor.forClass(Map.class);
                    verify(mocks.installationGraphQLClient(installationRef.installationId()))
                            .executeSync(anyString(), mapCaptor.capture());

                    verify(mocks.issue(2)).setBody("Dedicated issue body");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(messageFormatterMock, searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());

                    assertThat(mapCaptor.getValue()).containsValue(commentToMinimizeNodeId);
                });
    }

    @Test
    void topic_update_dedicatedIssueDoesNotExist() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issueBuilderMock = Mockito.mock(GHIssueBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(repositoryMock.createIssue(any())).thenReturn(issueBuilderMock);
                    var issue2Mock = mocks.issue(2);
                    when(issueBuilderMock.create()).thenReturn(issue2Mock);

                    when(messageFormatterMock.formatDedicatedIssueBodyMarkdown("yrodiere's report for quarkusio/quarkus",
                            "Some content"))
                            .thenReturn("Dedicated issue body");
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .update(" (updated 2017-11-06T06:00:00Z)", "Some content", true);
                })
                .then().github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");
                    verify(repositoryMock)
                            .createIssue("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(issueBuilderMock).assignee("yrodiere");
                    verify(issueBuilderMock).body("Dedicated issue body");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(messageFormatterMock, searchIssuesBuilderMock, issueBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_update_dedicatedIssueDoesNotExist_withConfusingOther() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        var issueBuilderMock = Mockito.mock(GHIssueBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "yrodiere's report for quarkusio/quarkusio.githbub.io");
                    var issuesMocks = mockPagedIterable(issue1Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(repositoryMock.createIssue(any())).thenReturn(issueBuilderMock);
                    var issue2Mock = mocks.issue(2);
                    when(issueBuilderMock.create()).thenReturn(issue2Mock);

                    when(messageFormatterMock.formatDedicatedIssueBodyMarkdown("yrodiere's report for quarkusio/quarkus",
                            "Some content"))
                            .thenReturn("Dedicated issue body");
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .update(" (updated 2017-11-06T06:00:00Z)", "Some content", true);
                })
                .then().github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");
                    verify(repositoryMock)
                            .createIssue("yrodiere's report for quarkusio/quarkus (updated 2017-11-06T06:00:00Z)");
                    verify(issueBuilderMock).assignee("yrodiere");
                    verify(issueBuilderMock).body("Dedicated issue body");
                    verify(mocks.issue(2)).comment("Some content");

                    verifyNoMoreInteractions(messageFormatterMock, searchIssuesBuilderMock, issueBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_isClosed_dedicatedIssueExists_open() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2,
                            "yrodiere's report for quarkusio/quarkus (updated 2017-11-05T06:00:00Z)");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.OPEN);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .isClosed())
                            .isFalse();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");

                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_isClosed_dedicatedIssueExists_closed() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issue2Mock = mockIssueForNotification(mocks, 2,
                            "yrodiere's report for quarkusio/quarkus (updated 2017-11-05T06:00:00Z)");
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    when(issue2Mock.getState()).thenReturn(GHIssueState.CLOSED);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .isClosed())
                            .isTrue();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");

                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void topic_isClosed_dedicatedIssueDoesNotExist() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var searchIssuesBuilderMock = Mockito.mock(GHIssueSearchBuilder.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var clientMock = mocks.installationClient(installationRef.installationId());

                    when(clientMock.searchIssues()).thenReturn(searchIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock);
                    when(searchIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.topic(TopicRef.notification("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .isClosed())
                            .isFalse();
                })
                .then().github(mocks -> {
                    verify(searchIssuesBuilderMock).q("repo:" + repoRef.repositoryName());
                    verify(searchIssuesBuilderMock).q("is:issue");
                    verify(searchIssuesBuilderMock).q("author:" + installationRef.appLogin());
                    verify(searchIssuesBuilderMock).q("assignee:yrodiere");

                    verifyNoMoreInteractions(searchIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

};