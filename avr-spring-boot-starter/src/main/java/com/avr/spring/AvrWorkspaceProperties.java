package com.avr.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可选的默认 Workspace 配置。 */
@ConfigurationProperties(prefix = "avr.workspace")
public class AvrWorkspaceProperties {
    /** 默认实现类型，支持 none、memory 和 disk。 */
    private String type = "none";
    /** 由应用定义的工作空间标识。 */
    private String id = "default";
    /** 磁盘实现使用的宿主机根目录。 */
    private String path = "./data/avr-workspace";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
