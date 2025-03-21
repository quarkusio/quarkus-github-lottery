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
import java.util.function.BiPredicate;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.github.IssueActionSide;
import io.quarkus.github.lottery.history.LotteryHistory;
import io.quarkus.github.lottery.util.BufferingIterator;
import io.quarkus.logging.Log;

/**
 * A lottery, with a {@link Bucket buckets} for each pool of issues.
 */
public final class Lottery {

    private final Instant now;
    private final LotteryConfig.Buckets config;
    private final Map<String, Set<String>> maintainerUsernamesByAreaLabel;
    private final Random random;
    private final Triage triage;
    private final Map<String, Maintenance> maintenanceByLabel;
    private final Stewardship stewardship;

    public Lottery(Instant now, LotteryConfig.Buckets config, Map<String, Set<String>> maintainerUsernamesByAreaLabel) {
        this.now = now;
        this.config = config;
        this.maintainerUsernamesByAreaLabel = maintainerUsernamesByAreaLabel;
        this.random = new Random();
        this.triage = new Triage();
        this.maintenanceByLabel = new LinkedHashMap<>();
        this.stewardship = new Stewardship();
    }

    Bucket triage() {
        return triage.bucket;
    }

    Maintenance maintenance(String areaLabel) {
        return maintenanceByLabel.computeIfAbsent(areaLabel, this::createMaintenance);
    }

    private Maintenance createMaintenance(String areaLabel) {
        return new Maintenance(areaLabel, maintainerUsernamesByAreaLabel.getOrDefault(areaLabel, Set.of()));
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
        private final Set<String> maintainerUsernames;
        private final Bucket created;
        private final Bucket feedbackNeeded;
        private final Bucket feedbackProvided;
        private final Bucket stale;

        Maintenance(String areaLabel, Set<String> maintainerUsernames) {
            this.areaLabel = areaLabel;
            this.maintainerUsernames = maintainerUsernames;
            String namePrefix = "maintenance - '" + areaLabel + "' - ";
            created = new Bucket(namePrefix + "created");
            feedbackNeeded = new Bucket(namePrefix + "feedbackNeeded");
            feedbackProvided = new Bucket(namePrefix + "feedbackProvided");
            stale = new Bucket(namePrefix + "stale");
        }

        public void addMaintainer(String username) {
            maintainerUsernames.add(username);
        }

        Bucket created() {
            return created;
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
            if (created.hasParticipation()) {
                var maxCutoff = now.minus(config.maintenance().created().notification().delay());
                var minCutoff = now.minus(config.maintenance().created().expiry());
                // Remove duplicates, but preserve order
                var ignoreLabels = new LinkedHashSet<String>();
                // Ignore issues with feedback request labels,
                // since they evidently got some attention from the team already.
                ignoreLabels.addAll(config.maintenance().feedback().labels());
                ignoreLabels.addAll(config.maintenance().created().ignoreLabels());
                var history = lotteryHistory.created();
                draws.add(created.createDraw(
                        repo.issuesOrPullRequestsNeverActedOnByTeamAndCreatedBetween(areaLabel, ignoreLabels,
                                maintainerUsernames, minCutoff,
                                maxCutoff)
                                .filter(issue -> history.lastNotificationTimedOutForIssueNumber(issue.number()))
                                .iterator(),
                        allWinnings,
                        // Don't notify maintainers about issues/PRs they created themselves:
                        // they already know about them, and needs *someone else* to have a look.
                        ((participation, issue) -> !participation.username().equals(issue.author()))));
            }
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
            return createDraw(issueIterator, allWinnings, (ignored1, ignored2) -> true);
        }

        Draw createDraw(Iterator<Issue> issueIterator, Set<Integer> allWinnings,
                BiPredicate<Participation, Issue> compatibilityFilter) {
            // Shuffle participations so that prizes are not always assigned in the same order.
            List<Participation> shuffledParticipations = new ArrayList<>(participations);
            Collections.shuffle(shuffledParticipations, random);
            return new Draw(name, shuffledParticipations, issueIterator, allWinnings, compatibilityFilter);
        }
    }

    private static class Draw {
        private final String name;
        private final List<Participation> shuffledParticipations;
        private final BufferingIterator<Issue> issueIterator;
        private final Set<Integer> allWinnings;
        private final BiPredicate<Participation, Issue> compatibilityFilter;

        Draw(String name, List<Participation> shuffledParticipations, Iterator<Issue> issueIterator,
                Set<Integer> allWinnings,
                BiPredicate<Participation, Issue> compatibilityFilter) {
            this.name = name;
            this.shuffledParticipations = shuffledParticipations;
            this.issueIterator = new BufferingIterator<>(issueIterator);
            this.allWinnings = allWinnings;
            this.compatibilityFilter = compatibilityFilter;
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
            for (var participationIterator = shuffledParticipations.iterator(); participationIterator.hasNext();) {
                Participation participation = participationIterator.next();
                // Re-consider previously skipped issues
                issueIterator.backToStart();
                if (!issueIterator.hasNext()) {
                    break;
                }

                Issue issue = null;
                while (issueIterator.hasNext() && issue == null) {
                    Issue issueCandidate = issueIterator.next();
                    if (!compatibilityFilter.test(participation, issueCandidate)) {
                        // Can't use this issue for this participation.
                        // Skip it, but keep the issue for another participation.
                        Log.tracef("Draw %s skipping issue %s for %s due to incompatibility", name, issueCandidate.number(),
                                participation);
                        continue;
                    }
                    if (!allWinnings.add(issueCandidate.number())) {
                        // This issue was already won, either in this draw or in a parallel one.
                        // Skip it, to avoid notifying twice about the same issue,
                        // and remove it, because it can't be used even for another participation.
                        issueIterator.remove();
                        continue;
                    }

                    // We found an issue!
                    issue = issueCandidate;
                    issueIterator.remove();
                }

                if (issue == null) {
                    // Cannot find any issue for this participation anymore
                    participationIterator.remove();
                    continue;
                }

                participation.issues.add(issue);
                Log.tracef("Draw %s assigned issue %s to %s", name, issue.number(), participation);
            }
            Log.tracef("End of round for draw %s", name);

            removeMaxedOutParticipations();

            // Re-consider previously skipped issues
            issueIterator.backToStart();
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
