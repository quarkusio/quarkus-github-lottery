package io.quarkus.github.lottery.draw;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.history.LotteryHistory;
import io.quarkus.logging.Log;

/**
 * A lottery, with a {@link Bucket buckets} for each pool of issues.
 */
public final class Lottery {

    private final Instant now;
    private final LotteryConfig.Buckets config;
    private final Random random;
    private final Triage triage;

    public Lottery(Instant now, LotteryConfig.Buckets config) {
        this.now = now;
        this.config = config;
        this.random = new Random();
        this.triage = new Triage();
    }

    Bucket triage() {
        return triage.bucket;
    }

    public void draw(GitHubRepository repo, LotteryHistory lotteryHistory) throws IOException {
        // We run draws for separate buckets in parallel,
        // because buckets may compete for issues
        // (e.g. an issue needing a reproducer, but annotated with two different area labels)
        // and we want to spread the load uniformly across buckets.
        List<Draw> draws = new ArrayList<>();
        // This is to avoid notifying twice about the same issue in parallel draws.
        Set<Integer> allWinnings = new HashSet<>();
        triage.createDraws(repo, lotteryHistory, draws);
        while (!draws.isEmpty()) {
            var drawsIterator = draws.iterator();
            while (drawsIterator.hasNext()) {
                var state = drawsIterator.next().runSingleRound(allWinnings);
                if (Draw.State.DRAINED.equals(state)) {
                    drawsIterator.remove();
                }
            }
        }
        Log.infof("Winnings of lottery for repository %s: %s", repo.ref(), allWinnings);
    }

    final class Triage {
        private final Bucket bucket;

        Triage() {
            bucket = new Bucket("triage");
        }

        void createDraws(GitHubRepository repo, LotteryHistory lotteryHistory, List<Draw> draws) throws IOException {
            if (triage.bucket.hasParticipation()) {
                String label = config.triage().label();
                var cutoff = now.minus(config.triage().notification().delay());
                var history = lotteryHistory.triage();
                draws.add(triage.bucket.createDraw(repo.issuesWithLabelLastUpdatedBefore(label, cutoff)
                        .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                        .iterator()));
            }
        }
    }

    final class Bucket {
        private final String name;
        private final List<Participation> participations = new ArrayList<>();

        Bucket(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "Bucket[" + name + "]";
        }

        void participate(Participation participation) {
            participations.add(participation);
        }

        public boolean hasParticipation() {
            return !participations.isEmpty();
        }

        Draw createDraw(Iterator<Issue> issueIterator) {
            // Shuffle participations so that prizes are not always assigned in the same order.
            List<Participation> shuffledParticipations = new ArrayList<>(participations);
            Collections.shuffle(shuffledParticipations, random);
            return new Draw(name, shuffledParticipations, issueIterator);
        }
    }

    private static class Draw {
        private final String name;
        private final List<Participation> shuffledParticipations;
        private final Iterator<Issue> issueIterator;

        Draw(String name, List<Participation> shuffledParticipations, Iterator<Issue> issueIterator) {
            this.name = name;
            this.shuffledParticipations = shuffledParticipations;
            this.issueIterator = issueIterator;
        }

        @Override
        public String toString() {
            return "Draw[" + name + "]";
        }

        State runSingleRound(Set<Integer> allWinnings) {
            Log.tracef("Start of round for draw %s...", name);

            // Participations may have reached their max number of issues in parallel draws
            // using the same participations;
            removeMaxedOutParticipations();

            // We proceed in rounds, each round yielding
            // at most one prize to each participation (if there are enough prizes),
            // so that prizes are spread evenly across participations.
            for (Participation participation : shuffledParticipations) {
                if (!issueIterator.hasNext()) {
                    break;
                }
                Issue issue = issueIterator.next();
                int issueNumber = issue.number();
                if (!allWinnings.add(issueNumber)) {
                    // This issue was already won, either in this draw or in a parallel one.
                    // Skip it, to avoid notifying twice about the same issue.
                    continue;
                }
                participation.issues.add(issue);
                Log.tracef("Draw %s assigned issue %s to %s", name, issueNumber, participation);
            }
            Log.tracef("End of round for draw %s", name);

            removeMaxedOutParticipations();

            if (!shuffledParticipations.isEmpty() && issueIterator.hasNext()) {
                Log.tracef("Draw %s may run again", name);
                return State.RUNNING;
            } else {
                Log.tracef("Draw %s is drained", name);
                return State.DRAINED;
            }
        }

        private void removeMaxedOutParticipations() {
            shuffledParticipations.removeIf(p -> p.issues.size() >= p.maxIssues);
        }

        enum State {
            RUNNING,
            DRAINED
        }
    }
}
