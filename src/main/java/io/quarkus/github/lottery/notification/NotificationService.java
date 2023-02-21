package io.quarkus.github.lottery.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.draw.DrawRef;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.message.MessageFormatter;

@ApplicationScoped
public class NotificationService {

    public static GitHubRepository notificationRepository(GitHubService gitHubService, DrawRef drawRef,
            LotteryConfig.Notifications config) {
        return gitHubService.repository(new GitHubRepositoryRef(drawRef.repositoryRef().installationRef(),
                config.createIssues().repository()));
    }

    @Inject
    MessageFormatter formatter;
    @Inject
    GitHubService gitHubService;

    public Notifier notifier(DrawRef drawRef, LotteryConfig.Notifications config) {
        GitHubRepository notificationRepo = notificationRepository(gitHubService, drawRef, config);
        // TODO check that the repo exists and we have access to it right now, to fail fast?
        //  Might be useful for config linting as well.
        return new Notifier(formatter, drawRef, notificationRepo);
    }

}
