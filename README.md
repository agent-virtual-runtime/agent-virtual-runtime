# Agent Virtual Runtime (AVR)

[English](README.md) | [简体中文](README_CN.md)

Maintainers: see [Maven Central release guide](RELEASING.md).

Documentation: [architecture and project scope](ARCHITECTURE.md) · [integration guide](INTEGRATION.md) · [runnable workbench](avr-examples/README.md) · [changelog](CHANGELOG.md)

> A lightweight virtual execution environment for centralized AI agents.

Agent Virtual Runtime is a Java 11+ framework that gives an AI agent a virtual world—files, commands, skills, artifacts and other agents—without provisioning a container or VM for every run. AVR owns a model-neutral agent loop while applications retain control over identity, tenancy, workspace naming and storage topology.

AVR is designed to be embedded in an existing Java service. A host application resolves the Workspace for each request, then calls an injected `Agent` or an `Agent` created by `AgentFactory`. AVR runs the model/tool loop and emits events; the host keeps ownership of HTTP APIs, authentication, quotas, secrets and business isolation. See [ARCHITECTURE.md](ARCHITECTURE.md) for the complete responsibility boundary and execution flow.

### Choose an integration path

| Scenario | Start here |
| --- | --- |
| Spring Boot Web service | Add `avr-spring-boot-starter`, configure YAML, inject `Agent` or `AgentFactory` |
| Plain Java application | Assemble `OpenAiLlm`, `WorkspaceTools`, `AgentLoop` and `Agent` directly |
| Existing storage platform | Implement `Workspace`, or adapt an `ObjectStore` for `ObjectWorkspace` |
| Custom model protocol | Implement `Llm`; keep the rest of the Runtime unchanged |
| Product UI or SSE service | Consume `RuntimeEventListener`; use `avr-examples` as a reference implementation |

## V1.0 features

- synchronous and asynchronous agent execution;
- model-neutral agent loop with native tool calling and JSON Schema definitions;
- concurrent execution of independent tool calls with deterministic result ordering;
- sequential barriers for workspace mutations, configurable tool timeout and no-progress fuse;
- immutable execution context and pluggable authorization policy;
- non-invasive Runtime event listeners for logging, streaming and observability;
- request-scoped Skill instruction injection and a Skill registry;
- memory, host-disk and generic object-store-backed virtual workspaces;
- file read, write, list, copy, move and delete operations;
- file and directory operations exposed directly as Function Calls, plus optional policy-controlled `http.get` with no OS process execution;
- immutable HTML/CSS/JS artifact snapshots with a declared entrypoint and persistent metadata on disk and object storage;
- named sub-agent delegation over a shared virtual workspace;
- cooperative cancellation for synchronous and asynchronous runs;
- optional bounded in-memory projection for run status and event queries.

AVR is not a kernel sandbox and does not execute arbitrary binaries. Network access, databases, Git, object storage and business APIs should be supplied as explicit tools with policy checks.

## Requirements and build

- JDK 11 or later
- Maven 3.6 or later

The source uses Lombok for boilerplate accessors. Lombok is configured with `provided` scope and is not an AVR runtime dependency. Maven builds need no extra setup; IDE annotation processing must be enabled.

```bash
mvn clean verify
```

## Minimal integration

```java
Workspace workspace = new MemoryWorkspace("any-id-chosen-by-your-application");

ToolRegistry tools = DefaultToolRegistry.builder()
        .register(new FileOpTool())
        .register(new DirectoryTool())
        .register(new PlanTool())
        .register(new CommitArtifactTool())
        .build();

RuntimeEventListener loggingListener = event ->
        System.out.println(event.getType() + " " + event.getDetail());

AgentRuntime runtime = new AgentLoop(
        llm,
        tools,
        ToolPolicy.allowAll(),
        AgentLoopOptions.defaults(),
        Collections.singletonList(loggingListener));

Agent agent = Agent.builder()
        .name("report-agent")
        .runtime(runtime)
        .workspace(workspace)
        .skill(new Skill("report-writing", "Write evidence-based HTML reports.",
                Collections.singletonList("file.op")))
        .build());

AgentResult result = agent.input("Read /inputs/data.txt and create /report/index.html");
```

`Llm` represents one model call. Map tool definitions to the provider's tool/function schema and map its response back to `LlmResponse` and `ToolCall`; `AgentLoop` performs the callback loop. Use `runtime.runAsync(request)` for a `CompletionStage`.

## OpenAI-compatible model

The `avr-model-openai` module implements the Chat Completions protocol, including SSE streaming, function tools, assistant `tool_calls`, and tool-result messages:

`AgentRequest.builder().webSearch(true)` enables AVR's `web.search` Function Tool. Applications
provide a `WebSearchProvider`, so the runtime can use a public search API or an internal company
service without leaking a vendor-specific schema into the model request. Use
`.webSearchMode(WebSearchMode.NATIVE)` only with an `Llm` implementation that supports a hosted
search protocol such as OpenAI Responses. `OpenAiLlm` is deliberately a Chat Completions adapter
and rejects native search before sending a malformed request.

```java
OpenAiConfig modelConfig = OpenAiConfig.builder()
        .apiUrl("https://api.openai.com/v1")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("your-model-name")
        .temperature(0.1)
        .maxTokens(4096)
        .stream(true)
        .timeout(Duration.ofMinutes(2))
        .build();

Llm llm = new OpenAiLlm(modelConfig);
AgentRuntime runtime = new AgentLoop(llm, tools);
```

`apiUrl` may be either a base URL such as `https://api.openai.com/v1` or a complete `/chat/completions` endpoint. Streaming is enabled by default; `OpenAiLlm` consumes the model's SSE response, emits text deltas as `model.delta` run events, and assembles fragmented function calls by their tool-call index. Use `stream(false)` for a JSON response. Compatible services can add headers through `header(name, value)`. The API key is optional so local services can be used without authentication.

### Tool-use flow

1. AVR converts every `ToolDefinition` to an OpenAI function tool.
2. The model returns one or more `tool_calls` containing call ID, name and JSON arguments.
3. AVR stores the assistant message and its original tool calls in conversation history.
4. Adjacent `PARALLEL` tools run on the configured executor; `SEQUENTIAL` tools form barriers.
5. Every result is appended as a `tool` message with the matching `tool_call_id`, in original call order.
6. AVR calls the model again until it returns a final answer or a loop guard stops the run.

When one model turn contains multiple tool calls, AVR executes adjacent `PARALLEL` tools concurrently and always appends their results to model history in the original call order. Tools that mutate ordered state declare `SEQUENTIAL` and act as barriers.

`maxSteps` is a soft budget rather than a mechanical stopping point. While tool rounds keep producing new successful results, the loop may extend up to `maxSteps × maxStepMultiplier`. Repeating the same tool arguments and result does not count as progress, the no-progress fuse still stops stalled runs early, and `loopTimeout` provides an independent wall-clock budget. Configure these guards, the executor, tool timeout and empty-response retry through `AgentLoopOptions`.

For cancellation, create a `CancellationSource`, pass its token to the Agent builder, and call `cancel()` from the hosting application. The loop checks cancellation before model and tool calls.

## Workspace isolation

AVR never assumes that a workspace maps to a user. The hosting application may resolve workspace IDs as `business/line/user/workspace`, a hash, a job ID, or any other scheme. Isolation is established by passing each request the correct `Workspace` and `ExecutionContext`, then enforcing application rules through `ToolPolicy`.

`Workspace` is the single virtual-file-system contract. The lightweight built-in implementations are shipped together in `avr-storage` and the `com.avr.storage` package:

```java
Workspace memory = new MemoryWorkspace("temporary-run");
Workspace disk = new DiskWorkspace("opaque-id", Paths.get("/mounted/avr/work-42"));
```

For centralized deployments, use `MemoryWorkspaceProvider` or `DiskWorkspaceProvider`, or implement the business-neutral `WorkspaceProvider` SPI. The disk provider accepts an application-owned path resolver, so AVR does not define any user or tenant directory scheme.

The standard capability set exposes one `file.op` tool for file lifecycle and content operations, one `directory.manage` tool for directory lifecycle, and `plan.manage` for create/revise/update/check. File operations include bounded range reads, search, write, append, insert, replace, copy, move, and delete. Line ranges are 1-based and inclusive. `DiskWorkspace` streams append, range-read, and search operations instead of loading a complete report into the model context. Applications can opt in to `AgentRequest.planMode(true)`: completion then requires a checked plan whose steps reference successful tool calls and may verify output paths, minimum file size, aggregate directory size, and removed source paths. A pending step can be revised when its verification target was planned incorrectly. Business-specific completion rules can additionally use `completionCheck`.

Applications can implement `Workspace` directly with an internal storage client. `ObjectWorkspace` is an optional convenience implementation backed by the small `ObjectStore` adapter; it does not require or impose an S3, OSS, COS or MinIO SDK.

`MemoryWorkspace` retains files and artifacts only for the lifetime of that Java object. `DiskWorkspace` persists both files and committed artifact snapshots below its configured root, while `ObjectWorkspace` persists them below its configured object-key prefix. Recreating a persistent Workspace with the same root or prefix restores `artifacts()`. The internal `/.avr` namespace is reserved for AVR metadata and is never exposed to the agent.

`http.get` is optional and is registered only when the application provides a `VirtualHttpClient`. The application must enforce host allowlists, credentials, timeouts and audit rules; AVR does not expose the host shell. File listing, reading, searching and editing use the file/directory Function Calls rather than command aliases.

## Multi-agent coordination

`AgentTaskManager` registers application-defined specialist runtimes and manages task creation, asynchronous execution, result collection and cooperative cancellation. `AgentManageTool` exposes `list_agents`, `list_tasks`, `create`, `execute`, `result`, and `cancel` to the parent Agent while passing through the current Workspace and ExecutionContext. AVR does not impose an organization structure, identity scheme or model choice.

```java
AgentTaskManager manager = new AgentTaskManager()
        .register("research-agent", "research and fact checking", researchRuntime)
        .register("writer-agent", "long-form report writing", writerRuntime);
Tool agentTool = new AgentManageTool(manager);
```

## Non-invasive runtime observation

AVR does not start an HTTP server or add monitoring methods to `Agent`. Register one or more `RuntimeEventListener` instances when constructing `AgentLoop`; Spring Boot applications only need to declare listener beans and the Starter collects them automatically. Events contain the run, agent and workspace identifiers, ordered sequence, state, type, detail and structured attributes. Tool events additionally carry display metadata, a bounded argument summary, duration, success state, and a bounded result preview for custom front-end rendering. Listener failures are isolated from Agent execution.

`InMemoryRunTracker` is an optional bounded listener that projects events into `RunSnapshot` values and retains limited event history. Applications can instead implement `RuntimeEventListener` to publish to logs, metrics, OpenTelemetry, a database, MQ, SSE or WebSocket. HTTP controllers, authentication, quotas and artifact delivery remain the hosting application's responsibility.

## Modules

- `avr-api`: public contracts and SPIs.
- `avr-core`: agent loop, tool registry and built-in tools.
- `avr-storage`: all built-in Workspace implementations in one dependency and package.
- `avr-command`: optional application-controlled HTTP retrieval tool.
- `avr-model-openai`: OpenAI-compatible Chat Completions client with native tool calling.
- `avr-spring-boot-starter`: Spring Boot configuration binding, listener discovery and Agent auto-configuration.
- `avr-examples`: runnable end-to-end example.

## Run the example

```bash
export OPENAI_API_KEY="your-api-key"
export AVR_MODEL="gpt-4.1-mini"
mvn -pl avr-examples -am clean install
mvn -pl avr-examples exec:java
```

The runnable examples build a stock-analysis agent with a real OpenAI-compatible model, financial-analysis Skill, disk Workspace, streamed Function Calls, and a committed `report.html`. They demonstrate both Spring-injected and plain Java initialization. See [avr-examples/README.md](avr-examples/README.md) for configuration and output details.

Contributions use Gitmoji Conventional Commit subjects, for example `✨ feat: add an object-storage workspace`. See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Read [ARCHITECTURE.md](ARCHITECTURE.md) for the system boundary and [INTEGRATION.md](INTEGRATION.md) for complete Java, Maven, Spring, model SSE, Function Call, Tool, Workspace, plan and event integration.

Licensed under the Apache License 2.0. See [LICENSE](LICENSE).
