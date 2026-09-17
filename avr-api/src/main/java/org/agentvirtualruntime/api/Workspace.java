// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.List;

public interface Workspace {
    String id();
    List<String> list(String directory);
    String readText(String path);
    void writeText(String path, String content);
    boolean exists(String path);
    Artifact commitArtifact(String root, String entrypoint);
    List<Artifact> artifacts();
}

