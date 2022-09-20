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
    private final GitHubRepository targetRepo;

    public Notifier(MessageFormatter formatter, GitHubRepository targetRepo) {
        this.formatter = formatter;
        this.targetRepo = targetRepo;
    }

    @Override
    public void close() {
        targetRepo.close();
    }

    public Optional<Instant> lastNotificationInstant(DrawRef drawRef, String username) throws IOException {
        String topic = formatter.formatNotificationTopicText(drawRef, username);
        return targetRepo.lastNotificationInstant(username, topic);
    }

    public void send(LotteryReport report) throws IOException {
        String topic = formatter.formatNotificationTopicText(report.drawRef(), report.username());
        String body = formatter.formatNotificationBodyMarkdown(report);
        targetRepo.commentOnDedicatedNotificationIssue(report.username(), topic, body);
    }

}
