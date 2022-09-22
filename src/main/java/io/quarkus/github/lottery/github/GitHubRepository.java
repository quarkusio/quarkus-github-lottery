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

    public String selfLogin() {
        return ref.installationRef().appName() + "[bot]";
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

    public void commentOnDedicatedIssue(String username, String topic, String markdownBody) throws IOException {
        var existingIssue = getDedicatedIssue(username, topic);
        GHIssue issue;
        if (existingIssue.isPresent()) {
            issue = existingIssue.get();
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
            issue = createDedicatedIssue(username, topic);
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
            if (issue.getTitle().equals(topic)) {
                return Optional.of(issue);
            }
        }
        return Optional.empty();
    }

    private GHIssue createDedicatedIssue(String username, String topic) throws IOException {
        return repository().createIssue(topic)
                .assignee(username)
                .body("This issue is dedicated to " + topic + ".")
                .create();
    }

    private Stream<GHIssueComment> getAppCommentsSince(GHIssue issue, Instant since) {
        String selfLogin = selfLogin();
        return toStream(issue.queryComments().since(Date.from(since)).list())
                .filter(uncheckedIO((GHIssueComment comment) -> selfLogin.equals(comment.getUser().getLogin()))::apply);
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
