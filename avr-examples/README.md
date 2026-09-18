# AVR Examples

`StockReportAgentExample` 展示一个接入者如何构建股票分析 Agent：从 `application.yml` 读取模型配置、准备虚拟 Workspace、注入财务分析 Skill、调用 OpenAI 兼容模型、处理流式 Function Call，并最终生成 `report.html`。

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
      max-tokens: 12000
      timeout: 5m
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
| `AVR_WORKSPACE_DIR` | `avr-examples/target/stock-agent-workspace` | Workspace 对应的宿主机目录 |

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

在这个版本中，`OpenAiConfig`、`Llm`、`ToolRegistry`、`AgentRuntime`、`DiskWorkspace` 和 `Agent` 都由 Starter 自动装配。`StockReportService` 只通过构造器注入 `Agent` 和 `Workspace`，然后调用 `agent.input(...)`。

`DiskWorkspace` 来自统一的 `avr-storage` 依赖和 `com.avr.storage` 包；更换为内存、对象存储或公司内部实现时，业务 Service 仍然只依赖 `Workspace` 接口。

### 非 Spring 接入

```bash
mvn -pl avr-examples exec:java
```

运行过程中可以看到模型调用、文本增量、Tool 开始和完成等事件。生成成功后，终端会打印 Artifact ID 和报告绝对路径。默认报告位置为：

```text
avr-examples/target/stock-agent-workspace/report/report.html
```

直接用浏览器打开该文件即可查看报告。

## 4. 示例的接入结构

1. Spring Starter 通过 `@ConfigurationProperties` 绑定 `application.yml`；
2. `DiskWorkspace` 准备 `/inputs/stock-data.json`；
3. `Skill` 注入财务指标、事实约束、风险分析和报告结构知识；
4. `DefaultToolRegistry` 注册文件读写、目录查看、虚拟命令和 Artifact 提交工具；
5. `OpenAiLlm` 使用 Chat Completions SSE 和原生 Function Call；
6. `AgentLoop` 调用模型、执行工具并把结果写回模型上下文；
7. Agent 写入 `/report/report.html` 并调用 `artifact.commit`；
8. 示例校验报告和 Artifact 都已生成，然后输出宿主机文件地址。

非 Spring 应用仍可以运行 `StockReportAgentExample`，通过 `OpenAiConfig.fromYaml()` 或 Builder 手动初始化相同组件。

## 5. 替换为业务数据

生产应用通常不会把固定数据写在 Java 类中，可以在调用 Agent 前把数据库、行情服务或上传文件的结果写入 Workspace：

```java
workspace.writeText("/inputs/stock-data.json", stockDataJson);
```

也可以把行情或财务数据查询包装成自定义 `Tool`。无论数据来自哪里，都建议在 Skill 中要求模型区分事实和推断、标注数据日期、禁止编造缺失指标，并明确报告用途。
