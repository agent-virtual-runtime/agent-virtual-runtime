package com.avr.examples;

import com.avr.api.AgentResult;
import com.avr.api.Artifact;
import com.avr.api.Message;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventTypes;
import com.avr.api.TextFileSlice;
import com.avr.api.TextSearchMatch;
import com.avr.api.WorkspaceEntry;
import com.avr.api.Workspace;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriUtils;

import javax.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将对话请求交给完整 AgentRuntime，并流式展示运行事件。 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private static final int MAX_CONTENT_LENGTH = 8_000;

    private final ChatAgentService chatService;

    public ChatController(ChatAgentService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null
                || request.getMessage().trim().isEmpty()
                || request.getMessage().length() > MAX_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容无效或过长");
        }

        ChatAgentService.ChatSession session = chatService.open(request.getSessionId());
        SseEmitter emitter = new SseEmitter(0L);
        send(emitter, "session", Map.of("sessionId", session.getId()));
        ChatAgentService.ChatRun run = chatService.start(
                session,
                request.getMessage(),
                request.getModel(),
                request.getWebSearch() == null
                        ? chatService.defaultWebSearch() : request.getWebSearch(),
                emitterSubscriber(emitter, session));
        send(emitter, "run", Map.of("runId", run.getRunId()));
        return emitter;
    }

    /** 浏览器刷新后通过 runId 回放事件并继续订阅正在执行的任务。 */
    @GetMapping(value = "/sessions/{sessionId}/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
            @PathVariable String sessionId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long after) {
        ChatAgentService.ChatSession session = chatService.require(sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        chatService.subscribe(session, runId, after, emitterSubscriber(emitter, session));
        return emitter;
    }

    /** 提前创建一个空会话，便于页面在首次模型调用前操作 Workspace。 */
    @PostMapping(value = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createSession() {
        return sessionState(chatService.open(null));
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> models() {
        List<Map<String, Object>> models = new ArrayList<Map<String, Object>>();
        for (ChatModelProfile profile : chatService.models()) {
            models.add(Map.of(
                    "id", profile.getId(),
                    "name", profile.getName(),
                    "webSearch", profile.getWebSearch(),
                    "webSearchSupported", profile.isWebSearchSupported()));
        }
        return Map.of(
                "defaultModel", chatService.defaultModel(),
                "models", models,
                "defaultWebSearch", chatService.defaultWebSearch());
    }

    @GetMapping(value = "/sessions/{sessionId}/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> files(@PathVariable String sessionId) {
        return chatService.require(sessionId).getWorkspace().list("/workspace");
    }

    @GetMapping(value = "/sessions/{sessionId}/entries", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> entries(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "/workspace") String path) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (WorkspaceEntry entry : chatService.require(sessionId)
                .getWorkspace().entries(path)) {
            result.add(Map.of(
                    "path", entry.getPath(),
                    "type", entry.getType().name().toLowerCase(),
                    "size", entry.getSize()));
        }
        return result;
    }

    @GetMapping(value = "/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> session(@PathVariable String sessionId) {
        return sessionState(chatService.require(sessionId));
    }

    private Map<String, Object> sessionState(ChatAgentService.ChatSession session) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", session.getId());
        result.put("workspaceId", session.getWorkspace().id());
        result.put("files", session.getWorkspace().list("/workspace"));
        result.put("entries", workspaceEntries(session.getWorkspace(), "/workspace"));
        result.put("artifacts", artifacts(session));
        List<Map<String, String>> history = new ArrayList<Map<String, String>>();
        for (Message message : chatService.history(session)) {
            history.add(Map.of(
                    "role", message.getRole().name().toLowerCase(),
                    "content", message.getContent()));
        }
        result.put("history", history);
        List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        for (ChatAgentService.ChatRun run : chatService.runs(session)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            synchronized (run) {
                item.put("runId", run.getRunId());
                item.put("prompt", run.getPrompt());
                item.put("model", run.getModel());
                item.put("webSearch", run.isWebSearch());
                item.put("webSearchMode", run.getWebSearchMode());
                item.put("status", run.getStatus());
                item.put("content", run.getContent());
                item.put("reasoning", run.getReasoning());
                item.put("modelRounds", run.getModelRounds());
                item.put("steps", run.getSteps());
                item.put("error", run.getError());
                List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
                for (RuntimeEvent event : run.getEvents()) {
                    events.add(runtimeEvent(event));
                }
                item.put("events", events);
            }
            runs.add(item);
        }
        result.put("runs", runs);
        return result;
    }

    @GetMapping(value = "/sessions/{sessionId}/files/content",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String fileContent(
            @PathVariable String sessionId,
            @RequestParam String path) {
        if (!path.startsWith("/workspace/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能读取演示工作目录");
        }
        ChatAgentService.ChatSession session = chatService.require(sessionId);
        if (!session.getWorkspace().exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return session.getWorkspace().readText(path);
    }

    @GetMapping(value = "/sessions/{sessionId}/files/range",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> readRange(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestParam(defaultValue = "1") int startLine,
            @RequestParam(defaultValue = "200") int endLine,
            @RequestParam(defaultValue = "12000") int maxChars) {
        validateWorkspacePath(path);
        TextFileSlice slice = chatService.require(sessionId).getWorkspace()
                .readLines(path, startLine, endLine, maxChars);
        return Map.of(
                "content", slice.getContent(),
                "startLine", slice.getStartLine(),
                "endLine", slice.getEndLine(),
                "totalLines", slice.getTotalLines(),
                "truncated", slice.isTruncated());
    }

    @GetMapping(value = "/sessions/{sessionId}/files/search",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> search(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestParam String query,
            @RequestParam(defaultValue = "false") boolean caseSensitive,
            @RequestParam(defaultValue = "20") int maxResults) {
        validateWorkspacePath(path);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (TextSearchMatch match : chatService.require(sessionId).getWorkspace()
                .searchText(path, query, caseSensitive, maxResults)) {
            result.add(Map.of("line", match.getLine(), "text", match.getText()));
        }
        return result;
    }

    @PutMapping(value = "/sessions/{sessionId}/files/content",
            consumes = MediaType.TEXT_PLAIN_VALUE)
    public void writeFile(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestBody String content) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace().writeText(path, content);
    }

    @PostMapping(value = "/sessions/{sessionId}/files/append",
            consumes = MediaType.TEXT_PLAIN_VALUE)
    public void appendFile(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestBody String content) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace().appendText(path, content);
    }

    @PostMapping(value = "/sessions/{sessionId}/files/insert",
            consumes = MediaType.TEXT_PLAIN_VALUE)
    public void insertFile(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestParam int beforeLine,
            @RequestBody String content) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace()
                .insertLines(path, beforeLine, content);
    }

    @PostMapping(value = "/sessions/{sessionId}/files/replace-lines",
            consumes = MediaType.TEXT_PLAIN_VALUE)
    public void replaceLines(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestParam int startLine,
            @RequestParam int endLine,
            @RequestBody String content) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace()
                .replaceLines(path, startLine, endLine, content);
    }

    @DeleteMapping(value = "/sessions/{sessionId}/files/lines")
    public void deleteLines(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestParam int startLine,
            @RequestParam int endLine) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace()
                .deleteLines(path, startLine, endLine);
    }

    @PostMapping(value = "/sessions/{sessionId}/directories")
    public void createDirectory(
            @PathVariable String sessionId,
            @RequestParam String path) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace().createDirectory(path);
    }

    @PostMapping(value = "/sessions/{sessionId}/directories/copy")
    public void copyDirectory(
            @PathVariable String sessionId,
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(defaultValue = "false") boolean merge) {
        validateWorkspacePath(source);
        validateWorkspacePath(target);
        chatService.require(sessionId).getWorkspace()
                .copyDirectory(source, target, merge);
    }

    @PostMapping(value = "/sessions/{sessionId}/directories/move")
    public void moveDirectory(
            @PathVariable String sessionId,
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(defaultValue = "false") boolean merge) {
        validateWorkspacePath(source);
        validateWorkspacePath(target);
        chatService.require(sessionId).getWorkspace()
                .moveDirectory(source, target, merge);
    }

    @DeleteMapping(value = "/sessions/{sessionId}/directories")
    public void deleteDirectory(
            @PathVariable String sessionId,
            @RequestParam String path,
            @RequestParam(defaultValue = "false") boolean recursive) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace()
                .deleteDirectory(path, recursive);
    }

    @PostMapping(value = "/sessions/{sessionId}/files/copy")
    public void copyFile(
            @PathVariable String sessionId,
            @RequestParam String source,
            @RequestParam String target) {
        validateWorkspacePath(source);
        validateWorkspacePath(target);
        chatService.require(sessionId).getWorkspace().copy(source, target);
    }

    @PostMapping(value = "/sessions/{sessionId}/files/move")
    public void moveFile(
            @PathVariable String sessionId,
            @RequestParam String source,
            @RequestParam String target) {
        validateWorkspacePath(source);
        validateWorkspacePath(target);
        chatService.require(sessionId).getWorkspace().move(source, target);
    }

    @DeleteMapping(value = "/sessions/{sessionId}/files")
    public void deleteFile(
            @PathVariable String sessionId,
            @RequestParam String path) {
        validateWorkspacePath(path);
        chatService.require(sessionId).getWorkspace().delete(path);
    }

    @GetMapping(value = "/sessions/{sessionId}/artifacts/{artifactId}/content")
    public String artifactContent(
            @PathVariable String sessionId,
            @PathVariable String artifactId,
            @RequestParam(required = false) String path) {
        ChatAgentService.ChatSession session = chatService.require(sessionId);
        for (Artifact artifact : session.getWorkspace().artifacts()) {
            if (artifact.getId().equals(artifactId)) {
                return artifact.readText(path == null ? artifact.getEntrypoint() : path);
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact 不存在");
    }

    @GetMapping(value = "/sessions/{sessionId}/artifacts/{artifactId}/files/**")
    public ResponseEntity<String> artifactFile(
            @PathVariable String sessionId,
            @PathVariable String artifactId,
            HttpServletRequest request) {
        String marker = "/artifacts/" + artifactId + "/files/";
        String requestPath = request.getRequestURI();
        int markerIndex = requestPath.indexOf(marker);
        if (markerIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact 路径无效");
        }
        String relativePath = decodeRelativePath(
                requestPath.substring(markerIndex + marker.length()));
        ChatAgentService.ChatSession session = chatService.require(sessionId);
        for (Artifact artifact : session.getWorkspace().artifacts()) {
            if (artifact.getId().equals(artifactId)) {
                String root = artifact.getRoot();
                String path = root + (root.endsWith("/") ? "" : "/") + relativePath;
                return ResponseEntity.ok()
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(contentType(path))
                        .body(artifact.readText(path));
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact 不存在");
    }

    /** 从实时 Workspace 提供同源预览，保证 HTML 的相对 CSS/JS 路径可正常加载。 */
    @GetMapping(value = "/sessions/{sessionId}/preview/**")
    public ResponseEntity<String> workspacePreview(
            @PathVariable String sessionId,
            HttpServletRequest request) {
        String marker = "/sessions/" + sessionId + "/preview/";
        String requestPath = request.getRequestURI();
        int markerIndex = requestPath.indexOf(marker);
        if (markerIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预览路径无效");
        }
        String relativePath = decodeRelativePath(
                requestPath.substring(markerIndex + marker.length()));
        String path = "/workspace/" + relativePath;
        ChatAgentService.ChatSession session = chatService.require(sessionId);
        if (!session.getWorkspace().exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预览文件不存在");
        }
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy",
                        "sandbox allow-scripts; default-src 'self' data: blob:; "
                                + "img-src 'self' data: blob:; "
                                + "style-src 'self' 'unsafe-inline'; "
                                + "script-src 'self' 'unsafe-inline'")
                .contentType(contentType(path))
                .body(session.getWorkspace().readText(path));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void close(@PathVariable String sessionId) {
        chatService.close(sessionId);
    }

    private static void sendRuntimeEvent(SseEmitter emitter, RuntimeEvent event) {
        if (RuntimeEventTypes.MODEL_DELTA.equals(event.getType())) {
            send(emitter, "delta", Map.of("content", event.getDetail()));
            return;
        }
        if (RuntimeEventTypes.MODEL_REASONING_DELTA.equals(event.getType())) {
            send(emitter, "reasoning", Map.of("content", event.getDetail()));
            return;
        }
        send(emitter, "trace", runtimeEvent(event));
    }

    private ChatAgentService.RunSubscriber emitterSubscriber(
            SseEmitter emitter,
            ChatAgentService.ChatSession session) {
        return new ChatAgentService.RunSubscriber() {
            @Override
            public void onEvent(RuntimeEvent event) {
                sendRuntimeEvent(emitter, event);
            }

            @Override
            public void onComplete(AgentResult result) {
                send(emitter, "done", donePayload(session, result));
                emitter.complete();
            }

            @Override
            public void onError(String message) {
                send(emitter, "error", Map.of("content", message));
                emitter.complete();
            }
        };
    }

    private static Map<String, Object> runtimeEvent(RuntimeEvent event) {
        Map<String, Object> trace = new LinkedHashMap<String, Object>();
        trace.put("type", event.getType());
        trace.put("sequence", event.getSequence());
        trace.put("runId", event.getRunId());
        trace.put("occurredAt", event.getOccurredAt().toString());
        if (event.getType().startsWith("tool.")) {
            trace.put("detail", event.getDetail());
        }
        trace.put("attributes", event.getAttributes());
        return trace;
    }

    private static Map<String, Object> donePayload(
            ChatAgentService.ChatSession session,
            AgentResult result) {
        Map<String, Object> done = new LinkedHashMap<String, Object>();
        done.put("content", result.getText());
        done.put("runId", result.getRunId());
        done.put("steps", result.getSteps());
        done.put("files", session.getWorkspace().list("/workspace"));
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        for (Artifact artifact : result.getArtifacts()) {
            values.add(Map.of(
                    "id", artifact.getId(),
                    "root", artifact.getRoot(),
                    "entrypoint", artifact.getEntrypoint()));
        }
        done.put("artifacts", values);
        return done;
    }

    private static List<Map<String, String>> artifacts(
            ChatAgentService.ChatSession session) {
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (Artifact artifact : session.getWorkspace().artifacts()) {
            result.add(Map.of(
                    "id", artifact.getId(),
                    "root", artifact.getRoot(),
                    "entrypoint", artifact.getEntrypoint()));
        }
        return result;
    }

    private static List<Map<String, Object>> workspaceEntries(
            Workspace workspace, String root) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<String> pending = new ArrayList<String>();
        pending.add(root);
        for (int index = 0; index < pending.size(); index++) {
            for (WorkspaceEntry entry : workspace.entries(pending.get(index))) {
                result.add(Map.of(
                        "path", entry.getPath(),
                        "type", entry.getType().name().toLowerCase(),
                        "size", entry.getSize()));
                if (entry.getType() == WorkspaceEntry.Type.DIRECTORY) {
                    pending.add(entry.getPath());
                }
            }
        }
        return result;
    }

    private static void validateWorkspacePath(String path) {
        if (path == null || !path.startsWith("/workspace/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "只能操作 /workspace 下的文件");
        }
    }

    private static String decodeRelativePath(String value) {
        String decoded = UriUtils.decode(value, StandardCharsets.UTF_8);
        if (decoded.isEmpty() || decoded.startsWith("/")
                || decoded.contains("../") || decoded.contains("..\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "相对路径无效");
        }
        return decoded;
    }

    private static MediaType contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return MediaType.TEXT_HTML;
        }
        if (lower.endsWith(".css")) {
            return MediaType.parseMediaType("text/css");
        }
        if (lower.endsWith(".js")) {
            return MediaType.parseMediaType("application/javascript");
        }
        if (lower.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (lower.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (lower.endsWith(".xml")) {
            return MediaType.APPLICATION_XML;
        }
        return MediaType.TEXT_PLAIN;
    }

    private static void send(
            SseEmitter emitter,
            String name,
            Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new IllegalStateException("浏览器连接已断开", exception);
        }
    }

    @Getter
    @Setter
    public static class ChatRequest {
        private String sessionId;
        private String message;
        private String model;
        private Boolean webSearch;
    }
}
