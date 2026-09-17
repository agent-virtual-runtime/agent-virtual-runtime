// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core.capability;

import org.agentvirtualruntime.api.Artifact;
import org.agentvirtualruntime.api.Capability;
import org.agentvirtualruntime.api.CapabilityCall;
import org.agentvirtualruntime.api.CapabilityContext;
import org.agentvirtualruntime.api.CapabilityDescriptor;
import org.agentvirtualruntime.api.CapabilityResult;

public final class CommitArtifactCapability implements Capability {
    private static final CapabilityDescriptor DESCRIPTOR = new CapabilityDescriptor(
            "artifact.commit", "Commit an immutable artifact. Arguments: root, entrypoint");

    @Override
    public CapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, CapabilityContext context) {
        Artifact artifact = context.getWorkspace().commitArtifact(
                call.requireString("root"), call.requireString("entrypoint"));
        return CapabilityResult.success(
                "committed artifact " + artifact.getId() + " entrypoint=" + artifact.getEntrypoint());
    }
}

