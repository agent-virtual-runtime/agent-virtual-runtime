package com.avr.model.openai;

import com.avr.api.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一些 OpenAI 兼容服务不会在流式工具调用中返回 id。 */
class OpenAiSseResponseParserCompatibilityTest {
    @Test
    void generatesIdForStreamedToolCallWithoutOne() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"file.write\","
                + "\"arguments\":\"{\\\"path\\\":\\\"/workspace/test.txt\\\","
                + "\\\"content\\\":\\\"ok\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";

        LlmResponse response = new OpenAiSseResponseParser(new ObjectMapper())
                .parse(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                        delta -> { });

        assertEquals(1, response.getToolCalls().size());
        assertTrue(response.getToolCalls().get(0).getId().startsWith("call_avr_"));
        assertEquals("file.write", response.getToolCalls().get(0).getName());
        assertEquals("ok", response.getToolCalls().get(0).requireString("content"));
    }

    @Test
    void sendsReasoningContentOnItsOwnChannel() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"checking facts\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"final answer\"},"
                + "\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        LlmResponse response = new OpenAiSseResponseParser(new ObjectMapper())
                .parse(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                        text::append, reasoning::append);

        assertEquals("final answer", response.getText());
        assertEquals("final answer", text.toString());
        assertEquals("checking facts", reasoning.toString());
    }
}
