package com.avr.api;

import java.util.List;

/** Agent 可见的虚拟文件工作空间，所有路径均为虚拟绝对路径。 */
public interface Workspace {
    /** 返回由接入应用定义的工作空间标识。 */
    String id();
    /** 递归列出虚拟目录下的文件。 */
    List<String> list(String directory);
    /** 读取 UTF-8 文本文件。 */
    String readText(String path);
    /** 创建或覆盖 UTF-8 文本文件。 */
    void writeText(String path, String content);

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

    boolean exists(String path);
    /** 将 {@code root} 下的文件提交为带入口文件的 Artifact 快照。 */
    Artifact commitArtifact(String root, String entrypoint);
    /** 返回当前工作空间已经提交的 Artifact。 */
    List<Artifact> artifacts();
}
