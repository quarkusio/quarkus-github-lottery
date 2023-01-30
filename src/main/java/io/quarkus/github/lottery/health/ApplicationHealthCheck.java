package io.quarkus.github.lottery.health;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class ApplicationHealthCheck implements HealthCheck {
    @Inject
    @ConfigProperty(name = "quarkus.application.name")
    String appName;

    @Inject
    @ConfigProperty(name = "quarkus.application.version")
    String appVersion;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("application")
                .up()
                .withData("name", appName)
                .withData("version", appVersion)
                .build();
    }
}