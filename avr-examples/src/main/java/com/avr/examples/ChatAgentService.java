package com.avr.examples;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.ExecutionContext;
import com.avr.api.Message;
import com.avr.api.Llm;
import com.avr.api.RunState;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventTypes;
import com.avr.api.Skill;
import com.avr.api.ToolRegistry;
import com.avr.api.WebSearchMode;
import com.avr.api.Workspace;
import com.avr.core.WorkspaceSkillRegistry;
import com.avr.storage.DiskWorkspace;
import com.avr.spring.AvrAgentProperties;
import com.avr.storage.MemoryWorkspace;
import com.avr.model.openai.OpenAiConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 为演示页面管理独立的虚拟工作空间和多轮 Agent 会话。 */
@Service
public class ChatAgentService {
    private static final Logger LOGGER = Logger.getLogger(ChatAgentService.class.getName());
    private static final int MAX_SESSIONS = 100;
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final Pattern MODEL_HTTP_STATUS = Pattern.compile(
            "model endpoint returned HTTP (\\d{3})");
    private static final String CHAT_SKILL = String.join("\n",
            "You are a helpful assistant running inside Agent Virtual Runtime.",
            "For all file operations use file.op: list, read, search, write, append, insert, replace, copy, move or delete.",
            "For directory operations use directory.manage. Never claim a file exists without verifying it.",
            "Use directory.manage for directory lifecycle operations.",
            "Use agent.manage when a task benefits from a registered specialist Agent.",
            "Keep user-created files under /workspace. Never claim completion before tool results "
                    + "confirm the requested changes; verify the final paths and file sizes.",
            "Bind size requirements to the actual deliverable. If a large report is split across "
                    + "multiple files, verify the report directory's aggregate minBytes; never "
                    + "require an index or README file alone to match the whole report size.",
            "For a website, write relative HTML/CSS/JS paths and commit an artifact when requested.");

    private final AgentRuntime runtime;
    private final ChatRunEventBridge eventBridge;
    private final AvrAgentProperties agentProperties;
    private final String workspaceType;
    private final Path workspaceRoot;
    private final String defaultModel;
    private final boolean defaultWebSearch;
    private final Map<String, ChatModelProfile> models;
    private final ConcurrentHashMap<String, ChatSession> sessions =
            new ConcurrentHashMap<String, ChatSession>();

    @Autowired
    public ChatAgentService(
            AgentRuntime runtime,
            ChatRunEventBridge eventBridge,
            AvrAgentProperties agentProperties,
            OpenAiConfig openAiConfig,
            Llm llm,
            ToolRegistry toolRegistry,
            ChatProperties chatProperties,
            @Value("${avr.chat.workspace.type:memory}") String workspaceType,
            @Value("${avr.chat.workspace.root:./avr-examples/target/chat-workspaces}")
                    String workspaceRoot) {
        this(runtime, eventBridge, agentProperties, openAiConfig,
                workspaceType, workspaceRoot, chatProperties,
                hasRuntimeSearch(toolRegistry), llm.supportsNativeWebSearch());
    }

    private ChatAgentService(
            AgentRuntime runtime,
            ChatRunEventBridge eventBridge,
            AvrAgentProperties agentProperties,
            OpenAiConfig openAiConfig,
            String workspaceType,
            String workspaceRoot,
            ChatProperties chatProperties,
            boolean runtimeSearchAvailable,
            boolean nativeSearchAvailable) {
        this.runtime = runtime;
        this.eventBridge = eventBridge;
        this.agentProperties = agentProperties;
        if (!"memory".equals(workspaceType) && !"disk".equals(workspaceType)) {
            throw new IllegalArgumentException("avr.chat.workspace.type must be memory or disk");
        }
        this.workspaceType = workspaceType;
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        this.defaultModel = defaultModel(openAiConfig.getModel(), chatProperties);
        this.models = Collections.unmodifiableMap(modelProfiles(
                defaultModel, chatProperties,
                runtimeSearchAvailable, nativeSearchAvailable));
        this.defaultWebSearch = chatProperties.isWebSearchEnabled()
                && this.models.get(defaultModel).isWebSearchSupported();
    }

    /** 保留非 Spring 测试和手动装配方式，默认使用名为 default 的模型。 */
    ChatAgentService(
            AgentRuntime runtime,
            ChatRunEventBridge eventBridge,
            AvrAgentProperties agentProperties,
            String workspaceType,
            String workspaceRoot) {
        this(runtime, eventBridge, agentProperties,
                OpenAiConfig.builder().model("default").build(),
                workspaceType, workspaceRoot, new ChatProperties(), false, false);
    }

    public ChatSession open(String sessionId) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            ChatSession existing = sessions.get(sessionId);
            if (existing == null && "disk".equals(workspaceType)
                    && sessionId.matches("[0-9a-fA-F-]{36}")) {
                existing = createSession(sessionId);
                ChatSession raced = sessions.putIfAbsent(sessionId, existing);
                existing = raced == null ? existing : raced;
            }
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已过期");
            }
            return existing;
        }
        if (sessions.size() >= MAX_SESSIONS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "演示会话数量已达上限");
        }
        String id = UUID.randomUUID().toString();
        ChatSession session = createSession(id);
        sessions.put(id, session);
        return session;
    }

    private ChatSession createSession(String id) {
        Workspace workspace = "disk".equals(workspaceType)
                ? new DiskWorkspace("chat-" + id, workspaceRoot.resolve(id))
                : new MemoryWorkspace("chat-" + id);
        if (!workspace.exists("/skills/chat/SKILL.md")) {
            workspace.writeText("/skills/chat/SKILL.md", CHAT_SKILL);
        }
        Skill skill = new WorkspaceSkillRegistry(workspace)
                .find("chat", ExecutionContext.empty())
                .orElseThrow(() -> new IllegalStateException("chat skill not found"));
        return new ChatSession(id, workspace, skill);
    }

    public ChatSession require(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已过期");
        }
        return open(sessionId);
    }

    public void close(String sessionId) {
        ChatSession session = require(sessionId);
        if (session.busy.get()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话仍在运行");
        }
        sessions.remove(sessionId, session);
    }

    public void claim(ChatSession session) {
        if (!session.busy.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话仍在运行");
        }
    }

    public void release(ChatSession session) {
        session.busy.set(false);
    }

    public List<Message> history(ChatSession session) {
        synchronized (session.history) {
            return new ArrayList<Message>(session.history);
        }
    }

    public AgentResult run(
            ChatSession session,
            String prompt,
            String model,
            Consumer<RuntimeEvent> onEvent) {
        return runInternal(session, prompt, model, true,
                UUID.randomUUID().toString(), onEvent);
    }

    private AgentResult runInternal(
            ChatSession session,
            String prompt,
            String model,
            boolean webSearch,
            String runId,
            Consumer<RuntimeEvent> onEvent) {
        AgentRequest request = AgentRequest.builder()
                .runId(runId)
                .agent("chat-agent")
                .prompt(prompt)
                .workspace(session.workspace)
                .history(session.history)
                .skill(session.skill)
                .model(resolveModel(model).getId())
                .webSearchMode(resolveWebSearchMode(model, webSearch))
                .maxSteps(agentProperties.getMaxSteps())
                .planMode(WorkspaceMutationCheck.requiresPlan(prompt, history(session)))
                .completionCheck(WorkspaceMutationCheck.forRequest(
                        prompt, history(session), session.workspace))
                .build();

        eventBridge.subscribe(runId, onEvent);
        try {
            AgentResult result = runtime.run(request);
            if (result.getState() == RunState.COMPLETED) {
                synchronized (session.history) {
                    session.history.add(Message.user(prompt));
                    session.history.add(Message.assistant(visibleText(result.getText())));
                    while (session.history.size() > MAX_HISTORY_MESSAGES) {
                        session.history.remove(0);
                    }
                }
            }
            return result;
        } finally {
            eventBridge.unsubscribe(runId);
        }
    }

    /** 在服务端启动一次独立运行，浏览器断开不会中止 Agent。 */
    public ChatRun start(
            ChatSession session,
            String prompt,
            String model,
            RunSubscriber subscriber) {
        return start(session, prompt, model, defaultWebSearch, subscriber);
    }

    public ChatRun start(
            ChatSession session,
            String prompt,
            String model,
            boolean webSearch,
            RunSubscriber subscriber) {
        ChatModelProfile selectedProfile = resolveModel(model);
        claim(session);
        ChatRun run = new ChatRun(
                UUID.randomUUID().toString(), prompt, selectedProfile.getId(),
                webSearch && selectedProfile.isWebSearchSupported(),
                webSearch && selectedProfile.isWebSearchSupported()
                        ? selectedProfile.getWebSearchMode() : WebSearchMode.NONE);
        run.subscribe(0, subscriber);
        synchronized (session.runs) {
            session.runs.add(run);
            while (session.runs.size() > MAX_HISTORY_MESSAGES / 2) {
                session.runs.remove(0);
            }
        }

        CompletableFuture.runAsync(() -> {
            try {
                AgentResult result = runInternal(
                        session, prompt, run.model, run.webSearch,
                        run.runId, run::record);
                if (result.getState() != RunState.COMPLETED) {
                    throw new IllegalStateException("agent run did not complete");
                }
                run.complete(result);
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Chat Agent run failed", exception);
                run.fail(publicFailureCode(exception));
            } finally {
                release(session);
            }
        });
        return run;
    }

    /** 订阅既有运行；先回放历史事件，再继续接收实时事件。 */
    public RunSubscription subscribe(
            ChatSession session,
            String runId,
            long afterSequence,
            RunSubscriber subscriber) {
        ChatRun run = findRun(session, runId);
        return run.subscribe(afterSequence, subscriber);
    }

    public List<ChatRun> runs(ChatSession session) {
        synchronized (session.runs) {
            return new ArrayList<ChatRun>(session.runs);
        }
    }

    private ChatRun findRun(ChatSession session, String runId) {
        synchronized (session.runs) {
            for (ChatRun run : session.runs) {
                if (run.runId.equals(runId)) {
                    return run;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在或已过期");
    }

    /** 使用默认模型执行一次对话。 */
    public AgentResult run(
            ChatSession session,
            String prompt,
            Consumer<RuntimeEvent> onEvent) {
        return run(session, prompt, null, onEvent);
    }

    public List<ChatModelProfile> models() {
        return new ArrayList<ChatModelProfile>(models.values());
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean defaultWebSearch() {
        return defaultWebSearch;
    }

    private ChatModelProfile resolveModel(String model) {
        String selected = model == null || model.trim().isEmpty()
                ? defaultModel : model.trim();
        ChatModelProfile profile = models.get(selected);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型未配置: " + selected);
        }
        return profile;
    }

    private WebSearchMode resolveWebSearchMode(String model, boolean requested) {
        ChatModelProfile profile = resolveModel(model);
        return requested && profile.isWebSearchSupported()
                ? profile.getWebSearchMode() : WebSearchMode.NONE;
    }

    private static boolean hasRuntimeSearch(ToolRegistry toolRegistry) {
        return toolRegistry.definitions().stream()
                .anyMatch(definition -> "web.search".equals(definition.getName()));
    }

    private static String defaultModel(
            String llmDefaultModel,
            ChatProperties properties) {
        for (ChatProperties.Model configured : properties.getModels()) {
            if (configured.getId() != null && !configured.getId().trim().isEmpty()) {
                return configured.getId().trim();
            }
        }
        return llmDefaultModel;
    }

    private static Map<String, ChatModelProfile> modelProfiles(
            String defaultModel,
            ChatProperties properties,
            boolean runtimeSearchAvailable,
            boolean nativeSearchAvailable) {
        LinkedHashMap<String, ChatModelProfile> result =
                new LinkedHashMap<String, ChatModelProfile>();
        for (ChatProperties.Model configured : properties.getModels()) {
            if (configured.getId() == null || configured.getId().trim().isEmpty()) {
                continue;
            }
            String id = configured.getId().trim();
            WebSearchMode mode = WebSearchMode.fromConfig(configured.getWebSearch());
            boolean supported = properties.isWebSearchEnabled()
                    && mode != WebSearchMode.NONE
                    && (mode != WebSearchMode.RUNTIME || runtimeSearchAvailable)
                    && (mode != WebSearchMode.NATIVE || nativeSearchAvailable);
            result.put(id, new ChatModelProfile(
                    id,
                    configured.getName() == null || configured.getName().trim().isEmpty()
                            ? id : configured.getName().trim(),
                    mode,
                    supported));
        }
        if (!result.containsKey(defaultModel)) {
            WebSearchMode mode = runtimeSearchAvailable
                    ? WebSearchMode.RUNTIME : WebSearchMode.NONE;
            result.put(defaultModel, new ChatModelProfile(
                    defaultModel, defaultModel, mode,
                    properties.isWebSearchEnabled() && runtimeSearchAvailable));
        }
        return result;
    }

    private static String visibleText(String text) {
        return text.replaceAll("(?is)<think>.*?</think>", "")
                .replaceAll("(?is)<think>.*$", "")
                .trim();
    }

    /** 对外只发送可定位的错误类别，完整响应体和堆栈仅保留在服务端日志。 */
    static String publicFailureCode(RuntimeException exception) {
        String message = exception.getMessage() == null
                ? "" : exception.getMessage();
        Matcher httpStatus = MODEL_HTTP_STATUS.matcher(message);
        if (httpStatus.find()) {
            return "model-http:" + httpStatus.group(1);
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return "timeout";
        }
        if (message.contains("agent exhausted step budget")) {
            return "step-limit";
        }
        if (message.contains("agent completion check failed")) {
            return "completion-unverified";
        }
        if (message.contains("agent made no progress")) {
            return "no-progress";
        }
        if (message.contains("model response was truncated")) {
            return "model-truncated";
        }
        if (message.contains("model SSE") || message.contains("streamed tool")) {
            return "model-stream-invalid";
        }
        if (message.contains("model request failed")) {
            return "model-network";
        }
        return "unknown";
    }

    /** 一个会话独占一个 Workspace，不对业务身份和目录命名作约束。 */
    public static final class ChatSession {
        private final String id;
        private final Workspace workspace;
        private final Skill skill;
        private final List<Message> history = new ArrayList<Message>();
        private final List<ChatRun> runs = new ArrayList<ChatRun>();
        private final AtomicBoolean busy = new AtomicBoolean();

        private ChatSession(String id, Workspace workspace, Skill skill) {
            this.id = id;
            this.workspace = workspace;
            this.skill = skill;
        }

        public String getId() {
            return id;
        }

        public Workspace getWorkspace() {
            return workspace;
        }
    }

    /** SSE 订阅者，同时接收运行事件和最终状态。 */
    public interface RunSubscriber {
        void onEvent(RuntimeEvent event);

        void onComplete(AgentResult result);

        void onError(String message);
    }

    /** 可用于在浏览器断开时解除单个 SSE 订阅。 */
    public interface RunSubscription {
        void unsubscribe();
    }

    /** 一次运行的可恢复状态，事件与增量内容均保存在服务端会话中。 */
    public static final class ChatRun {
        private final String runId;
        private final String prompt;
        private final String model;
        private final boolean webSearch;
        private final WebSearchMode webSearchMode;
        private final List<RuntimeEvent> events = new ArrayList<RuntimeEvent>();
        private final List<ModelRound> modelRounds = new ArrayList<ModelRound>();
        private final List<RunSubscriber> subscribers =
                new CopyOnWriteArrayList<RunSubscriber>();
        private volatile String status = "running";
        private volatile String content = "";
        private volatile String reasoning = "";
        private volatile Integer steps;
        private volatile AgentResult result;
        private volatile String error;

        private ChatRun(
                String runId,
                String prompt,
                String model,
                boolean webSearch,
                WebSearchMode webSearchMode) {
            this.runId = runId;
            this.prompt = prompt;
            this.model = model;
            this.webSearch = webSearch;
            this.webSearchMode = webSearchMode;
        }

        private synchronized RunSubscription subscribe(
                long afterSequence,
                RunSubscriber subscriber) {
            for (RuntimeEvent event : events) {
                if (event.getSequence() > afterSequence) {
                    subscriber.onEvent(event);
                }
            }
            if ("completed".equals(status)) {
                subscriber.onComplete(result);
            } else if ("failed".equals(status)) {
                subscriber.onError(error);
            } else {
                subscribers.add(subscriber);
            }
            return () -> subscribers.remove(subscriber);
        }

        private synchronized void record(RuntimeEvent event) {
            events.add(event);
            if (RuntimeEventTypes.MODEL_CALL.equals(event.getType())) {
                modelRounds.add(new ModelRound());
            } else if (RuntimeEventTypes.MODEL_DELTA.equals(event.getType())) {
                currentModelRound().text.append(event.getDetail());
                content += event.getDetail();
            } else if (RuntimeEventTypes.MODEL_REASONING_DELTA.equals(event.getType())) {
                currentModelRound().reasoning.append(event.getDetail());
                reasoning += event.getDetail();
            } else if (RuntimeEventTypes.MODEL_COMPLETED.equals(event.getType())) {
                Object count = event.getAttributes().get("toolCallCount");
                currentModelRound().toolCallCount = count instanceof Number
                        ? ((Number) count).intValue() : 0;
            }
            for (RunSubscriber subscriber : subscribers) {
                try {
                    subscriber.onEvent(event);
                } catch (RuntimeException exception) {
                    subscribers.remove(subscriber);
                }
            }
        }

        private synchronized void complete(AgentResult completedResult) {
            result = completedResult;
            String inlineReasoning = thinkingText(completedResult.getText());
            if (!inlineReasoning.isEmpty()) {
                reasoning += (reasoning.isEmpty() ? "" : "\n") + inlineReasoning;
            }
            content = visibleText(completedResult.getText());
            steps = completedResult.getSteps();
            status = "completed";
            for (RunSubscriber subscriber : subscribers) {
                try {
                    subscriber.onComplete(completedResult);
                } catch (RuntimeException ignored) {
                    // SSE 已断开时只移除订阅，不影响运行结果。
                }
            }
            subscribers.clear();
        }

        private synchronized void fail(String publicMessage) {
            status = "failed";
            error = publicMessage;
            for (RunSubscriber subscriber : subscribers) {
                try {
                    subscriber.onError(publicMessage);
                } catch (RuntimeException ignored) {
                    // SSE 已断开时只移除订阅，不影响服务端状态。
                }
            }
            subscribers.clear();
        }

        public String getRunId() {
            return runId;
        }

        public String getPrompt() {
            return prompt;
        }

        public String getModel() {
            return model;
        }

        public boolean isWebSearch() {
            return webSearch;
        }

        public String getWebSearchMode() {
            return webSearchMode.name().toLowerCase();
        }

        public String getStatus() {
            return status;
        }

        public String getContent() {
            return content;
        }

        public String getReasoning() {
            return reasoning;
        }

        public synchronized List<Map<String, Object>> getModelRounds() {
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            for (ModelRound round : modelRounds) {
                Map<String, Object> value = new java.util.LinkedHashMap<String, Object>();
                value.put("text", round.text.toString());
                value.put("reasoning", round.reasoning.toString());
                value.put("toolCallCount", round.toolCallCount);
                values.add(value);
            }
            return values;
        }

        private ModelRound currentModelRound() {
            if (modelRounds.isEmpty()) {
                modelRounds.add(new ModelRound());
            }
            return modelRounds.get(modelRounds.size() - 1);
        }

        public Integer getSteps() {
            return steps;
        }

        public String getError() {
            return error;
        }

        public synchronized List<RuntimeEvent> getEvents() {
            return new ArrayList<RuntimeEvent>(events);
        }
    }

    private static final class ModelRound {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private Integer toolCallCount;
    }

    private static String thinkingText(String text) {
        Matcher matcher = Pattern.compile("(?is)<think>(.*?)</think>").matcher(text);
        StringBuilder reasoning = new StringBuilder();
        while (matcher.find()) {
            if (reasoning.length() > 0) {
                reasoning.append('\n');
            }
            reasoning.append(matcher.group(1).trim());
        }
        return reasoning.toString();
    }
}
