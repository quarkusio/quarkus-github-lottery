package io.quarkus.github.lottery.draw;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import io.quarkus.github.lottery.github.Issue;

/**
 * A lottery bucket, to randomly assign prizes (issues from a single pool) to {@link #ticket(int) tickets}
 * on each {@link #draw(Iterator) draw}.
 */
final class LotteryBucket {

    private final Random random;
    private final List<LotteryTicket> tickets = new ArrayList<>();

    LotteryBucket(Random random) {
        this.random = random;
    }

    LotteryTicket ticket(int maxWinnings) {
        var ticket = new LotteryTicket(maxWinnings);
        tickets.add(ticket);
        return ticket;
    }

    public boolean hasTickets() {
        return !tickets.isEmpty();
    }

    void draw(Iterator<Issue> prizeIterator) {
        while (prizeIterator.hasNext() && !tickets.isEmpty()) {
            Issue prize = prizeIterator.next();
            int winnerIndex = random.nextInt(tickets.size());
            LotteryTicket winner = tickets.get(winnerIndex);
            winner.winnings.add(prize);
            if (winner.winnings.size() >= winner.maxWinnings) {
                tickets.remove(winner);
            }
        }
        // The remaining tickets just lost: there are no more prizes to win.
        tickets.clear();
    }
}
