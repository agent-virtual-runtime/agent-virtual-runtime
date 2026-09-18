package com.avr.examples;

import com.avr.api.Agent;
import com.avr.api.AgentResult;
import com.avr.api.Workspace;
import org.springframework.stereotype.Service;

/** 使用自动配置 Agent Bean 的股票报告业务服务。 */
@Service
public class StockReportService {
    private final Agent agent;
    private final Workspace workspace;

    public StockReportService(Agent agent, Workspace workspace) {
        this.agent = agent;
        this.workspace = workspace;
    }

    public synchronized AgentResult generateReport() {
        workspace.writeText(
                "/inputs/stock-data.json",
                StockReportAgentExample.sampleStockData());
        return agent.input(StockReportAgentExample.buildTaskPrompt());
    }
}
