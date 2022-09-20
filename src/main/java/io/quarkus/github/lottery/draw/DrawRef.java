package io.quarkus.github.lottery.draw;

import java.time.Instant;

import io.quarkus.github.lottery.github.GitHubRepositoryRef;

public record DrawRef(
        GitHubRepositoryRef repositoryRef,
        Instant instant) {

}
