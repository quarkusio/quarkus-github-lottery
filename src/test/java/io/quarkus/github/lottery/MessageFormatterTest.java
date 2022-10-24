package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.github.lottery.github.GitHubInstallationRef;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.message.MessageFormatter;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class MessageFormatterTest {

    GitHubInstallationRef installationRef;
    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @Inject
    MessageFormatter messageFormatter;

    @BeforeEach
    void setup() {
        installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1L);
        repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");
        var now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef, now);
    }

    @Test
    void formatNotificationTopicText() {
        assertThat(messageFormatter.formatNotificationTopicText(drawRef, "yrodiere"))
                .isEqualTo("yrodiere's report for quarkusio/quarkus");
    }

    @Test
    void formatNotificationTopicSuffixText() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(messageFormatter.formatNotificationTopicSuffixText(lotteryReport))
                .isEqualTo(" (updated 2017-11-06T06:00:00Z)");
    }

    @Test
    void formatNotificationTopicSuffixText_exoticTimezone() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.of(ZoneId.of("America/Los_Angeles")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(messageFormatter.formatNotificationTopicSuffixText(lotteryReport))
                .isEqualTo(" (updated 2017-11-05)");
    }

    @Test
    void formatNotificationBodyMarkdown_triage_empty() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.of(new LotteryReport.Bucket(List.of())),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Triage
                        No issues in this category this time.

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_triage_simple() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 3))),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Triage
                         - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                         - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_maintenance_empty() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(List.of())),
                Optional.of(new LotteryReport.Bucket(List.of())),
                Optional.of(new LotteryReport.Bucket(List.of())));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Reproducer needed
                        No issues in this category this time.
                        # Reproducer provided
                        No issues in this category this time.
                        # Stale
                        No issues in this category this time.

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_maintenance_someEmpty() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 3))),
                Optional.of(new LotteryReport.Bucket(List.of())),
                Optional.of(new LotteryReport.Bucket(List.of())));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Reproducer needed
                         - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                         - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3
                        # Reproducer provided
                        No issues in this category this time.
                        # Stale
                        No issues in this category this time.

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_maintenance_simple() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 3))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(4, 5))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(2, 7))));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Reproducer needed
                         - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                         - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3
                        # Reproducer provided
                         - [#4](http://github.com/quarkusio/quarkus/issues/4) Title for issue 4
                         - [#5](http://github.com/quarkusio/quarkus/issues/5) Title for issue 5
                        # Stale
                         - [#2](http://github.com/quarkusio/quarkus/issues/2) Title for issue 2
                         - [#7](http://github.com/quarkusio/quarkus/issues/7) Title for issue 7

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_all() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                Optional.of(new LotteryReport.Bucket(stubIssueList(1, 3))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(4, 5))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(2, 7))),
                Optional.of(new LotteryReport.Bucket(stubIssueList(8, 9))));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Triage
                         - [#1](http://github.com/quarkusio/quarkus/issues/1) Title for issue 1
                         - [#3](http://github.com/quarkusio/quarkus/issues/3) Title for issue 3
                        # Reproducer needed
                         - [#4](http://github.com/quarkusio/quarkus/issues/4) Title for issue 4
                         - [#5](http://github.com/quarkusio/quarkus/issues/5) Title for issue 5
                        # Reproducer provided
                         - [#2](http://github.com/quarkusio/quarkus/issues/2) Title for issue 2
                         - [#7](http://github.com/quarkusio/quarkus/issues/7) Title for issue 7
                        # Stale
                         - [#8](http://github.com/quarkusio/quarkus/issues/8) Title for issue 8
                         - [#9](http://github.com/quarkusio/quarkus/issues/9) Title for issue 9

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_exoticTimezone() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.of(ZoneId.of("America/Los_Angeles")),
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
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
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "gsmet",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(2, 4))),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "rick",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of())),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new LotteryReport.Serialized(drawRef.instant(), "jane",
                        Optional.empty(),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(7, 8))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(9))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(10, 11, 12)))),
                new LotteryReport.Serialized(drawRef.instant(), "jsmith",
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(25, 26))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(27, 28))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(29))),
                        Optional.of(new LotteryReport.Bucket.Serialized(List.of(30, 31, 32)))));
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
                        ## Reproducer needed
                         - quarkusio/quarkus#7
                         - quarkusio/quarkus#8
                        ## Reproducer provided
                         - quarkusio/quarkus#9
                        ## Stale
                         - quarkusio/quarkus#10
                         - quarkusio/quarkus#11
                         - quarkusio/quarkus#12

                        # jsmith
                        ## Triage
                         - quarkusio/quarkus#25
                         - quarkusio/quarkus#26
                        ## Reproducer needed
                         - quarkusio/quarkus#27
                         - quarkusio/quarkus#28
                        ## Reproducer provided
                         - quarkusio/quarkus#29
                        ## Stale
                         - quarkusio/quarkus#30
                         - quarkusio/quarkus#31
                         - quarkusio/quarkus#32

                        <!--:payload:
                        """);

        assertThat(messageFormatter.extractPayloadFromHistoryBodyMarkdown(formatted))
                .usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(lotteryReports);
    }

}