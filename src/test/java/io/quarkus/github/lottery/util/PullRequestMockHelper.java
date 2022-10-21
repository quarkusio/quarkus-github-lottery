package io.quarkus.github.lottery.util;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;

import io.quarkiverse.githubapp.testing.dsl.GitHubMockContext;

public class PullRequestMockHelper {

    public static PullRequestMockHelper start(GitHubMockContext context, long prId, GHRepository repoMock) {
        GHPullRequest pullRequestMock = context.pullRequest(prId);
        GHCommitPointer baseMock = stub(GHCommitPointer.class);
        when(pullRequestMock.getBase()).thenReturn(baseMock);
        when(baseMock.getRepository()).thenReturn(repoMock);
        return new PullRequestMockHelper(context, pullRequestMock, repoMock);
    }

    private final GitHubMockContext context;
    private final GHPullRequest pullRequestMock;
    private final GHRepository repoMock;

    private List<GHIssueComment> commentsMocks;
    private List<GHPullRequestCommitDetail> commitDetailsMocks;

    private PullRequestMockHelper(GitHubMockContext context,
            GHPullRequest pullRequestMock, GHRepository repoMock) {
        this.context = context;
        this.pullRequestMock = pullRequestMock;
        this.repoMock = repoMock;
    }

    public GHPullRequest pullRequestMock() {
        return pullRequestMock;
    }

    public PullRequestMockHelper commit(String message) {
        return commit(message, null);
    }

    public PullRequestMockHelper commit(String message, String sha) {
        if (commitDetailsMocks == null) {
            commitDetailsMocks = new ArrayList<>();
            PagedIterable<GHPullRequestCommitDetail> commitIterableMock = mockPagedIterable(commitDetailsMocks);
            when(pullRequestMock.listCommits()).thenReturn(commitIterableMock);
        }
        GHPullRequestCommitDetail commitDetailMock = stub(GHPullRequestCommitDetail.class);
        commitDetailsMocks.add(commitDetailMock);
        GHPullRequestCommitDetail.Commit commitMock = stub(GHPullRequestCommitDetail.Commit.class);
        when(commitDetailMock.getCommit()).thenReturn(commitMock);
        when(commitMock.getMessage()).thenReturn(message);
        if (sha != null) {
            when(commitDetailMock.getSha()).thenReturn(sha);
        }
        return this;
    }

    public PullRequestMockHelper comment(String body) throws IOException {
        if (commentsMocks == null) {
            commentsMocks = new ArrayList<>();
            PagedIterable<GHIssueComment> commitIterableMock = mockPagedIterable(commentsMocks);
            when(pullRequestMock.listComments()).thenReturn(commitIterableMock);
        }
        GHIssueComment commentMock = stub(GHIssueComment.class);
        when(commentMock.getBody()).thenReturn(body);
        commentsMocks.add(commentMock);
        return this;
    }

    @SuppressWarnings("unchecked")
    private static <T> PagedIterable<T> mockPagedIterable(List<T> contentMocks) {
        PagedIterable<T> iterableMock = mock(PagedIterable.class);
        when(iterableMock.iterator()).thenAnswer(ignored -> {
            PagedIterator<T> iteratorMock = mock(PagedIterator.class);
            Iterator<T> actualIterator = contentMocks.iterator();
            when(iteratorMock.next()).thenAnswer(ignored2 -> actualIterator.next());
            when(iteratorMock.hasNext()).thenAnswer(ignored2 -> actualIterator.hasNext());
            return iteratorMock;
        });
        return iterableMock;
    }

    private static <T> T stub(Class<T> clazz) {
        return mock(clazz, withSettings().stubOnly());
    }
}
