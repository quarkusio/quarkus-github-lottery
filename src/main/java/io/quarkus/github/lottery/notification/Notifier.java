package io.quarkus.github.lottery.notification;

import java.io.IOException;

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

    public void send(LotteryReport report) throws IOException {
        MarkdownNotification notification = formatter.formatToMarkdown(report);
        targetRepo.commentOnDedicatedNotificationIssue(notification.username(), notification.topic(),
                notification.body());
    }

}
