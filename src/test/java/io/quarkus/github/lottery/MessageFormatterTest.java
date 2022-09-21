package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.MockHelper.url;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

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
    void formatNotificationBodyMarkdown_empty() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(List.of()));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06.
                        # Triage
                        No issues in this category this time.
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_simple() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(List.of(
                        new Issue(1, "Hibernate ORM works too well", url(1)),
                        new Issue(3, "Hibernate Search needs Solr support", url(3)))));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06.
                        # Triage
                         - http://github.com/quarkus/issues/1
                         - http://github.com/quarkus/issues/3
                        """);
    }

    @Test
    void formatNotificationBodyMarkdown_exoticTimezone() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", ZoneId.of("America/Los_Angeles"),
                new LotteryReport.Bucket(List.of(
                        new Issue(1, "Hibernate ORM works too well", url(1)),
                        new Issue(3, "Hibernate Search needs Solr support", url(3)))));
        assertThat(messageFormatter.formatNotificationBodyMarkdown(lotteryReport))
                .isEqualTo("""
                        Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-05.
                        # Triage
                         - http://github.com/quarkus/issues/1
                         - http://github.com/quarkus/issues/3
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