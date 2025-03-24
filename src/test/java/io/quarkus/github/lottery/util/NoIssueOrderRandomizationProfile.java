package io.quarkus.github.lottery.util;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class NoIssueOrderRandomizationProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("lottery.min-chunk-size", "1",
                "lottery.max-chunk-size", "1");
    }
}
