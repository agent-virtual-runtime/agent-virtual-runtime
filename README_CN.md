# Agent Virtual Runtime（AVR）

[English](README.md) | [简体中文](README_CN.md)

> 面向中心化 AI Agent 的轻量级虚拟执行环境。

Agent Virtual Runtime 是一个兼容 Java 11 及以上版本的 Agent Runtime 框架。它为 AI Agent 提供由文件、命令、Skill、Artifact 和其他 Agent 组成的虚拟世界，无需为每次运行创建容器或虚拟机。AVR 负责执行标准 Agent Loop，接入应用继续掌控身份体系、Workspace 命名、隔离策略和存储结构。

## V1.0 能力

- 同步和异步 Agent 执行；
- 支持原生 Tool Calling 和 JSON Schema 的 Agent Loop；
- 同一轮独立 Tool 并发执行，并按原始调用顺序回填结果；
- Workspace 写操作串行屏障、Tool 超时和无进展熔断；
- 不可变执行上下文和可替换的授权策略；
- 用于日志、SSE 和可观测性的运行生命周期事件；
- 请求级 Skill 注入和 Skill 注册；
- 内存、宿主机磁盘和对象存储 Workspace；
- 文件读取、写入、查看、复制、移动和删除；
- 不启动宿主机进程的虚拟 `pwd`、`ls`、`cat`、`grep`、`wc` 和策略控制的 `curl`；
- 具有明确入口文件、磁盘与对象存储持久化元数据的 HTML/CSS/JS Artifact 快照和 HTTP 预览；
- 共享虚拟 Workspace 的具名子 Agent 委派；
- 同步和异步任务的协作式取消；
- 由应用决定 Workspace 解析规则的嵌入式 HTTP 服务；
- Spring Boot YAML 配置绑定和 Agent 自动装配。

AVR 不是内核沙箱，也不执行任意二进制程序。网络、数据库、Git、对象存储和业务 API 应作为具有明确参数和权限检查的 Tool 接入。

## 环境要求与构建

- JDK 11 或更高版本
- Maven 3.6 或更高版本

```bash
mvn clean verify
```

## Spring Boot 接入

引入 Starter：

```xml
<dependency>
    <groupId>org.agentvirtualruntime</groupId>
    <artifactId>avr-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

在 `application.yml` 中配置模型、Agent 和默认 Workspace：

```yaml
avr:
  llm:
    openai:
      api-url: ${AVR_API_URL:https://api.openai.com/v1}
      api-key: ${OPENAI_API_KEY}
      model: ${AVR_MODEL:gpt-4.1-mini}
      stream: true
      temperature: 0.1
      max-tokens: 12000
      timeout: 5m
  agent:
    name: report-agent
    max-steps: 20
    tool-timeout: 2m
    instructions: |
      根据 Workspace 中的资料生成 HTML 报告。
      将最终报告写入 /report/report.html 并提交 Artifact。
  workspace:
    type: disk
    id: report-workspace
    path: ${AVR_WORKSPACE_DIR:./data/report-workspace}
```

配置完成后，可以在业务 Service 中直接注入 `Agent`：

```java
@Service
public class ReportService {
    private final Agent agent;

    public ReportService(Agent agent) {
        this.agent = agent;
    }

    public AgentResult generate(String prompt) {
        return agent.input(prompt);
    }
}
```

Starter 会自动创建 `OpenAiConfig`、`Llm`、内置 Tool、`ToolRegistry`、`AgentRuntime`、`AgentFactory`，以及配置了单一 Workspace 时的 `Workspace` 和 `Agent`。

需要请求级 Workspace 隔离时，不配置全局 Workspace，改为注入 `AgentFactory`：

```java
Workspace workspace = workspaceResolver.resolve(workspaceId);
Agent agent = agentFactory.create(workspace);
AgentResult result = agent.input(prompt);
```

## 非 Spring 最小接入

```java
OpenAiConfig modelConfig = OpenAiConfig.fromYaml();
Llm llm = new OpenAiLlm(modelConfig);

Workspace workspace = new MemoryWorkspace("application-defined-id");

ToolRegistry tools = DefaultToolRegistry.builder()
        .register(new ReadFileTool())
        .register(new WriteFileTool())
        .register(new ListFilesTool())
        .register(new CommitArtifactTool())
        .register(new VirtualCommandTool())
        .build();

AgentRuntime runtime = new AgentLoop(llm, tools);

Agent agent = Agent.builder()
        .name("report-agent")
        .runtime(runtime)
        .workspace(workspace)
        .skill(new Skill(
                "report-writing",
                "生成具有数据依据的 HTML 报告。",
                Collections.singletonList("file.write")))
        .build();

AgentResult result = agent.input(
        "读取 /inputs/data.txt 并创建 /report/index.html");
```

非 Spring 应用既可以通过 `OpenAiConfig.fromYaml()` 读取 YAML，也可以使用 Builder 动态初始化：

```java
OpenAiConfig config = OpenAiConfig.builder()
        .apiUrl("https://api.openai.com/v1")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("your-model-name")
        .stream(true)
        .timeout(Duration.ofMinutes(2))
        .build();
```

## OpenAI 兼容模型

`avr-model-openai` 实现 OpenAI Chat Completions、SSE 流式响应、Function Tool、assistant `tool_calls` 和 Tool Result 消息。

`apiUrl` 可以是 `https://api.openai.com/v1` 这样的基础地址，也可以是完整的 `/chat/completions` 地址。流式返回默认开启；`OpenAiLlm` 消费模型 SSE，将文本增量发布为 `model.delta`，并根据 Tool Call Index 聚合分片的 Function Call。对于不支持 SSE 的服务，可以设置 `stream(false)`。

### Tool Calling 流程

1. AVR 将每个 `ToolDefinition` 转换为 OpenAI Function Tool；
2. 模型返回一个或多个包含 ID、名称和 JSON 参数的 `tool_calls`；
3. AVR 将 assistant 消息和原始 Tool Call 写入对话历史；
4. 相邻的 `PARALLEL` Tool 在配置的线程池并发执行，`SEQUENTIAL` Tool 形成串行屏障；
5. 每个 Tool Result 根据对应的 `tool_call_id`，按原始调用顺序写回模型上下文；
6. AVR 再次调用模型，直到获得最终回答或触发 Loop 保护机制。

可通过 `AgentLoopOptions` 配置 Tool 线程池、超时、空响应重试和无进展熔断。取消任务时，可以创建 `CancellationSource`，将 Token 传给 Agent，并由业务调用 `cancel()`。

## Workspace 隔离

AVR 不假设 Workspace 必须对应用户。接入应用可以使用 `业务/业务线/用户/Workspace`、哈希值、任务 ID 或任意组织方式。框架 API 不限制这些规则。

`Workspace` 是唯一的虚拟文件系统接口。内置的轻量实现统一由 `avr-storage` 模块提供，并位于 `com.avr.storage` 包：

```java
Workspace memory = new MemoryWorkspace("temporary-run");
Workspace disk = new DiskWorkspace(
        "opaque-id",
        Paths.get("/mounted/avr/work-42"));
```

中心化部署可以使用 `MemoryWorkspaceProvider`、`DiskWorkspaceProvider`，或实现 `WorkspaceProvider` SPI。磁盘 Provider 接收应用提供的路径解析逻辑，因此 AVR 不定义用户或租户目录结构。

接入应用也可以使用内部存储客户端直接实现 `Workspace`。`ObjectWorkspace` 只是基于轻量 `ObjectStore` 适配器提供的可选通用实现，不强制引入或绑定 S3、OSS、COS、MinIO SDK。

`MemoryWorkspace` 的文件和 Artifact 只跟随当前 Java 对象生命周期存在。`DiskWorkspace` 会在配置的根目录下持久化文件和 Artifact 快照，`ObjectWorkspace` 会在配置的对象 Key 前缀下持久化两者；使用相同根目录或前缀重新创建 Workspace 后，`artifacts()` 可以恢复。内部 `/.avr` 命名空间由 AVR 保留，不会暴露给 Agent。

可选的虚拟 `curl` 只有在 `VirtualCommandTool` 收到 `VirtualHttpClient` 时才可使用。应用可以通过它实现域名白名单、认证、超时和审计，而不暴露宿主机 Shell。

## HTTP 接入

`AgentRuntimeHttpServer` 提供：

- `GET /health`
- `POST /v1/runs`：同步运行
- `POST /v1/runs/async`：启动后台任务
- `GET /v1/runs/{runId}/events`：SSE 历史回放、实时事件和心跳
- `GET /v1/artifacts/{artifactId}/{relativePath}`：HTML、CSS、JavaScript 和文本预览

应用通过 `WorkspaceResolver` 决定请求对应哪个 Workspace。生产环境应在应用边界增加认证、授权和配额控制。

## 模块

- `avr-api`：公共 API 和 SPI；
- `avr-core`：Agent Loop、Tool 注册和内置文件 Tool；
- `avr-storage`：在一个依赖和包中提供全部内置 Workspace 实现；
- `avr-command`：安全虚拟命令；
- `avr-model-openai`：OpenAI 兼容模型、SSE 和原生 Tool Calling；
- `avr-spring-boot-starter`：Spring Boot 配置绑定和 Agent 自动装配；
- `avr-server`：嵌入式 JDK HTTP 服务；
- `avr-examples`：可运行的端到端示例。

## 运行股票分析示例

```bash
export OPENAI_API_KEY="your-api-key"
export AVR_MODEL="gpt-4.1-mini"
mvn -pl avr-examples -am clean install
```

运行 Spring Boot Web 示例：

```bash
mvn -pl avr-examples spring-boot:run \
  -Dspring-boot.run.main-class=com.avr.examples.StockReportSpringApplication

curl -X POST http://localhost:8080/api/reports/stock
```

运行非 Spring 示例：

```bash
mvn -pl avr-examples exec:java
```

示例使用真实的 OpenAI 兼容模型、财务分析 Skill、磁盘 Workspace、流式 Function Call，并生成和提交 `report.html`。详细说明见 [avr-examples/README.md](avr-examples/README.md)。

## 文档与贡献

- 完整接入说明：[INTEGRATION.md](INTEGRATION.md)
- Spring Boot Starter：[avr-spring-boot-starter/README.md](avr-spring-boot-starter/README.md)
- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 安全策略：[SECURITY.md](SECURITY.md)
- 行为准则：[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

提交信息遵循 Gitmoji Conventional Commit，例如：`✨ feat: add an object-storage workspace`。

本项目使用 Apache License 2.0，详见 [LICENSE](LICENSE)。
