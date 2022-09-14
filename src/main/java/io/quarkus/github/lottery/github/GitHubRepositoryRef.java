package io.quarkus.github.lottery.github;

/**
 * A reference to a GitHub repository as viewed from a GitHub App installation.
 *
 * @param installationId The installation ID.
 * @param repositoryName The full name of the GitHub repository.
 */
public record GitHubRepositoryRef(long installationId, String repositoryName) {

    @Override
    public String toString() {
        return repositoryName + "(through installation " + installationId + ")";
    }
}
