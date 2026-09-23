package com.avr.api;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Agent 可见的虚拟文件工作空间，所有路径均为虚拟绝对路径。 */
public interface Workspace {
    /** 返回由接入应用定义的工作空间标识。 */
    String id();
    /** 递归列出虚拟目录下的文件。 */
    List<String> list(String directory);

    /** 列出目录的直接子项；实现可覆盖以保留空目录。 */
    default List<WorkspaceEntry> entries(String directory) {
        String root = directory.endsWith("/") ? directory : directory + "/";
        Map<String, WorkspaceEntry> result = new LinkedHashMap<String, WorkspaceEntry>();
        for (String path : list(directory)) {
            String relative = path.substring(root.length());
            int slash = relative.indexOf('/');
            String child = slash < 0 ? path : root + relative.substring(0, slash);
            if (slash < 0) {
                result.put(child, new WorkspaceEntry(
                        child, WorkspaceEntry.Type.FILE, readText(path).length()));
            } else {
                result.put(child, new WorkspaceEntry(
                        child, WorkspaceEntry.Type.DIRECTORY, 0));
            }
        }
        return Collections.unmodifiableList(
                new ArrayList<WorkspaceEntry>(result.values()));
    }
    /** 读取 UTF-8 文本文件。 */
    String readText(String path);
    /** 创建或覆盖 UTF-8 文本文件。 */
    void writeText(String path, String content);

    /** 创建目录。需要空目录语义的实现应覆盖此方法。 */
    default void createDirectory(String path) {
        throw new UnsupportedOperationException("workspace does not support empty directories");
    }

    /** 判断目录是否存在。 */
    default boolean directoryExists(String path) {
        return !list(path).isEmpty();
    }

    /** 在已有文本文件末尾追加内容；文件不存在时创建。 */
    default void appendText(String path, String content) {
        writeText(path, (exists(path) ? readText(path) : "") + content);
    }

    /** 按 1 开始的闭区间读取文本，并限制返回字符数。 */
    default TextFileSlice readLines(
            String path, int startLine, int endLine, int maxChars) {
        if (startLine < 1 || endLine < startLine || maxChars < 1) {
            throw new IllegalArgumentException("invalid line range or maxChars");
        }
        String[] lines = readText(path).split("\\n", -1);
        int from = Math.min(startLine, lines.length + 1);
        int requestedEnd = Math.min(endLine, lines.length);
        StringBuilder content = new StringBuilder();
        int actualEnd = from - 1;
        for (int line = from; line <= requestedEnd; line++) {
            String value = lines[line - 1];
            String rendered = String.format("%4d| %s%n", line, value);
            if (content.length() > 0 && content.length() + rendered.length() > maxChars) {
                break;
            }
            content.append(rendered);
            actualEnd = line;
        }
        return new TextFileSlice(
                content.toString(), from, actualEnd, lines.length, actualEnd < requestedEnd);
    }

    /** 在文件中检索文本，返回有限数量的行摘要。 */
    default List<TextSearchMatch> searchText(
            String path, String query, boolean caseSensitive, int maxResults) {
        if (query == null || query.isEmpty() || maxResults < 1) {
            throw new IllegalArgumentException("query must not be empty and maxResults must be positive");
        }
        String[] lines = readText(path).split("\\n", -1);
        String needle = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
        List<TextSearchMatch> result = new ArrayList<TextSearchMatch>();
        for (int index = 0; index < lines.length && result.size() < maxResults; index++) {
            String candidate = caseSensitive
                    ? lines[index]
                    : lines[index].toLowerCase(java.util.Locale.ROOT);
            if (candidate.contains(needle)) {
                String text = lines[index].trim();
                result.add(new TextSearchMatch(index + 1,
                        text.length() > 240 ? text.substring(0, 240) + "…" : text));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** 在指定行之前插入内容。 */
    default int insertLines(String path, int beforeLine, String content) {
        String original = readText(path);
        String[] lines = original.split("\\n", -1);
        if (beforeLine < 1 || beforeLine > lines.length + 1) {
            throw new IllegalArgumentException("insert line is outside file");
        }
        List<String> updated = new ArrayList<String>(java.util.Arrays.asList(lines));
        List<String> inserted = java.util.Arrays.asList(content.split("\\n", -1));
        updated.addAll(beforeLine - 1, inserted);
        writeText(path, String.join("\n", updated));
        return inserted.size();
    }

    /** 删除闭区间内的文本行。 */
    default int deleteLines(String path, int startLine, int endLine) {
        String[] lines = readText(path).split("\\n", -1);
        validateExistingRange(startLine, endLine, lines.length);
        List<String> updated = new ArrayList<String>(java.util.Arrays.asList(lines));
        updated.subList(startLine - 1, endLine).clear();
        writeText(path, String.join("\n", updated));
        return endLine - startLine + 1;
    }

    /** 用新内容替换闭区间内的文本行。 */
    default int replaceLines(
            String path, int startLine, int endLine, String content) {
        String[] lines = readText(path).split("\\n", -1);
        validateExistingRange(startLine, endLine, lines.length);
        List<String> updated = new ArrayList<String>(java.util.Arrays.asList(lines));
        updated.subList(startLine - 1, endLine).clear();
        updated.addAll(startLine - 1,
                java.util.Arrays.asList(content.split("\\n", -1)));
        writeText(path, String.join("\n", updated));
        return endLine - startLine + 1;
    }

    /** 精确替换文本；默认只允许唯一匹配，避免误改多个位置。返回替换次数。 */
    default int replaceText(String path, String oldText, String newText, boolean replaceAll) {
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty");
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText must not be null");
        }
        String original = readText(path);
        int count = 0;
        int offset = 0;
        while ((offset = original.indexOf(oldText, offset)) >= 0) {
            count++;
            offset += oldText.length();
        }
        if (count == 0) {
            throw new IllegalArgumentException("text to replace was not found: " + path);
        }
        if (count > 1 && !replaceAll) {
            throw new IllegalArgumentException("text to replace is ambiguous: " + path);
        }
        String updated = replaceAll
                ? original.replace(oldText, newText)
                : original.replaceFirst(java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText));
        writeText(path, updated);
        return replaceAll ? count : 1;
    }

    /** 删除虚拟文件。 */
    void delete(String path);

    /** 复制虚拟文件并保留源文件。 */
    default void copy(String source, String target) {
        writeText(target, readText(source));
    }

    /** 将虚拟文件移动到新路径。 */
    default void move(String source, String target) {
        copy(source, target);
        delete(source);
    }

    /** 递归删除目录。 */
    default void deleteDirectory(String path, boolean recursive) {
        List<String> files = list(path);
        if (!recursive && !files.isEmpty()) {
            throw new IllegalStateException("directory is not empty: " + path);
        }
        for (String file : files) {
            delete(file);
        }
    }

    /** 递归复制目录；merge=false 时目标必须不存在。 */
    default void copyDirectory(String source, String target, boolean merge) {
        String from = source.endsWith("/") ? source : source + "/";
        String to = target.endsWith("/") ? target : target + "/";
        if (from.equals(to) || to.startsWith(from)) {
            throw new IllegalArgumentException(
                    "target directory cannot be inside source: " + target);
        }
        if (!merge && directoryExists(target)) {
            throw new IllegalArgumentException("target directory already exists: " + target);
        }
        createDirectory(target);
        for (String file : list(source)) {
            copy(file, to + file.substring(from.length()));
        }
    }

    /** 递归移动目录。 */
    default void moveDirectory(String source, String target, boolean merge) {
        copyDirectory(source, target, merge);
        deleteDirectory(source, true);
    }

    boolean exists(String path);
    /** 将 {@code root} 下的文件提交为带入口文件的 Artifact 快照。 */
    Artifact commitArtifact(String root, String entrypoint);
    /** 返回当前工作空间已经提交的 Artifact。 */
    List<Artifact> artifacts();

    static void validateExistingRange(int startLine, int endLine, int totalLines) {
        if (startLine < 1 || endLine < startLine || endLine > totalLines) {
            throw new IllegalArgumentException(
                    "line range " + startLine + "-" + endLine
                            + " is outside 1-" + totalLines);
        }
    }
}
