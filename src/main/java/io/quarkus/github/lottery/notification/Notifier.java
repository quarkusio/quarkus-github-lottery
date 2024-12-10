package io.quarkus.github.lottery.notification;

import java.io.IOException;
import java.util.Optional;

import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.TopicRef;
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

    public boolean isIgnoring(String username) throws IOException {
        return notificationRepository.topic(notificationTopic(username))
                .isClosed();
    }

    public void send(LotteryReport report) throws IOException {
        if (!drawRef.equals(report.drawRef())) {
            throw new IllegalStateException("Cannot send reports for different draws; expected '" + drawRef
                    + "', got '" + report.drawRef() + "'.");
        }
        String topicSuffix = formatter.formatNotificationTopicSuffixText(report);
        String body = formatter.formatNotificationBodyMarkdown(report, notificationRepository.ref());
        notificationRepository.topic(notificationTopic(report.username()))
                .update(topicSuffix, body,
                        // When the report has no content, we update the topic's description,
                        // but we don't comment, because that would trigger an unnecessary notification.
                        report.hasContent());
    }

    private TopicRef notificationTopic(String username) {
        return TopicRef.notification(username, formatter.formatNotificationTopicText(drawRef, username));
    }
}
