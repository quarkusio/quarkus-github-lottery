package io.quarkus.github.lottery.message;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;

@ApplicationScoped
public class MessageFormatter {

    private static final Collector<CharSequence, ?, String> MARKDOWN_BULLET_LIST_COLLECTOR = Collectors.joining("\n - ", " - ",
            "\n");
    private static final TypeReference<List<LotteryReport.Serialized>> LIST_OF_LOTTERY_REPORT_SERIALIZED = new TypeReference<>() {
    };
    private static final String PAYLOAD_BEGIN = "<!--:payload:\n";
    private static final String PAYLOAD_END = "\n:payload:-->";

    @Inject
    ObjectMapper jsonObjectMapper;

    public String formatNotificationTopicText(DrawRef drawRef, String username) {
        return username + "'s report for " + drawRef.repositoryRef().repositoryName();
    }

    public String formatNotificationBodyMarkdown(LotteryReport report) {
        // TODO produce better output, maybe with Qute templates?
        String repoName = report.drawRef().repositoryRef().repositoryName();
        LocalDate date = report.drawRef().instant().atZone(report.timezone()).toLocalDate();
        return "Hey @" + report.username() + ", here's your report for " + repoName + " on " + date + ".\n"
                + formatNotificationBodyBucket("Triage", report.triage());
    }

    private String formatNotificationBodyBucket(String title, LotteryReport.Bucket bucket) {
        var issues = bucket.issues();
        StringBuilder builder = new StringBuilder("# ").append(title).append('\n');
        if (issues.isEmpty()) {
            builder.append("No issues in this category this time.\n");
        } else {
            builder.append(issues.stream()
                    .map(issue -> issue.url().toString())
                    .collect(MARKDOWN_BULLET_LIST_COLLECTOR));
        }
        return builder.toString();
    }

    public String formatHistoryTopicText(DrawRef drawRef) {
        return "Lottery history for " + drawRef.repositoryRef().repositoryName();
    }

    public String formatHistoryBodyMarkdown(DrawRef drawRef, List<LotteryReport.Serialized> reports)
            throws JsonProcessingException {
        // TODO produce better output, maybe with Qute templates?
        return "Here are the reports for " + drawRef.repositoryRef().repositoryName() + " on " + drawRef.instant() + ".\n\n"
                + reports.stream().map(report -> this.formatHistoryBodyReport(drawRef, report))
                        .collect(Collectors.joining("\n"))
                + "\n" + PAYLOAD_BEGIN + jsonObjectMapper.writeValueAsString(reports) + PAYLOAD_END;
    }

    private String formatHistoryBodyReport(DrawRef drawRef, LotteryReport.Serialized report) {
        StringBuilder builder = new StringBuilder("# ").append(report.username()).append('\n');
        builder.append(formatHistoryBodyBucket(drawRef, "Triage", report.triage()));
        return builder.toString();
    }

    private String formatHistoryBodyBucket(DrawRef drawRef, String title, LotteryReport.Bucket.Serialized bucket) {
        String repoName = drawRef.repositoryRef().repositoryName();
        StringBuilder builder = new StringBuilder("## ").append(title).append('\n');
        var issueNumbers = bucket.issueNumbers();
        if (!issueNumbers.isEmpty()) {
            builder.append(issueNumbers.stream()
                    .map(issueId -> repoName + "#" + issueId)
                    .collect(MARKDOWN_BULLET_LIST_COLLECTOR));
        }
        return builder.toString();
    }

    public List<LotteryReport.Serialized> extractPayloadFromHistoryBodyMarkdown(String body) throws JsonProcessingException {
        int beginIndex = body.indexOf(PAYLOAD_BEGIN);
        int endIndex = body.lastIndexOf(PAYLOAD_END);
        if (beginIndex < 0 || endIndex < 0) {
            throw new IllegalArgumentException("Cannot extract payload from " + body);
        }
        return jsonObjectMapper.readValue(body.substring(beginIndex + PAYLOAD_BEGIN.length(), endIndex),
                LIST_OF_LOTTERY_REPORT_SERIALIZED);
    }

}
