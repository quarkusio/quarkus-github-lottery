package io.quarkus.github.lottery.notification;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.quarkus.github.lottery.config.LotteryConfig;
import io.quarkus.github.lottery.github.GitHubRepository;
import io.quarkus.github.lottery.github.GitHubRepositoryRef;
import io.quarkus.github.lottery.github.GitHubService;
import io.quarkus.github.lottery.message.MessageFormatter;

@ApplicationScoped
public class NotificationService {

    @Inject
    MessageFormatter formatter;
    @Inject
    GitHubService gitHubService;

    public Notifier notifier(GitHubRepository sourceRepo, LotteryConfig.NotificationsConfig config) {
        GitHubRepository notificationRepo = gitHubService
                .repository(new GitHubRepositoryRef(sourceRepo.ref().installationId(), config.createIssues().repository()));
        // TODO check that the repo exists and we have access to it right now, to fail fast?
        //  Might be useful for config linting as well.
        return new Notifier(formatter, notificationRepo);
    }

}
