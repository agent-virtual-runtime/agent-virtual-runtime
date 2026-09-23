package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 一次标准化的联网搜索请求。 */
@Getter
public final class WebSearchRequest {
    private final String query;
    private final int maxResults;

    public WebSearchRequest(String query, int maxResults) {
        this.query = Objects.requireNonNull(query, "query").trim();
        if (this.query.isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (maxResults < 1 || maxResults > 10) {
            throw new IllegalArgumentException("maxResults must be between 1 and 10");
        }
        this.maxResults = maxResults;
    }
}
