# Agent Virtual Runtime 接入指南

本文介绍如何把 Agent Virtual Runtime（AVR）嵌入 Java 应用。AVR 要求 JDK 11 或更高版本，应用负责选择模型、组织 Workspace 标识以及实现自身的认证和业务权限。

开始前建议先阅读 [全局架构](ARCHITECTURE.md)。AVR 是嵌入式 Runtime，不会自行启动公共 HTTP Server：接入方把模型、Workspace、Tool 和策略装配给 Runtime，再由自己的 Controller、RPC 或任务系统调用。

| 目标 | 对应章节 |
| --- | --- |
| Spring Boot 配置后直接注入 | 第 2 节 |
| 普通 Java 手动装配 | 第 3 节 |
| 模型、SSE 和联网搜索 | 第 4 节 |
| 标准 Tool 与自定义 Tool | 第 5、6 节 |
| Workspace、长文件和 Artifact | 第 7 节 |
| Skill、事件与前端协议 | 第 8、9、12 节 |
| 多轮计划和多 Agent | 第 10、11 节 |

## 1. Maven 依赖

AVR 按能力拆分模块。发布到 Maven 仓库后，通常至少引入 Core、统一 Storage 模块和一个模型实现：

```xml
<properties>
    <avr.version>1.0.2</avr.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.agent-virtual-runtime</groupId>
        <artifactId>avr-core</artifactId>
        <version>${avr.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.agent-virtual-runtime</groupId>
        <artifactId>avr-storage</artifactId>
        <version>${avr.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.agent-virtual-runtime</groupId>
        <artifactId>avr-model-openai</artifactId>
        <version>${avr.version}</version>
    </dependency>
</dependencies>
```

可选模块：

| 模块 | 用途 |
| --- | --- |
| `avr-api` | 公共接口；已由其他 AVR 模块传递依赖 |
| `avr-core` | Agent Loop、Tool 注册和内置文件工具 |
| `avr-storage` | 单一依赖，包含内存、磁盘和对象存储 Workspace 实现 |
| `avr-command` | 可选的受控 HTTP 读取工具，不启动系统进程 |
| `avr-model-openai` | OpenAI Chat Completions、SSE 和 Function Call |

在 Maven 正式发布前，可以从源码执行 `mvn clean install` 安装到本地仓库。

## 2. Spring Boot 接入

Web 服务通常只需引入 Starter：

```xml
<dependency>
    <groupId>io.github.agent-virtual-runtime</groupId>
    <artifactId>avr-spring-boot-starter</artifactId>
    <version>${avr.version}</version>
</dependency>
```

`application.yml`：

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
      max-retries: 2
  agent:
    name: report-agent
    max-steps: 30
    max-step-multiplier: 3
    max-no-progress-rounds: 5
    loop-timeout: 90m
    tool-timeout: 2m
    instructions: |
      使用 Workspace 和已注册 Tool 完成任务。
      文件变更后必须核对最终路径，不得凭空声称已经完成。
  workspace:
    type: disk
    id: default-workspace
    path: ${AVR_WORKSPACE_DIR:./data/avr-workspace}
```

配置单一 Workspace 时，Starter 会自动创建 `OpenAiConfig`、`Llm`、标准 Workspace Tool、`ToolRegistry`、`AgentLoopOptions`、`AgentRuntime`、`AgentFactory`、`Workspace` 和 `Agent`。业务 Service 可以直接注入：

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

中心化服务通常需要请求级 Workspace。此时不要配置全局 `avr.workspace.type`，由应用完成身份校验和 Workspace 映射，再使用 `AgentFactory`：

```java
Workspace workspace = workspaceResolver.resolve(requestContext);
Agent agent = agentFactory.create(workspace);
AgentResult result = agent.input(prompt);
```

自定义 Bean 会替换或扩展默认装配：

- 声明 `Llm` 替换默认 `OpenAiLlm`；
- 声明任意 `Tool` Bean 自动进入 `ToolRegistry`；
- 声明 `ToolPolicy` 实现请求级授权；
- 声明 `Skill` Bean 注入领域知识；
- 声明 `RuntimeEventListener` Bean 接入日志、SSE、MQ 或指标；
- 声明 `WebSearchProvider` Bean 自动注册 `web.search`；
- 声明 `VirtualHttpClient` Bean 自动注册受控 `http.get`。

## 3. 非 Spring 最小接入

```java
import com.avr.api.Agent;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.ToolRegistry;
import com.avr.api.Workspace;
import com.avr.core.AgentLoop;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.FileOpTool;
import com.avr.core.tool.DirectoryTool;
import com.avr.core.tool.PlanTool;
import com.avr.model.openai.OpenAiConfig;
import com.avr.model.openai.OpenAiLlm;
import com.avr.storage.MemoryWorkspace;

OpenAiConfig modelConfig = OpenAiConfig.fromYaml();

OpenAiLlm llm = new OpenAiLlm(modelConfig);

ToolRegistry tools = DefaultToolRegistry.builder()
        .register(new FileOpTool())
        .register(new DirectoryTool())
        .register(new PlanTool())
        .register(new CommitArtifactTool())
        .build();

AgentRuntime runtime = new AgentLoop(llm, tools);
Workspace workspace = new MemoryWorkspace("job-20260917-001");

Agent agent = Agent.builder()
        .name("report-agent")
        .runtime(runtime)
        .workspace(workspace)
        .build();

AgentResult result = agent.input(
        "读取 /inputs/data.txt，生成 /report/index.html，并提交为 Artifact");
```

直接构建 `AgentRequest` 时可使用 `.model("模型 ID")` 覆盖默认模型。调用
`.webSearch(true)` 时，运行时仅暴露标准 `web.search` Function Tool；应用需要提供
`WebSearchProvider` Bean。`.webSearchMode(WebSearchMode.NATIVE)` 留给支持厂商托管搜索
协议的 `Llm` 实现，Chat Completions 适配器不会伪造 hosted tool。

```java
@Bean
WebSearchProvider webSearchProvider(CompanySearchClient client) {
    return (request, context) -> client.search(
            request.getQuery(), request.getMaxResults());
}
```

Spring Boot 检测到该 Bean 后会自动注册 `web.search`。未注册 Provider 时，runtime 模式
会在调用模型前报出明确配置错误，而不会让模型假装已经搜索。

升级提示：旧版的 `ReadFileTool`、`WriteFileTool`、`ListFilesTool`、`CopyFileTool`、`MoveFileTool`、`DeleteFileTool` 和 `DelegateAgentTool` 已从源码移除。文件操作统一迁移到 `FileOpTool`（`file.op`，用 `op` 指定动作），多 Agent 协同使用 `AgentManageTool`。这是 Java API 的不兼容变更，已有接入方升级依赖时需要调整导入和注册代码。

调用前可以由应用写入输入文件：

```java
workspace.writeText("/inputs/data.txt", "revenue=120");
```

AVR 不解释 Workspace ID。它可以是任务 ID、随机字符串、哈希值或业务自行定义的多级路径映射。

## 4. OpenAI 兼容模型

### application.yml

SDK 可以从类路径根目录的 `application.yml` 加载 `avr.llm.openai`：

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
      headers: {}
```

```java
OpenAiConfig config = OpenAiConfig.fromYaml();
OpenAiLlm llm = new OpenAiLlm(config);
```

支持 `${NAME}` 和 `${NAME:default}` 占位符。查找顺序为 JVM System Property、环境变量、占位符默认值。没有默认值的占位符未配置时会立即失败，不会带着空 API Key 启动。

也可以读取自定义类路径资源或外部配置文件：

```java
OpenAiConfig classpathConfig = OpenAiConfig.fromYaml("agent-model.yml");
OpenAiConfig externalConfig = OpenAiConfig.fromYaml(
        Paths.get("/etc/avr/application.yml"));
```

YAML 支持 `api-url`、`api-key`、`model`、`stream`、`temperature`、`max-tokens`、`timeout`、`max-retries` 和 `headers`。`timeout` 接受 `500ms`、`30s`、`5m`、`2h` 或 ISO-8601 Duration。`max-retries` 默认 2，仅对模型端返回 429/500/502/503/504 且尚未开始 SSE 输出的请求重试；设置为 0 可关闭。模型服务持续报错时仍会终止运行，不会无限重试。

### Java 初始化

动态模型路由或密钥服务场景可以继续使用 Builder：

`apiUrl` 同时接受服务根地址和完整接口地址：

```java
.apiUrl("https://api.openai.com/v1")
// 或
.apiUrl("https://model.example.com/v1/chat/completions")
```

对于需要额外认证头的兼容服务：

```java
OpenAiConfig config = OpenAiConfig.builder()
        .apiUrl("https://model.example.com/v1")
        .model("model-name")
        .header("X-Organization", "example")
        .header("Authorization", "custom-token")
        .build();
```

如果同时设置 `apiKey` 和自定义 `Authorization`，自定义 Header 最终生效。`apiKey` 可以为空，便于接入本地模型服务。

### 模型 SSE

模型流式响应默认开启，也可以显式设置 `.stream(true)`。`OpenAiLlm` 会：

1. 发送 `stream=true`；
2. 持续读取 `text/event-stream`；
3. 拼接 `delta.content`；
4. 按 `tool_calls[index]` 聚合分片；
5. 分别拼接 Function Call 的 `id`、`name` 和 `arguments`；
6. 处理 `finish_reason` 和 `[DONE]`；
7. 把正文增量发布为 `model.delta`；若服务端提供 `reasoning_content`，则独立发布 `model.reasoning_delta`，不混入最终正文；
8. Function Call 完整后交给 Agent Loop 执行。

不支持 SSE 的兼容服务可以使用：

```java
.stream(false)
```

## 5. 标准 Tool 与自定义 Tool

`WorkspaceTools.defaults()` 返回 AVR 的标准 Workspace 能力：

| Tool | 操作 | 用途 |
| --- | --- | --- |
| `file.op` | `list`、`read`、`search`、`write`、`append`、`insert`、`replace_text`、`replace_lines`、`delete_lines`、`copy`、`move`、`delete` | 文件生命周期与长文本编辑 |
| `directory.manage` | `list`、`create`、`copy`、`move`、`merge`、`delete` | 目录全生命周期；重命名使用 `move` |
| `artifact.commit` | 提交目录和入口文件 | 生成不可变 HTML/CSS/JS 等产物快照 |
| `plan.manage` | `create`、`revise`、`update`、`check` | 建立计划并用真实执行证据校验完成状态 |

```java
DefaultToolRegistry.Builder registry = DefaultToolRegistry.builder();
WorkspaceTools.defaults().forEach(registry::register);
ToolRegistry tools = registry.build();
```

这些 Tool 按领域收敛，模型通过 `op` 选择动作。文件能力不再重复包装成 `ls`、`cat`、`wc` 等虚拟命令。

### 自定义业务 Tool

```java
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;

public final class QueryOrderTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "order.query",
            "Query one order by its identifier",
            "{\"type\":\"object\","
                    + "\"properties\":{\"orderId\":{\"type\":\"string\"}},"
                    + "\"required\":[\"orderId\"]}");

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String orderId = call.requireString("orderId");
        return ToolResult.success("{\"orderId\":\"" + orderId
                + "\",\"status\":\"PAID\"}");
    }
}
```

注册后，AVR 会把 `ToolDefinition` 转成 OpenAI Function Tool，并负责 Function Call 回调：

```java
ToolRegistry tools = DefaultToolRegistry.builder()
        .register(new QueryOrderTool())
        .build();
```

模型返回的工具参数属于不可信输入。Tool 应校验参数，并通过 `ToolResult.failure(...)` 返回可供模型理解的错误。

工具可在 `ToolDefinition` 指定默认 `displayName` 和 `ToolDisplayType`，供接入方 UI 展示。若同一工具的不同操作返回不同结构，可用 `ToolResult.success(content, rowCount, displayType)` 覆盖单次结果；支持 `TEXT`、`CODE`、`JSON`、`TABLE`、`TREE`、`MARKDOWN`。`rowCount` 是结果摘要中的业务行数，由工具给出，不要求前端猜测 JSON 内容。

## 6. 并发与串行 Tool

Tool 默认使用 `PARALLEL`。同一模型响应中的相邻并行 Tool 会并发执行，但结果始终按照原始 `tool_calls` 顺序写回模型上下文。

修改共享状态的 Tool 应声明为串行：

```java
@Override
public ToolExecutionMode executionMode() {
    return ToolExecutionMode.SEQUENTIAL;
}
```

串行 Tool 会成为执行屏障。AVR 内置的写入、复制、移动、删除和 Artifact 提交工具已经使用串行模式。

可以为 Agent Loop 提供独立线程池和超时：

```java
ExecutorService toolExecutor = Executors.newFixedThreadPool(16);

AgentLoopOptions options = AgentLoopOptions.builder()
        .toolExecutor(toolExecutor)
        .toolTimeout(Duration.ofMinutes(10))
        .loopTimeout(Duration.ofMinutes(90))
        .maxStepMultiplier(3)
        .maxEmptyResponses(1)
        .maxNoProgressRounds(5)
        .build();

AgentRuntime runtime = new AgentLoop(
        llm,
        tools,
        ToolPolicy.allowAll(),
        options);
```

`AgentRequest.maxSteps` 或 Spring 的 `avr.agent.max-steps` 表示软上限。有新的成功 Tool 结果时，Loop 可自动延展到软上限乘以 `maxStepMultiplier`；重复的相同调用结果不算进展，连续无进展仍由 `maxNoProgressRounds` 提前熔断。整体运行时间由 `loopTimeout` 独立限制。

线程池生命周期由接入方管理。应用关闭时应调用 `toolExecutor.shutdown()`。

## 7. Workspace

`com.avr.api.Workspace` 是 AVR 唯一的虚拟文件系统接口。所有内置实现统一位于 `avr-storage` 模块的 `com.avr.storage` 包，不需要为不同存储方式选择不同的 Maven 依赖。

### 内存 Workspace

```java
import com.avr.storage.MemoryWorkspace;

Workspace workspace = new MemoryWorkspace("opaque-workspace-id");
```

### 磁盘 Workspace

```java
import com.avr.storage.DiskWorkspace;

Workspace workspace = new DiskWorkspace(
        "opaque-workspace-id",
        Paths.get("/data/avr/workspaces/job-42"));
```

Agent 只能看到 `/inputs/data.txt` 这样的虚拟绝对路径。`DiskWorkspace` 不会把宿主机根目录暴露给模型，并禁止 `..`、反斜杠和符号链接逃逸。

### 对象存储

公司内部已有统一存储组件时，可以直接实现 `Workspace`，不需要修改 AVR，也不需要继承特定的用户、租户或目录模型：

```java
public final class CompanyWorkspace implements Workspace {
    // 将 Workspace 的虚拟路径映射到公司内部存储组件。
}
```

如果希望复用 AVR 的对象 Key 和虚拟路径映射，可以实现轻量 `ObjectStore` 适配器，再创建 `ObjectWorkspace`：

```java
ObjectStore store = new CompanyObjectStore(companyStorageClient);
Workspace workspace = new ObjectWorkspace(
        "opaque-workspace-id",
        store,
        "application-defined/prefix");
```

`ObjectStore` 是 `ObjectWorkspace` 的底层适配点，不是另一套 VFS API。对 Agent、Tool 和 Agent Loop 而言，唯一可见的文件系统接口仍然是 `Workspace`。对象 Key 的组织方式由接入应用决定，AVR 不约束业务、业务线、用户或 Workspace 的层级关系。

### Workspace 和 Artifact 生命周期

| 实现 | 文件生命周期 | Artifact 生命周期 |
| --- | --- | --- |
| `MemoryWorkspace` | 当前 Java 对象存活期间 | 当前 Java 对象存活期间 |
| `DiskWorkspace` | 持久化到配置的磁盘根目录 | 元数据和不可变内容快照持久化到同一根目录 |
| `ObjectWorkspace` | 持久化到配置的对象 Key 前缀 | 元数据和不可变内容快照持久化到同一前缀 |

对于磁盘和对象存储实现，使用相同根目录或对象 Key 前缀重新创建 Workspace 后，`artifacts()` 会重新加载已提交的 Artifact。Artifact 使用提交时的内容快照，后续修改 Workspace 文件不会改变已有 Artifact。

AVR 使用保留的 `/.avr` 命名空间保存内部元数据。这个目录不会出现在 `list()` 结果中，Agent 也不能读取、写入或删除其中内容。自定义 `Workspace` 实现应提供等价的元数据隔离和持久化语义。

## 8. Skill

Skill 是注入本次运行的可复用指令：

```java
Skill reportSkill = new Skill(
        "report-writing",
        "生成结构清晰、包含数据来源的 HTML 报告。",
        Arrays.asList("file.op", "artifact.commit"));

Agent agent = Agent.builder()
        .name("report-agent")
        .runtime(runtime)
        .workspace(workspace)
        .skill(reportSkill)
        .build();
```

`tools` 字段是提示信息，不代替 `ToolRegistry` 注册和 `ToolPolicy` 授权。

## 9. 运行事件与异步调用

运行观测是 Runtime 级能力，不挂在 `Agent` 或 `AgentRequest` 上。非 Spring 应用在创建 `AgentLoop` 时注册监听器：

```java
RuntimeEventListener listener = event -> {
    System.out.println(event.getSequence()
            + " " + event.getRunId()
            + " " + event.getAgent()
            + " " + event.getWorkspaceId()
            + " " + event.getType()
            + " " + event.getDetail());
};

AgentRuntime runtime = new AgentLoop(
        llm,
        tools,
        ToolPolicy.allowAll(),
        AgentLoopOptions.defaults(),
        Collections.singletonList(listener));

Agent agent = Agent.builder()
        .runtime(runtime)
        .workspace(workspace)
        .build();

CompletionStage<AgentResult> future =
        agent.inputAsync("生成分析报告");
```

Spring Boot 应用只需声明 Bean，Starter 会将所有 `RuntimeEventListener` Bean 注册到默认 `AgentLoop`：

```java
@Bean
public RuntimeEventListener auditListener(AuditService auditService) {
    return event -> auditService.record(event);
}
```

常见事件包括：

- `run.created`、`run.preparing`：创建并准备运行；
- `model.call`、`model.completed`：模型调用开始与完成；
- `model.delta`：模型 SSE 正文增量；
- `model.reasoning_delta`：可选的思考增量，仅在模型提供 `reasoning_content` 时产生；
- `tool.started`：开始执行 Tool；
- `tool.completed`、`tool.failed`：Tool 执行结果；
- `run.completed`、`run.failed`、`run.cancelled`：运行终态。

内置名称也可以通过 `RuntimeEventTypes` 常量引用，事件类型仍使用字符串，以便自定义 Runtime 扩展自己的事件。

`RuntimeEvent` 是不可变对象，包含运行、Agent 和 Workspace 标识、有序序号、状态、发生时间、事件类型、说明以及结构化 `attributes`。模型完成事件会携带步骤、耗时、Tool Call 数和截断状态；Tool 开始与结束事件共享 `toolCallId`，可在前端更新同一行。结束事件携带耗时、成功状态、`displayName`、`displayType`、可选 `rows` 和有界 `resultPreview`。Web/SSE 适配层应序列化 `occurredAt`，以保留开始和结束时间。

监听器仅用于观察，抛出的运行时异常会被 Runtime 隔离，不会改变 Agent 结果。监听器默认在事件产生线程执行，因此数据库、网络或 MQ 等耗时操作应在监听器内部切换到应用管理的线程池，并自行处理队列容量和背压。

### 可选的状态查询投影

`InMemoryRunTracker` 同时是一个监听器。它按照配置上限保存运行快照和历史事件，但不会自动启用：

```java
InMemoryRunTracker tracker = new InMemoryRunTracker(
        1_000,
        500);

AgentRuntime runtime = new AgentLoop(
        llm,
        tools,
        ToolPolicy.allowAll(),
        AgentLoopOptions.defaults(),
        Collections.singletonList(tracker));

Optional<RunSnapshot> snapshot = tracker.find(runId);
List<RuntimeEvent> history = tracker.events(runId);
List<RunSnapshot> running = tracker.running();
```

Spring 应用可以把它声明为 Bean，既会被 Starter 自动注册，也能注入业务 Controller：

```java
@Bean
public InMemoryRunTracker runTracker() {
    return new InMemoryRunTracker(1_000, 500);
}
```

如果需要持久化、跨节点聚合或实时推送，直接实现 `RuntimeEventListener` 并写入数据库、Redis、Kafka、SSE 或 WebSocket。AVR 不启动 HTTP Server，也不规定应用的 URL、鉴权、Workspace 解析和响应协议。

## 10. 多轮会话、计划与完成校验

AVR 不强制提供某一种会话存储。应用保存普通用户/助手消息，在下一轮通过 `history` 传入；模型 Tool Call 和 Tool Result 由当前运行内部维护，不应伪造后重新注入：

```java
AgentRequest request = AgentRequest.builder()
        .runId(runId)
        .agent("report-agent")
        .prompt(prompt)
        .workspace(workspace)
        .history(previousMessages)
        .model(selectedModel)
        .maxSteps(30)
        .planMode(true)
        .webSearchMode(WebSearchMode.RUNTIME)
        .completionCheck(ws -> ws.exists("/workspace/report/index.html"))
        .build();

AgentResult result = runtime.run(request);
```

`history` 只接受不含 Tool Call 的普通 `USER` 和 `ASSISTANT` 消息。生产环境应把会话和运行记录持久化；示例工程为了便于理解，只提供有容量上限的进程内会话索引，磁盘 Workspace 文件可以跨进程恢复，但内存中的对话和 SSE 订阅不能替代数据库。

开启 `planMode` 后，模型必须先创建计划，再以真实成功 Tool 调用推进步骤，最后执行 `plan.manage.check`。步骤可以声明：

- `verifyPath`：目标文件必须存在；
- `verifyDirectory`：目标目录必须存在；
- `minBytes`：单文件大小或目录下文件总大小达到要求；
- `absentPath`：旧路径必须已经移除。

计划模式防止“没有执行却声称完成”，但不能证明报告内容在业务语义上正确。财务口径、审批状态等复杂要求应通过 `completionCheck` 或自定义校验 Tool 实现。

## 11. 多 Agent 协同

应用先为专业 Agent 准备独立 `AgentRuntime`，再注册到 `AgentTaskManager`。主 Agent 通过单一 `agent.manage` Tool 管理生命周期：

```java
AgentTaskManager manager = new AgentTaskManager()
        .register("research-agent", "资料检索与事实校验", researchRuntime)
        .register("writer-agent", "长报告编写", writerRuntime);

Tool agentManage = new AgentManageTool(manager);
```

支持 `list_agents`、`list_tasks`、`create`、`execute`、`result` 和 `cancel`。子 Agent 使用当前请求传入的 Workspace 与 `ExecutionContext`，但各 Agent 的模型、Skill、Tool、预算和授权策略仍由应用决定。任务管理器不是租户系统，身份隔离和配额仍应在应用边界完成。

## 12. Artifact、SSE 与产品 UI

生成 Web 页面时，Agent 应在同一虚拟目录中使用相对引用，例如 `index.html` 引用 `styles.css` 和 `app.js`。随后使用 `artifact.commit` 指定目录与入口文件；Workspace 会保存提交时的不可变快照，使预览不受后续文件修改影响。

AVR 只产生运行事件，不规定 HTTP 路由。产品层通常按以下方式映射：

1. `model.delta` 增量追加最终正文；
2. `model.reasoning_delta` 放入独立、可折叠的思考区域；
3. 用相同 `toolCallId` 将 `tool.started` 原位更新为 `tool.completed` 或 `tool.failed`；
4. 根据 `displayType` 渲染文本、代码、JSON、表格、树或 Markdown；
5. Workspace 变更事件触发文件树和当前预览刷新；
6. 将事件按 `runId + sequence` 持久化，浏览器重连时先回放，再订阅实时流。

`avr-examples` 是完整参考实现而不是核心框架的强制 Server。它演示了 Chat SSE、断线续传、多轮历史、模型选择、联网搜索开关、计划面板、Tool 事件、虚拟文件树、文件编辑和 HTML Artifact 预览。接入方可以复用页面和 Controller 思路，也可以仅使用 AVR Runtime 自行定义协议。

## 13. 生产接入建议

- Workspace 的分配、认证、租户隔离和配额由接入应用处理；
- 使用 `ToolPolicy` 做请求级授权，不要仅依赖 Prompt；
- 网络、数据库和业务 API 应包装成显式 Tool；
- 不要在 Tool 中直接执行模型提供的宿主机 Shell 命令；
- 为模型、Tool 和整次任务分别设置超时；
- 记录 `runId`、Tool 调用、耗时和失败结果；
- 不要在事件监听器中执行无界阻塞操作；模型增量和 Tool 信息可能包含敏感内容，落库或外发前应按业务规则脱敏；
- 将 API Key 放入密钥系统或环境变量，不要写入源码；
- 长期运行的集中式服务应使用持久化 Workspace，而不是仅使用内存实现。
