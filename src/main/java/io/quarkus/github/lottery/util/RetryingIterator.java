package io.quarkus.github.lottery.util;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.function.Supplier;

import org.kohsuke.github.GHException;
import org.kohsuke.github.PagedIterator;

import io.quarkus.logging.Log;

// Workaround for https://github.com/hub4j/github-api/issues/2009
class RetryingIterator<T> implements Iterator<T> {
    private static final int MAX_RETRY = 3;

    // Wait for unambiguously over one minute per GitHub guidance
    private static final long DEFAULT_WAIT_MILLIS = 61 * 1000;

    private final PagedIterator<T> delegate;

    public RetryingIterator(PagedIterator<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
        return doWithRetry(delegate::hasNext);
    }

    @Override
    public T next() {
        return doWithRetry(delegate::next);
    }

    private <T> T doWithRetry(Supplier<T> action) {
        RuntimeException rateLimitException = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            if (rateLimitException != null) {
                waitBeforeRetry(rateLimitException);
            }
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (isSecondaryRateLimitReached(e)) {
                    if (rateLimitException == null) {
                        rateLimitException = e;
                    } else {
                        rateLimitException.addSuppressed(e);
                    }
                } else {
                    throw e;
                }
            }
        }
        throw rateLimitException;
    }

    private static boolean isSecondaryRateLimitReached(RuntimeException e) {
        return e instanceof GHException
                && e.getCause() != null && e.getCause().getMessage().contains("secondary rate limit");
    }

    private void waitBeforeRetry(RuntimeException e) {
        Log.infof("GitHub API reached a secondary rate limit; waiting %s ms before retrying...", DEFAULT_WAIT_MILLIS);
        try {
            Thread.sleep(DEFAULT_WAIT_MILLIS);
        } catch (InterruptedException ex) {
            throw new UncheckedIOException((InterruptedIOException) new InterruptedIOException().initCause(e));
        }
    }
}
