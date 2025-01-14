package io.quarkus.github.lottery.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "lottery")
public interface DeploymentConfig {

    @WithDefault("false")
    boolean dryRun();

}
