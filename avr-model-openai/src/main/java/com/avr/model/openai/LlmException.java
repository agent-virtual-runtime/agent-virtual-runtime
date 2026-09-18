package com.avr.model.openai;

/** 调用模型端点时发生的 HTTP 或协议异常。 */
public final class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
