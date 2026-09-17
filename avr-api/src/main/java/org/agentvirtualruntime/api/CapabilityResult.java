// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.Objects;

public final class CapabilityResult {
    private final boolean success;
    private final String content;

    private CapabilityResult(boolean success, String content) {
        this.success = success;
        this.content = Objects.requireNonNull(content, "content");
    }

    public static CapabilityResult success(String content) {
        return new CapabilityResult(true, content);
    }

    public static CapabilityResult failure(String content) {
        return new CapabilityResult(false, content);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }
}

