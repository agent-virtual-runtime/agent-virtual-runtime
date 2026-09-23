package com.avr.model.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取并绑定 YAML 中的 {@code avr.llm.openai} 配置。 */
final class OpenAiConfigYamlLoader {
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)(?::([^}]*))?}");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private OpenAiConfigYamlLoader() {
    }

    static OpenAiConfig fromClasspath(String resourceName) {
        String normalized = requireText(resourceName, "resourceName");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(normalized)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "YAML resource does not exist: " + normalized);
            }
            return read(input, normalized);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "cannot read YAML resource: " + normalized, exception);
        }
    }

    static OpenAiConfig fromPath(Path path) {
        if (path == null) {
            throw new NullPointerException("path");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return read(input, path.toString());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "cannot read YAML file: " + path, exception);
        }
    }

    private static OpenAiConfig read(InputStream input, String source) {
        try {
            Map<String, Object> root = YAML.readValue(input, MAP_TYPE);
            Map<String, Object> config = section(root, "avr", source);
            config = section(config, "llm", source);
            config = section(config, "openai", source);
            return bind(config);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "invalid YAML configuration: " + source, exception);
        }
    }

    private static OpenAiConfig bind(Map<String, Object> values) {
        OpenAiConfig.Builder builder = OpenAiConfig.builder();
        String apiUrl = string(values, "api-url", "apiUrl");
        String apiKey = string(values, "api-key", "apiKey");
        String model = string(values, "model");
        String timeout = string(values, "timeout");

        if (apiUrl != null) {
            builder.apiUrl(apiUrl);
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        }
        builder.model(requireText(model, "avr.llm.openai.model"));
        if (timeout != null) {
            builder.timeout(duration(timeout));
        }

        Number temperature = number(values, "temperature");
        if (temperature != null) {
            builder.temperature(temperature.doubleValue());
        }
        Number maxTokens = number(values, "max-tokens", "maxTokens");
        if (maxTokens != null) {
            builder.maxTokens(maxTokens.intValue());
        }
        Number maxRetries = number(values, "max-retries", "maxRetries");
        if (maxRetries != null) {
            builder.maxRetries(maxRetries.intValue());
        }
        Boolean stream = bool(values, "stream");
        if (stream != null) {
            builder.stream(stream);
        }

        for (Map.Entry<String, Object> header : map(values, "headers").entrySet()) {
            builder.header(header.getKey(), resolve(String.valueOf(header.getValue())));
        }
        return builder.build();
    }

    private static Duration duration(String value) {
        String normalized = value.trim().toLowerCase();
        try {
            if (normalized.endsWith("ms")) {
                return Duration.ofMillis(numberPart(normalized, 2));
            }
            if (normalized.endsWith("s")) {
                return Duration.ofSeconds(numberPart(normalized, 1));
            }
            if (normalized.endsWith("m")) {
                return Duration.ofMinutes(numberPart(normalized, 1));
            }
            if (normalized.endsWith("h")) {
                return Duration.ofHours(numberPart(normalized, 1));
            }
            return Duration.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "invalid duration for avr.llm.openai.timeout: " + value,
                    exception);
        }
    }

    private static long numberPart(String value, int suffixLength) {
        return Long.parseLong(value.substring(0, value.length() - suffixLength));
    }

    private static String string(Map<String, Object> values, String... names) {
        Object value = first(values, names);
        return value == null ? null : resolve(String.valueOf(value));
    }

    private static Number number(Map<String, Object> values, String... names) {
        Object value = first(values, names);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return (Number) value;
        }
        try {
            return Double.valueOf(resolve(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "configuration value must be a number: " + names[0], exception);
        }
    }

    private static Boolean bool(Map<String, Object> values, String... names) {
        Object value = first(values, names);
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        String resolved = resolve(String.valueOf(value));
        if (!"true".equalsIgnoreCase(resolved)
                && !"false".equalsIgnoreCase(resolved)) {
            throw new IllegalArgumentException(
                    "configuration value must be true or false: " + names[0]);
        }
        return Boolean.valueOf(resolved);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
            Map<String, Object> values,
            String name) {
        Object value = values.get(name);
        if (value == null) {
            return Collections.emptyMap();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    "configuration section must be a map: " + name);
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> section(
            Map<String, Object> values,
            String name,
            String source) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "missing avr.llm.openai configuration in " + source);
        }
        Object value = values.get(name);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    "missing configuration section " + name + " in " + source);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static Object first(Map<String, Object> values, String... names) {
        for (String name : names) {
            if (values.containsKey(name)) {
                return values.get(name);
            }
        }
        return null;
    }

    private static String resolve(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getProperty(name);
            if (replacement == null) {
                replacement = System.getenv(name);
            }
            if (replacement == null) {
                replacement = matcher.group(2);
            }
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "missing configuration placeholder: " + name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString().trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
