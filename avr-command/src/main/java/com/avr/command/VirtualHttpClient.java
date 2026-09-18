package com.avr.command;

import com.avr.api.ExecutionContext;

/** 由接入应用控制、供虚拟 curl 命令使用的 HTTP 客户端。 */
public interface VirtualHttpClient {
    String get(String url, ExecutionContext context);
}
