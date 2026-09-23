package com.avr.storage;

import com.avr.api.Artifact;
import com.avr.api.TextFileSlice;
import com.avr.api.TextSearchMatch;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceEntry;

import java.io.BufferedReader;
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
    public synchronized List<WorkspaceEntry> entries(String directory) {
        Path start = resolve(directory, true);
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyList();
        }
        try (Stream<Path> children = Files.list(start)) {
            List<WorkspaceEntry> result = children
                    .filter(path -> !ArtifactPersistence.isInternalPath(virtualPath(path)))
                    .map(path -> new WorkspaceEntry(
                            virtualPath(path),
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                    ? WorkspaceEntry.Type.DIRECTORY
                                    : WorkspaceEntry.Type.FILE,
                            fileSize(path)))
                    .sorted(java.util.Comparator.comparing(WorkspaceEntry::getPath))
                    .collect(Collectors.toList());
            return Collections.unmodifiableList(result);
        } catch (IOException exception) {
            throw failure("list entries " + directory, exception);
        }
    }

    @Override
    public synchronized void createDirectory(String path) {
        Path directory = resolve(path, true);
        try {
            Files.createDirectories(directory);
            ensureNoSymlink(directory);
        } catch (IOException exception) {
            throw failure("create directory " + path, exception);
        }
    }

    @Override
    public synchronized boolean directoryExists(String path) {
        return Files.isDirectory(resolve(path, true), LinkOption.NOFOLLOW_LINKS);
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
    public synchronized void appendText(String path, String content) {
        Path file = resolve(path, false);
        try {
            Path parent = file.getParent();
            Files.createDirectories(parent);
            ensureNoSymlink(parent);
            Files.write(file, Objects.requireNonNull(content, "content")
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw failure("append " + path, exception);
        }
    }

    @Override
    public synchronized TextFileSlice readLines(
            String path, int startLine, int endLine, int maxChars) {
        if (startLine < 1 || endLine < startLine || maxChars < 1) {
            throw new IllegalArgumentException("invalid line range or maxChars");
        }
        Path file = resolve(path, false);
        StringBuilder content = new StringBuilder();
        int currentLine = 0;
        int actualEnd = startLine - 1;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String value;
            while ((value = reader.readLine()) != null) {
                currentLine++;
                if (currentLine < startLine || currentLine > endLine) {
                    continue;
                }
                String rendered = String.format("%4d| %s%n", currentLine, value);
                if (content.length() > 0
                        && content.length() + rendered.length() > maxChars) {
                    truncated = true;
                    continue;
                }
                if (!truncated) {
                    content.append(rendered);
                    actualEnd = currentLine;
                }
            }
        } catch (IOException exception) {
            throw failure("read lines " + path, exception);
        }
        int from = Math.min(startLine, currentLine + 1);
        return new TextFileSlice(content.toString(), from, actualEnd,
                currentLine, truncated || actualEnd < Math.min(endLine, currentLine));
    }

    @Override
    public synchronized List<TextSearchMatch> searchText(
            String path, String query, boolean caseSensitive, int maxResults) {
        if (query == null || query.isEmpty() || maxResults < 1) {
            throw new IllegalArgumentException(
                    "query must not be empty and maxResults must be positive");
        }
        Path file = resolve(path, false);
        String needle = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
        List<TextSearchMatch> result = new ArrayList<TextSearchMatch>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String value;
            int line = 0;
            while ((value = reader.readLine()) != null && result.size() < maxResults) {
                line++;
                String candidate = caseSensitive
                        ? value : value.toLowerCase(java.util.Locale.ROOT);
                if (candidate.contains(needle)) {
                    String text = value.trim();
                    result.add(new TextSearchMatch(line,
                            text.length() > 240 ? text.substring(0, 240) + "…" : text));
                }
            }
        } catch (IOException exception) {
            throw failure("search " + path, exception);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized int replaceText(
            String path, String oldText, String newText, boolean replaceAll) {
        return Workspace.super.replaceText(path, oldText, newText, replaceAll);
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
    public synchronized void deleteDirectory(String path, boolean recursive) {
        Path directory = resolve(path, true);
        if (directory.equals(root)) {
            throw new IllegalArgumentException("workspace root cannot be deleted");
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("directory does not exist: " + path);
        }
        try {
            if (!recursive) {
                Files.delete(directory);
                return;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path value : paths.sorted(java.util.Comparator.reverseOrder())
                        .collect(Collectors.toList())) {
                    Files.delete(value);
                }
            }
        } catch (IOException exception) {
            throw failure("delete directory " + path, exception);
        }
    }

    @Override
    public synchronized void copyDirectory(String source, String target, boolean merge) {
        Path from = resolve(source, true);
        Path to = resolve(target, true);
        if (to.equals(from) || to.startsWith(from)) {
            throw new IllegalArgumentException(
                    "target directory cannot be inside source: " + target);
        }
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("directory does not exist: " + source);
        }
        if (Files.exists(to) && !merge) {
            throw new IllegalArgumentException("target directory already exists: " + target);
        }
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path value : paths.collect(Collectors.toList())) {
                Path destination = to.resolve(from.relativize(value)).normalize();
                ensureNoSymlink(value);
                if (Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(value, destination,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw failure("copy directory " + source, exception);
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

    private static long fileSize(Path path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw failure("read file size", exception);
        }
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
