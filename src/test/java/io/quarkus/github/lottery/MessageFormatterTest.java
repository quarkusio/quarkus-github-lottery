package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.util.MockHelper.url;
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
import io.quarkus.github.lottery.github.Issue;
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
                new LotteryReport.Bucket(List.of()));
        assertThat(messageFormatter.formatNotificationTopicSuffixText(lotteryReport))
                .isEqualTo(" (updated 2017-11-06T06:00:00Z)");
    }

    @Test
    void formatNotificationTopicSuffixText_exoticTimezone() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.of(ZoneId.of("America/Los_Angeles")),
                new LotteryReport.Bucket(List.of()));
        assertThat(messageFormatter.formatNotificationTopicSuffixText(lotteryReport))
                .isEqualTo(" (updated 2017-11-05)");
    }

    @Test
    void formatNotificationBodyMarkdown_triage_empty() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", Optional.empty(),
                new LotteryReport.Bucket(List.of()));
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
                new LotteryReport.Bucket(List.of(
                        new Issue(1, "Hibernate ORM works too well", url(1)),
                        new Issue(3, "Hibernate Search needs Solr support", url(3)))));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06T06:00:00Z.

                        # Triage
                         - [#1](http://github.com/quarkusio/quarkus/issues/1) Hibernate ORM works too well
                         - [#3](http://github.com/quarkusio/quarkus/issues/3) Hibernate Search needs Solr support

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
                new LotteryReport.Bucket(List.of(
                        new Issue(1, "Hibernate ORM works too well", url(1)),
                        new Issue(3, "Hibernate Search needs Solr support", url(3)))));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-05.

                        # Triage
                         - [#1](http://github.com/quarkusio/quarkus/issues/1) Hibernate ORM works too well
                         - [#3](http://github.com/quarkusio/quarkus/issues/3) Hibernate Search needs Solr support

                        ---
                        <sup>If you no longer want to receive these notifications, \
                        send a pull request to the GitHub repository `quarkusio/quarkus` \
                        to remove the section relative to your username from the file \
                        `/.github/quarkus-github-lottery.yml`.</sup>
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
                        new LotteryReport.Bucket.Serialized(List.of(1, 3))),
                new LotteryReport.Serialized(drawRef.instant(), "gsmet",
                        new LotteryReport.Bucket.Serialized(List.of(2, 4))),
                new LotteryReport.Serialized(drawRef.instant(), "rick",
                        new LotteryReport.Bucket.Serialized(List.of())));
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

                        <!--:payload:
                        """);

        assertThat(messageFormatter.extractPayloadFromHistoryBodyMarkdown(formatted))
                .usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(lotteryReports);
    }

}