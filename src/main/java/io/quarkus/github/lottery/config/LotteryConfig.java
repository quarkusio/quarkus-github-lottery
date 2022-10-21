package io.quarkus.github.lottery.config;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(ignoreNested = false) // For deserialization from the GitHub repository
public record LotteryConfig(
        @JsonProperty(required = true) Notifications notifications,
        @JsonProperty(required = true) Buckets buckets,
        List<Participant> participants) {

    public static final String FILE_NAME = "quarkus-github-lottery.yml";

    public record Notifications(
            @JsonProperty(required = true) CreateIssuesConfig createIssues) {
        public record CreateIssuesConfig(
                @JsonProperty(required = true) String repository) {
        }
    }

    public record Buckets(
            @JsonProperty(required = true) Triage triage) {

        public record Triage(
                String label,
                @JsonUnwrapped @JsonProperty(access = JsonProperty.Access.READ_ONLY) Notification notification) {
            // https://stackoverflow.com/a/71539100/6692043
            // Also gives us a less verbose constructor for tests
            @JsonCreator
            public Triage(@JsonProperty(required = true) String label,
                    @JsonProperty(required = true) Duration delay, @JsonProperty(required = true) Duration timeout) {
                this(label, new Notification(delay, timeout));
            }
        }

        public record Notification(
                @JsonProperty(required = true) Duration delay,
                @JsonProperty(required = true) Duration timeout) {
        }
    }

    public record Participant(
            @JsonProperty(required = true) String username,
            Optional<ZoneId> timezone,
            Triage triage) {

        public record Triage(
                @JsonDeserialize(as = TreeSet.class) Set<DayOfWeek> days,
                @JsonUnwrapped @JsonProperty(access = JsonProperty.Access.READ_ONLY) Participation participation) {
            // https://stackoverflow.com/a/71539100/6692043
            @JsonCreator
            public Triage(@JsonProperty(required = true) Set<DayOfWeek> days, @JsonProperty(required = true) int maxIssues) {
                this(days, new Participation(maxIssues));
            }
        }

        public record Participation(@JsonProperty(required = true) int maxIssues) {
        }
    }

}
