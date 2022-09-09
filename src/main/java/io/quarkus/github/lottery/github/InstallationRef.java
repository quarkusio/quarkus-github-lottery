package io.quarkus.github.lottery.github;

public record InstallationRef(long installationId, String repositoryName) {

    @Override
    public String toString() {
        return repositoryName + "(through installation " + installationId + ")";
    }
}
