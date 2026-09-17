// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core.capability;

import org.agentvirtualruntime.api.Capability;
import org.agentvirtualruntime.api.CapabilityCall;
import org.agentvirtualruntime.api.CapabilityContext;
import org.agentvirtualruntime.api.CapabilityDescriptor;
import org.agentvirtualruntime.api.CapabilityResult;

public final class WriteFileCapability implements Capability {
    private static final CapabilityDescriptor DESCRIPTOR = new CapabilityDescriptor(
            "file.write", "Write a UTF-8 text file to the virtual workspace. Arguments: path, content");

    @Override
    public CapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, CapabilityContext context) {
        String path = call.requireString("path");
        String content = call.requireString("content");
        context.getWorkspace().writeText(path, content);
        return CapabilityResult.success("wrote " + path + " (" + content.length() + " chars)");
    }
}

