package io.quarkus.github.lottery.draw;

import java.util.ArrayList;
import java.util.Collections;
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
        // Shuffle tickets so that prizes are not always assigned in the same order.
        List<LotteryTicket> shuffledTickets = new ArrayList<>(tickets);
        Collections.shuffle(shuffledTickets, random);

        while (prizeIterator.hasNext() && !shuffledTickets.isEmpty()) {
            // We proceed in rounds, each round yielding
            // at most one prize to each ticket (if there are enough prizes),
            // so that prizes are spread evenly across tickets.
            var ticketIterator = shuffledTickets.iterator();
            while (prizeIterator.hasNext() && ticketIterator.hasNext()) {
                Issue prize = prizeIterator.next();
                LotteryTicket winner = ticketIterator.next();
                winner.winnings.add(prize);
                if (winner.winnings.size() >= winner.maxWinnings) {
                    // This ticket got its expected winnings:
                    // it won't participate in the next round(s).
                    ticketIterator.remove();
                }
            }
        }

        // The draw is done; new draws would require new tickets.
        tickets.clear();
    }
}
