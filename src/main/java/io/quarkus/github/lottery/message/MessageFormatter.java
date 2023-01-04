package io.quarkus.github.lottery.message;

import java.time.temporal.Temporal;
import java.util.List;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Qute;
import io.quarkus.qute.TemplateExtension;
import io.quarkus.qute.TemplateInstance;

@ApplicationScoped
public class MessageFormatter {

    private static final TypeReference<List<LotteryReport.Serialized>> LIST_OF_LOTTERY_REPORT_SERIALIZED = new TypeReference<>() {
    };
    private static final String PAYLOAD_BEGIN = "<!--:payload:\n";
    private static final String PAYLOAD_END = "\n:payload:-->";

    @Inject
    ObjectMapper jsonObjectMapper;

    public String formatDedicatedIssueBodyMarkdown(String topic, String latestCommentBodyMarkdown) {
        return Templates.dedicatedIssueBody(topic, latestCommentBodyMarkdown).render();
    }

    public String formatNotificationTopicText(DrawRef drawRef, String username) {
        return Qute.fmt("{}'s report for {}", username, drawRef.repositoryRef().repositoryName());
    }

    public String formatNotificationTopicSuffixText(LotteryReport report) {
        return Qute.fmt(" (updated {})", TemplateExtensions.localDate(report));
    }

    public String formatNotificationBodyMarkdown(LotteryReport report, GitHubRepositoryRef notificationRepoRef) {
        return Templates.notificationBody(report, notificationRepoRef.repositoryName()).render();
    }

    public String formatHistoryTopicText(DrawRef drawRef) {
        return Qute.fmt("Lottery history for {}", drawRef.repositoryRef().repositoryName());
    }

    public String formatHistoryBodyMarkdown(DrawRef drawRef, List<LotteryReport.Serialized> reports)
            throws JsonProcessingException {
        return Templates.historyBody(drawRef, reports,
                PAYLOAD_BEGIN + jsonObjectMapper.writeValueAsString(reports) + PAYLOAD_END)
                .render();
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

    @CheckedTemplate
    private static class Templates {

        public static native TemplateInstance dedicatedIssueBody(String topic, String latestCommentBodyMarkdown);

        public static native TemplateInstance historyBody(DrawRef drawRef, List<LotteryReport.Serialized> reports,
                String payload);

        public static native TemplateInstance notificationBody(LotteryReport report, String notificationRepositoryName);

    }

    @TemplateExtension
    @SuppressWarnings("unused")
    private static class TemplateExtensions {

        static String asMarkdownQuote(String string) {
            return string.lines().map(s -> "> " + s).collect(Collectors.joining("\n"));
        }

        static Temporal localDate(LotteryReport report) {
            var instant = report.drawRef().instant();
            return report.timezone().map(zone -> (Temporal) instant.atZone(zone).toLocalDate())
                    // Degrade gracefully to displaying a locale-independent instant.
                    .orElse(instant);
        }

        static String repositoryName(LotteryReport report) {
            return repositoryName(report.drawRef());
        }

        static String repositoryName(DrawRef drawRef) {
            return drawRef.repositoryRef().repositoryName();
        }

    }

    @TemplateExtension(namespace = "github")
    @SuppressWarnings("unused")
    private static class TemplateGitHubExtensions {

        static String configPath() {
            return "/.github/" + LotteryConfig.FILE_NAME;
        }

    }

}
