package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 一条与搜索供应商无关的搜索结果。 */
@Getter
public final class WebSearchItem {
    private final String title;
    private final String url;
    private final String snippet;
    private final String publishedAt;

    public WebSearchItem(String title, String url, String snippet) {
        this(title, url, snippet, null);
    }

    public WebSearchItem(
            String title,
            String url,
            String snippet,
            String publishedAt) {
        this.title = Objects.requireNonNull(title, "title");
        this.url = Objects.requireNonNull(url, "url");
        this.snippet = snippet == null ? "" : snippet;
        this.publishedAt = publishedAt;
    }
}
