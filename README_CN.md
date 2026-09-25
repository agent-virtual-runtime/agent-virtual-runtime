# Agent Virtual Runtime（AVR）

[English](README.md) | [简体中文](README_CN.md)

维护者发布 Maven Central 请参阅 [发布指南](RELEASING.md)。

文档导航：[全局架构与边界](ARCHITECTURE.md) · [完整接入指南](INTEGRATION.md) · [可运行工作台](avr-examples/README.md) · [版本记录](CHANGELOG.md)

> 面向中心化 AI Agent 的轻量级虚拟执行环境。

Agent Virtual Runtime 是一个兼容 Java 11 及以上版本的 Agent Runtime 框架。它为 AI Agent 提供由文件、命令、Skill、Artifact 和其他 Agent 组成的虚拟世界，无需为每次运行创建容器或虚拟机。AVR 负责执行标准 Agent Loop，接入应用继续掌控身份体系、Workspace 命名、隔离策略和存储结构。

AVR 的定位是嵌入现有 Java 服务，而不是再启动一个独立 Agent Server。宿主应用为每次请求解析 Workspace，然后调用注入的 `Agent` 或由 `AgentFactory` 创建的 Agent；AVR 负责模型与 Tool 循环并输出运行事件，HTTP API、认证、配额、密钥和业务隔离仍由宿主应用掌控。完整职责边界和执行链路见 [ARCHITECTURE.md](ARCHITECTURE.md)。

### 选择接入方式

| 场景 | 推荐入口 |
| --- | --- |
| Spring Boot Web 服务 | 引入 `avr-spring-boot-starter`，配置 YAML，注入 `Agent` 或 `AgentFactory` |
| 普通 Java 应用 | 直接装配 `OpenAiLlm`、`WorkspaceTools`、`AgentLoop` 和 `Agent` |
| 已有内部存储 | 实现 `Workspace`，或用 `ObjectStore` 适配 `ObjectWorkspace` |
| 其他模型协议 | 实现 `Llm`，无需改动 Runtime 和 Tool |
| 产品 UI 或 SSE 服务 | 消费 `RuntimeEventListener`，参考 `avr-examples` 的完整工作台 |

## V1.0 能力

- 同步和异步 Agent 执行；
- 支持原生 Tool Calling 和 JSON Schema 的 Agent Loop；
- 同一轮独立 Tool 并发执行，并按原始调用顺序回填结果；
- Workspace 写操作串行屏障、Tool 超时和无进展熔断；
- 不可变执行上下文和可替换的授权策略；
- 用于日志、SSE 和可观测性的非侵入 Runtime 事件监听；
- 请求级 Skill 注入和 Skill 注册；
- 内存、宿主机磁盘和对象存储 Workspace；
- 目录创建、浏览、复制、移动、合并、重命名和递归删除；
- 文件创建、读取、覆盖、复制、移动、重命名和删除；
- 面向超长文件的范围读取、检索、追加、行插入、行替换和行删除；
- 通过文件/目录 Function Call 直接操作 Workspace，以及可选的受控 `http.get`；不启动宿主机进程；
- 具有明确入口文件、磁盘与对象存储持久化元数据的 HTML/CSS/JS Artifact 快照；
- 共享虚拟 Workspace 的具名子 Agent 委派；
- 同步和异步任务的协作式取消；
- 可选、有容量上限的运行状态与事件内存投影；
- Spring Boot YAML 配置绑定和 Agent 自动装配。

AVR 不是内核沙箱，也不执行任意二进制程序。网络、数据库、Git、对象存储和业务 API 应作为具有明确参数和权限检查的 Tool 接入。

## 环境要求与构建

- JDK 11 或更高版本
- Maven 3.6 或更高版本

项目使用 Lombok 精简 Getter/Setter，依赖范围为 `provided`，不会成为 AVR 的运行时依赖。通过 Maven 构建无需额外操作；IDE 中需要启用注解处理。

```bash
mvn clean verify
```

## Spring Boot 接入

引入 Starter：

以下坐标在 `1.0.2` 正式发布到 Maven Central 后可直接使用。

```xml
<dependency>
    <groupId>io.github.agent-virtual-runtime</groupId>
    <artifactId>avr-spring-boot-starter</artifactId>
    <version>1.0.2</version>
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
    max-steps: 30
    max-step-multiplier: 3
    loop-timeout: 90m
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

DefaultToolRegistry.Builder toolBuilder = DefaultToolRegistry.builder();
WorkspaceTools.defaults().forEach(toolBuilder::register);
ToolRegistry tools = toolBuilder.build();

AgentRuntime runtime = new AgentLoop(llm, tools);

Agent agent = Agent.builder()
        .name("report-agent")
        .runtime(runtime)
        .workspace(workspace)
        .skill(new Skill(
                "report-writing",
                "生成具有数据依据的 HTML 报告。",
                Collections.singletonList("file.op")))
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

`AgentRequest.builder().webSearch(true)` 默认启用 AVR 的 `web.search` Function Tool。
应用只需实现并注入 `WebSearchProvider`，即可接入公共搜索 API 或公司内部搜索组件，
不会把供应商专有协议混入模型请求。只有使用支持托管搜索协议的 `Llm` 实现时，才应显式
配置 `.webSearchMode(WebSearchMode.NATIVE)`。`OpenAiLlm` 是 Chat Completions 适配器，
会在发送请求前拒绝 native 模式，避免向 MiniMax 等兼容网关发送无 `function` 的工具。

`apiUrl` 可以是 `https://api.openai.com/v1` 这样的基础地址，也可以是完整的 `/chat/completions` 地址。流式返回默认开启；`OpenAiLlm` 消费模型 SSE，将正文增量发布为 `model.delta`，可选的 `reasoning_content` 单独发布为 `model.reasoning_delta`，并根据 Tool Call Index 聚合分片的 Function Call。对于不支持 SSE 的服务，可以设置 `stream(false)`。

### Tool Calling 流程

1. AVR 将每个 `ToolDefinition` 转换为 OpenAI Function Tool；
2. 模型返回一个或多个包含 ID、名称和 JSON 参数的 `tool_calls`；
3. AVR 将 assistant 消息和原始 Tool Call 写入对话历史；
4. 相邻的 `PARALLEL` Tool 在配置的线程池并发执行，`SEQUENTIAL` Tool 形成串行屏障；
5. 每个 Tool Result 根据对应的 `tool_call_id`，按原始调用顺序写回模型上下文；
6. AVR 再次调用模型，直到获得最终回答或触发 Loop 保护机制。

`max-steps` 是软上限，不再是复杂任务的机械终点。只要 Tool 调用仍产生新的成功结果，Loop 可以继续执行到 `max-steps × max-step-multiplier` 的安全硬上限；完全相同的工具参数与结果重复出现不会被当作进展，连续无进展仍会提前熔断。`loop-timeout` 提供独立的整体时间预算。

可通过 `AgentLoopOptions` 配置 Tool 线程池、单 Tool 超时、Loop 整体超时、自动延展倍数、空响应重试和无进展熔断。取消任务时，可以创建 `CancellationSource`，将 Token 传给 Agent，并由业务调用 `cancel()`。

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

标准文件能力不是示例工程的私有逻辑，而是由 `Workspace` 契约和 `avr-core` Tool 统一提供：

- `directory.manage`：`list`、`create`、`copy`、`move`、`merge`、`delete`；目录重命名使用 `move`；
- `file.op`：统一处理 `list`、`read`、`search`、`write`、`append`、`insert`、`replace_text`、`replace_lines`、`delete_lines`、`copy`、`move`、`delete`；文件创建使用 `write`，重命名使用 `move`；
- `artifact.commit`：提交具有入口文件的不可变产物快照。
- `plan.manage`：`create`、`revise`、`update`、`check`，用于任务计划和交付校验。

`file.op.read` 允许设置 `startLine`、`endLine` 和 `maxChars`，模型可以分段阅读和持续写入超长报告，避免把完整文件塞入一次模型上下文。`DiskWorkspace` 的追加、范围读取和检索采用流式磁盘访问；行级改写会安全地通过 Workspace 语义重写目标内容。`replace_text` 默认要求旧文本唯一匹配，避免 Agent 在存在歧义时误改。内存、磁盘和对象存储实现共享同一套语义。

业务可以对 `AgentRequest` 开启 `planMode(true)`。运行时会要求先用 `plan.manage.create` 建立步骤，再实际调用文件/目录等工具；`update(status=done)` 时，Runtime 会自动匹配本轮真实成功的工具调用，模型不需要填写工具调用 ID。可用 `verifyPath`、`verifyDirectory`、`minBytes`、`absentPath` 验证交付状态，其中 `minBytes` 既能校验单文件，也能递归汇总目录内的全部文件。待完成步骤如果选错交付路径，可通过 `revise` 修订，避免重复执行必然失败的校验。全部步骤完成后还须调用 `plan.manage.check`，否则 Runtime 不接受模型的“已完成”回复。此机制不等于语义真实性验证：复杂业务条件仍应通过 `completionCheck` 或应用自定义校验实现。示例工作台对文件变更任务启用计划模式。

例如，生成至少 100 KB 的单文件报告可创建步骤 `{"id":"report","description":"生成报告","verifyPath":"/workspace/report.html","minBytes":102400}`；拆分为多个章节时则使用 `{"id":"report","description":"生成报告章节","verifyDirectory":"/workspace/report","minBytes":102400}`。执行 `file.op` 写入/追加后，调用 `update` 将步骤设为 `done`，最后调用 `check`。目录迁移可用 `verifyDirectory` 验证目标目录、`absentPath` 验证原目录已移除。

接入应用也可以使用内部存储客户端直接实现 `Workspace`。`ObjectWorkspace` 只是基于轻量 `ObjectStore` 适配器提供的可选通用实现，不强制引入或绑定 S3、OSS、COS、MinIO SDK。

`MemoryWorkspace` 的文件和 Artifact 只跟随当前 Java 对象生命周期存在。`DiskWorkspace` 会在配置的根目录下持久化文件和 Artifact 快照，`ObjectWorkspace` 会在配置的对象 Key 前缀下持久化两者；使用相同根目录或前缀重新创建 Workspace 后，`artifacts()` 可以恢复。内部 `/.avr` 命名空间由 AVR 保留，不会暴露给 Agent。

可选的 `http.get` 仅在应用提供 `VirtualHttpClient` 时注册。域名白名单、认证、超时和审计由应用实现；文件列表、读取、检索和编辑直接使用文件/目录 Function Call，不再包装成命令。

## 多 Agent 协同

`AgentTaskManager` 是与具体业务无关的子 Agent 注册表和任务生命周期管理器。应用自行决定 Agent 的名称、专业能力、模型、Skill 和 Runtime；AVR 不限制组织关系。`agent.manage` 将以下动作作为标准 Tool 暴露给主 Agent：

- `list_agents`、`list_tasks`；
- `create`：用当前请求的 Workspace 和 ExecutionContext 创建子任务；
- `execute`：异步启动，可选择短暂等待结果；
- `result`：读取状态、runId、结果、错误和时间；
- `cancel`：协作式取消任务。

```java
AgentTaskManager manager = new AgentTaskManager()
        .register("research-agent", "检索与事实梳理", researchRuntime)
        .register("writer-agent", "长报告写作", writerRuntime);

Tool agentTool = new AgentManageTool(manager);
```

子 Agent 与主 Agent 共享当前任务传入的虚拟 Workspace，但身份映射、授权、配额和 Workspace 解析仍由宿主应用控制。`avr-examples` 注册了 research/writer 两个示例 Agent，演示主 Agent 将其作为 Tool 调度。

## 非侵入式运行观测

AVR 不启动独立 HTTP Server，也不在 `Agent` 上增加状态查询或监听方法。监听器注册在 Runtime 层：非 Spring 应用在创建 `AgentLoop` 时传入 `RuntimeEventListener`，Spring Boot 应用只需声明监听器 Bean，Starter 会自动收集。

```java
RuntimeEventListener listener = event ->
        System.out.println(event.getRunId()
                + " " + event.getType());

AgentRuntime runtime = new AgentLoop(
        llm,
        tools,
        ToolPolicy.allowAll(),
        AgentLoopOptions.defaults(),
        Collections.singletonList(listener));
```

事件包含 `runId`、Agent 名称、Workspace ID、有序序号、发生时间、状态、类型、说明和结构化属性。每次 Tool 调用先发 `tool.started`，再发 `tool.completed` 或 `tool.failed`，使用同一个 `toolCallId` 原位更新展示。Tool 事件还包含 `toolName`、`displayName`、`displayType`、脱敏后的参数摘要、耗时、成功状态和有界结果预览；工具可通过 `ToolResult.success(content, rowCount, displayType)` 为单次结果指定行数与展示类型（`TEXT`、`CODE`、`JSON`、`TABLE`、`TREE`、`MARKDOWN`）。前端可以据此展示一行状态及悬浮结果。监听器异常不会改变 Agent 的执行结果。开发者可以自行把事件写入日志、指标、OpenTelemetry、数据库、MQ，或者转换为 SSE 和 WebSocket。

`InMemoryRunTracker` 是可选的有界内存投影，可查询 `RunSnapshot`、运行中的任务以及有限的历史事件。它不会自动启用；需要持久化时，应自行实现 `RuntimeEventListener`。HTTP Controller、认证、配额以及 Artifact 文件响应均由宿主应用负责。

## 模块

- `avr-api`：公共 API 和 SPI；
- `avr-core`：Agent Loop、Tool 注册和内置文件 Tool；
- `avr-storage`：在一个依赖和包中提供全部内置 Workspace 实现；
- `avr-command`：可选的受控 HTTP 读取工具；
- `avr-model-openai`：OpenAI 兼容模型、SSE 和原生 Tool Calling；
- `avr-spring-boot-starter`：Spring Boot 配置绑定、监听器发现和 Agent 自动装配；
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

- 全局架构与职责边界：[ARCHITECTURE.md](ARCHITECTURE.md)
- 完整接入说明：[INTEGRATION.md](INTEGRATION.md)
- 可部署工作台说明：[avr-examples/README.md](avr-examples/README.md)
- Spring Boot Starter：[avr-spring-boot-starter/README.md](avr-spring-boot-starter/README.md)
- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 安全策略：[SECURITY.md](SECURITY.md)
- 行为准则：[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

提交信息遵循 Gitmoji Conventional Commit，例如：`✨ feat: add an object-storage workspace`。

本项目使用 Apache License 2.0，详见 [LICENSE](LICENSE)。
