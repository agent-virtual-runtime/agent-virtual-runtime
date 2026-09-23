package com.avr.examples;

import com.avr.api.WebSearchMode;
import lombok.Getter;

import java.util.Objects;

/** 前端可选模型及其联网搜索能力。 */
@Getter
public final class ChatModelProfile {
    private final String id;
    private final String name;
    private final WebSearchMode webSearchMode;
    private final boolean webSearchSupported;

    public ChatModelProfile(
            String id,
            String name,
            WebSearchMode webSearchMode,
            boolean webSearchSupported) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.webSearchMode = Objects.requireNonNull(webSearchMode, "webSearchMode");
        this.webSearchSupported = webSearchSupported;
    }

    /** 返回供 JSON API 使用的小写模式名。 */
    public String getWebSearch() {
        return webSearchMode.name().toLowerCase();
    }
}
