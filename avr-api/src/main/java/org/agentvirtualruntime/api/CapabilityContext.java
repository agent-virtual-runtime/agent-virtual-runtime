// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

public final class CapabilityContext {
    private final Workspace workspace;

    public CapabilityContext(Workspace workspace) {
        this.workspace = workspace;
    }

    public Workspace getWorkspace() {
        return workspace;
    }
}

