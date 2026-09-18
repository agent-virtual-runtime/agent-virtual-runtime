package com.avr.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 默认 Agent 和循环配置。 */
@ConfigurationProperties(prefix = "avr.agent")
public class AvrAgentProperties {
    /** Agent 名称。 */
    private String name = "default";
    /** 单次运行允许的最大循环步数。 */
    private int maxSteps = 20;
    /** 单个工具调用的最长等待时间。 */
    private Duration toolTimeout = Duration.ofMinutes(5);
    /** 模型连续空响应的最大重试次数。 */
    private int maxEmptyResponses = 1;
    /** 没有产生有效进展时允许的最大轮数。 */
    private int maxNoProgressRounds = 5;
    /** 自动创建的默认 Skill 名称。 */
    private String skillName = "application-agent";
    /** 注入默认 Skill 的业务指令。 */
    private String instructions;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Duration getToolTimeout() {
        return toolTimeout;
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    public int getMaxEmptyResponses() {
        return maxEmptyResponses;
    }

    public void setMaxEmptyResponses(int maxEmptyResponses) {
        this.maxEmptyResponses = maxEmptyResponses;
    }

    public int getMaxNoProgressRounds() {
        return maxNoProgressRounds;
    }

    public void setMaxNoProgressRounds(int maxNoProgressRounds) {
        this.maxNoProgressRounds = maxNoProgressRounds;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
