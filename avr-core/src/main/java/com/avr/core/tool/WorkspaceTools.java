package com.avr.core.tool;

import com.avr.api.Tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** AVR 标准 Workspace 工具集合，供 Spring 与非 Spring 应用统一接入。 */
public final class WorkspaceTools {
    private WorkspaceTools() {
    }

    /** 返回完整的标准文件与 Artifact 工具集合。 */
    public static List<Tool> defaults() {
        return Collections.unmodifiableList(Arrays.asList(
                new FileOpTool(),
                new DirectoryTool(),
                new CommitArtifactTool(),
                new PlanTool()));
    }
}
