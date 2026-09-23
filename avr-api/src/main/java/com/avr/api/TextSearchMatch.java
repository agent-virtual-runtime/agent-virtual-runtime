package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 文本检索命中的行号和内容摘要。 */
@Getter
public final class TextSearchMatch {
    private final int line;
    private final String text;

    public TextSearchMatch(int line, String text) {
        this.line = line;
        this.text = Objects.requireNonNull(text, "text");
    }
}
