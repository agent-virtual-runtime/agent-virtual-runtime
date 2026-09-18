package com.avr.spring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 默认 Agent 和循环配置。 */
@ConfigurationProperties(prefix = "avr.agent")
@Getter
@Setter
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

}
