// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Artifact {
    private final String id;
    private final String root;
    private final String entrypoint;
    private final List<String> files;

    public Artifact(String id, String root, String entrypoint, List<String> files) {
        this.id = id;
        this.root = root;
        this.entrypoint = entrypoint;
        this.files = Collections.unmodifiableList(new ArrayList<String>(files));
    }

    public String getId() {
        return id;
    }

    public String getRoot() {
        return root;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public List<String> getFiles() {
        return files;
    }
}
