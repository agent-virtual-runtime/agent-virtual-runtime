package com.avr.api;

import java.util.Optional;

/** 根据名称和执行上下文解析 Skill。 */
public interface SkillRegistry {
    Optional<Skill> find(String name, ExecutionContext context);
}
