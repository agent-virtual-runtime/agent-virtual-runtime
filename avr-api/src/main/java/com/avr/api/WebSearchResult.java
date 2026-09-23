package com.avr.api;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 一次标准化的联网搜索结果。 */
@Getter
public final class WebSearchResult {
    private final String query;
    private final List<WebSearchItem> items;

    public WebSearchResult(String query, List<WebSearchItem> items) {
        this.query = Objects.requireNonNull(query, "query");
        Objects.requireNonNull(items, "items");
        this.items = Collections.unmodifiableList(
                new ArrayList<WebSearchItem>(items));
    }
}
