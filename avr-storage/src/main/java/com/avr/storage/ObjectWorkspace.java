package com.avr.storage;

import com.avr.api.Artifact;
import com.avr.api.Workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 基于通用对象存储的 UTF-8 虚拟工作空间。 */
public final class ObjectWorkspace implements Workspace {
    private final String id;
    private final ObjectStore store;
    private final String prefix;

    public ObjectWorkspace(String id, ObjectStore store, String prefix) {
        this.id = Objects.requireNonNull(id, "id");
        this.store = Objects.requireNonNull(store, "store");
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized List<String> list(String directory) {
        String normalized = normalize(directory, true);
        List<String> result = new ArrayList<String>();
        for (String key : store.list(prefix + strip(normalized))) {
            if (key.startsWith(prefix)) {
                String virtualPath = "/" + key.substring(prefix.length());
                if (!ArtifactPersistence.isInternalPath(virtualPath)) {
                    result.add(virtualPath);
                }
            }
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized String readText(String path) {
        String normalized = normalize(path, false);
        byte[] value = store.get(key(normalized)).orElseThrow(
                () -> new IllegalArgumentException("file does not exist: " + normalized));
        return new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void writeText(String path, String content) {
        String normalized = normalize(path, false);
        store.put(key(normalized), Objects.requireNonNull(content, "content")
                .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void delete(String path) {
        String normalized = normalize(path, false);
        if (!exists(normalized)) {
            throw new IllegalArgumentException("file does not exist: " + normalized);
        }
        store.delete(key(normalized));
    }

    @Override
    public synchronized boolean exists(String path) {
        return store.get(key(normalize(path, false))).isPresent();
    }

    @Override
    public synchronized Artifact commitArtifact(String root, String entrypoint) {
        String normalizedRoot = normalize(root, true);
        String normalizedEntry = normalize(normalizedRoot + strip(entrypoint), false);
        if (!exists(normalizedEntry)) {
            throw new IllegalArgumentException(
                    "artifact entrypoint does not exist: " + normalizedEntry);
        }
        List<String> files = list(normalizedRoot);
        Map<String, String> contents = new LinkedHashMap<String, String>();
        for (String path : files) {
            contents.put(path, readText(path));
        }
        Artifact artifact = new Artifact(
                "art-" + UUID.randomUUID(), normalizedRoot, normalizedEntry, files, contents);
        persistArtifact(artifact);
        return artifact;
    }

    @Override
    public synchronized List<Artifact> artifacts() {
        String artifactPrefix = prefix + ArtifactPersistence.ARTIFACT_DIRECTORY + "/";
        List<String> manifestKeys = new ArrayList<String>();
        for (String key : store.list(artifactPrefix)) {
            if (key.startsWith(artifactPrefix)
                    && key.endsWith("/" + ArtifactPersistence.MANIFEST_NAME)) {
                manifestKeys.add(key);
            }
        }
        Collections.sort(manifestKeys);

        List<Artifact> result = new ArrayList<Artifact>(manifestKeys.size());
        for (String manifestKey : manifestKeys) {
            result.add(readArtifact(manifestKey));
        }
        return Collections.unmodifiableList(result);
    }

    private void persistArtifact(Artifact artifact) {
        String artifactPrefix = artifactPrefix(artifact.getId());
        for (Map.Entry<String, String> content : artifact.getContents().entrySet()) {
            String contentKey = artifactPrefix
                    + ArtifactPersistence.CONTENT_DIRECTORY + "/"
                    + ArtifactPersistence.encodePath(content.getKey());
            store.put(contentKey, content.getValue().getBytes(StandardCharsets.UTF_8));
        }
        // manifest 最后写入，避免对象上传未完成时暴露不完整 Artifact。
        store.put(artifactPrefix + ArtifactPersistence.MANIFEST_NAME,
                ArtifactPersistence.encodeManifest(artifact));
    }

    private Artifact readArtifact(String manifestKey) {
        byte[] manifest = store.get(manifestKey).orElseThrow(() ->
                new IllegalStateException("artifact manifest disappeared: " + manifestKey));
        String artifactPrefix = manifestKey.substring(
                0, manifestKey.length() - ArtifactPersistence.MANIFEST_NAME.length());
        return ArtifactPersistence.decodeManifest(manifest, path -> {
            String contentKey = artifactPrefix
                    + ArtifactPersistence.CONTENT_DIRECTORY + "/"
                    + ArtifactPersistence.encodePath(path);
            byte[] value = store.get(contentKey).orElseThrow(() ->
                    new IllegalStateException("artifact content does not exist: " + path));
            return new String(value, StandardCharsets.UTF_8);
        });
    }

    private String artifactPrefix(String artifactId) {
        return prefix + ArtifactPersistence.ARTIFACT_DIRECTORY + "/" + artifactId + "/";
    }

    private String key(String path) {
        return prefix + strip(path);
    }

    private static String normalizePrefix(String value) {
        Objects.requireNonNull(value, "prefix");
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result.isEmpty() || result.endsWith("/") ? result : result + "/";
    }

    private static String normalize(String path, boolean directory) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.indexOf('\0') >= 0 || path.contains("\\")) {
            throw new IllegalArgumentException("invalid absolute virtual path: " + path);
        }
        StringBuilder result = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("relative segments are forbidden: " + path);
            }
            result.append('/').append(part);
        }
        String normalized = result.length() == 0 ? "/" : result.toString();
        if (ArtifactPersistence.isInternalPath(normalized)) {
            throw new IllegalArgumentException("reserved virtual path: " + path);
        }
        if (!directory && "/".equals(normalized)) {
            throw new IllegalArgumentException("file path required");
        }
        return directory && !normalized.endsWith("/") ? normalized + "/" : normalized;
    }

    private static String strip(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
