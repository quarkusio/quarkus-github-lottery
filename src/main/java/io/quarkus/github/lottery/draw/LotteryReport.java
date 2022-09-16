package io.quarkus.github.lottery.draw;

import java.time.ZoneId;
import java.util.List;

import io.quarkus.github.lottery.github.Issue;

public record LotteryReport(
        DrawRef drawRef,
        String username,
        ZoneId timezone,
        Bucket triage) {

    public record Bucket(
            List<Issue> issues) {
    }
}
