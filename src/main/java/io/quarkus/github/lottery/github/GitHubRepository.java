package io.quarkus.github.lottery.github;

import static io.quarkus.github.lottery.util.Streams.toStream;
import static io.quarkus.github.lottery.util.UncheckedIOFunction.uncheckedIO;

import java.io.IOException;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.github.lottery.util.Streams;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHIssueEvent;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

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
    private final MessageFormatter messageFormatter;
    private final GitHubRepositoryRef ref;

    private GitHub client;
    private GHRepository repository;
    private DynamicGraphQLClient graphQLClient;

    public GitHubRepository(Clock clock, GitHubClientProvider clientProvider, GitHubConfigFileProvider configFileProvider,
            MessageFormatter messageFormatter, GitHubRepositoryRef ref) {
        this.clock = clock;
        this.clientProvider = clientProvider;
        this.configFileProvider = configFileProvider;
        this.messageFormatter = messageFormatter;
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

    /**
     * Lists issues that were last updated before the given instant.
     *
     * @param updatedBefore An instant; all returned issues must have been last updated before that instant.
     * @return A lazily populated stream of matching issues.
     * @throws IOException In case of I/O failure.
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesLastUpdatedBefore(Instant updatedBefore) throws IOException {
        return toStream(repository().queryIssues()
                .state(GHIssueState.OPEN)
                .sort(GHIssueQueryBuilder.Sort.UPDATED)
                .direction(GHDirection.DESC)
                .list())
                .filter(notPullRequest())
                .filter(updatedBefore(updatedBefore))
                .map(toIssueRecord());
    }

    /**
     * Lists issues with the given label that were last updated before the given instant.
     *
     * @param label A GitHub label; if non-null, all returned issues must have been assigned that label.
     * @param updatedBefore An instant; all returned issues must have been last updated before that instant.
     * @return A lazily populated stream of matching issues.
     * @throws IOException In case of I/O failure.
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesWithLabelLastUpdatedBefore(String label, Instant updatedBefore) throws IOException {
        return toStream(repository().queryIssues().label(label)
                .state(GHIssueState.OPEN)
                .sort(GHIssueQueryBuilder.Sort.UPDATED)
                .direction(GHDirection.DESC)
                .list())
                .filter(notPullRequest())
                .filter(updatedBefore(updatedBefore))
                .map(toIssueRecord());
    }

    /**
     * Lists issues with the given labels that were last acted on (label applied or comment)
     * by the given "side" (team or outsider) and were last updated before the given instant.
     *
     * @param initialActionLabels A set of GitHub labels; all returned issues must have been assigned one of these labels.
     *        The last time this label was assigned is considered the first "action" on an issue.
     * @param filterLabel A secondary GitHub label; all returned issues must have been assigned that label.
     *        This label is not relevant to determining the last action.
     * @param updatedBefore An instant; all returned issues must have been last updated before that instant.
     * @return A lazily populated stream of matching issues.
     * @throws IOException In case of I/O failure.
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesLastActedOnByAndLastUpdatedBefore(Set<String> initialActionLabels, String filterLabel,
            IssueActionSide lastActionSide, Instant updatedBefore) throws IOException {
        var theRepository = repository();
        var streams = initialActionLabels.stream()
                .map(initialActionLabel -> toStream(theRepository.queryIssues()
                        .label(initialActionLabel)
                        .label(filterLabel)
                        .state(GHIssueState.OPEN)
                        .sort(GHIssueQueryBuilder.Sort.UPDATED)
                        .direction(GHDirection.DESC)
                        .list())
                        .filter(notPullRequest())
                        .filter(updatedBefore(updatedBefore))
                        .filter(uncheckedIO((GHIssue ghIssue) -> lastActionSide
                                .equals(lastActionSide(ghIssue, initialActionLabels)))::apply)
                        .map(toIssueRecord()))
                .toList();
        return Streams.interleave(streams);
    }

    private Predicate<GHIssue> updatedBefore(Instant updatedBefore) {
        return uncheckedIO((GHIssue ghIssue) -> ghIssue.getUpdatedAt().toInstant().isBefore(updatedBefore))::apply;
    }

    private Predicate<GHIssue> notPullRequest() {
        return (GHIssue ghIssue) -> !ghIssue.isPullRequest();
    }

    private IssueActionSide lastActionSide(GHIssue ghIssue, Set<String> initialActionLabels) throws IOException {
        // Optimization: don't even fetch older comments as they wouldn't affect the result
        // (we're looking for the *last* action).
        Instant lastEventActionSideInstant = null;
        for (GHIssueEvent event : ghIssue.listEvents()) {
            if (io.quarkiverse.githubapp.event.Issue.Labeled.NAME.equals(event.getEvent())
                    && initialActionLabels.contains(event.getLabel().getName())) {
                lastEventActionSideInstant = event.getCreatedAt().toInstant();
            }
        }
        GHIssueCommentQueryBuilder queryCommentsBuilder = ghIssue.queryComments();
        if (lastEventActionSideInstant != null) {
            queryCommentsBuilder.since(Date.from(lastEventActionSideInstant));
        }

        Optional<GHIssueComment> lastComment = toStream(queryCommentsBuilder.list()).reduce(last());
        if (lastComment.isEmpty()) {
            // No action since the label was assigned.
            return IssueActionSide.TEAM;
        }
        return switch (repository().getPermission(lastComment.get().getUser())) {
            case ADMIN, WRITE -> IssueActionSide.TEAM;
            case READ, UNKNOWN, NONE -> IssueActionSide.OUTSIDER;
        };
    }

    private Function<GHIssue, Issue> toIssueRecord() {
        return ghIssue -> new Issue(ghIssue.getNumber(), ghIssue.getTitle(), ghIssue.getHtmlUrl());
    }

    /**
     * Checks whether an issue identified by its assignee and topic (title prefix) exists, but has been closed.
     *
     * @param assignee The GitHub username of issue assignee.
     * @param topic The issue's topic, a string that should be prefixed to the issue title.
     *
     * @throws IOException If a GitHub API call fails.
     * @throws java.io.UncheckedIOException If a GitHub API call fails.
     * @see #commentOnDedicatedIssue(String, String, String, String)
     */
    public boolean hasClosedDedicatedIssue(String assignee, String topic)
            throws IOException {
        var existingIssue = getDedicatedIssue(assignee, topic);
        return existingIssue.isPresent() && GHIssueState.CLOSED.equals(existingIssue.get().getState());
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
                        .reduce(last());
                lastRecentComment.ifPresent(this::minimizeOutdatedComment);
            } catch (Exception e) {
                Log.errorf(e, "Failed to minimize last notification for issue %s#%s", ref.repositoryName(), issue.getNumber());
            }

            // Update the issue description with the content of the latest comment,
            // for convenience.
            // This must be done before the comment, so that notifications triggered by the comment are only sent
            // when the issue is fully updated.
            issue.setBody(messageFormatter.formatDedicatedIssueBodyMarkdown(topic, markdownBody));
        } else {
            issue = createDedicatedIssue(assignee, targetTitle, topic, markdownBody);
        }

        issue.comment(markdownBody);
    }

    public Stream<String> extractCommentsFromDedicatedIssue(String assignee, String topic, Instant since)
            throws IOException {
        return getDedicatedIssue(assignee, topic)
                .map(uncheckedIO(issue -> getAppCommentsSince(issue, since)))
                .orElse(Stream.of())
                .map(GHIssueComment::getBody);
    }

    private Optional<GHIssue> getDedicatedIssue(String assignee, String topic) throws IOException {
        var builder = repository().queryIssues().creator(appLogin());
        if (assignee != null) {
            builder.assignee(assignee);
        }
        builder.state(GHIssueState.ALL);
        for (var issue : builder.list()) {
            if (issue.getTitle().startsWith(topic)) {
                return Optional.of(issue);
            }
        }
        return Optional.empty();
    }

    private GHIssue createDedicatedIssue(String username, String title, String topic, String lastCommentMarkdownBody)
            throws IOException {
        return repository().createIssue(title)
                .assignee(username)
                .body(messageFormatter.formatDedicatedIssueBodyMarkdown(topic, lastCommentMarkdownBody))
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

    private <T> BinaryOperator<T> last() {
        return (first, second) -> second;
    }
}
