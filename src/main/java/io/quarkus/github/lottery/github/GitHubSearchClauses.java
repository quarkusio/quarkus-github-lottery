package io.quarkus.github.lottery.github;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

public final class GitHubSearchClauses {

    private GitHubSearchClauses() {
    }

    public static String not(String clause) {
        return "-" + clause;
    }

    public static String repo(GitHubRepositoryRef ref) {
        return "repo:" + ref.repositoryName();
    }

    public static String isIssue() {
        return "is:issue";
    }

    public static String anyLabel(Set<String> labels) {
        return label(String.join(",", labels));
    }

    public static String label(String label) {
        return "label:" + label;
    }

    public static String updatedBefore(Instant updatedBefore) {
        return "updated:<" + updatedBefore.atOffset(ZoneOffset.UTC).toLocalDateTime().toString();
    }

    public static String author(String author) {
        return "author:" + author;
    }

    public static String assignee(String assignee) {
        return "assignee:" + assignee;
    }
}
