package io.quarkus.github.lottery.notification;

import java.util.List;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.Issue;

@ApplicationScoped
public class NotificationFormatter {

    public MarkdownNotification formatToMarkdown(LotteryReport report) {
        // TODO produce better output, maybe with Qute templates?
        return new MarkdownNotification(report.username(),
                "Hey @" + report.username() + ", here's your report for " + report.drawRef().repositoryName()
                // TODO apply user timezone if possible
                        + " on " + report.drawRef().instant() + "\n"
                        + renderCategory("Triage", report.issuesToTriage()));
    }

    private String renderCategory(String title, List<Issue> issues) {
        StringBuilder builder = new StringBuilder("# ").append(title).append('\n');
        if (issues.isEmpty()) {
            builder.append("No issues in this category this time.\n");
        } else {
            builder.append(issues.stream()
                    .map(issue -> issue.url().toString())
                    .collect(Collectors.joining("\n - ", " - ", "\n")));
        }
        return builder.toString();
    }

}
