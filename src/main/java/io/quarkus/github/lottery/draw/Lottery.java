package io.quarkus.github.lottery.draw;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.github.IssueActionSide;
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
    private final Map<String, Maintenance> maintenanceByLabel;
    private final Stewardship stewardship;

    public Lottery(Instant now, LotteryConfig.Buckets config) {
        this.now = now;
        this.config = config;
        this.random = new Random();
        this.triage = new Triage();
        this.maintenanceByLabel = new LinkedHashMap<>();
        this.stewardship = new Stewardship();
    }

    Bucket triage() {
        return triage.bucket;
    }

    Maintenance maintenance(String areaLabel) {
        return maintenanceByLabel.computeIfAbsent(areaLabel, Maintenance::new);
    }

    Bucket stewardship() {
        return stewardship.bucket;
    }

    public void draw(GitHubRepository repo, LotteryHistory lotteryHistory) throws IOException {
        // We run draws for separate buckets in parallel,
        // because buckets may compete for issues
        // (e.g. an issue needing feedback, but annotated with two different area labels)
        // and we want to spread the load uniformly across buckets.
        List<Draw> draws = new ArrayList<>();
        // This is to avoid notifying twice about the same issue in parallel draws.
        Set<Integer> allTriageWinnings = new HashSet<>();
        Set<Integer> allMaintenanceWinnings = new HashSet<>();
        Set<Integer> allStewardshipWinnings = new HashSet<>();
        triage.createDraws(repo, lotteryHistory, draws, allTriageWinnings);
        for (Maintenance maintenance : maintenanceByLabel.values()) {
            maintenance.createDraws(repo, lotteryHistory, draws, allMaintenanceWinnings);
        }
        stewardship.createDraws(repo, lotteryHistory, draws, allStewardshipWinnings);
        while (!draws.isEmpty()) {
            var drawsIterator = draws.iterator();
            while (drawsIterator.hasNext()) {
                var state = drawsIterator.next().runSingleRound();
                if (Draw.State.DRAINED.equals(state)) {
                    drawsIterator.remove();
                }
            }
        }
        Log.infof("Winnings of lottery for repository %s / triage: %s", repo.ref(), allTriageWinnings);
        Log.infof("Winnings of lottery for repository %s / maintenance: %s", repo.ref(), allMaintenanceWinnings);
        Log.infof("Winnings of lottery for repository %s / stewardship: %s", repo.ref(), allStewardshipWinnings);
    }

    final class Triage {
        private final Bucket bucket;

        Triage() {
            bucket = new Bucket("triage");
        }

        void createDraws(GitHubRepository repo, LotteryHistory lotteryHistory, List<Draw> draws,
                Set<Integer> allWinnings) throws IOException {
            if (triage.bucket.hasParticipation()) {
                String label = config.triage().label();
                var cutoff = now.minus(config.triage().notification().delay());
                var history = lotteryHistory.triage();
                draws.add(triage.bucket.createDraw(repo.issuesOrPullRequestsWithLabelLastUpdatedBefore(label, Set.of(), cutoff)
                        .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                        .iterator(),
                        allWinnings));
            }
        }
    }

    final class Maintenance {
        private final String areaLabel;
        private final Bucket feedbackNeeded;
        private final Bucket feedbackProvided;
        private final Bucket stale;

        Maintenance(String areaLabel) {
            this.areaLabel = areaLabel;
            String namePrefix = "maintenance - '" + areaLabel + "' - ";
            feedbackNeeded = new Bucket(namePrefix + "feedbackNeeded");
            feedbackProvided = new Bucket(namePrefix + "feedbackProvided");
            stale = new Bucket(namePrefix + "stale");
        }

        Bucket feedbackNeeded() {
            return feedbackNeeded;
        }

        Bucket feedbackProvided() {
            return feedbackProvided;
        }

        Bucket stale() {
            return stale;
        }

        void createDraws(GitHubRepository repo, LotteryHistory lotteryHistory, List<Draw> draws,
                Set<Integer> allWinnings) throws IOException {
            // Remove duplicates, but preserve order
            Set<String> needFeedbackLabels = new LinkedHashSet<>(config.maintenance().feedback().labels());
            if (feedbackNeeded.hasParticipation()) {
                var cutoff = now.minus(config.maintenance().feedback().needed().notification().delay());
                var history = lotteryHistory.feedbackNeeded();
                draws.add(feedbackNeeded.createDraw(
                        repo.issuesLastActedOnByAndLastUpdatedBefore(needFeedbackLabels, areaLabel,
                                IssueActionSide.TEAM, cutoff)
                                .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                                .iterator(),
                        allWinnings));
            }
            if (feedbackProvided.hasParticipation()) {
                var cutoff = now.minus(config.maintenance().feedback().provided().notification().delay());
                var history = lotteryHistory.feedbackProvided();
                draws.add(feedbackProvided.createDraw(
                        repo.issuesLastActedOnByAndLastUpdatedBefore(needFeedbackLabels, areaLabel,
                                IssueActionSide.OUTSIDER, cutoff)
                                .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                                .iterator(),
                        allWinnings));
            }
            if (stale.hasParticipation()) {
                var cutoff = now.minus(config.maintenance().stale().notification().delay());
                // Remove duplicates, but preserve order
                var ignoreLabels = new LinkedHashSet<>(config.maintenance().stale().ignoreLabels());
                var history = lotteryHistory.stale();
                draws.add(stale.createDraw(
                        repo.issuesOrPullRequestsWithLabelLastUpdatedBefore(areaLabel, ignoreLabels, cutoff)
                                .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                                .iterator(),
                        allWinnings));
            }
        }
    }

    final class Stewardship {
        private final Bucket bucket;

        Stewardship() {
            bucket = new Bucket("stewardship");
        }

        void createDraws(GitHubRepository repo, LotteryHistory lotteryHistory, List<Draw> draws,
                Set<Integer> allWinnings) throws IOException {
            if (stewardship.bucket.hasParticipation()) {
                var cutoff = now.minus(config.stewardship().notification().delay());
                var ignoreLabels = new LinkedHashSet<>(config.stewardship().ignoreLabels());
                var history = lotteryHistory.stewardship();
                draws.add(bucket.createDraw(
                        repo.issuesOrPullRequestsLastUpdatedBefore(ignoreLabels, cutoff)
                                .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                                .iterator(),
                        allWinnings));
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

        Draw createDraw(Iterator<Issue> issueIterator, Set<Integer> allWinnings) {
            // Shuffle participations so that prizes are not always assigned in the same order.
            List<Participation> shuffledParticipations = new ArrayList<>(participations);
            Collections.shuffle(shuffledParticipations, random);
            return new Draw(name, shuffledParticipations, issueIterator, allWinnings);
        }
    }

    private static class Draw {
        private final String name;
        private final List<Participation> shuffledParticipations;
        private final Iterator<Issue> issueIterator;
        private final Set<Integer> allWinnings;

        Draw(String name, List<Participation> shuffledParticipations, Iterator<Issue> issueIterator, Set<Integer> allWinnings) {
            this.name = name;
            this.shuffledParticipations = shuffledParticipations;
            this.issueIterator = issueIterator;
            this.allWinnings = allWinnings;
        }

        @Override
        public String toString() {
            return "Draw[" + name + "]";
        }

        State runSingleRound() {
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
