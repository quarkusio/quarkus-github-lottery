package io.quarkus.github.lottery.util;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;

import org.kohsuke.github.GHException;

import io.quarkus.logging.Log;

/**
 * Utility for managing GitHub API rate limits and throttling.
 * <p>
 * Handles both secondary rate limit retries and proactive throttling to avoid hitting limits.
 */
public final class GitHubApiLimits {

    private static final int MAX_RETRY = 3;

    // Wait for unambiguously over one minute per GitHub guidance for secondary rate limit retries
    // Can be overridden via system property (e.g., for tests)
    private static final long RETRY_WAIT_MILLIS = Long.getLong(
            "github.lottery.github-api.retry-wait-millis",
            61 * 1000);

    // Throttle mutations (writes/deletes) more conservatively
    private static final long MUTATION_THROTTLE_MILLIS = Long.getLong(
            "github.lottery.github-api.mutation-throttle-millis",
            1000);

    // Throttle reads less aggressively
    private static final long READ_THROTTLE_MILLIS = Long.getLong(
            "github.lottery.github-api.read-throttle-millis",
            200);

    private GitHubApiLimits() {
    }

    /**
     * Executes an action with automatic retry on secondary rate limit errors.
     *
     * @param action The action to execute
     * @param <T> The return type
     * @return The result of the action
     * @throws RuntimeException If the action fails after all retries
     */
    public static <T> T executeWithRetry(Supplier<T> action) {
        RuntimeException rateLimitException = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            if (rateLimitException != null) {
                waitBeforeRetry();
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

    /**
     * Executes an action with automatic retry on secondary rate limit errors.
     * Variant for actions that don't return a value.
     *
     * @param action The action to execute
     * @throws RuntimeException If the action fails after all retries
     */
    public static void executeWithRetry(Runnable action) {
        executeWithRetry(() -> {
            action.run();
            return null;
        });
    }

    private static boolean isSecondaryRateLimitReached(RuntimeException e) {
        return e instanceof GHException
                && e.getCause() != null && e.getCause().getMessage().contains("secondary rate limit");
    }

    private static void waitBeforeRetry() {
        Log.infof("GitHub API reached a secondary rate limit; waiting %s ms before retrying...", RETRY_WAIT_MILLIS);
        try {
            Thread.sleep(RETRY_WAIT_MILLIS);
        } catch (InterruptedException ex) {
            throw new UncheckedIOException((InterruptedIOException) new InterruptedIOException().initCause(ex));
        }
    }

    /**
     * Sleeps for the mutation throttling delay to avoid triggering secondary rate limits.
     * Should be called between mutation operations (writes, deletes).
     */
    public static void sleepForMutationThrottling() {
        try {
            Thread.sleep(MUTATION_THROTTLE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException((InterruptedIOException) new InterruptedIOException().initCause(e));
        }
    }

    /**
     * Sleeps for the read throttling delay to avoid triggering secondary rate limits.
     * Should be called between read operations.
     */
    public static void sleepForReadThrottling() {
        try {
            Thread.sleep(READ_THROTTLE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException((InterruptedIOException) new InterruptedIOException().initCause(e));
        }
    }
}
