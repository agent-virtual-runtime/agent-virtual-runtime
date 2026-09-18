package com.avr.api;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 注入智能体运行的可复用指令和工具提示。 */
@Getter
public final class Skill {
    private final String name;
    private final String instructions;
    private final List<String> tools;

    public Skill(String name, String instructions, List<String> tools) {
        this.name = name;
        this.instructions = instructions;
        this.tools = Collections.unmodifiableList(new ArrayList<String>(tools));
    }

}
