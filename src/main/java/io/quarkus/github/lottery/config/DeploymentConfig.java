package io.quarkus.github.lottery.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "lottery")
public interface DeploymentConfig {

    @WithDefault("false")
    boolean dryRun();

    /**
     * @return How many issues to retrieve from GitHub at a time, when drawing.
     *         Can be higher than GitHub API limits, in which case multiple API calls will be done.
     *         The higher this number, the more GitHub API calls are needed,
     *         but also the more random issue selection is,
     *         because each chunk is shuffled before being processed.
     */
    @WithDefault("100")
    int chunkSize();

}
