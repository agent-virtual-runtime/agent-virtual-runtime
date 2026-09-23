package com.avr.command;

import com.avr.api.ExecutionContext;

/** 由接入应用控制的 HTTP 客户端，负责域名白名单、超时与审计。 */
public interface VirtualHttpClient {
    String get(String url, ExecutionContext context);
}
