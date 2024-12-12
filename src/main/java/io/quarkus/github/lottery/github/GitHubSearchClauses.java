package io.quarkus.github.lottery.github;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GitHubSearchClauses {

    private static final Logger log = LoggerFactory.getLogger(GitHubSearchClauses.class);

    private GitHubSearchClauses() {
    }

    public static String not(String clause) {
        return "-" + clause;
    }

    public static <T> String range(String field, T min, T max, Function<T, String> renderer) {
        if (min == null && max == null) {
            return " ";
        } else if (min == null) {
            return field + ":<" + renderer.apply(max);
        } else if (max == null) {
            return field + ":>=" + renderer.apply(min);
        } else {
            return field + ":" + renderer.apply(min) + ".." + renderer.apply(max);
        }
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

    public static String created(Instant min, Instant max) {
        return range("created", min, max, GitHubSearchClauses::renderInstant);
    }

    public static String updated(Instant min, Instant max) {
        return range("updated", min, max, GitHubSearchClauses::renderInstant);
    }

    public static String author(String author) {
        return "author:" + author;
    }

    public static String assignee(String assignee) {
        return "assignee:" + assignee;
    }

    public static String commenter(String commenter) {
        return "commenter:" + commenter;
    }

    private static String renderInstant(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime().toString();
    }
}
