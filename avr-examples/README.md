# AVR Examples

本模块同时提供两类可运行示例：`StockReportAgentExample` 展示接入者如何构建股票分析 Agent；Spring Boot 工作台展示完整的多轮 Chat、SSE 恢复、计划、Tool 事件、虚拟文件树、文件编辑、模型选择、联网搜索和 Artifact 预览。两者都通过完整 `AgentRuntime` 工作，不会绕过 Runtime 直接调用模型。

框架边界先阅读 [全局架构](../ARCHITECTURE.md)，Java/Spring API 见 [完整接入指南](../INTEGRATION.md)。本文件专注于如何配置、运行和二次开发示例应用。

示例使用虚构公司和虚构数据，不提供投资建议，也不会联网获取实时行情。

## 1. 使用 application.yml 配置模型

示例配置文件位于 `src/main/resources/application.yml`：

```yaml
avr:
  llm:
    openai:
      api-url: ${AVR_API_URL:https://api.openai.com/v1}
      api-key: ${OPENAI_API_KEY}
      model: ${AVR_MODEL:gpt-4.1-mini}
      stream: true
      temperature: 0.1
      timeout: 5m
      max-retries: ${AVR_MODEL_MAX_RETRIES:2}
      headers: {}
```

代码只需要加载约定配置：

```java
OpenAiConfig modelConfig = OpenAiConfig.fromYaml();
OpenAiLlm llm = new OpenAiLlm(modelConfig);
```

配置值支持 `${ENV_NAME}` 和 `${ENV_NAME:default-value}` 占位符。API Key 通过环境变量传入，不写入代码、YAML 明文或日志：

```bash
export OPENAI_API_KEY="your-api-key"
export AVR_API_URL="https://api.openai.com/v1"
export AVR_MODEL="gpt-4.1-mini"
```

可选配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `OPENAI_API_KEY` | 无 | 模型服务 API Key，由 `application.yml` 引用 |
| `AVR_API_URL` | `https://api.openai.com/v1` | OpenAI 兼容服务地址 |
| `AVR_MODEL` | `gpt-4.1-mini` | 服务支持的模型名称 |
| `AVR_LLM_OPENAI_MAX_TOKENS` | 不设置 | Spring Boot 下可选的单次输出 token 上限；不设置时不在请求中发送 `max_tokens` |
| `AVR_WORKSPACE_DIR` | `avr-examples/target/stock-agent-workspace` | Workspace 对应的宿主机目录 |

示例中的 `avr.agent.max-steps: 30` 是推荐软上限。任务持续产生新的 Tool 结果时，可按 `max-step-multiplier: 3` 动态延展到最多 90 轮；重复调用由无进展熔断器提前终止，整体运行仍受 `loop-timeout: 90m` 限制。

如果使用其他 OpenAI 兼容服务，只需替换 `AVR_API_URL`、`AVR_MODEL` 和对应的 API Key。

也可以加载指定的类路径资源或外部文件：

```java
OpenAiConfig classpathConfig = OpenAiConfig.fromYaml("agent-model.yml");
OpenAiConfig externalConfig = OpenAiConfig.fromYaml(
        Paths.get("/etc/avr/application.yml"));
```

对于动态配置场景，原有 Builder 仍然可用：

```java
OpenAiConfig codeConfig = OpenAiConfig.builder()
        .apiUrl("https://model.example.com/v1")
        .apiKey(secretService.get("model-api-key"))
        .model(modelRouter.currentModel())
        .stream(true)
        .timeout(Duration.ofMinutes(5))
        .build();
```

## 2. 编译

从项目根目录执行：

```bash
mvn -pl avr-examples -am clean install
```

## 3. 运行

### Spring Boot Web 接入

```bash
mvn -pl avr-examples spring-boot:run \
  -Dspring-boot.run.main-class=com.avr.examples.StockReportSpringApplication
```

调用业务接口：

```bash
curl -X POST http://localhost:8080/api/reports/stock
```

启动后也可以打开 `http://localhost:8080/` 使用 Agent 聊天页面。页面调用
`POST /api/chat/stream`，服务端调用 Starter 自动装配的 **AgentRuntime**，
而不是直接调用 `Llm`。每个演示会话拥有独立的 `Workspace`，
并从其 `/skills/chat/SKILL.md` 加载 Skill；Runtime 的模型、工具和运行事件按
`runId` 转成 SSE，页面显示执行轨迹、虚拟文件和 Artifact 数量。

工作台基于 Bootstrap 组件体系，提供深色、浅色和跟随系统三种主题，以及中文、英文
两套平台级文案。品牌标记、头像和用户气泡沿用 Bootstrap 蓝色主题；参考页面只用于对话密度、字体层级和交互布局。Markdown 语法由 WebJar 提供的 Marked（含 GFM 表格）解析，再由 DOMPurify 清理 HTML；本地代码仅为表格添加横向滚动容器，不自行实现 Markdown 语法。它包含左右分栏的 Markdown 对话、位于 Agent 回复上方的可折叠 Tool Call
轨迹、Tool 结果悬浮预览、层级文件树、系统式右键菜单、模型选择、文件/目录全生命周期管理，
以及 HTML Workspace/Artifact 沙箱预览。右侧只保留文件和预览，不重复展示对话中的执行事件。
浏览器只保存不透明会话 ID；默认使用磁盘 Workspace，
文件和 Artifact 位于 `avr-examples/target/chat-workspaces/<sessionId>/`，重启后可以恢复。
对话运行状态由服务端会话持有，不依赖某一条 SSE 连接：页面刷新或网络短暂断开后，
工作台会恢复当前轮次、已生成文本和 Tool 轨迹，并从最后一个事件序号继续订阅；Agent
任务会在原连接断开后继续执行。工具执行事件按 `toolCallId` 合并为单行：每条只显示状态图标、工具名称及耗时/行数。事件区无卡片边框，可折叠，超过约六条后在内部滚动。工具完成或失败后，名称分别变为普通或红色结果链接；鼠标悬停可查看按 `displayType` 渲染的文本、JSON 或表格，移入悬浮窗后仍可选中复制内容。运行失败只向页面提供安全的错误类别（如模型 HTTP 状态），完整异常保留在服务端日志。文件相关操作直接通过文件/目录 Function Call 完成，不再伪装成 `ls`、`cat`、`grep` 或 `wc` 命令；可选的 `http.get` 仅在应用提供受控 `VirtualHttpClient` 时启用。当前对话与运行事件默认保存在进程内，服务进程重启后只恢复磁盘 Workspace 和 Artifact，不恢复尚未完成的 Agent 运行。

模型等待期间，消息区持续展示跳动动画、当前阶段和等待秒数；进入工具执行或正文流式输出时状态会同步切换。模型返回的 `<think>...</think>` 与 OpenAI 兼容流中的 `reasoning_content` 会按模型调用轮次、思考片段分别放入可折叠的“思考与执行过程”，不会串成一段。调用工具前输出的普通文字单列为“执行说明”，不冒充模型私有思考，也不会累积在最终回复卡片中。没有相关内容时只展示状态动画。运行中的分段内容可在页面刷新后从当前进程内会话恢复；进程重启后仍遵循上述会话持久性限制。
文件变更任务启用计划模式：模型通过 `plan.manage` 创建步骤、执行工具后更新状态、最后检查计划；Runtime 自动匹配真实成功的工具调用，模型无需填写调用 ID。工作台将最新计划独立显示在输入框上方，以只读 TODO 复选框实时呈现 `pending`、`in_progress` 和 `done`，完成项显示删除线；计划操作不会再混入普通 Tool 执行轨迹，页面刷新后会从运行事件中恢复最新计划。未完成或未经校验的步骤会阻止成功回复。文件操作统一使用 `file.op`，目录操作使用 `directory.manage`。

可设置 `AVR_CHAT_WORKSPACE_TYPE=memory` 改为短生命周期内存空间，或通过
`AVR_CHAT_WORKSPACE_ROOT` 调整磁盘根目录。点击“新建任务”会切换到新会话，
不会删除旧的磁盘 Workspace。页面支持以下接口：

- `POST /api/chat/stream`：提交 `{ "sessionId": null, "message": "你好" }`，返回 `session`、`trace`、`delta`、可选 `reasoning`、`done` 或 `error` 事件；
- `GET /api/chat/sessions/{sessionId}/runs/{runId}/events?after={sequence}`：页面刷新后回放缺失事件并续接实时 SSE；
- `POST /api/chat/sessions`：在调用模型前显式创建独立会话和 Workspace；
- `GET /api/chat/models`：返回当前可选模型和默认模型；`POST /api/chat/stream` 可携带 `model`；
- `GET /api/chat/sessions/{sessionId}/files`：列出 `/workspace` 内的文件；
- `GET /api/chat/sessions/{sessionId}/entries`：列出目录直接子项；
- `GET /api/chat/sessions/{sessionId}/files/content?path=/workspace/example.txt`：以纯文本读取文件；
- `PUT /api/chat/sessions/{sessionId}/files/content`：保存工作台编辑的文件；
- `GET /api/chat/sessions/{sessionId}/files/range|search`：范围读取和文本检索；
- `POST /api/chat/sessions/{sessionId}/files/append|insert|replace-lines`：增量编辑长文件；
- `DELETE /api/chat/sessions/{sessionId}/files/lines`：删除指定行范围；
- `POST /api/chat/sessions/{sessionId}/files/copy|move`：复制或移动虚拟文件；
- `DELETE /api/chat/sessions/{sessionId}/files`：删除虚拟文件；
- `POST /api/chat/sessions/{sessionId}/directories`：创建目录；
- `POST /api/chat/sessions/{sessionId}/directories/copy|move`：复制、移动或合并目录；
- `DELETE /api/chat/sessions/{sessionId}/directories`：删除空目录或递归删除目录；
- `GET /api/chat/sessions/{sessionId}/preview/{relativePath}`：从 Workspace 实时预览 HTML，并让相对路径的 CSS、JavaScript、图片等资源继续经过同一个虚拟目录解析；
- `GET /api/chat/sessions/{sessionId}/artifacts/{artifactId}/content`：读取不可变 Artifact 快照；
- `DELETE /api/chat/sessions/{sessionId}`：释放会话。

股票报告仍通过 `/api/reports/stock` 使用业务 Agent 和磁盘 Workspace。

示例还注册了 `research-agent` 和 `writer-agent`。主 Agent 可通过 `agent.manage` 创建、启动、
查询和取消子 Agent 任务；任务继续使用当前会话的 Workspace 和 ExecutionContext。该注册方式只用于
展示框架能力，生产应用应按照自己的业务角色、权限、模型与 Skill 进行注册。

输入框左下角的模型选择器使用 `avr.chat.models`。每个模型独立声明联网模式：
`runtime` 使用 AVR 的 `web.search` Function Tool，`native` 交给支持托管搜索的自定义
`Llm` 实现，`none` 则在前端禁用联网开关。模型仍可共用同一个 OpenAI-compatible
`api-url` 和 `api-key`，请求中只切换 `model`。数组中的第一个模型是工作台默认模型：

```yaml
avr:
  llm:
    openai:
      model: ${AVR_MODEL:MiniMax-M3}
  chat:
    web-search-enabled: ${AVR_WEB_SEARCH_ENABLED:true}
    models:
      - id: MiniMax-M3
        name: MiniMax M3
        web-search: runtime
      - id: model-id-2
        name: Model 2
        web-search: none
      - id: responses-model
        name: Responses Model
        web-search: native
```

模型 ID 必须与网关实际接受的值完全一致；示例不会替接入方猜测或改写模型名。不同模型若使用
不同地址或 API Key，应该分别实现/注册 `Llm` 路由，不要把密钥配置进前端模型列表。

容器环境中配置多个模型时，推荐用 Spring Boot 原生的 `SPRING_APPLICATION_JSON`：

```bash
export SPRING_APPLICATION_JSON='{"avr":{"chat":{"models":[
  {"id":"MiniMax-M3","name":"MiniMax M3","web-search":"runtime"},
  {"id":"model-id-2","name":"Model 2","web-search":"none"}
]}}}'
```

也可以使用数组下标环境变量，例如
`AVR_CHAT_MODELS_0_ID`、`AVR_CHAT_MODELS_0_NAME`、
`AVR_CHAT_MODELS_0_WEBSEARCH`。Spring 的环境变量映射会移除属性名中的连字符。

工作台提供请求级“联网”开关，用户选择保存在浏览器本地，并随模型能力自动启用或禁用。
`runtime` 模式要求应用注入 `WebSearchProvider`；Spring 自动配置随后注册
`WebSearchTool`。没有 Provider 时，模型目录会把该能力标记为不可用。`native` 模式只能
配合相应的模型协议适配器使用；当前 `OpenAiLlm` 是 Chat Completions 客户端，不会向
MiniMax 发送 `{"type":"web_search"}`，从根源上避免 `function is empty` 错误。

若接入其他 OpenAI 兼容服务，例如 MiniMax-M3，可在启动前设置：

```bash
export AVR_API_URL="https://m.aiio.chat/v1/chat/completions"
export AVR_MODEL="MiniMax-M3"
export OPENAI_API_KEY="<从安全环境注入的密钥>"
```

如果从 IntelliJ 的 Run/Debug Configuration 启动，请把这三个变量也加入该配置的
**Environment variables**；终端里的 `export` 不会自动传给已启动的 IDE。可在启动
配置中确认 `AVR_API_URL`、`AVR_MODEL` 和 `OPENAI_API_KEY` 均已注入。
不要将密钥写入受版本控制的文件。

模型上下文窗口与单次输出上限是两个不同概念，具体数值由模型服务和接入网关决定。
示例默认不发送 `max_tokens`，避免在不知道网关限制时人为截断输出；
实际可用长度仍取决于模型、当前输入和网关策略。如需限制单次输出成本，
Spring Boot 可设置 `AVR_LLM_OPENAI_MAX_TOKENS`；非 Spring 接入可在自定义 YAML 中
配置 `max-tokens`，或通过 `OpenAiConfig.Builder.maxTokens(...)` 设置。

不要将真实密钥提交到仓库，也不要把这个无认证的演示页面直接暴露在公网。
示例服务默认只监听 `127.0.0.1`；若需修改监听地址，可设置 `AVR_SERVER_ADDRESS`，
但对外开放前应自行增加认证、授权和限流。

在这个版本中，`OpenAiConfig`、`Llm`、`ToolRegistry`、`AgentRuntime`、`DiskWorkspace` 和 `Agent` 都由 Starter 自动装配。`StockReportService` 只通过构造器注入 `Agent` 和 `Workspace`，然后调用 `agent.input(...)`。

`DiskWorkspace` 来自统一的 `avr-storage` 依赖和 `com.avr.storage` 包；更换为内存、对象存储或公司内部实现时，业务 Service 仍然只依赖 `Workspace` 接口。

### 非 Spring 接入

```bash
mvn -pl avr-examples exec:java
```

非 Spring 示例在创建 `AgentLoop` 时注册 `RuntimeEventListener`，因此运行过程中可以看到模型调用、文本增量、Tool 开始和完成等事件，而 `Agent` 本身不包含监听逻辑。生成成功后，终端会打印 Artifact ID 和报告绝对路径。默认报告位置为：

```text
avr-examples/target/stock-agent-workspace/report/report.html
```

直接用浏览器打开该文件即可查看报告。

## 4. 示例的接入结构

1. Spring Starter 通过 `@ConfigurationProperties` 绑定 `application.yml`；
2. `DiskWorkspace` 准备 `/inputs/stock-data.json`；
3. `Skill` 注入财务指标、事实约束、风险分析和报告结构知识；
4. `DefaultToolRegistry` 注册文件读写、目录管理和 Artifact 提交工具；网络读取需显式接入受控 `VirtualHttpClient`；
5. `OpenAiLlm` 使用 Chat Completions SSE 和原生 Function Call；
6. `AgentLoop` 调用模型、执行工具、回填模型上下文，并向 Runtime 监听器发布不可变事件；
7. Agent 写入 `/report/report.html` 并调用 `artifact.commit`；
8. 示例校验报告和 Artifact 都已生成，然后输出宿主机文件地址。

非 Spring 应用仍可以运行 `StockReportAgentExample`，通过 `OpenAiConfig.fromYaml()` 或 Builder 手动初始化相同组件。

## 5. 替换为业务数据

生产应用通常不会把固定数据写在 Java 类中，可以在调用 Agent 前把数据库、行情服务或上传文件的结果写入 Workspace：

```java
workspace.writeText("/inputs/stock-data.json", stockDataJson);
```

也可以把行情或财务数据查询包装成自定义 `Tool`。无论数据来自哪里，都建议在 Skill 中要求模型区分事实和推断、标注数据日期、禁止编造缺失指标，并明确报告用途。

## 6. 隔离工作空间与真实模型烟测

普通测试不会访问模型服务：`ExampleWorkspaceTest` 验证两个磁盘 Workspace 隔离、从虚拟文件加载 Skill、HTML/CSS/JS 相对路径、Artifact 快照和持久化。运行：

```bash
mvn -pl avr-examples -am test
```

`RealModelSmokeTest` 只有设置 `AVR_RUN_LIVE_TESTS=true` 才发起真实请求。它在 `avr-examples/target/real-model-smoke/<随机 ID>/` 建立独立的虚拟工作空间，使用虚构输入，让 Agent 通过流式 Chat Completions、Function Call 和虚拟文件工具生成三文件网页并提交 Artifact。测试检查运行事件、文件及 Artifact；普通构建会跳过它，避免意外产生费用。

可使用具有免费额度的 Gemini OpenAI 兼容接口进行协议烟测（仍须从 Google AI Studio 取得 API Key，且额度受项目限制）：

```bash
export AVR_API_URL="https://generativelanguage.googleapis.com/v1beta/openai"
export AVR_MODEL="gemini-2.5-flash-lite"
export OPENAI_API_KEY="<你的 Gemini API Key>"
export AVR_RUN_LIVE_TESTS=true
mvn -pl avr-examples -am test -Dtest=RealModelSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

这验证的是**真实远端模型的 OpenAI 兼容协议**，不能代替 OpenAI 官方服务的兼容性验证。要测试官方服务，保持默认 `AVR_API_URL`，把 `OPENAI_API_KEY` 换成 OpenAI API Key，并选择账户可用的模型。不要把密钥写入仓库、测试数据、日志或聊天。免费服务可能有速率限制；此烟测只传输虚构数据。
