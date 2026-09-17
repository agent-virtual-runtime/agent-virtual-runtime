// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelResponse {
    private final String text;
    private final List<CapabilityCall> capabilityCalls;

    private ModelResponse(String text, List<CapabilityCall> capabilityCalls) {
        this.text = text == null ? "" : text;
        this.capabilityCalls = Collections.unmodifiableList(
                new ArrayList<CapabilityCall>(capabilityCalls));
    }

    public static ModelResponse answer(String text) {
        return new ModelResponse(text, Collections.<CapabilityCall>emptyList());
    }

    public static ModelResponse calls(List<CapabilityCall> calls) {
        return new ModelResponse("", calls);
    }

    public String getText() {
        return text;
    }

    public List<CapabilityCall> getCapabilityCalls() {
        return capabilityCalls;
    }
}

