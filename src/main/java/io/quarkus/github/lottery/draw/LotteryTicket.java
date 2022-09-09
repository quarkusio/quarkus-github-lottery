package io.quarkus.github.lottery.draw;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.github.lottery.github.Issue;

final class LotteryTicket {
    final int maxWinnings;

    public final List<Issue> winnings = new ArrayList<>();

    LotteryTicket(int maxWinnings) {
        this.maxWinnings = maxWinnings;
    }
}
