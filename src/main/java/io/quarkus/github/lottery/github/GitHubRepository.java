package io.quarkus.github.lottery.github;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.github.lottery.config.LotteryConfig;

/**
 * A GitHub repository as viewed from a GitHub App installation.
 */
public class GitHubRepository {

    private final GitHubClientProvider clientProvider;
    private final GitHubConfigFileProvider configFileProvider;
    private final GitHubRepositoryRef ref;

    public GitHubRepository(GitHubClientProvider clientProvider, GitHubConfigFileProvider configFileProvider,
            GitHubRepositoryRef ref) {
        this.clientProvider = clientProvider;
        this.configFileProvider = configFileProvider;
        this.ref = ref;
    }

    private GitHub client() {
        return clientProvider.getInstallationClient(ref.installationId());
    }

    public Optional<LotteryConfig> fetchLotteryConfig() throws IOException {
        GHRepository repo = client().getRepository(ref.repositoryName());
        return configFileProvider.fetchConfigFile(repo, LotteryConfig.FILE_NAME, ConfigFile.Source.DEFAULT,
                LotteryConfig.class);
    }

    public Iterator<Issue> issuesWithLabel(String label) throws IOException {
        GHRepository repo = client().getRepository(ref.repositoryName());
        return toIterator(repo.queryIssues().label(label)
                .state(GHIssueState.OPEN)
                .sort(GHIssueQueryBuilder.Sort.UPDATED)
                .direction(GHDirection.DESC)
                .pageSize(20)
                .list());
    }

    private Iterator<Issue> toIterator(PagedIterable<GHIssue> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false)
                .map(ghIssue -> new Issue(ghIssue.getId(), ghIssue.getTitle(), ghIssue.getHtmlUrl()))
                .iterator();
    }
}
