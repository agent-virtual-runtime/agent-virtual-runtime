package com.avr.examples;

import com.avr.api.Agent;
import com.avr.api.AgentResult;
import com.avr.api.Artifact;
import com.avr.api.Skill;
import com.avr.api.ToolRegistry;
import com.avr.api.Workspace;
import com.avr.api.RuntimeEvent;
import com.avr.command.VirtualCommandTool;
import com.avr.core.AgentLoop;
import com.avr.core.AgentLoopOptions;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.ListFilesTool;
import com.avr.core.tool.ReadFileTool;
import com.avr.core.tool.WriteFileTool;
import com.avr.model.openai.OpenAiConfig;
import com.avr.model.openai.OpenAiLlm;
import com.avr.storage.DiskWorkspace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

/** 生成 HTML 股票分析报告的端到端示例。 */
public final class StockReportAgentExample {
    private static final String INPUT_FILE = "/inputs/stock-data.json";
    private static final String REPORT_FILE = "/report/report.html";

    private StockReportAgentExample() {
    }

    public static void main(String[] args) {
        OpenAiConfig modelConfig = OpenAiConfig.fromYaml();
        Path workspaceDirectory = Paths.get(workspaceDirectory())
                .toAbsolutePath()
                .normalize();

        Workspace workspace = new DiskWorkspace(
                "stock-report-example", workspaceDirectory);
        workspace.writeText(INPUT_FILE, sampleStockData());

        ToolRegistry tools = DefaultToolRegistry.builder()
                .register(new ReadFileTool())
                .register(new WriteFileTool())
                .register(new ListFilesTool())
                .register(new CommitArtifactTool())
                .register(new VirtualCommandTool())
                .build();

        AgentLoopOptions loopOptions = AgentLoopOptions.builder()
                .toolTimeout(Duration.ofMinutes(2))
                .maxEmptyResponses(1)
                .maxNoProgressRounds(5)
                .build();

        AgentLoop runtime = new AgentLoop(
                new OpenAiLlm(modelConfig),
                tools,
                com.avr.api.ToolPolicy.allowAll(),
                loopOptions,
                java.util.Collections.singletonList(
                        StockReportAgentExample::printEvent));

        Agent agent = Agent.builder()
                .name("stock-report-agent")
                .runtime(runtime)
                .workspace(workspace)
                .skill(financialAnalysisSkill())
                .maxSteps(12)
                .build();

        System.out.println("Using model: " + modelConfig.getModel());
        System.out.println("Model endpoint: " + modelConfig.getEndpoint());
        System.out.println("Workspace: " + workspaceDirectory);
        System.out.println("API key: configured (value is never printed)");

        AgentResult result = agent.input(buildTaskPrompt());
        Artifact artifact = requireReport(result, workspace);
        Path reportPath = workspaceDirectory.resolve("report/report.html");

        System.out.println();
        System.out.println("Agent answer: " + result.getText());
        System.out.println("Artifact: " + artifact.getId());
        System.out.println("Report: " + reportPath);
    }

    private static String workspaceDirectory() {
        String configured = System.getenv("AVR_WORKSPACE_DIR");
        if (configured == null || configured.trim().isEmpty()) {
            return "avr-examples/target/stock-agent-workspace";
        }
        return configured.trim();
    }

    private static Skill financialAnalysisSkill() {
        String instructions = String.join("\n",
                "You are a cautious equity research analyst.",
                "Use only facts contained in the supplied stock-data file.",
                "Do not invent live prices, forecasts, sources, or company events.",
                "Calculate and explain revenue growth, gross margin, operating margin,",
                "net margin, EPS growth, free-cash-flow margin, debt-to-equity,",
                "current ratio, P/E, and free-cash-flow yield when inputs permit.",
                "Separate observed facts from interpretation.",
                "Discuss profitability, growth quality, balance-sheet resilience,",
                "valuation, catalysts, and material risks.",
                "The conclusion must include bull, base, and bear cases.",
                "State that the report is an educational example, not investment advice.",
                "Create a self-contained UTF-8 HTML document with embedded CSS.",
                "Write it to /report/report.html, then call artifact.commit with",
                "root=/report and entrypoint=/report.html.");
        return new Skill(
                "fundamental-stock-analysis",
                instructions,
                Arrays.asList(
                        "file.read",
                        "file.write",
                        "file.list",
                        "artifact.commit"));
    }

    static String buildTaskPrompt() {
        return String.join("\n",
                "Analyze the fictional company AVR Technologies (ticker: AVR).",
                "First read " + INPUT_FILE + ".",
                "Produce a professional Chinese stock analysis report.",
                "Show important calculations and clearly label data dates.",
                "Use tables or compact cards where they improve readability.",
                "Do not rely on information outside the supplied file.",
                "Write the final report to " + REPORT_FILE + ".",
                "After writing it, commit /report as an Artifact with /report.html",
                "as the entrypoint. Do not return the full HTML in the final answer;",
                "return only a short completion summary after the Artifact is committed.");
    }

    private static Artifact requireReport(AgentResult result, Workspace workspace) {
        if (!workspace.exists(REPORT_FILE)) {
            throw new IllegalStateException(
                    "Agent finished without creating " + REPORT_FILE);
        }
        if (result.getArtifacts().isEmpty()) {
            throw new IllegalStateException(
                    "Agent created the report but did not call artifact.commit");
        }
        return result.getArtifacts().get(result.getArtifacts().size() - 1);
    }

    private static void printEvent(RuntimeEvent event) {
        if ("model.delta".equals(event.getType())) {
            System.out.print(event.getDetail());
            return;
        }
        System.out.println("[" + event.getSequence() + "] "
                + event.getType() + " - " + event.getDetail());
    }

    static String sampleStockData() {
        return "{\n"
                + "  \"company\": \"AVR Technologies\",\n"
                + "  \"ticker\": \"AVR\",\n"
                + "  \"currency\": \"USD\",\n"
                + "  \"dataDate\": \"2026-06-30\",\n"
                + "  \"price\": 48.20,\n"
                + "  \"sharesOutstandingMillion\": 120,\n"
                + "  \"financialsMillion\": {\n"
                + "    \"revenueTtm\": 1850,\n"
                + "    \"revenuePreviousTtm\": 1520,\n"
                + "    \"grossProfitTtm\": 888,\n"
                + "    \"operatingIncomeTtm\": 296,\n"
                + "    \"netIncomeTtm\": 222,\n"
                + "    \"netIncomePreviousTtm\": 174,\n"
                + "    \"freeCashFlowTtm\": 250,\n"
                + "    \"cash\": 610,\n"
                + "    \"totalDebt\": 420,\n"
                + "    \"shareholdersEquity\": 980,\n"
                + "    \"currentAssets\": 940,\n"
                + "    \"currentLiabilities\": 510\n"
                + "  },\n"
                + "  \"operatingFacts\": [\n"
                + "    \"Subscription revenue is 68% of total revenue\",\n"
                + "    \"Customer retention was 94% for the latest twelve months\",\n"
                + "    \"The largest customer represents 11% of revenue\"\n"
                + "  ],\n"
                + "  \"knownRisks\": [\n"
                + "    \"Enterprise software spending may slow\",\n"
                + "    \"Two competitors introduced lower-priced products\",\n"
                + "    \"International revenue creates foreign-exchange exposure\"\n"
                + "  ],\n"
                + "  \"notice\": \"Fictional data for the AVR integration example\"\n"
                + "}";
    }
}
