package com.avr.core;

import com.avr.api.ExecutionContext;
import com.avr.api.Skill;
import com.avr.api.SkillRegistry;
import com.avr.api.Workspace;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/** 从工作空间的 {@code /skills/<name>/SKILL.md} 加载 Skill 指令。 */
public final class WorkspaceSkillRegistry implements SkillRegistry {
    private final Workspace workspace;
    private final String root;

    public WorkspaceSkillRegistry(Workspace workspace) {
        this(workspace, "/skills");
    }

    public WorkspaceSkillRegistry(Workspace workspace, String root) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.root = normalizeRoot(root);
    }

    @Override
    public Optional<Skill> find(String name, ExecutionContext context) {
        validateName(name);
        String path = root + "/" + name + "/SKILL.md";
        if (!workspace.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(new Skill(
                name, workspace.readText(path), Collections.<String>emptyList()));
    }

    private static String normalizeRoot(String value) {
        Objects.requireNonNull(value, "root");
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("skill root must be absolute: " + value);
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid skill name: " + name);
        }
    }
}
