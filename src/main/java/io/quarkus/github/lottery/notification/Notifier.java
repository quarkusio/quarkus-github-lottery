package io.quarkus.github.lottery.notification;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.message.MessageFormatter;

public class Notifier implements AutoCloseable {

    private final MessageFormatter formatter;
    private final DrawRef drawRef;
    private final GitHubRepository notificationRepository;

    public Notifier(MessageFormatter formatter, DrawRef drawRef, GitHubRepository notificationRepository) {
        this.formatter = formatter;
        this.drawRef = drawRef;
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void close() {
        notificationRepository.close();
    }

    public Optional<Instant> lastNotificationInstant(String username) throws IOException {
        String topic = formatter.formatNotificationTopicText(drawRef, username);
        return notificationRepository.lastNotificationInstant(username, topic);
    }

    public void send(LotteryReport report) throws IOException {
        String topic = formatter.formatNotificationTopicText(report.drawRef(), report.username());
        String body = formatter.formatNotificationBodyMarkdown(report);
        notificationRepository.commentOnDedicatedNotificationIssue(report.username(), topic, body);
    }

}
