package io.quarkus.github.lottery.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "lottery")
public interface DeploymentConfig {

    @WithDefault("false")
    boolean dryRun();

    /**
     * @return How many issues/PRs to retrieve from GitHub at a time, at a minimum, when drawing.
     *         The effective chunk size may be higher, depending on how many issues are requested by lottery participants.
     *         The chunk size may be higher than GitHub API limits, in which case multiple API calls will be done.
     *         The higher this number, the more GitHub API calls are needed,
     *         but also the more random issue selection is,
     *         because each chunk is (independently) shuffled before being processed.
     */
    @WithDefault("20")
    int minChunkSize();

    /**
     * @return How many issues/PRs to retrieve from GitHub at a time, at a maximum, when drawing.
     *         The effective chunk size may be lower, depending on how many issues are requested by lottery participants.
     *         If set to 1, chunking and issue order randomization are disabled.
     * @see #minChunkSize()
     */
    @WithDefault("40")
    int maxChunkSize();

    /**
     * @return How many issues/PRs to retrieve from GitHub at a time, when performing a GitHub API call.
     *         Cannot be higher than 100, per GitHub API limits.
     *         Can be set independently of the chunk size:
     *         when smaller than the chunk size, multiple API calls will be needed per chunk;
     *         when larger than the chunk size, one API call may yield more issues than necessary,
     *         which might then be used for the next chunk.
     *         Best set to something slightly larger than the chunk size,
     *         to account for in-memory processing that may filter out some of the returned issues.
     */
    @WithDefault("60")
    int pageSize();

}
