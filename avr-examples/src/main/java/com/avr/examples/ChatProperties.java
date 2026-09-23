package com.avr.examples;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat 工作台的模型目录和联网搜索配置。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "avr.chat")
public class ChatProperties {
    private boolean webSearchEnabled = true;
    private List<Model> models = new ArrayList<Model>();

    /** 一个可在工作台中选择的模型。 */
    @Getter
    @Setter
    public static class Model {
        private String id;
        private String name;
        private String webSearch = "none";
    }
}
