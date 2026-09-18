package com.avr.core;

import com.avr.api.Skill;
import com.avr.api.SkillRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 基于内存 Map 保存和查询 Skill。 */
public final class MapSkillRegistry implements SkillRegistry {
    private final Map<String, Skill> skills = new LinkedHashMap<String, Skill>();

    public MapSkillRegistry register(Skill skill) {
        if (skills.put(skill.getName(), skill) != null) {
            throw new IllegalArgumentException("duplicate skill: " + skill.getName());
        }
        return this;
    }

    @Override
    public Optional<Skill> find(String name, com.avr.api.ExecutionContext context) {
        return Optional.ofNullable(skills.get(name));
    }
}
