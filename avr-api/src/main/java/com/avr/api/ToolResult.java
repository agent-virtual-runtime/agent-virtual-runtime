package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 一次工具调用的成功或失败结果。 */
@Getter
public final class ToolResult {
    private final boolean success;
    private final String content;
    private final Integer rowCount;
    private final ToolDisplayType displayType;

    private ToolResult(
            boolean success,
            String content,
            Integer rowCount,
            ToolDisplayType displayType) {
        this.success = success;
        this.content = Objects.requireNonNull(content, "content");
        if (rowCount != null && rowCount < 0) {
            throw new IllegalArgumentException("rowCount must not be negative");
        }
        this.rowCount = rowCount;
        this.displayType = displayType;
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, null);
    }

    /** 创建带展示行数的成功结果，供前端按工具协议展示摘要。 */
    public static ToolResult success(String content, int rowCount) {
        return new ToolResult(true, content, rowCount, null);
    }

    public static ToolResult success(String content, ToolDisplayType displayType) {
        return new ToolResult(true, content, null,
                Objects.requireNonNull(displayType, "displayType"));
    }

    /** 单次结果可覆盖工具默认展示类型，例如同一工具的查询结果与编辑结果。 */
    public static ToolResult success(
            String content,
            int rowCount,
            ToolDisplayType displayType) {
        return new ToolResult(true, content, rowCount, displayType);
    }

    public static ToolResult failure(String content) {
        return new ToolResult(false, content, null, null);
    }

}
