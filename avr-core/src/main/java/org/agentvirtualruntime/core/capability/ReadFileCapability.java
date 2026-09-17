// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core.capability;

import org.agentvirtualruntime.api.Capability;
import org.agentvirtualruntime.api.CapabilityCall;
import org.agentvirtualruntime.api.CapabilityContext;
import org.agentvirtualruntime.api.CapabilityDescriptor;
import org.agentvirtualruntime.api.CapabilityResult;

public final class ReadFileCapability implements Capability {
    private static final CapabilityDescriptor DESCRIPTOR = new CapabilityDescriptor(
            "file.read", "Read a UTF-8 text file from the virtual workspace. Argument: path");

    @Override
    public CapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, CapabilityContext context) {
        String path = call.requireString("path");
        return CapabilityResult.success(context.getWorkspace().readText(path));
    }
}

