package com.avr.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAI 兼容模型配置。 */
@ConfigurationProperties(prefix = "avr.llm.openai")
public class AvrLlmProperties {
    /** 模型服务基础地址或完整 Chat Completions 地址。 */
    private String apiUrl = "https://api.openai.com/v1";
    /** 模型服务 API Key。 */
    private String apiKey;
    /** 调用的模型名称。 */
    private String model;
    /** 是否使用 SSE 流式响应。 */
    private boolean stream = true;
    /** 模型采样温度。 */
    private Double temperature;
    /** 单次响应的最大 Token 数。 */
    private Integer maxTokens;
    /** 单次模型请求超时时间。 */
    private Duration timeout = Duration.ofMinutes(2);
    /** 发送给兼容服务的附加请求头。 */
    private Map<String, String> headers = new LinkedHashMap<String, String>();

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
}
