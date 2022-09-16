package io.quarkus.github.lottery;

import static io.quarkus.github.lottery.MockHelper.url;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.Issue;
import io.quarkus.github.lottery.notification.MarkdownNotification;
import io.quarkus.github.lottery.notification.NotificationFormatter;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@ExtendWith(MockitoExtension.class)
public class NotificationFormatterTest {

    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @Inject
    NotificationFormatter notificationFormatter;

    @BeforeEach
    void setup() {
        repoRef = new GitHubRepositoryRef(1L, "quarkusio/quarkus");
        var now = LocalDateTime.of(2017, 11, 6, 6, 0).toInstant(ZoneOffset.UTC);
        drawRef = new DrawRef(repoRef.repositoryName(), now);
    }

    @Test
    void formatToTopicText() {

        assertThat(notificationFormatter.formatToTopicText(drawRef, "yrodiere"))
                .isEqualTo("yrodiere's report for quarkusio/quarkus");
    }

    @Test
    void formatToMarkdown_empty() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(List.of()));
        assertThat(notificationFormatter.formatToMarkdown(lotteryReport))
                .isEqualTo(new MarkdownNotification("yrodiere",
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06.
                                # Triage
                                No issues in this category this time.
                                """));
    }

    @Test
    void formatToMarkdown_simple() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", ZoneOffset.UTC,
                new LotteryReport.Bucket(List.of(
                        new Issue(1, "Hibernate ORM works too well", url(1)),
                        new Issue(3, "Hibernate Search needs Solr support", url(3)))));
        assertThat(notificationFormatter.formatToMarkdown(lotteryReport))
                .isEqualTo(new MarkdownNotification("yrodiere",
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-06.
                                # Triage
                                 - http://github.com/quarkus/issues/1001
                                 - http://github.com/quarkus/issues/1003
                                """));
    }

    @Test
    void formatToMarkdown_exoticTimezone() {
        var lotteryReport = new LotteryReport(drawRef, "yrodiere", ZoneId.of("America/Los_Angeles"),
                new LotteryReport.Bucket(List.of(
                        new Issue(1, "Hibernate ORM works too well", url(1)),
                        new Issue(3, "Hibernate Search needs Solr support", url(3)))));
        assertThat(notificationFormatter.formatToMarkdown(lotteryReport))
                .isEqualTo(new MarkdownNotification("yrodiere",
                        """
                                Hey @yrodiere, here's your report for quarkusio/quarkus on 2017-11-05.
                                # Triage
                                 - http://github.com/quarkus/issues/1001
                                 - http://github.com/quarkus/issues/1003
                                """));
    }

}