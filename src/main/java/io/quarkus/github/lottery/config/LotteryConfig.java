package io.quarkus.github.lottery.config;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(ignoreNested = false) // For deserialization from the GitHub repository
public record LotteryConfig(
        @JsonProperty(required = true) Notifications notifications,
        @JsonProperty(required = true) Buckets buckets,
        List<Participant> participants) {

    public static final String FILE_NAME = "quarkus-github-lottery.yml";

    public record Buckets(
            @JsonProperty(required = true) TriageBucket triage) {

        public record TriageBucket(
                @JsonProperty(required = true) String needsTriageLabel,
                @JsonProperty(required = true) Duration notificationExpiration) {
        }
    }

    public record Notifications(
            @JsonProperty(required = true) CreateIssuesConfig createIssues) {
        public record CreateIssuesConfig(
                @JsonProperty(required = true) String repository) {
        }
    }

    public record Participant(
            @JsonProperty(required = true) String username,
            @JsonProperty(required = true) @JsonDeserialize(as = TreeSet.class) Set<DayOfWeek> days,
            Optional<ZoneId> timezone,
            Participation triage) {

    }

    public record Participation(
            @JsonProperty(required = true) int maxIssues) {
    }

}
