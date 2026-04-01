package io.quarkus.github.lottery.util;

import static io.quarkus.github.lottery.util.GitHubApiRetry.executeWithRetry;

import java.util.Iterator;

import org.kohsuke.github.PagedIterator;

// Workaround for https://github.com/hub4j/github-api/issues/2009
class RetryingIterator<T> implements Iterator<T> {

    private final PagedIterator<T> delegate;

    public RetryingIterator(PagedIterator<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
        return executeWithRetry(delegate::hasNext);
    }

    @Override
    public T next() {
        return executeWithRetry(delegate::next);
    }
}
