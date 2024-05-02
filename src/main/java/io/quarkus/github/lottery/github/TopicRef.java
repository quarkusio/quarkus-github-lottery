package io.quarkus.github.lottery.github;

/**
 * A reference to a {@link GitHubRepository#topic(TopicRef) topic} in a GitHub repository.
 *
 * @param assignee The GitHub username of issue assignee.
 * @param topic The issue's topic, a string that should be prefixed to the title of dedicated issues.
 * @param expectedSuffixStart If issue titles are suffixed (e.g. with the last update date),
 *        the (constant) string these suffixes are expected to start with.
 *        If issue titles are identical to the topic, a {@code null} string.
 */
public record TopicRef(String assignee,
        String topic,
        String expectedSuffixStart) {

    public TopicRef {
        if (expectedSuffixStart != null && expectedSuffixStart.isEmpty()) {
            throw new IllegalArgumentException("expectedSuffixStart must not be empty; pass either null or a non-empty string");
        }
    }

    public static TopicRef history(String topic) {
        return new TopicRef(null, topic, null);
    }

    public static TopicRef notification(String assignee, String topic) {
        return new TopicRef(assignee, topic, " (updated");
    }

}
