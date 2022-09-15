package io.quarkus.github.lottery.notification;

import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;

import io.quarkus.github.lottery.draw.LotteryReport;

@ApplicationScoped
public class NotificationFormatter {

    public MarkdownNotification formatToMarkdown(LotteryReport report) {
        // TODO produce better output, maybe with Qute templates?
        return new MarkdownNotification(report.username(),
                "Hey @" + report.username() + ", here's your report for " + report.drawRef().repositoryName()
                // TODO apply user timezone if possible
                        + " on " + report.drawRef().instant() + "\n"
                        + "# Triage\n"
                        + report.issuesToTriage().stream()
                                .map(issue -> issue.url().toString())
                                .collect(Collectors.joining("\n - ", " - ", "\n")));
    }

}
