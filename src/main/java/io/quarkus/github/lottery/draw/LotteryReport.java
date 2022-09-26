package io.quarkus.github.lottery.draw;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.quarkus.github.lottery.github.Issue;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(ignoreNested = false) // For serialization of the "Serialized" classes
public record LotteryReport(
        DrawRef drawRef,
        String username,
        Optional<ZoneId> timezone,
        Bucket triage) {

    public record Bucket(
            List<Issue> issues) {

        public Serialized serialized() {
            return new Serialized(issues.stream().map(Issue::number).collect(Collectors.toList()));
        }

        public record Serialized(
                List<Integer> issueNumbers) {
        }
    }

    public Serialized serialized() {
        return new Serialized(drawRef.instant(), username, triage.serialized());
    }

    public record Serialized(
            Instant instant,
            String username,
            Bucket.Serialized triage) {
    }
}
