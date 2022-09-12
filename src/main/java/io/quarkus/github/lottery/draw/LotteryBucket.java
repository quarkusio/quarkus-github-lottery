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
            // We proceed in rounds, each round yielding
            // at most one prize to each participant (if there are enough prizes),
            // so that prizes are spread evenly.
            List<LotteryTicket> roundTickets = new ArrayList<>(tickets);
            do {
                Issue prize = prizeIterator.next();
                int winnerIndex = random.nextInt(roundTickets.size());
                // We remove the winner ticket from the list
                // to ensure the next prizes in the same round will
                // be won by different tickets.
                LotteryTicket winner = roundTickets.remove(winnerIndex);
                winner.winnings.add(prize);
                if (winner.winnings.size() >= winner.maxWinnings) {
                    // This ticket got its expected winnings:
                    // they won't participate in the next round(s).
                    tickets.remove(winner);
                }
            } while (prizeIterator.hasNext() && !roundTickets.isEmpty());
        }
        // The remaining tickets just lost: there are no more prizes to win.
        tickets.clear();
    }
}
