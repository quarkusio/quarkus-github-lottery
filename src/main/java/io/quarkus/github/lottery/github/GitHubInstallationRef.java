package io.quarkus.github.lottery.github;

/**
 * A reference to a GitHub application installation.
 *
 * @param appName The application name.
 * @param installationId The installation ID.
 */
public record GitHubInstallationRef(String appName, long installationId) {
}
