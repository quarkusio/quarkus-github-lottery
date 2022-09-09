package io.quarkus.github.lottery.github;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.github.lottery.config.LotteryConfig;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

/**
 * An installation of the application on a GitHub repository.
 */
public class Installation {

    private final GitHubClientProvider clientProvider;
    private final InstallationRef ref;

    public Installation(GitHubClientProvider clientProvider, InstallationRef ref) {
        this.clientProvider = clientProvider;
        this.ref = ref;
    }

    private GitHub client() {
        return clientProvider.getInstallationClient(ref.installationId());
    }

    public LotteryConfig fetchLotteryConfig() throws IOException {
        // TODO retrieve a YAML file from the GitHub repo and deserialize instead
        return new LotteryConfig(null, List.of());
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
