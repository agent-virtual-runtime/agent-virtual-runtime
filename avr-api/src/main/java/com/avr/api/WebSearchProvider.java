package com.avr.api;

/** 由应用实现的联网搜索提供者，可对接公共搜索服务或公司内部搜索组件。 */
public interface WebSearchProvider {
    /** 执行搜索并返回统一结构，避免模型依赖具体供应商协议。 */
    WebSearchResult search(WebSearchRequest request, ExecutionContext context);
}
