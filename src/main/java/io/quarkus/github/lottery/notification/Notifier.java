package io.quarkus.github.lottery.notification;

import java.io.IOException;

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

    public boolean hasClosedDedicatedIssue(String username) throws IOException {
        String topic = formatter.formatNotificationTopicText(drawRef, username);
        return notificationRepository.hasClosedDedicatedIssue(username, topic);
    }

    public void send(LotteryReport report) throws IOException {
        if (!drawRef.equals(report.drawRef())) {
            throw new IllegalStateException("Cannot send reports for different draws; expected '" + drawRef
                    + "', got '" + report.drawRef() + "'.");
        }
        String topic = formatter.formatNotificationTopicText(drawRef, report.username());
        String topicSuffix = formatter.formatNotificationTopicSuffixText(report);
        String body = formatter.formatNotificationBodyMarkdown(report, notificationRepository.ref());
        notificationRepository.commentOnDedicatedIssue(report.username(), topic, topicSuffix, body);
    }
}
