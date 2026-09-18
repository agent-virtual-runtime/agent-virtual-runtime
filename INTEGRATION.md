# Agent Virtual Runtime 接入指南

本文介绍如何把 Agent Virtual Runtime（AVR）嵌入 Java 应用。AVR 要求 JDK 11 或更高版本，应用负责选择模型、组织 Workspace 标识以及实现自身的认证和业务权限。

## 1. Maven 依赖

AVR 按能力拆分模块。发布到 Maven 仓库后，通常至少引入 Core、统一 Storage 模块和一个模型实现：

```xml
<properties>
    <avr.version>1.0.0</avr.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.agentvirtualruntime</groupId>
        <artifactId>avr-core</artifactId>
        <version>${avr.version}</version>
    </dependency>
    <dependency>
        <groupId>org.agentvirtualruntime</groupId>
        <artifactId>avr-storage</artifactId>
        <version>${avr.version}</version>
    </dependency>
    <dependency>
        <groupId>org.agentvirtualruntime</groupId>
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
| `avr-command` | 不启动系统进程的虚拟命令 |
| `avr-model-openai` | OpenAI Chat Completions、SSE 和 Function Call |

在 Maven 正式发布前，可以从源码执行 `mvn clean install` 安装到本地仓库。

## 2. 最小接入

```java
import com.avr.api.Agent;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.ToolRegistry;
import com.avr.api.Workspace;
import com.avr.core.AgentLoop;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.ListFilesTool;
import com.avr.core.tool.ReadFileTool;
import com.avr.core.tool.WriteFileTool;
import com.avr.model.openai.OpenAiConfig;
import com.avr.model.openai.OpenAiLlm;
import com.avr.storage.MemoryWorkspace;

OpenAiConfig modelConfig = OpenAiConfig.fromYaml();

OpenAiLlm llm = new OpenAiLlm(modelConfig);

ToolRegistry tools = DefaultToolRegistry.builder()
        .register(new ReadFileTool())
        .register(new WriteFileTool())
        .register(new ListFilesTool())
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

调用前可以由应用写入输入文件：

```java
workspace.writeText("/inputs/data.txt", "revenue=120");
```

AVR 不解释 Workspace ID。它可以是任务 ID、随机字符串、哈希值或业务自行定义的多级路径映射。

## 3. OpenAI 兼容模型

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

YAML 支持 `api-url`、`api-key`、`model`、`stream`、`temperature`、`max-tokens`、`timeout` 和 `headers`。`timeout` 接受 `500ms`、`30s`、`5m`、`2h` 或 ISO-8601 Duration。

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
7. 把文本增量发布为 `model.delta` 运行事件；
8. Function Call 完整后交给 Agent Loop 执行。

不支持 SSE 的兼容服务可以使用：

```java
.stream(false)
```

## 4. 自定义 Tool

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

## 5. 并发与串行 Tool

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
        .maxEmptyResponses(1)
        .maxNoProgressRounds(5)
        .build();

AgentRuntime runtime = new AgentLoop(
        llm,
        tools,
        ToolPolicy.allowAll(),
        options);
```

线程池生命周期由接入方管理。应用关闭时应调用 `toolExecutor.shutdown()`。

## 6. Workspace

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

## 7. Skill

Skill 是注入本次运行的可复用指令：

```java
Skill reportSkill = new Skill(
        "report-writing",
        "生成结构清晰、包含数据来源的 HTML 报告。",
        Arrays.asList("file.read", "file.write", "artifact.commit"));

Agent agent = Agent.builder()
        .name("report-agent")
        .runtime(runtime)
        .workspace(workspace)
        .skill(reportSkill)
        .build();
```

`tools` 字段是提示信息，不代替 `ToolRegistry` 注册和 `ToolPolicy` 授权。

## 8. 运行事件与异步调用

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
- `model.delta`：模型 SSE 文本增量；
- `tool.started`：开始执行 Tool；
- `tool.completed`、`tool.failed`：Tool 执行结果；
- `run.completed`、`run.failed`、`run.cancelled`：运行终态。

内置名称也可以通过 `RuntimeEventTypes` 常量引用，事件类型仍使用字符串，以便自定义 Runtime 扩展自己的事件。

`RuntimeEvent` 是不可变对象，包含运行、Agent 和 Workspace 标识、有序序号、状态、发生时间、事件类型、说明以及结构化 `attributes`。模型完成事件会携带步骤、耗时、Tool Call 数和截断状态；Tool 事件会携带 Tool Call ID、Tool 名称、耗时和成功状态。

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

## 9. 生产接入建议

- Workspace 的分配、认证、租户隔离和配额由接入应用处理；
- 使用 `ToolPolicy` 做请求级授权，不要仅依赖 Prompt；
- 网络、数据库和业务 API 应包装成显式 Tool；
- 不要在 Tool 中直接执行模型提供的宿主机 Shell 命令；
- 为模型、Tool 和整次任务分别设置超时；
- 记录 `runId`、Tool 调用、耗时和失败结果；
- 不要在事件监听器中执行无界阻塞操作；模型增量和 Tool 信息可能包含敏感内容，落库或外发前应按业务规则脱敏；
- 将 API Key 放入密钥系统或环境变量，不要写入源码；
- 长期运行的集中式服务应使用持久化 Workspace，而不是仅使用内存实现。
