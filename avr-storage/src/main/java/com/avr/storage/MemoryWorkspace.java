package com.avr.storage;

import com.avr.api.Artifact;
import com.avr.api.Workspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 适合短生命周期任务和测试的线程安全内存工作空间。 */
public final class MemoryWorkspace implements Workspace {
    private final String id;
    private final Map<String, String> files = new LinkedHashMap<String, String>();
    private final List<Artifact> artifacts = new ArrayList<Artifact>();

    public MemoryWorkspace(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized List<String> list(String directory) {
        String normalized = normalizeDirectory(directory);
        List<String> result = new ArrayList<String>();
        for (String path : files.keySet()) {
            if (path.startsWith(normalized)) {
                result.add(path);
            }
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized String readText(String path) {
        String normalized = normalizeFile(path);
        String content = files.get(normalized);
        if (content == null) {
            throw new IllegalArgumentException("file does not exist: " + normalized);
        }
        return content;
    }

    @Override
    public synchronized void writeText(String path, String content) {
        files.put(normalizeFile(path), Objects.requireNonNull(content, "content"));
    }

    @Override
    public synchronized void delete(String path) {
        String normalized = normalizeFile(path);
        if (files.remove(normalized) == null) {
            throw new IllegalArgumentException("file does not exist: " + normalized);
        }
    }

    @Override
    public synchronized boolean exists(String path) {
        return files.containsKey(normalizeFile(path));
    }

    @Override
    public synchronized Artifact commitArtifact(String root, String entrypoint) {
        String normalizedRoot = normalizeDirectory(root);
        String normalizedEntrypoint = normalizeFile(
                normalizedRoot.substring(0, normalizedRoot.length() - 1) + "/" + stripLeadingSlash(entrypoint));
        if (!files.containsKey(normalizedEntrypoint)) {
            throw new IllegalArgumentException("artifact entrypoint does not exist: " + normalizedEntrypoint);
        }
        List<String> artifactFiles = list(normalizedRoot);
        Map<String, String> contents = new LinkedHashMap<String, String>();
        for (String path : artifactFiles) {
            contents.put(path, files.get(path));
        }
        Artifact artifact = new Artifact(
                "art-" + UUID.randomUUID(), normalizedRoot, normalizedEntrypoint,
                artifactFiles, contents);
        artifacts.add(artifact);
        return artifact;
    }

    @Override
    public synchronized List<Artifact> artifacts() {
        return Collections.unmodifiableList(new ArrayList<Artifact>(artifacts));
    }

    private static String normalizeDirectory(String path) {
        String normalized = normalize(path);
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static String normalizeFile(String path) {
        String normalized = normalize(path);
        if ("/".equals(normalized) || normalized.endsWith("/")) {
            throw new IllegalArgumentException("file path must identify a file: " + path);
        }
        return normalized;
    }

    private static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("virtual path must be absolute: " + path);
        }
        if (path.indexOf('\0') >= 0 || path.contains("\\")) {
            throw new IllegalArgumentException("invalid virtual path: " + path);
        }
        StringBuilder result = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("relative segments are forbidden: " + path);
            }
            result.append('/').append(segment);
        }
        String normalized = result.length() == 0 ? "/" : result.toString();
        if (ArtifactPersistence.isInternalPath(normalized)) {
            throw new IllegalArgumentException("reserved virtual path: " + path);
        }
        return normalized;
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
