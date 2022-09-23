package io.quarkus.github.lottery.github;

import static io.quarkus.github.lottery.util.UncheckedIOFunction.uncheckedIO;

import java.io.IOException;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * A GitHub repository as viewed from a GitHub App installation.
 */
public class GitHubRepository implements AutoCloseable {

    private final Clock clock;
    private final GitHubClientProvider clientProvider;
    private final GitHubConfigFileProvider configFileProvider;
    private final GitHubRepositoryRef ref;

    private GitHub client;
    private GHRepository repository;
    private DynamicGraphQLClient graphQLClient;

    public GitHubRepository(Clock clock, GitHubClientProvider clientProvider, GitHubConfigFileProvider configFileProvider,
            GitHubRepositoryRef ref) {
        this.clock = clock;
        this.clientProvider = clientProvider;
        this.configFileProvider = configFileProvider;
        this.ref = ref;
    }

    @Override
    public void close() {
        if (graphQLClient != null) {
            try {
                graphQLClient.close();
            } catch (Exception e) {
                Log.errorf(e, "Could not close GraphQL client");
            }
        }
    }

    public GitHubRepositoryRef ref() {
        return ref;
    }

    public String appLogin() {
        return ref.installationRef().appLogin();
    }

    private GitHub client() {
        if (client == null) {
            client = clientProvider.getInstallationClient(ref.installationRef().installationId());
        }
        return client;
    }

    private GHRepository repository() throws IOException {
        if (repository == null) {
            repository = client().getRepository(ref.repositoryName());
        }
        return repository;
    }

    private DynamicGraphQLClient graphQLClient() {
        if (graphQLClient == null) {
            graphQLClient = clientProvider.getInstallationGraphQLClient(ref.installationRef().installationId());
        }
        return graphQLClient;
    }

    public Optional<LotteryConfig> fetchLotteryConfig() throws IOException {
        return configFileProvider.fetchConfigFile(repository(), LotteryConfig.FILE_NAME, ConfigFile.Source.DEFAULT,
                LotteryConfig.class);
    }

    public Stream<Issue> issuesWithLabel(String label) throws IOException {
        return toStream(repository().queryIssues().label(label)
                .state(GHIssueState.OPEN)
                .sort(GHIssueQueryBuilder.Sort.UPDATED)
                .direction(GHDirection.DESC)
                .list())
                .map(ghIssue -> new Issue(ghIssue.getNumber(), ghIssue.getTitle(), ghIssue.getHtmlUrl()));
    }

    /**
     * Adds a comment to an issue identified by its assignee and topic (title prefix).
     *
     * @param assignee The GitHub username of issue assignee.
     *        Two calls with the same username + topic will result in a comment on the same issue.
     * @param topic The issue's topic, a string that will be prefixed to the issue title.
     *        Two calls with the same username + topic will result in a comment on the same issue.
     * @param topicSuffix A string that should be appended to the topic in the issue title.
     *        Each time that suffix changes for a new comment,
     *        the issue title will be updated,
     *        and so will the subject of any email notification sent as a result of that comment.
     *        In conversation-based email clients such as GMail,
     *        this will result in the comment appearing in a new conversation,
     *        which can be useful to avoid huge conversations
     * @param markdownBody The body of the comment to add.
     *
     * @throws IOException If a GitHub API call fails.
     * @throws java.io.UncheckedIOException If a GitHub API call fails.
     */
    public void commentOnDedicatedIssue(String assignee, String topic, String topicSuffix, String markdownBody)
            throws IOException {
        String targetTitle = topic + topicSuffix;
        var existingIssue = getDedicatedIssue(assignee, topic);
        GHIssue issue;
        if (existingIssue.isPresent()) {
            issue = existingIssue.get();
            if (!issue.getTitle().equals(targetTitle)) {
                issue.setTitle(targetTitle);
            }
            if (GHIssueState.CLOSED.equals(issue.getState())) {
                issue.reopen();
            }
            try {
                // We try to minimize the last comment on a best-effort basis,
                // taking into account only recent comments,
                // to avoid performance hogs on issues with many comments.
                // (There's no way to retrieve comments of an issue in anti-chronological order...)
                Optional<GHIssueComment> lastRecentComment = getAppCommentsSince(issue,
                        clock.instant().minus(21, ChronoUnit.DAYS))
                        .reduce((first, second) -> second);
                lastRecentComment.ifPresent(this::minimizeOutdatedComment);
            } catch (Exception e) {
                Log.errorf(e, "Failed to minimize last notification for issue %s#%s", ref.repositoryName(), issue.getNumber());
            }
        } else {
            issue = createDedicatedIssue(assignee, targetTitle, topic);
        }

        issue.comment(markdownBody);
    }

    public Stream<String> extractCommentsFromDedicatedIssue(String login, String topic, Instant since)
            throws IOException {
        return getDedicatedIssue(login, topic)
                .map(uncheckedIO(issue -> getAppCommentsSince(issue, since)))
                .orElse(Stream.of())
                .map(GHIssueComment::getBody);
    }

    private Optional<GHIssue> getDedicatedIssue(String username, String topic) throws IOException {
        for (var issue : repository().queryIssues().assignee(username).list()) {
            if (issue.getTitle().startsWith(topic)) {
                return Optional.of(issue);
            }
        }
        return Optional.empty();
    }

    private GHIssue createDedicatedIssue(String username, String title, String topic) throws IOException {
        return repository().createIssue(title)
                .assignee(username)
                .body("This issue is dedicated to " + topic + ".")
                .create();
    }

    private Stream<GHIssueComment> getAppCommentsSince(GHIssue issue, Instant since) {
        String appLogin = appLogin();
        return toStream(issue.queryComments().since(Date.from(since)).list())
                .filter(uncheckedIO((GHIssueComment comment) -> appLogin.equals(comment.getUser().getLogin()))::apply);
    }

    private void minimizeOutdatedComment(GHIssueComment comment) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("subjectId", comment.getNodeId());
            graphQLClient().executeSync("""
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

    private <T> Stream<T> toStream(PagedIterable<T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    }
}
