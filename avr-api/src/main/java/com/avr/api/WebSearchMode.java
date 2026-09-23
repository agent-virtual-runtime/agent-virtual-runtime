package com.avr.api;

/** 定义一次运行如何获得联网搜索能力。 */
public enum WebSearchMode {
    /** 不向模型提供联网搜索能力。 */
    NONE,

    /** 通过 AVR 注册的 {@code web.search} Function Tool 搜索。 */
    RUNTIME,

    /** 由模型厂商的原生协议托管搜索。 */
    NATIVE;

    /** 解析配置值；空值使用安全的关闭状态。 */
    public static WebSearchMode fromConfig(String value) {
        if (value == null || value.trim().isEmpty()) {
            return NONE;
        }
        for (WebSearchMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "web search mode must be one of: none, runtime, native");
    }
}
