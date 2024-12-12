package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static io.quarkus.github.lottery.util.MockHelper.stubReport;
import static io.quarkus.github.lottery.util.MockHelper.stubReportConfig;
import static io.quarkus.github.lottery.util.MockHelper.stubReportMaintenance;
import static io.quarkus.github.lottery.util.MockHelper.stubReportStewardship;
import static io.quarkus.github.lottery.util.MockHelper.stubReportTriage;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class MessageFormatterTest {

    GitHubInstallationRef installationRef;
    GitHubRepositoryRef repoRef;
    DrawRef drawRef;
    GitHubRepositoryRef notificationRepoRef;

    @Inject
    MessageFormatter messageFormatter;

    @BeforeEach
    void setup() {
        installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1L);
        repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");
        var now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef, now);
        notificationRepoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus-github-lottery-reports");
    }

    @Test
    void dedicatedIssueBodyMarkdown() {
        assertThat(messageFormatter.formatDedicatedIssueBodyMarkdown("yrodiere's report for quarkusio/quarkus",
                """
                        Some report over
                        multiple lines."""))
                .isEqualTo("""
                        This issue is dedicated to yrodiere's report for quarkusio/quarkus.

                        Latest update:

                        > Some report over
                        > multiple lines.
                        """);
    }

    @Test
    void formatNotificationTopicText() {
        assertThat(messageFormatter.formatNotificationTopicText(drawRef, "yrodiere"))
                .isEqualTo("yrodiere's report for quarkusio/quarkus");
    }

    @Test
    void formatNotificationTopicSuffixText() {
        var lotteryReport = stubReport(drawRef, "yrodiere", Optional.empty(),
                stubReportConfig(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertThat(messageFormatter.formatNotificationTopicSuffixText(lotteryReport))
                .isEqualTo(" (updated 2017-11-06T06:00:00Z)");
    }

    @Test
    void formatNotificationTopicSuffixText_exoticTimezone() {
        var lotteryReport = stubReport(drawRef, "yrodiere", Optional.of(ZoneId.of("America/Los_Angeles")),
                stubReportConfig(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertThat(messageFormatter.formatNotificationTopicSuffixText(lotteryReport))
                .isEqualTo(" (updated 2017-11-05)");
    }

    @Test
    void formatNotificationBodyMarkdown_triage_empty() {
        var lotteryReport = stubReportTriage(drawRef, "yrodiere", Optional.empty(),
                List.of());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                # Triage

                                No issues in this category this time.

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_triage_simple() {
        var lotteryReport = stubReportTriage(drawRef, "yrodiere", Optional.empty(),
                stubIssueList(1, 3));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                # Triage

                                <sup>Issues/PRs that haven't been assigned an area yet. Please add an area label, remove the `triage/needs-triage` label, optionally ping maintainers.</sup>

                                 - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                                 - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_maintenance_empty() {
        var lotteryReport = stubReportMaintenance(drawRef, "yrodiere", Optional.empty(),
                List.of("area/hibernate-orm", "area/hibernate-search"),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                Maintenance areas: `area/hibernate-orm`/`area/hibernate-search`.

                                # Created

                                No issues in this category this time.

                                # Feedback needed

                                No issues in this category this time.

                                # Feedback provided

                                No issues in this category this time.

                                # Stale

                                No issues in this category this time.

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_maintenance_someEmpty() {
        var lotteryReport = stubReportMaintenance(drawRef, "yrodiere", Optional.empty(),
                List.of("area/hibernate-orm", "area/hibernate-search"),
                List.of(),
                stubIssueList(1, 3),
                List.of(),
                List.of());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                Maintenance areas: `area/hibernate-orm`/`area/hibernate-search`.

                                # Created

                                No issues in this category this time.

                                # Feedback needed

                                <sup>Issues with missing reproducer/information. Please ping the reporter, or close the issue if it's taking too long.</sup>

                                 - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                                 - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3

                                # Feedback provided

                                No issues in this category this time.

                                # Stale

                                No issues in this category this time.

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_maintenance_simple() {
        var lotteryReport = stubReportMaintenance(drawRef, "yrodiere", Optional.empty(),
                List.of("area/hibernate-orm", "area/hibernate-search"),
                stubIssueList(8, 9),
                stubIssueList(1, 3),
                stubIssueList(4, 5),
                stubIssueList(2, 7));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                Maintenance areas: `area/hibernate-orm`/`area/hibernate-search`.

                                # Created

                                <sup>Issues or PRs that just got created in your area. Please review, ask for reproducer/information, or plan future work.</sup>

                                 - [#8](http://github.com/quarkusio/quarkus/issues/8) Title for issue 8
                                 - [#9](http://github.com/quarkusio/quarkus/issues/9) Title for issue 9

                                # Feedback needed

                                <sup>Issues with missing reproducer/information. Please ping the reporter, or close the issue if it's taking too long.</sup>

                                 - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                                 - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3

                                # Feedback provided

                                <sup>Issues with newly provided reproducer/information. Please have a closer look, possibly remove the `triage/needs-reproducer`/`triage/needs-feedback` label, and plan further work.</sup>

                                 - [#4](http://github.com/quarkusio/quarkus/issues/4) Title for issue 4
                                 - [#5](http://github.com/quarkusio/quarkus/issues/5) Title for issue 5

                                # Stale

                                <sup>Issues or PRs last updated a long time ago. Please have a closer look, re-prioritize, ping someone, label as "on ice", close the issue/PR, ...</sup>

                                 - [#2](http://github.com/quarkusio/quarkus/issues/2) Title for issue 2
                                 - [#7](http://github.com/quarkusio/quarkus/issues/7) Title for issue 7

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_stewardship_empty() {
        var lotteryReport = stubReportStewardship(drawRef, "geoand", Optional.empty(),
                List.of());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @geoand, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                # Stewardship

                                No issues in this category this time.

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_stewardship_simple() {
        var lotteryReport = stubReportStewardship(drawRef, "geoand", Optional.empty(),
                stubIssueList(1, 3));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @geoand, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                # Stewardship

                                <sup>Issues or PRs across all areas last updated a long time ago. Please have a closer look, re-prioritize, ping someone, label as "on ice", close the issue/PR, ...</sup>

                                 - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                                 - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_all() {
        var lotteryReport = stubReport(drawRef, "yrodiere", Optional.empty(),
                stubReportConfig(),
                Optional.of(stubIssueList(1, 3)),
                Optional.of(stubIssueList(12, 13)),
                Optional.of(stubIssueList(4, 5)),
                Optional.of(stubIssueList(2, 7)),
                Optional.of(stubIssueList(8, 9)),
                Optional.of(stubIssueList(10, 11)));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .isEqualTo(
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                                # Triage

                                <sup>Issues/PRs that haven't been assigned an area yet. Please add an area label, remove the `triage/needs-triage` label, optionally ping maintainers.</sup>

                                 - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                                 - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3

                                # Created

                                <sup>Issues or PRs that just got created in your area. Please review, ask for reproducer/information, or plan future work.</sup>

                                 - [#12](http://github.com/quarkusio/quarkus/issues/12) Title for issue 12
                                 - [#13](http://github.com/quarkusio/quarkus/issues/13) Title for issue 13

                                # Feedback needed

                                <sup>Issues with missing reproducer/information. Please ping the reporter, or close the issue if it's taking too long.</sup>

                                 - [#4](http://github.com/quarkusio/quarkus/issues/4) Title for issue 4
                                 - [#5](http://github.com/quarkusio/quarkus/issues/5) Title for issue 5

                                # Feedback provided

                                <sup>Issues with newly provided reproducer/information. Please have a closer look, possibly remove the `triage/needs-reproducer`/`triage/needs-feedback` label, and plan further work.</sup>

                                 - [#2](http://github.com/quarkusio/quarkus/issues/2) Title for issue 2
                                 - [#7](http://github.com/quarkusio/quarkus/issues/7) Title for issue 7

                                # Stale

                                <sup>Issues or PRs last updated a long time ago. Please have a closer look, re-prioritize, ping someone, label as "on ice", close the issue/PR, ...</sup>

                                 - [#8](http://github.com/quarkusio/quarkus/issues/8) Title for issue 8
                                 - [#9](http://github.com/quarkusio/quarkus/issues/9) Title for issue 9

                                # Stewardship

                                <sup>Issues or PRs across all areas last updated a long time ago. Please have a closer look, re-prioritize, ping someone, label as "on ice", close the issue/PR, ...</sup>

                                 - [#10](http://github.com/quarkusio/quarkus/issues/10) Title for issue 10
                                 - [#11](http://github.com/quarkusio/quarkus/issues/11) Title for issue 11

                                ---
                                <sup>If you no longer want to receive these notifications, \
                                just close [any issue assigned to you in the notification repository](https://github.com/quarkusio/quarkus-github-lottery-reports/issues/assigned/@me). \
                                Reopening the issue will resume the notifications.</sup>
                                """);
    }

    @Test
    void formatNotificationBodyMarkdown_exoticTimezone() {
        var lotteryReport = stubReport(drawRef, "yrodiere", Optional.of(ZoneId.of("America/Los_Angeles")),
                stubReportConfig(),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport, notificationRepoRef))
                .startsWith("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-05.
                        """);
    }

    @Test
    void formatHistoryTopicText() {
        assertThat(messageFormatter.formatHistoryTopicText(drawRef))
                .isEqualTo("Lottery history for quarkusio/quarkus");
    }

    @Test
    void formatHistoryBodyMarkdown() throws IOException {
        var lotteryReports = List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 3))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "gsmet",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(2, 4))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "rick",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of())),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jane",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(13, 14))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(7, 8))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(9))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(10, 11, 12))),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jsmith",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(25, 26))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(45, 46))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(27, 28))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(29))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(30, 31, 32))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(41, 42)))),
                new LotteryReport.Serialized(drawRef.instant(), "geoand",
                        Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(51, 52)))));
        String formatted = messageFormatter.formatHistoryBodyMarkdown(drawRef, lotteryReports);
        assertThat(formatted)
                .startsWith("""
                        Here are the reports for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # yrodiere
                        ## Triage
                         - quarkusio/quarkus#1
                         - quarkusio/quarkus#3

                        # gsmet
                        ## Triage
                         - quarkusio/quarkus#2
                         - quarkusio/quarkus#4

                        # rick
                        ## Triage
                        No issues in this category this time.

                        # jane
                        ## Created
                         - quarkusio/quarkus#13
                         - quarkusio/quarkus#14
                        ## Feedback needed
                         - quarkusio/quarkus#7
                         - quarkusio/quarkus#8
                        ## Feedback provided
                         - quarkusio/quarkus#9
                        ## Stale
                         - quarkusio/quarkus#10
                         - quarkusio/quarkus#11
                         - quarkusio/quarkus#12

                        # jsmith
                        ## Triage
                         - quarkusio/quarkus#25
                         - quarkusio/quarkus#26
                        ## Created
                         - quarkusio/quarkus#45
                         - quarkusio/quarkus#46
                        ## Feedback needed
                         - quarkusio/quarkus#27
                         - quarkusio/quarkus#28
                        ## Feedback provided
                         - quarkusio/quarkus#29
                        ## Stale
                         - quarkusio/quarkus#30
                         - quarkusio/quarkus#31
                         - quarkusio/quarkus#32
                        ## Stewardship
                         - quarkusio/quarkus#41
                         - quarkusio/quarkus#42

                        # geoand
                        ## Stewardship
                         - quarkusio/quarkus#51
                         - quarkusio/quarkus#52

                        <!--:payload:
                        """);

        assertThat(messageFormatter.extractPayloadFromHistoryBodyMarkdown(formatted))
                .usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(lotteryReports);
    }

    @Test
    void extractPayloadFromHistoryBodyMarkdown_oldFormatWithReproducer() throws IOException {
        var lotteryReports = List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 3))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "gsmet",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(2, 4))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "rick",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of())),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jane",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(7, 8))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(9))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(10, 11, 12))),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jsmith",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(25, 26))),
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(27, 28))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(29))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(30, 31, 32))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(41, 42)))),
                new LotteryReport.Serialized(drawRef.instant(), "geoand",
                        Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(51, 52)))));
        String formatted = """
                Foo

                bar
                <span>foobar</span>

                <!--:payload:
                [{"instant":"2017-11-06T06:00:00Z","username":"yrodiere","triage":{"issueNumbers":[1,3]},"reproducerNeeded":null,"reproducerProvided":null,"stale":null,"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"gsmet","triage":{"issueNumbers":[2,4]},"reproducerNeeded":null,"reproducerProvided":null,"stale":null,"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"rick","triage":{"issueNumbers":[]},"reproducerNeeded":null,"reproducerProvided":null,"stale":null,"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"jane","triage":null,"reproducerNeeded":{"issueNumbers":[7,8]},"reproducerProvided":{"issueNumbers":[9]},"stale":{"issueNumbers":[10,11,12]},"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"jsmith","triage":{"issueNumbers":[25,26]},"reproducerNeeded":{"issueNumbers":[27,28]},"reproducerProvided":{"issueNumbers":[29]},"stale":{"issueNumbers":[30,31,32]},"stewardship":{"issueNumbers":[41,42]}},{"instant":"2017-11-06T06:00:00Z","username":"geoand","triage":null,"reproducerNeeded":null,"reproducerProvided":null,"stale":null,"stewardship":{"issueNumbers":[51,52]}}]
                :payload:-->
                """;

        assertThat(messageFormatter.extractPayloadFromHistoryBodyMarkdown(formatted))
                .usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(lotteryReports);
    }

    @Test
    void extractPayloadFromHistoryBodyMarkdown_oldFormatWithoutCreated() throws IOException {
        var lotteryReports = List.of(
                new LotteryReport.Serialized(drawRef.instant(), "yrodiere",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(1, 3))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "gsmet",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(2, 4))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "rick",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of())),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jane",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(7, 8))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(9))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(10, 11, 12))),
                        Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jsmith",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(25, 26))),
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(27, 28))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(29))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(30, 31, 32))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(41, 42)))),
                new LotteryReport.Serialized(drawRef.instant(), "geoand",
                        Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(51, 52)))));
        String formatted = """
                Foo

                bar
                <span>foobar</span>

                <!--:payload:
                [{"instant":"2017-11-06T06:00:00Z","username":"yrodiere","triage":{"issueNumbers":[1,3]},"feedbackNeeded":null,"feedbackProvided":null,"stale":null,"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"gsmet","triage":{"issueNumbers":[2,4]},"feedbackNeeded":null,"feedbackProvided":null,"stale":null,"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"rick","triage":{"issueNumbers":[]},"feedbackNeeded":null,"feedbackProvided":null,"stale":null,"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"jane","triage":null,"feedbackNeeded":{"issueNumbers":[7,8]},"feedbackProvided":{"issueNumbers":[9]},"stale":{"issueNumbers":[10,11,12]},"stewardship":null},{"instant":"2017-11-06T06:00:00Z","username":"jsmith","triage":{"issueNumbers":[25,26]},"feedbackNeeded":{"issueNumbers":[27,28]},"feedbackProvided":{"issueNumbers":[29]},"stale":{"issueNumbers":[30,31,32]},"stewardship":{"issueNumbers":[41,42]}},{"instant":"2017-11-06T06:00:00Z","username":"geoand","triage":null,"feedbackNeeded":null,"feedbackProvided":null,"stale":null,"stewardship":{"issueNumbers":[51,52]}}]
                :payload:-->
                """;

        assertThat(messageFormatter.extractPayloadFromHistoryBodyMarkdown(formatted))
                .usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(lotteryReports);
    }
}