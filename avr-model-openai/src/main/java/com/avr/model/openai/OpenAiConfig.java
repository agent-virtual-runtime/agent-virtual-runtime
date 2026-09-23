package com.avr.model.openai;

import lombok.Getter;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** OpenAI 兼容 Chat Completions 端点配置。 */
@Getter
public final class OpenAiConfig {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Double temperature;
    private final Integer maxTokens;
    private final int maxRetries;
    private final boolean stream;
    private final Map<String, String> headers;

    private OpenAiConfig(Builder builder) {
        this.endpoint = resolveEndpoint(builder.apiUrl);
        this.apiKey = unresolvedPlaceholder(builder.apiKey, "apiKey");
        this.model = requireText(builder.model, "model");
        this.timeout = builder.timeout;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.maxRetries = builder.maxRetries;
        this.stream = builder.stream;
        this.headers = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(builder.headers));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 从类路径 {@code application.yml} 加载 {@code avr.llm.openai}。 */
    public static OpenAiConfig fromYaml() {
        return OpenAiConfigYamlLoader.fromClasspath("application.yml");
    }

    /** 从指定类路径 YAML 资源加载 {@code avr.llm.openai}。 */
    public static OpenAiConfig fromYaml(String resourceName) {
        return OpenAiConfigYamlLoader.fromClasspath(resourceName);
    }

    /** 从外部 YAML 文件加载 {@code avr.llm.openai}。 */
    public static OpenAiConfig fromYaml(Path path) {
        return OpenAiConfigYamlLoader.fromPath(path);
    }

    private static URI resolveEndpoint(String apiUrl) {
        String value = requireText(apiUrl, "apiUrl");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.endsWith("/chat/completions")) {
            value += "/chat/completions";
        }
        URI endpoint = URI.create(value);
        if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("apiUrl must use http or https");
        }
        return endpoint;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return unresolvedPlaceholder(value, name);
    }

    private static String unresolvedPlaceholder(String value, String name) {
        if (value != null && value.contains("${")) {
            throw new IllegalArgumentException(
                    name + " contains an unresolved configuration placeholder");
        }
        return value;
    }

    /** 使用代码方式构建模型配置。 */
    public static final class Builder {
        private String apiUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model;
        private Duration timeout = Duration.ofMinutes(2);
        private Double temperature;
        private Integer maxTokens;
        private int maxRetries = 2;
        private boolean stream = true;
        private final Map<String, String> headers = new LinkedHashMap<String, String>();

        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            if (maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            this.maxTokens = maxTokens;
            return this;
        }

        /** 仅用于模型端返回 429/5xx 时的额外尝试次数；设为 0 可关闭。 */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0 || maxRetries > 5) {
                throw new IllegalArgumentException("maxRetries must be between 0 and 5");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(requireText(name, "header name"),
                    requireText(value, "header value"));
            return this;
        }

        public OpenAiConfig build() {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            return new OpenAiConfig(this);
        }
    }
}
