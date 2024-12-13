package io.quarkus.github.lottery.util;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueEvent;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.PagedSearchIterable;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

import io.quarkiverse.githubapp.testing.dsl.GitHubMockContext;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.draw.LotteryReport;
import io.quarkus.github.lottery.github.Issue;

public class MockHelper {

    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <T> PagedSearchIterable<T> mockPagedIterable(T... contentMocks) {
        PagedSearchIterable<T> iterableMock = mock(PagedSearchIterable.class,
                withSettings().stubOnly().strictness(Strictness.LENIENT).defaultAnswer(Answers.RETURNS_SELF));
        when(iterableMock.spliterator()).thenAnswer(ignored -> List.of(contentMocks).spliterator());
        when(iterableMock.iterator()).thenAnswer(ignored -> {
            PagedIterator<T> iteratorMock = mock(PagedIterator.class, withSettings().stubOnly().strictness(Strictness.LENIENT));
            Iterator<T> actualIterator = List.of(contentMocks).iterator();
            when(iteratorMock.next()).thenAnswer(ignored2 -> actualIterator.next());
            when(iteratorMock.hasNext()).thenAnswer(ignored2 -> actualIterator.hasNext());
            return iteratorMock;
        });
        return iterableMock;
    }

    public static URL url(int issueNumber) {
        try {
            return new URL("http://github.com/quarkusio/quarkus/issues/" + issueNumber);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Issue> stubIssueList(int... numbers) {
        return IntStream.of(numbers).mapToObj(MockHelper::stubIssue).toList();
    }

    private static Issue stubIssue(int number) {
        return new Issue(number, "Title for issue " + number, url(number));
    }

    public static LotteryReport.Config stubReportConfig(String... maintenanceLabels) {
        return new LotteryReport.Config("triage/needs-triage",
                new LinkedHashSet<>(List.of("triage/needs-reproducer", "triage/needs-feedback")),
                new LinkedHashSet<>(List.of(maintenanceLabels)));
    }

    public static LotteryReport stubReportTriage(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            List<Issue> triage) {
        return stubReport(drawRef, username, timezone, stubReportConfig(),
                Optional.of(triage),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static LotteryReport stubReportCreated(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            List<String> maintenanceLabels,
            List<Issue> created) {
        return stubReport(drawRef, username, timezone, stubReportConfig(maintenanceLabels.toArray(String[]::new)),
                Optional.empty(),
                Optional.of(created),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static LotteryReport stubReportFeedback(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            List<String> maintenanceLabels,
            List<Issue> feedbackNeeded,
            List<Issue> feedbackProvided) {
        return stubReport(drawRef, username, timezone, stubReportConfig(maintenanceLabels.toArray(String[]::new)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(feedbackNeeded),
                Optional.of(feedbackProvided),
                Optional.empty(),
                Optional.empty());
    }

    public static LotteryReport stubReportStale(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            List<Issue> stale) {
        return stubReport(drawRef, username, timezone, stubReportConfig(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(stale),
                Optional.empty());
    }

    public static LotteryReport stubReportMaintenance(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            List<String> maintenanceLabels,
            List<Issue> created,
            List<Issue> feedbackNeeded,
            List<Issue> feedbackProvided,
            List<Issue> stale) {
        return stubReport(drawRef, username, timezone, stubReportConfig(maintenanceLabels.toArray(String[]::new)),
                Optional.empty(),
                Optional.of(created),
                Optional.of(feedbackNeeded),
                Optional.of(feedbackProvided),
                Optional.of(stale),
                Optional.empty());
    }

    public static LotteryReport stubReportStewardship(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            List<Issue> stewardship) {
        return stubReport(drawRef, username, timezone, stubReportConfig(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(stewardship));
    }

    public static LotteryReport stubReport(DrawRef drawRef,
            String username,
            Optional<ZoneId> timezone,
            LotteryReport.Config config,
            Optional<List<Issue>> triage,
            Optional<List<Issue>> created,
            Optional<List<Issue>> feedbackNeeded,
            Optional<List<Issue>> feedbackProvided,
            Optional<List<Issue>> stale,
            Optional<List<Issue>> stewardship) {
        return new LotteryReport(drawRef, username, timezone, config,
                triage.map(LotteryReport.Bucket::new),
                created.map(LotteryReport.Bucket::new),
                feedbackNeeded.map(LotteryReport.Bucket::new),
                feedbackProvided.map(LotteryReport.Bucket::new),
                stale.map(LotteryReport.Bucket::new),
                stewardship.map(LotteryReport.Bucket::new));
    }

    public static GHIssue mockIssueForLottery(GitHubMockContext context, int number)
            throws IOException {
        GHIssue mock = context.issue(10000L + number);
        when(mock.getNumber()).thenReturn(number);
        when(mock.getTitle()).thenReturn("Title for issue " + number);
        when(mock.getHtmlUrl()).thenReturn(url(number));
        return mock;
    }

    public static GHIssue mockIssueForLottery(GitHubMockContext context, int number, GHUser reporter)
            throws IOException {
        GHIssue mock = context.issue(10000L + number);
        when(mock.getNumber()).thenReturn(number);
        when(mock.getTitle()).thenReturn("Title for issue " + number);
        when(mock.getHtmlUrl()).thenReturn(url(number));
        when(mock.getUser()).thenReturn(reporter);
        return mock;
    }

    public static GHIssue mockIssueForLotteryFilteredOutByRepository(GitHubMockContext context, int number)
            throws IOException {
        GHIssue mock = context.issue(10000L + number);
        return mock;
    }

    public static GHIssue mockIssueForLotteryFilteredOutByRepository(GitHubMockContext context, int number,
            GHUser reporter)
            throws IOException {
        GHIssue mock = context.issue(10000L + number);
        when(mock.getUser()).thenReturn(reporter);
        return mock;
    }

    public static GHIssue mockIssueForNotification(GitHubMockContext context, long id, String title) {
        GHIssue mock = context.issue(id);
        when(mock.getTitle()).thenReturn(title);
        return mock;
    }

    public static GHIssueEvent mockIssueEvent(String type) {
        GHIssueEvent mock = Mockito.mock(GHIssueEvent.class);
        when(mock.getEvent()).thenReturn(type);
        return mock;
    }

    public static GHLabel mockLabel(String name) {
        GHLabel mock = Mockito.mock(GHLabel.class);
        when(mock.getName()).thenReturn(name);
        return mock;
    }

    public static GHPullRequestFileDetail mockGHPullRequestFileDetail(String filename) {
        GHPullRequestFileDetail mock = mock(GHPullRequestFileDetail.class);
        lenient().when(mock.getFilename()).thenReturn(filename);
        return mock;
    }

    public static GHUser mockUserForInspectedComments(GitHubMockContext context, GHRepository repositoryMock,
            long id, String login)
            throws IOException {
        return mockUserForInspectedComments(context, repositoryMock, id, login, null);
    }

    public static GHUser mockUserForInspectedComments(GitHubMockContext context, GHRepository repositoryMock,
            long id, String login, GHPermissionType permissionType)
            throws IOException {
        GHUser mock = context.ghObject(GHUser.class, id);
        when(mock.getLogin()).thenReturn(login);
        if (permissionType != null) {
            when(repositoryMock.getPermission(login)).thenReturn(permissionType);
        }
        return mock;
    }

    public static GHIssueComment mockIssueComment(GitHubMockContext context, long id, GHUser author)
            throws IOException {
        return mockIssueComment(context, id, author, null);
    }

    public static GHIssueComment mockIssueComment(GitHubMockContext context, long id, GHUser author, String body)
            throws IOException {
        GHIssueComment mock = context.issueComment(id);
        when(mock.getUser()).thenReturn(author);
        if (body != null) {
            when(mock.getBody()).thenReturn(body);
        }
        return mock;
    }

}
