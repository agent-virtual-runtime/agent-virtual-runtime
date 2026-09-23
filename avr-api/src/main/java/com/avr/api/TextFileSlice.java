package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 文本文件的一段带行号范围的内容。 */
@Getter
public final class TextFileSlice {
    private final String content;
    private final int startLine;
    private final int endLine;
    private final int totalLines;
    private final boolean truncated;

    public TextFileSlice(
            String content,
            int startLine,
            int endLine,
            int totalLines,
            boolean truncated) {
        this.content = Objects.requireNonNull(content, "content");
        this.startLine = startLine;
        this.endLine = endLine;
        this.totalLines = totalLines;
        this.truncated = truncated;
    }
}
