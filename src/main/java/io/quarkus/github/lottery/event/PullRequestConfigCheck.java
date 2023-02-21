package io.quarkus.github.lottery.event;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

import jakarta.inject.Inject;

import io.quarkus.github.lottery.util.Streams;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCheckRunBuilder;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.event.CheckRun;
import io.quarkiverse.githubapp.event.CheckSuite;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.github.lottery.config.LotteryConfig;

public class PullRequestConfigCheck {

    private static final String CONFIG_FILE_ABSOLUTE_PATH = ".github/" + LotteryConfig.FILE_NAME;

    @Inject
    GitHubConfigFileProvider configFileProvider;

    void pullRequestChanged(
            @PullRequest.Opened @PullRequest.Reopened @PullRequest.Synchronize GHEventPayload.PullRequest payload)
            throws IOException {
        checkLotteryConfig(payload.getRepository(), payload.getPullRequest());
    }

    void checkRunRequested(@CheckRun.Rerequested GHEventPayload.CheckRun payload) throws IOException {
        for (GHPullRequest pullRequest : payload.getCheckRun().getPullRequests()) {
            checkLotteryConfig(payload.getRepository(), pullRequest);
        }
    }

    void checkSuiteRequested(@CheckSuite.Requested @CheckSuite.Rerequested GHEventPayload.CheckSuite payload)
            throws IOException {
        for (GHPullRequest pullRequest : payload.getCheckSuite().getPullRequests()) {
            checkLotteryConfig(payload.getRepository(), pullRequest);
        }
    }

    // GitHub sometimes mentions pull requests in the payload that are definitely not related to the changes,
    // such as very old pull requests on the branch that just got updated,
    // or pull requests on different repositories.
    // We have to ignore those, otherwise we'll end up creating checks on old pull requests.
    private boolean shouldCheck(GHRepository repository, GHPullRequest pullRequest) {
        return !GHIssueState.CLOSED.equals(pullRequest.getState())
                && repository.getId() == pullRequest.getBase().getRepository().getId();
    }

    private void checkLotteryConfig(GHRepository repository, GHPullRequest pullRequest) throws IOException {
        if (shouldCheck(repository, pullRequest) && Streams.toStream(pullRequest.listFiles())
                .noneMatch(f -> f.getFilename().equals(CONFIG_FILE_ABSOLUTE_PATH))) {
            // Config did not change
            return;
        }
        GHCheckRun checkRun = repository.createCheckRun("Quarkus GitHub Lottery Config", pullRequest.getHead().getSha())
                .withStartedAt(Date.from(Instant.now()))
                .withStatus(GHCheckRun.Status.IN_PROGRESS)
                .create();
        try {
            configFileProvider.fetchConfigFile(repository, pullRequest.getHead().getSha(),
                    LotteryConfig.FILE_NAME, ConfigFile.Source.CURRENT_REPOSITORY, LotteryConfig.class);
            repository.updateCheckRun(checkRun.getId())
                    .withCompletedAt(Date.from(Instant.now()))
                    .withStatus(GHCheckRun.Status.COMPLETED)
                    .withConclusion(GHCheckRun.Conclusion.SUCCESS)
                    .add(new GHCheckRunBuilder.Output(CONFIG_FILE_ABSOLUTE_PATH + " passed syntax checks", ""))
                    .create();
        } catch (Exception e) {
            repository.updateCheckRun(checkRun.getId())
                    .withCompletedAt(Date.from(Instant.now()))
                    .withStatus(GHCheckRun.Status.COMPLETED)
                    .withConclusion(GHCheckRun.Conclusion.FAILURE)
                    .add(new GHCheckRunBuilder.Output(CONFIG_FILE_ABSOLUTE_PATH + " failed syntax checks",
                            "```\n" + ExceptionUtils.getStackTrace(e) + "\n```"))
                    .create();
        }
    }

}
