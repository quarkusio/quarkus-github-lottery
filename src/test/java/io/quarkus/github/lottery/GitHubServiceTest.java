package io.quarkus.github.lottery;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueEvent;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForLottery;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForLotteryFilteredOutByRepository;
import static io.quarkus.github.lottery.util.MockHelper.mockIssueForNotification;
import static io.quarkus.github.lottery.util.MockHelper.mockLabel;
import static io.quarkus.github.lottery.util.MockHelper.mockPagedIterable;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.IssueActionSide;
import io.quarkus.test.junit.QuarkusMock;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueBuilder;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHIssueEvent;
import org.kohsuke.github.GHIssueQueryBuilder;
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
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
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
                                        label: "triage/needs-triage"
                                        delay: PT0S
                                        timeout: P3D
                                      maintenance:
                                        reproducer:
                                          label: "needs-reproducer"
                                          needed:
                                            delay: P21D
                                            timeout: P3D
                                          provided:
                                            delay: P7D
                                            timeout: P3D
                                        stale:
                                          delay: P60D
                                          timeout: P14D
                                    participants:
                                      - username: "yrodiere"
                                        triage:
                                          days: ["MONDAY", "TUESDAY", "FRIDAY"]
                                          maxIssues: 3
                                        maintenance:
                                          labels: ["area/hibernate-orm", "area/hibernate-search"]
                                          days: ["MONDAY"]
                                          reproducer:
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
                                          reproducer:
                                            needed:
                                              maxIssues: 1
                                            provided:
                                              maxIssues: 1
                                          stale:
                                            maxIssues: 5
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
                                                    new LotteryConfig.Buckets.Maintenance.Reproducer(
                                                            "needs-reproducer",
                                                            new LotteryConfig.Buckets.Maintenance.Reproducer.Needed(
                                                                    Duration.ofDays(21), Duration.ofDays(3)),
                                                            new LotteryConfig.Buckets.Maintenance.Reproducer.Provided(
                                                                    Duration.ofDays(7), Duration.ofDays(3))),
                                                    new LotteryConfig.Buckets.Maintenance.Stale(
                                                            Duration.ofDays(60), Duration.ofDays(14)))),
                                    List.of(
                                            new LotteryConfig.Participant("yrodiere",
                                                    Optional.empty(),
                                                    Optional.of(new LotteryConfig.Participant.Triage(
                                                            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY),
                                                            new LotteryConfig.Participant.Participation(3))),
                                                    Optional.of(new LotteryConfig.Participant.Maintenance(
                                                            List.of("area/hibernate-orm", "area/hibernate-search"),
                                                            Set.of(DayOfWeek.MONDAY),
                                                            new LotteryConfig.Participant.Maintenance.Reproducer(
                                                                    new LotteryConfig.Participant.Participation(4),
                                                                    new LotteryConfig.Participant.Participation(2)),
                                                            new LotteryConfig.Participant.Participation(5)))),
                                            new LotteryConfig.Participant("gsmet",
                                                    Optional.of(ZoneId.of("Europe/Paris")),
                                                    Optional.of(new LotteryConfig.Participant.Triage(
                                                            Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                                                            new LotteryConfig.Participant.Participation(10))),
                                                    Optional.empty()),
                                            new LotteryConfig.Participant("jsmith",
                                                    Optional.empty(),
                                                    Optional.empty(),
                                                    Optional.of(new LotteryConfig.Participant.Maintenance(
                                                            List.of("area/someobscurelibrary"),
                                                            Set.of(DayOfWeek.MONDAY),
                                                            new LotteryConfig.Participant.Maintenance.Reproducer(
                                                                    new LotteryConfig.Participant.Participation(1),
                                                                    new LotteryConfig.Participant.Participation(1)),
                                                            new LotteryConfig.Participant.Participation(5)))))));
                })
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesWithLabelLastUpdatedBefore() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        Date beforeCutoff = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date afterCutoff = Date.from(cutoff.plus(1, ChronoUnit.HOURS));

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));
        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1, beforeCutoff);
                    var issue2Mock = mockIssueForLottery(mocks, 3, beforeCutoff);
                    var issue3Mock = mockIssueForLottery(mocks, 2, beforeCutoff);
                    var issue4Mock = mockIssueForLottery(mocks, 4, beforeCutoff);
                    var issue5Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 5, afterCutoff);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock, issue4Mock, issue5Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesWithLabelLastUpdatedBefore("triage/needs-triage", cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 3, 2, 4));
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
    void issuesLastActedOnByAndLastUpdatedBefore_team() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        Date beforeCutoff = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date afterCutoff = Date.from(cutoff.plus(1, ChronoUnit.HOURS));
        Date issue1ActionLabelEvent = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date issue2ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
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
        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForLottery(mocks, 1, beforeCutoff);
                    var issue2Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 2, beforeCutoff);
                    var issue3Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 3, beforeCutoff);
                    var issue4Mock = mockIssueForLottery(mocks, 4, beforeCutoff);
                    var issue5Mock = mockIssueForLottery(mocks, 5, beforeCutoff);
                    var issue6Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 6, afterCutoff);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock,
                            issue4Mock, issue5Mock, issue6Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var adminUser = mocks.ghObject(GHUser.class, 1L);
                    when(repositoryMock.getPermission(adminUser)).thenReturn(GHPermissionType.ADMIN);
                    var writeUser = mocks.ghObject(GHUser.class, 2L);
                    when(repositoryMock.getPermission(writeUser)).thenReturn(GHPermissionType.WRITE);
                    var readUser = mocks.ghObject(GHUser.class, 3L);
                    when(repositoryMock.getPermission(readUser)).thenReturn(GHPermissionType.READ);
                    var noneUser = mocks.ghObject(GHUser.class, 4L);
                    when(repositoryMock.getPermission(noneUser)).thenReturn(GHPermissionType.NONE);

                    var needsReproducerLabelMock = mockLabel("needs-reproducer");
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
                    var issue1Comment1Mock = mocks.issueComment(101);
                    var issue1Comment2Mock = mocks.issueComment(102);
                    when(issue1Comment2Mock.getUser()).thenReturn(adminUser);
                    var issue1CommentsMocks = mockPagedIterable(issue1Comment1Mock, issue1Comment2Mock);
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
                    var issue2Comment1Mock = mocks.issueComment(201);
                    var issue2Comment2Mock = mocks.issueComment(202);
                    when(issue2Comment2Mock.getUser()).thenReturn(readUser);
                    var issue2CommentsMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock);
                    when(issue2Mock.queryComments()).thenReturn(issue2QueryCommentsBuilderMock);
                    when(issue2QueryCommentsBuilderMock.list()).thenReturn(issue2CommentsMocks);

                    PagedSearchIterable<GHIssueEvent> issue3EventsMocks = mockPagedIterable();
                    when(issue3Mock.listEvents()).thenReturn(issue3EventsMocks);
                    var issue3Comment1Mock = mocks.issueComment(301);
                    var issue3Comment2Mock = mocks.issueComment(302);
                    when(issue3Comment2Mock.getUser()).thenReturn(noneUser);
                    var issue3CommentsMocks = mockPagedIterable(issue3Comment1Mock, issue3Comment2Mock);
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
                    var issue5Comment1Mock = mocks.issueComment(501);
                    var issue5Comment2Mock = mocks.issueComment(502);
                    when(issue5Comment2Mock.getUser()).thenReturn(writeUser);
                    var issue5CommentsMocks = mockPagedIterable(issue5Comment1Mock, issue5Comment2Mock);
                    when(issue5Mock.queryComments()).thenReturn(issue5QueryCommentsBuilderMock);
                    when(issue5QueryCommentsBuilderMock.list()).thenReturn(issue5CommentsMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                            "area/hibernate-search", IssueActionSide.TEAM, cutoff))
                            .containsExactlyElementsOf(stubIssueList(1, 4, 5));
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).state(GHIssueState.OPEN);
                    verify(queryIssuesBuilderMock).sort(GHIssueQueryBuilder.Sort.UPDATED);
                    verify(queryIssuesBuilderMock).direction(GHDirection.DESC);
                    verify(queryIssuesBuilderMock).label("needs-reproducer");
                    verify(queryIssuesBuilderMock).label("area/hibernate-search");

                    verify(issue1QueryCommentsBuilderMock).since(issue1ActionLabelEvent);
                    verify(issue2QueryCommentsBuilderMock).since(issue2ActionLabelEvent);

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void issuesLastActedOnByAndLastUpdatedBefore_outsider() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        Date beforeCutoff = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date afterCutoff = Date.from(cutoff.plus(1, ChronoUnit.HOURS));
        Date issue1ActionLabelEvent = Date.from(cutoff.minus(1, ChronoUnit.DAYS));
        Date issue2ActionLabelEvent = Date.from(cutoff.minus(2, ChronoUnit.DAYS));

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
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
        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 1, beforeCutoff);
                    var issue2Mock = mockIssueForLottery(mocks, 2, beforeCutoff);
                    var issue3Mock = mockIssueForLottery(mocks, 3, beforeCutoff);
                    var issue4Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 4, beforeCutoff);
                    var issue5Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 5, beforeCutoff);
                    var issue6Mock = mockIssueForLotteryFilteredOutByRepository(mocks, 6, afterCutoff);
                    var issuesMocks = mockPagedIterable(issue1Mock, issue2Mock, issue3Mock,
                            issue4Mock, issue5Mock, issue6Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);

                    var adminUser = mocks.ghObject(GHUser.class, 1L);
                    when(repositoryMock.getPermission(adminUser)).thenReturn(GHPermissionType.ADMIN);
                    var writeUser = mocks.ghObject(GHUser.class, 2L);
                    when(repositoryMock.getPermission(writeUser)).thenReturn(GHPermissionType.WRITE);
                    var readUser = mocks.ghObject(GHUser.class, 3L);
                    when(repositoryMock.getPermission(readUser)).thenReturn(GHPermissionType.READ);
                    var noneUser = mocks.ghObject(GHUser.class, 4L);
                    when(repositoryMock.getPermission(noneUser)).thenReturn(GHPermissionType.NONE);

                    var needsReproducerLabelMock = mockLabel("needs-reproducer");
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
                    var issue1Comment1Mock = mocks.issueComment(101);
                    var issue1Comment2Mock = mocks.issueComment(102);
                    when(issue1Comment2Mock.getUser()).thenReturn(adminUser);
                    var issue1CommentsMocks = mockPagedIterable(issue1Comment1Mock, issue1Comment2Mock);
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
                    var issue2Comment1Mock = mocks.issueComment(201);
                    var issue2Comment2Mock = mocks.issueComment(202);
                    when(issue2Comment2Mock.getUser()).thenReturn(readUser);
                    var issue2CommentsMocks = mockPagedIterable(issue2Comment1Mock, issue2Comment2Mock);
                    when(issue2Mock.queryComments()).thenReturn(issue2QueryCommentsBuilderMock);
                    when(issue2QueryCommentsBuilderMock.list()).thenReturn(issue2CommentsMocks);

                    PagedSearchIterable<GHIssueEvent> issue3EventsMocks = mockPagedIterable();
                    when(issue3Mock.listEvents()).thenReturn(issue3EventsMocks);
                    var issue3Comment1Mock = mocks.issueComment(301);
                    var issue3Comment2Mock = mocks.issueComment(302);
                    when(issue3Comment2Mock.getUser()).thenReturn(noneUser);
                    var issue3CommentsMocks = mockPagedIterable(issue3Comment1Mock, issue3Comment2Mock);
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
                    var issue5Comment1Mock = mocks.issueComment(501);
                    var issue5Comment2Mock = mocks.issueComment(502);
                    when(issue5Comment2Mock.getUser()).thenReturn(writeUser);
                    var issue5CommentsMocks = mockPagedIterable(issue5Comment1Mock, issue5Comment2Mock);
                    when(issue5Mock.queryComments()).thenReturn(issue5QueryCommentsBuilderMock);
                    when(issue5QueryCommentsBuilderMock.list()).thenReturn(issue5CommentsMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.issuesLastActedOnByAndLastUpdatedBefore("needs-reproducer",
                            "area/hibernate-search", IssueActionSide.OUTSIDER, cutoff))
                            .containsExactlyElementsOf(stubIssueList(2, 3));
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).state(GHIssueState.OPEN);
                    verify(queryIssuesBuilderMock).sort(GHIssueQueryBuilder.Sort.UPDATED);
                    verify(queryIssuesBuilderMock).direction(GHDirection.DESC);
                    verify(queryIssuesBuilderMock).label("needs-reproducer");
                    verify(queryIssuesBuilderMock).label("area/hibernate-search");

                    verify(issue1QueryCommentsBuilderMock).since(issue1ActionLabelEvent);
                    verify(issue2QueryCommentsBuilderMock).since(issue2ActionLabelEvent);

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

    @Test
    void hasClosedDedicatedIssue_dedicatedIssueExists_open() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
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
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.hasClosedDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .isFalse();
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("yrodiere");

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void hasClosedDedicatedIssue_dedicatedIssueExists_closed() throws Exception {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");

        Instant now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        var clockMock = Clock.fixed(now, ZoneOffset.UTC);
        QuarkusMock.installMockForType(clockMock, Clock.class);

        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
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
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.hasClosedDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .isTrue();
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("yrodiere");

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void hasClosedDedicatedIssue_dedicatedIssueDoesNotExist() throws IOException {
        var repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-lottery-reports");
        var queryIssuesBuilderMock = Mockito.mock(GHIssueQueryBuilder.ForRepository.class,
                withSettings().defaultAnswer(Answers.RETURNS_SELF));

        given()
                .github(mocks -> {
                    var repositoryMock = mocks.repository(repoRef.repositoryName());

                    when(repositoryMock.queryIssues()).thenReturn(queryIssuesBuilderMock);
                    var issue1Mock = mockIssueForNotification(mocks, 1, "An unrelated issue");
                    var issuesMocks = mockPagedIterable(issue1Mock);
                    when(queryIssuesBuilderMock.list()).thenReturn(issuesMocks);
                })
                .when(() -> {
                    var repo = gitHubService.repository(repoRef);

                    assertThat(repo.hasClosedDedicatedIssue("yrodiere", "yrodiere's report for quarkusio/quarkus"))
                            .isFalse();
                })
                .then().github(mocks -> {
                    verify(queryIssuesBuilderMock).creator(installationRef.appLogin());
                    verify(queryIssuesBuilderMock).assignee("yrodiere");

                    verifyNoMoreInteractions(queryIssuesBuilderMock);
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

};