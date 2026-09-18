package com.avr.spring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可选的默认 Workspace 配置。 */
@ConfigurationProperties(prefix = "avr.workspace")
@Getter
@Setter
public class AvrWorkspaceProperties {
    /** 默认实现类型，支持 none、memory 和 disk。 */
    private String type = "none";
    /** 由应用定义的工作空间标识。 */
    private String id = "default";
    /** 磁盘实现使用的宿主机根目录。 */
    private String path = "./data/avr-workspace";

}
