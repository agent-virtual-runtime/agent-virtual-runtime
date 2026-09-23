package com.avr.model.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiConfigYamlLoaderTest {
    @Test
    void rejectsUnresolvedApiKeyPlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiConfig.builder()
                        .model("test-model")
                        .apiKey("${OPENAI_API_KEY}")
                        .build());
    }

    @Test
    void loadsYamlAndResolvesPlaceholders() {
        String previousKey = System.getProperty("test.api.key");
        String previousUrl = System.getProperty("test.api.url");
        try {
            System.setProperty("test.api.key", "secret-value");
            System.setProperty("test.api.url", "https://models.example/v1");

            OpenAiConfig config = OpenAiConfig.fromYaml("openai-config.yml");

            assertEquals(
                    "https://models.example/v1/chat/completions",
                    config.getEndpoint().toString());
            assertEquals("secret-value", config.getApiKey());
            assertEquals("test-model", config.getModel());
            assertEquals(Duration.ofSeconds(90), config.getTimeout());
            assertEquals(0.25, config.getTemperature());
            assertEquals(2048, config.getMaxTokens());
            assertEquals(2, config.getMaxRetries());
            assertFalse(config.isStream());
            assertEquals("default-tenant", config.getHeaders().get("X-Test-Tenant"));
        } finally {
            restore("test.api.key", previousKey);
            restore("test.api.url", previousUrl);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
