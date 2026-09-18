# AVR Spring Boot Starter

引入 `avr-spring-boot-starter` 后，Spring Boot 会根据 `application.yml` 自动创建 `OpenAiConfig`、`Llm`、内置 `Tool`、`ToolRegistry`、`AgentLoopOptions`、`AgentRuntime` 和 `AgentFactory` Bean。

Starter 会传递引入统一的 `avr-storage` 模块。`Workspace` 是唯一的 VFS 接口，内置 `MemoryWorkspace`、`DiskWorkspace` 和对象存储相关实现都位于 `com.avr.storage` 包。

配置 `avr.workspace.type=memory` 或 `disk` 后，还会创建单一 `Workspace` 和可直接注入的 `Agent` Bean：

```java
@Service
public class ReportService {
    private final Agent agent;

    public ReportService(Agent agent) {
        this.agent = agent;
    }

    public AgentResult create(String prompt) {
        return agent.input(prompt);
    }
}
```

如果应用需要按请求、任务或业务规则选择 Workspace，不配置默认 Workspace，改为注入 `AgentFactory`：

```java
Agent agent = agentFactory.create(workspaceResolver.resolve(workspaceId));
AgentResult result = agent.input(prompt);
```

用户声明同类型 Bean 时，自动配置会退让，因此可以替换 `Llm`、`ToolRegistry`、`ToolPolicy`、`AgentRuntime`、`Workspace`、`Agent` 或其他默认组件。自定义 `Tool` 和 `Skill` Bean 会自动加入默认 Agent。

接入公司内部存储时，直接注册自定义 `Workspace` Bean 即可：

```java
@Bean
public Workspace workspace(CompanyStorageClient client) {
    return new CompanyWorkspace(client);
}
```

此时无需配置 `avr.workspace.type`，默认的内存或磁盘 Workspace 自动配置会退让。
