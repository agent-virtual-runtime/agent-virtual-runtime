package com.avr.server;

/** 为保持 HTTP 模块零额外依赖而提供的轻量 JSON 字段读取器。 */
final class JsonFields {
    private JsonFields() {
    }

    static String string(String json, String name, boolean required) {
        int valueStart = valueStart(json, name);
        if (valueStart < 0) {
            if (required) {
                throw new IllegalArgumentException("missing JSON field: " + name);
            }
            return null;
        }
        if (json.charAt(valueStart) != '"') {
            throw new IllegalArgumentException("JSON field must be a string: " + name);
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = valueStart + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaped) {
                value.append(unescape(character));
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return value.toString();
            } else {
                value.append(character);
            }
        }
        throw new IllegalArgumentException("unterminated JSON string: " + name);
    }

    static int integer(String json, String name, int fallback) {
        int valueStart = valueStart(json, name);
        if (valueStart < 0) {
            return fallback;
        }
        int end = valueStart;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == valueStart) {
            throw new IllegalArgumentException("JSON field must be a positive integer: " + name);
        }
        return Integer.parseInt(json.substring(valueStart, end));
    }

    private static int valueStart(String json, String name) {
        String key = "\"" + name + "\"";
        int keyStart = json.indexOf(key);
        if (keyStart < 0) {
            return -1;
        }
        int colon = json.indexOf(':', keyStart + key.length());
        if (colon < 0) {
            throw new IllegalArgumentException("invalid JSON field: " + name);
        }
        int index = colon + 1;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        if (index >= json.length()) {
            throw new IllegalArgumentException("missing JSON value: " + name);
        }
        return index;
    }

    private static char unescape(char character) {
        switch (character) {
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case '"':
                return '"';
            case '\\':
                return '\\';
            default:
                throw new IllegalArgumentException("unsupported JSON escape: " + character);
        }
    }
}
