package io.quarkus.github.lottery.github;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.kohsuke.github.PagedIterable;

public final class GitHubUtils {
    private GitHubUtils() {
    }

    public static <T> Stream<T> toStream(PagedIterable<T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    }
}
