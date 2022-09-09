package io.quarkus.github.lottery.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.quarkiverse.githubapp.GitHubClientProvider;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

@ApplicationScoped
public class GitHubService {

    @Inject
    GitHubClientProvider clientProvider;

    public List<InstallationRef> listInstallations() throws IOException {
        List<InstallationRef> result = new ArrayList<>();
        GitHub client = clientProvider.getApplicationClient();
        for (GHAppInstallation installation : client.getApp().listInstallations().withPageSize(20)) {
            for (GHRepository repository : installation.listRepositories().withPageSize(20)) {
                result.add(new InstallationRef(installation.getId(), repository.getFullName()));
            }
        }
        return result;
    }

    public Installation installation(InstallationRef ref) {
        return new Installation(clientProvider, ref);
    }
}
