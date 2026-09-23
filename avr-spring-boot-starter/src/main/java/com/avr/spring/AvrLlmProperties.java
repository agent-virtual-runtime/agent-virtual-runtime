package com.avr.spring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAI 兼容模型配置。 */
@ConfigurationProperties(prefix = "avr.llm.openai")
@Getter
@Setter
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
    /** 模型端返回 429/500/502/503/504 时，最多额外重试的次数。 */
    private int maxRetries = 2;
    /** 单次模型请求超时时间。 */
    private Duration timeout = Duration.ofMinutes(2);
    /** 发送给兼容服务的附加请求头。 */
    private Map<String, String> headers = new LinkedHashMap<String, String>();

}
