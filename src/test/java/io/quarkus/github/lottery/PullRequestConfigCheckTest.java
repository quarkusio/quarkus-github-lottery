package io.quarkus.github.lottery;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.util.MockHelper;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCheckRunBuilder;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.PagedIterable;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
@ExtendWith(MockitoExtension.class)
public class PullRequestConfigCheckTest {
    private static final long PR_ID = 1095197456L;
    private static final String PR_HEAD_SHA = "8f4fdc737d6f8dcd00e0a304eab63bf9cce52b10";
    private static final String PR_REPO_NAME = "yrodiere/quarkus-github-playground";

    private final GHCheckRunBuilder checkRunCreateBuilderMock = mockCheckRunBuilder();
    private final GHCheckRunBuilder checkRunUpdateBuilderMock = mockCheckRunBuilder();

    @Test
    void pullRequestDoesNotContainFile() throws IOException {
        given()
                .github(mocks -> {
                    var prMock = mocks.pullRequest(PR_ID);
                    PagedIterable<GHPullRequestFileDetail> changedFilesMocks = MockHelper.mockPagedIterable(
                            MockHelper.mockGHPullRequestFileDetail("foo/Something.java"),
                            MockHelper.mockGHPullRequestFileDetail("something/foobar/SomethingElse.java"));
                    when(prMock.listFiles()).thenReturn(changedFilesMocks);
                })
                .when()
                .payloadFromClasspath("/pullrequest-opened-syntax-error.json")
                .event(GHEvent.PULL_REQUEST)
                .then()
                .github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void pullRequestContainsFile_syntaxOk() throws IOException {
        given()
                .github(mocks -> {
                    GHRepository repoMock = mocks.repository(PR_REPO_NAME);

                    var prMock = mocks.pullRequest(PR_ID);
                    PagedIterable<GHPullRequestFileDetail> changedFilesMocks = MockHelper.mockPagedIterable(
                            MockHelper.mockGHPullRequestFileDetail("foo/Something.java"),
                            MockHelper.mockGHPullRequestFileDetail(".github/quarkus-github-lottery.yml"),
                            MockHelper.mockGHPullRequestFileDetail("something/foobar/SomethingElse.java"));
                    when(prMock.listFiles()).thenReturn(changedFilesMocks);

                    mocks.configFile(LotteryConfig.FILE_NAME)
                            .withRef(PR_HEAD_SHA)
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
                                        feedback:
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
                                      stewardship:
                                        delay: P60D
                                        timeout: P14D
                                    participants:
                                      - username: "yrodiere"
                                        triage:
                                          days: ["MONDAY", "TUESDAY", "FRIDAY"]
                                          maxIssues: 3
                                      - username: "gsmet"
                                        timezone: "Europe/Paris"
                                        triage:
                                          days: ["MONDAY", "WEDNESDAY", "FRIDAY"]
                                          maxIssues: 10
                                    """);

                    mockCheckRun(repoMock, PR_HEAD_SHA);
                })
                .when()
                .payloadFromClasspath("/pullrequest-opened-syntax-error.json")
                .event(GHEvent.PULL_REQUEST)
                .then()
                .github(mocks -> {
                    var outputCaptor = ArgumentCaptor.forClass(GHCheckRunBuilder.Output.class);
                    verify(checkRunUpdateBuilderMock).add(outputCaptor.capture());
                    var output = outputCaptor.getValue();
                    assertThat(output)
                            .extracting("title", InstanceOfAssertFactories.STRING)
                            .contains(".github/quarkus-github-lottery.yml",
                                    "passed syntax check");
                    assertThat(output)
                            .extracting("summary", InstanceOfAssertFactories.STRING)
                            .isEqualTo("");

                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void pullRequestContainsFile_syntaxError() throws IOException {
        given()
                .github(mocks -> {
                    GHRepository repoMock = mocks.repository(PR_REPO_NAME);

                    var prMock = mocks.pullRequest(PR_ID);
                    PagedIterable<GHPullRequestFileDetail> changedFilesMocks = MockHelper.mockPagedIterable(
                            MockHelper.mockGHPullRequestFileDetail("foo/Something.java"),
                            MockHelper.mockGHPullRequestFileDetail(".github/quarkus-github-lottery.yml"),
                            MockHelper.mockGHPullRequestFileDetail("something/foobar/SomethingElse.java"));
                    when(prMock.listFiles()).thenReturn(changedFilesMocks);

                    mocks.configFile(LotteryConfig.FILE_NAME)
                            .withRef(PR_HEAD_SHA)
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
                                        feedback:
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
                                      stewardship:
                                        delay: P60D
                                        timeout: P14D
                                    participants:
                                      - username: "yrodiere"
                                        triage:
                                          days: ["MONDAY", "TUESDAY", "FRIDAY"]
                                          maxIssues: 3
                                      - username: "gsmet"
                                        timezone: "Europe/Parris"
                                        triage:
                                          days: ["MONDAY", "WEDNESDAY", "FRIDAY"]
                                          maxIssues: 10
                                    """);

                    mockCheckRun(repoMock, PR_HEAD_SHA);
                })
                .when()
                .payloadFromClasspath("/pullrequest-opened-syntax-error.json")
                .event(GHEvent.PULL_REQUEST)
                .then()
                .github(mocks -> {
                    var outputCaptor = ArgumentCaptor.forClass(GHCheckRunBuilder.Output.class);
                    verify(checkRunUpdateBuilderMock).add(outputCaptor.capture());
                    var output = outputCaptor.getValue();
                    assertThat(output)
                            .extracting("title", InstanceOfAssertFactories.STRING)
                            .contains(".github/quarkus-github-lottery.yml",
                                    "failed syntax check");
                    assertThat(output)
                            .extracting("summary", InstanceOfAssertFactories.STRING)
                            .contains(
                                    "Error deserializing config file .github/quarkus-github-lottery.yml to type io.quarkus.github.lottery.config.LotteryConfig",
                                    "Cannot deserialize value of type `java.time.ZoneId` from String \"Europe/Parris\"",
                                    "Unknown time-zone ID: Europe/Parris");

                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    private GHCheckRunBuilder mockCheckRunBuilder() {
        return mock(GHCheckRunBuilder.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
    }

    private void mockCheckRun(GHRepository repoMock, String headSHA) throws IOException {
        GHCheckRun mock = mock(GHCheckRun.class);
        mockCreateCheckRun(repoMock, "Quarkus GitHub Lottery Config", headSHA,
                checkRunCreateBuilderMock, mock, 42L);
        mockUpdateCheckRun(repoMock, 42L, checkRunUpdateBuilderMock, mock);
    }

    private void mockCreateCheckRun(GHRepository repoMock, String name, String headSHA,
            GHCheckRunBuilder checkRunBuilderMock, GHCheckRun checkRunMock, long checkRunId) throws IOException {
        when(repoMock.createCheckRun(name, headSHA))
                .thenReturn(checkRunBuilderMock);
        when(checkRunMock.getId()).thenReturn(checkRunId);
        when(checkRunBuilderMock.create()).thenReturn(checkRunMock);
    }

    private void mockUpdateCheckRun(GHRepository repoMock, long checkRunId,
            GHCheckRunBuilder checkRunBuilderMock, GHCheckRun checkRunMock) throws IOException {
        when(repoMock.updateCheckRun(checkRunId)).thenReturn(checkRunBuilderMock);
        when(checkRunBuilderMock.create()).thenReturn(checkRunMock);
    }

}
