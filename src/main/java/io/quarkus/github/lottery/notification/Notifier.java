package io.quarkus.github.lottery.notification;

import java.io.IOException;

import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;

public class Notifier {

    private final NotificationFormatter formatter;
    private final GitHubRepository targetRepo;

    public Notifier(NotificationFormatter formatter, GitHubRepository targetRepo) {
        this.formatter = formatter;
        this.targetRepo = targetRepo;
    }

    public void send(LotteryReport report) throws IOException {
        MarkdownNotification notification = formatter.formatToMarkdown(report);
        targetRepo.commentOnDedicatedNotificationIssue(notification.username(), notification.body());
    }

}
