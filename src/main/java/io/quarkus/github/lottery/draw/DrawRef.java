package io.quarkus.github.lottery.draw;

import java.time.Instant;

public record DrawRef(
        String repositoryName,
        Instant instant) {

}
