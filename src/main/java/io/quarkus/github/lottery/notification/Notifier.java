package io.quarkus.github.lottery.notification;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;

public class Notifier implements AutoCloseable {

    private final NotificationFormatter formatter;
    private final GitHubRepository targetRepo;

    public Notifier(NotificationFormatter formatter, GitHubRepository targetRepo) {
        this.formatter = formatter;
        this.targetRepo = targetRepo;
    }

    @Override
    public void close() {
        targetRepo.close();
    }

    public Optional<Instant> lastNotificationInstant(DrawRef drawRef, String username) throws IOException {
        String topic = formatter.formatToTopicText(drawRef, username);
        return targetRepo.lastNotificationInstant(username, topic);
    }

    public void send(LotteryReport report) throws IOException {
        String topic = formatter.formatToTopicText(report.drawRef(), report.username());
        MarkdownNotification notification = formatter.formatToMarkdown(report);
        targetRepo.commentOnDedicatedNotificationIssue(notification.username(), topic,
                notification.body());
    }

}
