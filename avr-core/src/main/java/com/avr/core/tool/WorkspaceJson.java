package com.avr.core.tool;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/** Core 工具使用的轻量 JSON 序列化器，避免引入额外运行时依赖。 */
final class WorkspaceJson {
    private WorkspaceJson() {
    }

    static String encode(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            StringBuilder result = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> iterator =
                    ((Map<?, ?>) value).entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                result.append(encode(String.valueOf(entry.getKey())))
                        .append(':').append(encode(entry.getValue()));
                if (iterator.hasNext()) {
                    result.append(',');
                }
            }
            return result.append('}').toString();
        }
        if (value instanceof Collection) {
            StringBuilder result = new StringBuilder("[");
            Iterator<?> iterator = ((Collection<?>) value).iterator();
            while (iterator.hasNext()) {
                result.append(encode(iterator.next()));
                if (iterator.hasNext()) {
                    result.append(',');
                }
            }
            return result.append(']').toString();
        }
        String text = String.valueOf(value);
        return '"' + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + '"';
    }
}
