package io.quarkus.github.lottery.github;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
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

    public GitHubRepositoryRef ref() {
        return ref;
    }

    private GitHub client() {
        return clientProvider.getInstallationClient(ref.installationId());
    }

    private DynamicGraphQLClient graphQLClient() {
        return clientProvider.getInstallationGraphQLClient(ref.installationId());
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
                .list());
    }

    public void commentOnDedicatedNotificationIssue(String username, String topic, String markdownBody) throws IOException {
        GHRepository repo = client().getRepository(ref.repositoryName());
        var existingIssue = getDedicatedNotificationIssue(repo, username, topic);
        GHIssue issue;
        if (existingIssue.isPresent()) {
            issue = existingIssue.get();
            if (GHIssueState.CLOSED.equals(issue.getState())) {
                issue.reopen();
            }
            try {
                getLastNotificationComment(issue).ifPresent(this::minimizeOutdatedComment);
            } catch (Exception e) {
                Log.errorf(e, "Failed to minimize last notification for issue %s#%s", ref.repositoryName(), issue.getNumber());
            }
        } else {
            issue = createDedicatedNotificationIssue(repo, username, topic);
        }

        issue.comment(markdownBody);
    }

    private Optional<GHIssue> getDedicatedNotificationIssue(GHRepository repo, String username, String topic) {
        for (var issue : repo.queryIssues().assignee(username).list()) {
            if (issue.getTitle().equals(topic)) {
                return Optional.of(issue);
            }
        }
        return Optional.empty();
    }

    private GHIssue createDedicatedNotificationIssue(GHRepository repo, String username, String topic) throws IOException {
        return repo.createIssue(topic)
                .assignee(username)
                .body("This issue is dedicated to " + topic + ".")
                .create();
    }

    private Optional<GHIssueComment> getLastNotificationComment(GHIssue issue) throws IOException {
        long selfId = client().getMyself().getId();
        // TODO ideally we'd use "since" to ignore older comments (e.g. 1+ year old) that are unlikely to be relevant
        //  (see 'since' in https://docs.github.com/en/rest/issues/comments#list-issue-comments)
        //  but that's not supported yet in the library we're using...
        GHIssueComment lastNotificationComment = null;
        for (GHIssueComment comment : issue.listComments()) {
            if (selfId == comment.getUser().getId()) {
                lastNotificationComment = comment;
            }
        }
        return Optional.ofNullable(lastNotificationComment);
    }

    private void minimizeOutdatedComment(GHIssueComment comment) {
        try (var graphQLClient = graphQLClient()) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("subjectId", comment.getNodeId());
            graphQLClient.executeSync("""
                    mutation MinimizeOutdatedContent($subjectId: ID!) {
                      minimizeComment(input: {
                        subjectId: $subjectId,
                        classifier: OUTDATED}) {
                          minimizedComment {
                            isMinimized
                          }
                        }
                    }
                    """, variables);
        } catch (Exception e) {
            throw new RuntimeException("Could not minimize comment " + comment.getNodeId(), e);
        }
    }

    private Iterator<Issue> toIterator(PagedIterable<GHIssue> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false)
                .map(ghIssue -> new Issue(ghIssue.getId(), ghIssue.getTitle(), ghIssue.getHtmlUrl()))
                .iterator();
    }
}
