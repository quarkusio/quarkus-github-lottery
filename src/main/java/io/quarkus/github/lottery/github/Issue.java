package io.quarkus.github.lottery.github;

import java.net.URL;

public record Issue(int number, String title, String author, URL url) {
}
