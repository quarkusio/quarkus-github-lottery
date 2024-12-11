package io.quarkus.github.lottery.draw;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.quarkus.github.lottery.github.Issue;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(ignoreNested = false) // For serialization of the "Serialized" classes
public record LotteryReport(
        DrawRef drawRef,
        String username,
        Optional<ZoneId> timezone,
        Config config,
        Optional<Bucket> triage,
        Optional<Bucket> feedbackNeeded,
        Optional<Bucket> feedbackProvided,
        Optional<Bucket> stale,
        Optional<Bucket> stewardship) {

    Stream<Bucket> buckets() {
        return Stream.of(triage, feedbackNeeded, feedbackProvided, stale, stewardship)
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    public boolean hasContent() {
        return buckets().anyMatch(Bucket::hasContent);
    }

    public record Config(
            String triageLabel,
            Set<String> feedbackLabels,
            Set<String> maintenanceLabels) {
    }

    public record Bucket(
            List<Issue> issues) {

        public boolean hasContent() {
            return !issues().isEmpty();
        }

        public Serialized serialized() {
            return new Serialized(issues.stream().map(Issue::number).collect(Collectors.toList()));
        }

        public record Serialized(
                List<Integer> issueNumbers) {
        }
    }

    public Serialized serialized() {
        return new Serialized(drawRef.instant(), username, triage.map(Bucket::serialized),
                feedbackNeeded.map(Bucket::serialized),
                feedbackProvided.map(Bucket::serialized),
                stale.map(Bucket::serialized),
                stewardship.map(Bucket::serialized));
    }

    public record Serialized(
            Instant instant,
            String username,
            Optional<Bucket.Serialized> triage,
            @JsonAlias("reproducerNeeded") Optional<Bucket.Serialized> feedbackNeeded,
            @JsonAlias("reproducerProvided") Optional<Bucket.Serialized> feedbackProvided,
            Optional<Bucket.Serialized> stale,
            Optional<Bucket.Serialized> stewardship) {
    }
}
