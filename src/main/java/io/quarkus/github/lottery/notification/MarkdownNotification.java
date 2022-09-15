package io.quarkus.github.lottery.notification;

public record MarkdownNotification(String username, String topic, String body) {
}
