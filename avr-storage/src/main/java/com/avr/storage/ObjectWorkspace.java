package com.avr.storage;

import com.avr.api.Artifact;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceEntry;

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
    private static final String DIRECTORY_MARKER = ".avr-directory";
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
            if (key.startsWith(prefix) && !key.endsWith("/" + DIRECTORY_MARKER)) {
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
    public synchronized List<WorkspaceEntry> entries(String directory) {
        String normalized = normalize(directory, true);
        String keyPrefix = prefix + strip(normalized);
        Map<String, WorkspaceEntry> result = new LinkedHashMap<String, WorkspaceEntry>();
        for (String objectKey : store.list(keyPrefix)) {
            if (!objectKey.startsWith(prefix)) {
                continue;
            }
            String virtual = "/" + objectKey.substring(prefix.length());
            if (ArtifactPersistence.isInternalPath(virtual)) {
                continue;
            }
            String relative = virtual.substring(normalized.length());
            if (relative.isEmpty()) {
                continue;
            }
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                String child = normalized.substring(0, normalized.length() - 1)
                        + "/" + relative.substring(0, slash);
                result.put(child, new WorkspaceEntry(
                        child, WorkspaceEntry.Type.DIRECTORY, 0));
            } else if (!DIRECTORY_MARKER.equals(relative)) {
                long size = store.get(objectKey).map(value -> (long) value.length).orElse(0L);
                result.put(virtual, new WorkspaceEntry(
                        virtual, WorkspaceEntry.Type.FILE, size));
            }
        }
        List<WorkspaceEntry> entries = new ArrayList<WorkspaceEntry>(result.values());
        entries.sort(java.util.Comparator.comparing(WorkspaceEntry::getPath));
        return Collections.unmodifiableList(entries);
    }

    @Override
    public synchronized void createDirectory(String path) {
        String normalized = normalize(path, true);
        store.put(prefix + strip(normalized) + DIRECTORY_MARKER, new byte[0]);
    }

    @Override
    public synchronized boolean directoryExists(String path) {
        String normalized = normalize(path, true);
        return !store.list(prefix + strip(normalized)).isEmpty();
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
    public synchronized void appendText(String path, String content) {
        Workspace.super.appendText(path, content);
    }

    @Override
    public synchronized int replaceText(
            String path, String oldText, String newText, boolean replaceAll) {
        return Workspace.super.replaceText(path, oldText, newText, replaceAll);
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
    public synchronized void deleteDirectory(String path, boolean recursive) {
        String normalized = normalize(path, true);
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("workspace root cannot be deleted");
        }
        List<String> keys = new ArrayList<String>(store.list(prefix + strip(normalized)));
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("directory does not exist: " + path);
        }
        String ownMarker = prefix + strip(normalized) + DIRECTORY_MARKER;
        boolean hasContent = keys.stream().anyMatch(value -> !value.equals(ownMarker));
        if (hasContent && !recursive) {
            throw new IllegalStateException("directory is not empty: " + path);
        }
        for (String value : keys) {
            store.delete(value);
        }
    }

    @Override
    public synchronized void copyDirectory(String source, String target, boolean merge) {
        String from = normalize(source, true);
        String to = normalize(target, true);
        if (from.equals(to) || to.startsWith(from)) {
            throw new IllegalArgumentException(
                    "target directory cannot be inside source: " + target);
        }
        if (!directoryExists(from)) {
            throw new IllegalArgumentException("directory does not exist: " + source);
        }
        if (directoryExists(to) && !merge) {
            throw new IllegalArgumentException("target directory already exists: " + target);
        }
        createDirectory(to);
        for (String sourceKey : new ArrayList<String>(store.list(prefix + strip(from)))) {
            byte[] value = store.get(sourceKey).orElseThrow(() ->
                    new IllegalStateException("object disappeared: " + sourceKey));
            String relative = sourceKey.substring((prefix + strip(from)).length());
            store.put(prefix + strip(to) + relative, value);
        }
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
