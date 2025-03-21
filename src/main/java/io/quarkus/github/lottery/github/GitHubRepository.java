package io.quarkus.github.lottery.github;

import static io.quarkus.github.lottery.github.GitHubSearchClauses.anyLabel;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.assignee;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.author;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.commenter;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.created;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.isIssue;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.label;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.not;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.repo;
import static io.quarkus.github.lottery.github.GitHubSearchClauses.updated;
import static io.quarkus.github.lottery.util.Streams.toStream;
import static io.quarkus.github.lottery.util.UncheckedIOFunction.uncheckedIO;

import java.io.IOException;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.quarkus.github.lottery.config.DeploymentConfig;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHIssueEvent;
import org.kohsuke.github.GHIssueSearchBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.github.lottery.util.GitHubConstants;
import io.quarkus.github.lottery.util.Streams;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * A GitHub repository as viewed from a GitHub App installation.
 */
public class GitHubRepository implements AutoCloseable {

    private final DeploymentConfig deploymentConfig;
    private final Clock clock;
    private final GitHubClientProvider clientProvider;
    private final GitHubConfigFileProvider configFileProvider;
    private final MessageFormatter messageFormatter;
    private final GitHubRepositoryRef ref;
    private final LoadingCache<String, IssueActionSide> noContextIssueActionSideCache;

    private GitHub client;
    private GHRepository repository;
    private DynamicGraphQLClient graphQLClient;

    public GitHubRepository(DeploymentConfig deploymentConfig, Clock clock,
            GitHubClientProvider clientProvider, GitHubConfigFileProvider configFileProvider,
            MessageFormatter messageFormatter, GitHubRepositoryRef ref) {
        this.deploymentConfig = deploymentConfig;
        this.clock = clock;
        this.clientProvider = clientProvider;
        this.configFileProvider = configFileProvider;
        this.messageFormatter = messageFormatter;
        this.ref = ref;
        this.noContextIssueActionSideCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build(this::computeNoContextIssueActionSide);
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

    private GHIssueSearchBuilder searchIssuesOnly() {
        return client().searchIssues()
                .q(repo(ref))
                .q(isIssue());
    }

    private GHIssueSearchBuilder searchIssuesOrPullRequests() {
        return client().searchIssues()
                .q(repo(ref));
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
     * Lists issues or pull requests that were last updated before the given instant.
     *
     * @param ignoreLabels GitHub labels. Issues assigned with any of these labels are ignored (not returned).
     * @param updatedBefore An instant; all returned issues must have been last updated before that instant.
     * @return A lazily populated stream of matching issues.
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesOrPullRequestsLastUpdatedBefore(Set<String> ignoreLabels, Instant updatedBefore) {
        var builder = searchIssuesOrPullRequests()
                .isOpen()
                .q(updated(null, updatedBefore))
                .sort(GHIssueSearchBuilder.Sort.UPDATED)
                .order(GHDirection.DESC);
        if (!ignoreLabels.isEmpty()) {
            builder.q(not(anyLabel(ignoreLabels)));
        }
        return toStream(builder.list()).map(toIssueRecord());
    }

    /**
     * Lists issues or pull requests with the given label that were last updated before the given instant.
     *
     * @param label A GitHub label; if non-null, all returned issues must have been assigned that label.
     * @param ignoreLabels GitHub labels. Issues assigned with any of these labels are ignored (not returned).
     * @param updatedBefore An instant; all returned issues must have been last updated before that instant.
     * @return A lazily populated stream of matching issues.
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesOrPullRequestsWithLabelLastUpdatedBefore(String label, Set<String> ignoreLabels,
            Instant updatedBefore) {
        var builder = searchIssuesOrPullRequests()
                .isOpen()
                .q(label(label))
                .q(updated(null, updatedBefore))
                .sort(GHIssueSearchBuilder.Sort.UPDATED)
                .order(GHDirection.DESC);
        if (!ignoreLabels.isEmpty()) {
            builder.q(not(anyLabel(ignoreLabels)));
        }
        return toStream(builder.list()).map(toIssueRecord());
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
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesLastActedOnByAndLastUpdatedBefore(Set<String> initialActionLabels, String filterLabel,
            IssueActionSide lastActionSide, Instant updatedBefore) {
        return toStream(searchIssuesOnly()
                .isOpen()
                .q(anyLabel(initialActionLabels))
                .q(label(filterLabel))
                .q(updated(null, updatedBefore))
                .sort(GHIssueSearchBuilder.Sort.UPDATED)
                .order(GHDirection.DESC)
                .list())
                .filter(uncheckedIO((GHIssue ghIssue) -> lastActionSide
                        .equals(lastActionSide(ghIssue, initialActionLabels)))::apply)
                .map(toIssueRecord());
    }

    /**
     * Lists issues or pull requests with the given labels that were never acted on (commented)
     * by the team and were last updated before the given instant.
     *
     * @param filterLabel A secondary GitHub label; all returned issues must have been assigned that label.
     *        This label is not relevant to determining the last action.
     * @param ignoreLabels GitHub labels. Issues assigned with any of these labels are ignored (not returned).
     * @param ignoreUsers GitHub usernames. Issues are ignored (not returned) if commented on by any of these users,
     * or of they are not a PR and were submitted by any of these users.
     * @param createdAfter An instant; all returned issues must have been created after that instant.
     * @param createdBefore An instant; all returned issues must have been created before that instant.
     * @return A lazily populated stream of matching issues.
     * @throws java.io.UncheckedIOException In case of I/O failure.
     */
    public Stream<Issue> issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween(String filterLabel,
            Set<String> ignoreLabels, Set<String> ignoreUsers,
            Instant createdAfter, Instant createdBefore) {
        var builder = searchIssuesOrPullRequests()
                .isOpen()
                .q(label(filterLabel))
                .q(not(anyLabel(ignoreLabels)))
                .q(created(createdAfter, createdBefore))
                .sort(GHIssueSearchBuilder.Sort.CREATED)
                .order(GHDirection.ASC);
        for (String username : ignoreUsers) {
            builder.q(not(commenter(username)));
        }
        return toStream(builder.list())
                // We don't want to consider (non-PR) issues created by people in ignoreCommentedBy,
                // because we assume the author of such issues will initiate discussion themselves.
                .filter(uncheckedIO((GHIssue i) -> i.isPullRequest() || !ignoreUsers.contains(i.getUser().getLogin()))::apply)
                // Note: "ignoreCommentedBy" cannot be assumed to be the full list of maintainers,
                // so we use "ignoreCommentedBy" for optimization
                // (to skip issues that we know for sure aren't relevant),
                // but we still need to have a closer look at issues afterward
                // (to skip issues commented on by team members which are not in "ignoreCommentedBy").
                .filter(this::hasNoTeamAction)
                .map(toIssueRecord());
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

        Optional<GHIssueComment> lastComment = getNonBotCommentsSince(ghIssue, lastEventActionSideInstant)
                .reduce(Streams.last());
        if (lastComment.isEmpty()) {
            // No action since the label was assigned.
            return IssueActionSide.TEAM;
        }
        return getIssueActionSide(ghIssue, lastComment.get().getUser().getLogin());
    }

    private boolean hasNoTeamAction(GHIssue ghIssue) {
        return getNonBotCommentsSince(ghIssue, null)
                .map(uncheckedIO((GHIssueComment c) -> getIssueActionSide(ghIssue, c.getUser().getLogin())))
                .noneMatch(Predicate.isEqual(IssueActionSide.TEAM));
    }

    private IssueActionSide getIssueActionSide(GHIssue issue, String login) throws IOException {
        if (issue.getUser().getLogin().equals(login)) {
            // This is the reporter; even if part of the team,
            // we'll consider he's acting as an outsider here,
            // because he's unlikely to ask for feedback from himself.
            return IssueActionSide.OUTSIDER;
        }

        // Caching in case a same user is encountered multiple times in the same run.
        return noContextIssueActionSideCache.get(login);
    }

    private IssueActionSide computeNoContextIssueActionSide(String login) throws IOException {
        return switch (repository().getPermission(login)) {
            case ADMIN, WRITE, UNKNOWN -> IssueActionSide.TEAM; // "Unknown" includes "triage"
            case READ, NONE -> IssueActionSide.OUTSIDER;
        };
    }

    private Function<GHIssue, Issue> toIssueRecord() {
        return ghIssue -> new Issue(ghIssue.getNumber(), ghIssue.getTitle(), ghIssue.getHtmlUrl());
    }

    /**
     * Retrieves a topic backed by GitHub issues.
     *
     * @param ref The topic reference.
     */
    public Topic topic(TopicRef ref) {
        return new Topic(ref);
    }

    public class Topic {
        private final TopicRef ref;

        private Topic(TopicRef ref) {
            this.ref = ref;
        }

        /**
         * Checks whether all issues of this topic exist, but have been closed.
         *
         * @throws IOException If a GitHub API call fails.
         * @throws java.io.UncheckedIOException If a GitHub API call fails.
         * @see #update(String, String, boolean)
         */
        public boolean isClosed() throws IOException {
            var existingIssue = getDedicatedIssues().findFirst();
            return existingIssue.isPresent() && GHIssueState.CLOSED.equals(existingIssue.get().getState());
        }

        /**
         * Updates an issue identified by its assignee and topic (title prefix),
         * changing the summary in its description and commenting if necessary.
         *
         * @param topicSuffix A string that should be appended to the topic in the issue title.
         *        Each time that suffix changes for a new comment,
         *        the issue title will be updated,
         *        and so will the subject of any email notification sent as a result of that comment.
         *        In conversation-based email clients such as GMail,
         *        this will result in the comment appearing in a new conversation,
         *        which can be useful to avoid huge conversations.
         * @param markdownBody The body of the description to update.
         * @param comment Whether The body should also be added as a comment, triggering a GitHub notification.
         *
         * @throws IOException If a GitHub API call fails.
         * @throws java.io.UncheckedIOException If a GitHub API call fails.
         */
        public void update(String topicSuffix, String markdownBody, boolean comment)
                throws IOException {
            if (deploymentConfig.dryRun()) {
                Log.infof("[DRY RUN] Topic update:\n\tTopic:%s\n\tSuffix:%s\n\tBody:%s\n\tComment:%s",
                        ref, topicSuffix, markdownBody, comment);
                return;
            }

            var dedicatedIssue = getDedicatedIssues().findFirst();
            if (ref.expectedSuffixStart() != null && !topicSuffix.startsWith(ref.expectedSuffixStart())
                    || ref.expectedSuffixStart() == null && !topicSuffix.isEmpty()) {
                throw new IllegalArgumentException(
                        "expectedSuffixStart = '%s' but topicSuffix = '%s'".formatted(ref.expectedSuffixStart(), topicSuffix));
            }
            String targetTitle = ref.topic() + topicSuffix;
            GHIssue issue;
            if (dedicatedIssue.isPresent()) {
                issue = dedicatedIssue.get();
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
                            .reduce(Streams.last());
                    lastRecentComment.ifPresent(GitHubRepository.this::minimizeOutdatedComment);
                } catch (Exception e) {
                    Log.errorf(e, "Failed to minimize last notification for issue %s#%s",
                            GitHubRepository.this.ref.repositoryName(), issue.getNumber());
                }

                // Update the issue description with the content of the latest comment,
                // for convenience.
                // This must be done before the comment, so that notifications triggered by the comment are only sent
                // when the issue is fully updated.
                issue.setBody(messageFormatter.formatDedicatedIssueBodyMarkdown(ref.topic(), markdownBody));
            } else {
                issue = createDedicatedIssue(targetTitle, markdownBody);
            }

            if (comment) {
                issue.comment(markdownBody);
            }
        }

        private Stream<GHIssue> getDedicatedIssues() throws IOException {
            var builder = searchIssuesOnly()
                    .q(author(appLogin()));
            if (ref.assignee() != null) {
                builder.q(assignee(ref.assignee()));
            }
            return toStream(builder.list())
                    .filter(ref.expectedSuffixStart() != null
                            ? issue -> issue.getTitle().startsWith(ref.topic() + ref.expectedSuffixStart())
                            // Try exact match in this case to avoid confusion if there are two issues and one is
                            // the exact topic while the other just starts with the topic.
                            // Example:
                            //     topic = Lottery history for quarkusio/quarkus
                            //     issue1.title = Lottery history for quarkusio/quarkusio.github.io
                            //     issue2.title = Lottery history for quarkusio/quarkus
                            : issue -> issue.getTitle().equals(ref.topic()));
        }

        public Stream<String> extractComments(Instant since)
                throws IOException {
            return getDedicatedIssues()
                    .flatMap(uncheckedIO(issue -> getAppCommentsSince(issue, since)))
                    .map(GHIssueComment::getBody);
        }

        private GHIssue createDedicatedIssue(String title, String lastCommentMarkdownBody)
                throws IOException {
            return repository().createIssue(title)
                    .assignee(ref.assignee())
                    .body(messageFormatter.formatDedicatedIssueBodyMarkdown(ref.topic(), lastCommentMarkdownBody))
                    .create();
        }
    }

    private Stream<GHIssueComment> getAppCommentsSince(GHIssue issue, Instant since) {
        String appLogin = appLogin();
        GHIssueCommentQueryBuilder queryCommentsBuilder = issue.queryComments();
        if (since != null) {
            queryCommentsBuilder.since(Date.from(since));
        }
        return toStream(queryCommentsBuilder.list())
                .filter(uncheckedIO((GHIssueComment comment) -> appLogin.equals(comment.getUser().getLogin()))::apply);
    }

    private Stream<GHIssueComment> getNonBotCommentsSince(GHIssue issue, Instant since) {
        GHIssueCommentQueryBuilder queryCommentsBuilder = issue.queryComments();
        if (since != null) {
            queryCommentsBuilder.since(Date.from(since));
        }
        return toStream(queryCommentsBuilder.list())
                // Relying on the login rather than getType(), because that would involve an additional request.
                .filter(uncheckedIO((GHIssueComment comment) -> !comment.getUser().getLogin()
                        .endsWith(GitHubConstants.BOT_LOGIN_SUFFIX))::apply);
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

}
