package com.avr.storage;

import com.avr.api.Artifact;
import com.avr.api.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 以宿主机目录为根的虚拟工作空间，不向 Agent 暴露真实路径。 */
public final class DiskWorkspace implements Workspace {
    private final String id;
    private final Path root;
    private final Path artifactRoot;

    public DiskWorkspace(String id, Path root) {
        this.id = Objects.requireNonNull(id, "id");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.artifactRoot = this.root.resolve(ArtifactPersistence.ARTIFACT_DIRECTORY);
        try {
            Files.createDirectories(this.root);
            rejectInternalSymlink();
            Files.createDirectories(this.artifactRoot);
            ensureNoSymlink(this.artifactRoot);
        } catch (IOException exception) {
            throw failure("create workspace", exception);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized List<String> list(String directory) {
        Path start = resolve(directory, true);
        if (!Files.exists(start)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(start)) {
            List<String> result = paths.filter(Files::isRegularFile)
                    .map(this::virtualPath)
                    .filter(path -> !ArtifactPersistence.isInternalPath(path))
                    .sorted().collect(Collectors.toList());
            return Collections.unmodifiableList(result);
        } catch (IOException exception) {
            throw failure("list " + directory, exception);
        }
    }

    @Override
    public synchronized String readText(String path) {
        Path file = resolve(path, false);
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw failure("read " + path, exception);
        }
    }

    @Override
    public synchronized void writeText(String path, String content) {
        Path file = resolve(path, false);
        try {
            Path parent = file.getParent();
            Files.createDirectories(parent);
            ensureNoSymlink(parent);
            Files.write(file, Objects.requireNonNull(content, "content").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw failure("write " + path, exception);
        }
    }

    @Override
    public synchronized void delete(String path) {
        Path file = resolve(path, false);
        try {
            if (!Files.deleteIfExists(file)) {
                throw new IllegalArgumentException("file does not exist: " + path);
            }
        } catch (IOException exception) {
            throw failure("delete " + path, exception);
        }
    }

    @Override
    public synchronized boolean exists(String path) {
        return Files.isRegularFile(resolve(path, false), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public synchronized Artifact commitArtifact(String rootPath, String entrypoint) {
        String virtualRoot = normalize(rootPath, true);
        String virtualEntry = normalize(virtualRoot + strip(entrypoint), false);
        if (!exists(virtualEntry)) {
            throw new IllegalArgumentException(
                    "artifact entrypoint does not exist: " + virtualEntry);
        }
        List<String> files = list(virtualRoot);
        Map<String, String> contents = new LinkedHashMap<String, String>();
        for (String path : files) {
            contents.put(path, readText(path));
        }
        Artifact artifact = new Artifact(
                "art-" + UUID.randomUUID(), virtualRoot, virtualEntry, files, contents);
        persistArtifact(artifact);
        return artifact;
    }

    @Override
    public synchronized List<Artifact> artifacts() {
        if (!Files.isDirectory(artifactRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyList();
        }
        try (Stream<Path> directories = Files.list(artifactRoot)) {
            List<Artifact> result = directories
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> Files.isRegularFile(
                            path.resolve(ArtifactPersistence.MANIFEST_NAME),
                            LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .map(this::readArtifact)
                    .collect(Collectors.toList());
            return Collections.unmodifiableList(result);
        } catch (IOException exception) {
            throw failure("list artifacts", exception);
        }
    }

    private void persistArtifact(Artifact artifact) {
        Path artifactDirectory = artifactRoot.resolve(artifact.getId());
        Path contentDirectory = artifactDirectory.resolve(ArtifactPersistence.CONTENT_DIRECTORY);
        try {
            Files.createDirectory(artifactDirectory);
            Files.createDirectory(contentDirectory);
            for (Map.Entry<String, String> content : artifact.getContents().entrySet()) {
                Path destination = contentDirectory.resolve(
                        ArtifactPersistence.encodePath(content.getKey()));
                Files.write(destination, content.getValue().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW);
            }
            // manifest 最后落盘，只有内容完整的 Artifact 才会被 artifacts() 发现。
            Files.write(artifactDirectory.resolve(ArtifactPersistence.MANIFEST_NAME),
                    ArtifactPersistence.encodeManifest(artifact),
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw failure("persist artifact " + artifact.getId(), exception);
        }
    }

    private Artifact readArtifact(Path artifactDirectory) {
        Path manifest = artifactDirectory.resolve(ArtifactPersistence.MANIFEST_NAME);
        try {
            byte[] metadata = Files.readAllBytes(manifest);
            return ArtifactPersistence.decodeManifest(metadata, path ->
                    readArtifactContent(artifactDirectory, path));
        } catch (IOException exception) {
            throw failure("read artifact " + artifactDirectory.getFileName(), exception);
        }
    }

    private String readArtifactContent(Path artifactDirectory, String virtualPath) {
        Path content = artifactDirectory.resolve(ArtifactPersistence.CONTENT_DIRECTORY)
                .resolve(ArtifactPersistence.encodePath(virtualPath));
        try {
            return new String(Files.readAllBytes(content), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw failure("read artifact content " + virtualPath, exception);
        }
    }

    private Path resolve(String virtual, boolean directory) {
        String normalized = normalize(virtual, directory);
        if (ArtifactPersistence.isInternalPath(normalized)) {
            throw new IllegalArgumentException("reserved virtual path: " + virtual);
        }
        Path result = root.resolve(strip(normalized)).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + virtual);
        }
        ensureNoSymlink(result);
        return result;
    }

    private void rejectInternalSymlink() {
        Path internalDirectory = root.resolve(ArtifactPersistence.INTERNAL_DIRECTORY);
        if (Files.isSymbolicLink(internalDirectory)) {
            throw new IllegalArgumentException("reserved metadata directory cannot be a symbolic link");
        }
    }

    private void ensureNoSymlink(Path path) {
        Path current = root;
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                        "symbolic links are forbidden: " + virtualPath(current));
            }
        }
    }

    private String virtualPath(Path path) {
        return "/" + root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String strip(String path) {
        String result = path;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static String normalize(String path, boolean directory) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.indexOf('\0') >= 0 || path.contains("\\")) {
            throw new IllegalArgumentException("invalid absolute virtual path: " + path);
        }
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("relative segments are forbidden: " + path);
            }
            out.append('/').append(part);
        }
        String value = out.length() == 0 ? "/" : out.toString();
        if (!directory && "/".equals(value)) {
            throw new IllegalArgumentException("file path required");
        }
        return directory && !value.endsWith("/") ? value + "/" : value;
    }

    private static IllegalStateException failure(String action, IOException cause) {
        return new IllegalStateException("cannot " + action + ": " + cause.getMessage(), cause);
    }
}
