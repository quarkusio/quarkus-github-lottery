package io.quarkus.github.lottery.github;

import java.net.URL;

public record Issue(long id, String title, URL url) {
}
