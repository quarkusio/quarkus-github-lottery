package io.quarkus.github.lottery.github;

import io.quarkus.github.lottery.util.GitHubConstants;

/**
 * A reference to a GitHub application installation.
 *
 * @param appSlug The application slug (a cleaned-up version of the app name).
 * @param installationId The installation ID.
 */
public record GitHubInstallationRef(String appSlug, long installationId) {

    public String appLogin() {
        return appSlug() + GitHubConstants.BOT_LOGIN_SUFFIX;
    }

}
