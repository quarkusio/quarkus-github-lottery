package io.quarkus.github.lottery.draw;

import static io.quarkus.github.lottery.util.MockHelper.stubIssueList;
import static io.quarkus.github.lottery.util.MockHelper.stubReportConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.github.lottery.github.GitHubInstallationRef;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;

class LotteryReportTest {

    GitHubInstallationRef installationRef;
    GitHubRepositoryRef repoRef;
    DrawRef drawRef;

    @BeforeEach
    void setup() {
        installationRef = new GitHubInstallationRef("quarkus-github-lottery", 1L);
        repoRef = new GitHubRepositoryRef(installationRef, "quarkusio/quarkus");
        drawRef = new DrawRef(repoRef, LocalDateTime.of(2017, 11, 6, 8, 0).toInstant(ZoneOffset.UTC));
    }

    // This simply tests that all buckets are returned by buckets(),
    // which should guarantee that hasContent() works correctly.
    // This should start failing when we add a new bucket to LotteryReport but forget
    // to reference it in buckets().
    @Test
    void buckets() {
        List<LotteryReport.Bucket> buckets = new ArrayList<>();
        var report = new LotteryReport(drawRef, "geoand", Optional.empty(),
                stubReportConfig(),
                newBucket(buckets),
                newBucket(buckets),
                newBucket(buckets),
                newBucket(buckets),
                newBucket(buckets),
                newBucket(buckets));
        assertThat(report.buckets()).containsExactlyInAnyOrderElementsOf(buckets);
    }

    private Optional<LotteryReport.Bucket> newBucket(List<LotteryReport.Bucket> buckets) {
        var bucket = new LotteryReport.Bucket(stubIssueList(
                // Using a different issue number for each bucket so that it's different according to equals().
                buckets.size()));
        buckets.add(bucket);
        return Optional.of(bucket);
    }

}