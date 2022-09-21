package io.quarkus.github.lottery.github;

/**
 * A reference to a GitHub repository as viewed from a GitHub App installation.
 *
 * @param installationRef A reference to the GitHub installation.
 * @param repositoryName The full name of the GitHub repository.
 */
public record GitHubRepositoryRef(GitHubInstallationRef installationRef, String repositoryName) {

    @Override
    public String toString() {
        return repositoryName + "(through " + installationRef + ")";
    }
}
