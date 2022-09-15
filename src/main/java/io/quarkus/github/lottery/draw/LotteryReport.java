package io.quarkus.github.lottery.draw;

import java.util.List;

import io.quarkus.github.lottery.github.Issue;

public record LotteryReport(
        DrawRef drawRef,
        String username,
        List<Issue> issuesToTriage) {
}
