package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolResult;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 运行级计划工具；完成步骤需要真实成功的工具调用，并可校验交付文件。 */
public final class PlanTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "plan.manage",
            "Track a task plan. First create steps with id and description. Use update to mark a step "
                    + "in_progress or done. The runtime automatically matches successful tool calls "
                    + "to completed steps; do not guess tool call IDs. Optional verifyPath, "
                    + "verifyDirectory, minBytes and absentPath check workspace outcomes. minBytes "
                    + "checks one file with verifyPath or all files recursively with verifyDirectory. "
                    + "Use revise when a pending step targets the wrong deliverable; an empty path "
                    + "clears that check. "
                    + "Call check after real work; it automatically reconciles completed steps. "
                    + "Incomplete plans cannot finish in plan mode.",
            "{\"type\":\"object\",\"required\":[\"op\"],\"additionalProperties\":false,\"properties\":{"
                    + "\"op\":{\"type\":\"string\",\"enum\":[\"create\",\"revise\",\"update\",\"check\"]},"
                    + "\"steps\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\","
                    + "\"required\":[\"id\",\"description\"],\"additionalProperties\":false,\"properties\":{"
                    + "\"id\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},"
                    + "\"verifyPath\":{\"type\":\"string\"},\"minBytes\":{\"type\":\"integer\"},"
                    + "\"verifyDirectory\":{\"type\":\"string\"},"
                    + "\"absentPath\":{\"type\":\"string\"}}}},"
                    + "\"stepId\":{\"type\":\"string\"},"
                    + "\"description\":{\"type\":\"string\"},"
                    + "\"verifyPath\":{\"type\":\"string\"},"
                    + "\"minBytes\":{\"type\":\"integer\"},"
                    + "\"verifyDirectory\":{\"type\":\"string\"},"
                    + "\"absentPath\":{\"type\":\"string\"},"
                    + "\"status\":{\"type\":\"string\",\"enum\":[\"in_progress\",\"done\"]}}}",
            "执行计划",
            ToolDisplayType.JSON);

    private final Map<String, Plan> plans = new ConcurrentHashMap<String, Plan>();

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String runId = context.getRunId();
        if (runId == null || runId.isEmpty()) {
            return ToolResult.failure("plan tool requires an AgentLoop run");
        }
        Plan plan = plans.computeIfAbsent(runId, ignored -> new Plan());
        synchronized (plan) {
            String op = call.requireString("op");
            switch (op) {
                case "create":
                    return create(plan, call);
                case "revise":
                    return revise(plan, call, context.getWorkspace());
                case "update":
                    return update(plan, call, context.getWorkspace());
                case "check":
                    reconcile(plan, context.getWorkspace());
                    return snapshot(plan, context.getWorkspace(), true);
                default:
                    return ToolResult.failure("unsupported plan operation: " + op);
            }
        }
    }

    /** 仅记录本次运行中确实成功的非计划工具调用。 */
    public void record(String runId, ToolCall call, ToolResult result) {
        if (!result.isSuccess() || "plan.manage".equals(call.getName())) {
            return;
        }
        Plan plan = plans.computeIfAbsent(runId, ignored -> new Plan());
        synchronized (plan) {
            plan.evidence.put(call.getId(), new Evidence(call));
            plan.checked = false;
        }
    }

    /** 运行结束时清理暂存状态，避免长寿命服务器积累计划。 */
    public void release(String runId) {
        plans.remove(runId);
    }

    public boolean hasPlan(String runId) {
        Plan plan = plans.get(runId);
        if (plan == null) {
            return false;
        }
        synchronized (plan) {
            return !plan.steps.isEmpty();
        }
    }

    /** 仅在计划已创建、所有步骤经证据确认且执行过最终检查时返回 true。 */
    public boolean isComplete(String runId, Workspace workspace) {
        Plan plan = plans.get(runId);
        if (plan == null) {
            return false;
        }
        synchronized (plan) {
            if (plan.steps.isEmpty() || !plan.checked) {
                return false;
            }
            for (Step step : plan.steps.values()) {
                if (!"done".equals(step.status) || !verified(step, workspace)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static ToolResult create(Plan plan, ToolCall call) {
        if (!plan.steps.isEmpty()) {
            return snapshot(plan, null, false);
        }
        Object raw = call.getArguments().get("steps");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            return ToolResult.failure("create requires non-empty steps");
        }
        Map<String, Step> created = new LinkedHashMap<String, Step>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                return ToolResult.failure("each step must be an object");
            }
            Map<?, ?> values = (Map<?, ?>) item;
            String id = required(values, "id");
            String description = required(values, "description");
            if (created.containsKey(id)) {
                return ToolResult.failure("duplicate step id: " + id);
            }
            int minBytes = 0;
            if (values.get("minBytes") != null) {
                if (!(values.get("minBytes") instanceof Number)) {
                    return ToolResult.failure("minBytes must be an integer");
                }
                minBytes = ((Number) values.get("minBytes")).intValue();
                if (minBytes < 0) {
                    return ToolResult.failure("minBytes must be non-negative");
                }
            }
            if (minBytes > 0 && optional(values, "verifyPath") == null
                    && optional(values, "verifyDirectory") == null) {
                return ToolResult.failure("minBytes requires verifyPath or verifyDirectory");
            }
            created.put(id, new Step(id, description,
                    optional(values, "verifyPath"), optional(values, "verifyDirectory"),
                    minBytes, optional(values, "absentPath")));
        }
        plan.steps.putAll(created);
        plan.checked = false;
        return snapshot(plan, null, false);
    }

    private static ToolResult revise(Plan plan, ToolCall call, Workspace workspace) {
        Step step = plan.steps.get(call.requireString("stepId"));
        if (step == null) {
            return ToolResult.failure("unknown plan step");
        }
        if ("done".equals(step.status)) {
            return ToolResult.failure("completed plan steps cannot be revised");
        }
        Map<String, Object> values = call.getArguments();
        if (values.containsKey("description")) {
            step.description = required(values, "description");
        }
        if (values.containsKey("verifyPath")) {
            step.verifyPath = optional(values, "verifyPath");
        }
        if (values.containsKey("verifyDirectory")) {
            step.verifyDirectory = optional(values, "verifyDirectory");
        }
        if (values.containsKey("absentPath")) {
            step.absentPath = optional(values, "absentPath");
        }
        if (values.containsKey("minBytes")) {
            Object raw = values.get("minBytes");
            if (!(raw instanceof Number)) {
                return ToolResult.failure("minBytes must be an integer");
            }
            step.minBytes = ((Number) raw).intValue();
            if (step.minBytes < 0) {
                return ToolResult.failure("minBytes must be non-negative");
            }
        }
        if (step.minBytes > 0 && step.verifyPath == null && step.verifyDirectory == null) {
            return ToolResult.failure("minBytes requires verifyPath or verifyDirectory");
        }
        step.status = "pending";
        step.evidenceCallId = null;
        plan.checked = false;
        return snapshot(plan, workspace, false);
    }

    private static ToolResult update(Plan plan, ToolCall call, Workspace workspace) {
        Step step = plan.steps.get(call.requireString("stepId"));
        if (step == null) {
            return ToolResult.failure("unknown plan step");
        }
        String status = call.requireString("status");
        if ("in_progress".equals(status)) {
            step.status = status;
            step.evidenceCallId = null;
        } else if ("done".equals(status)) {
            String verificationError = verificationError(step, workspace);
            if (verificationError != null) {
                return ToolResult.failure("step " + step.id + ": " + verificationError
                        + ". If this verification target is wrong, revise the pending step");
            }
            String evidenceId = matchingEvidence(plan, step);
            if (evidenceId == null) {
                return ToolResult.failure("step " + step.id
                        + ": execute a matching file or directory tool before marking done");
            }
            step.status = status;
            step.evidenceCallId = evidenceId;
        } else {
            return ToolResult.failure("status must be in_progress or done");
        }
        plan.checked = false;
        return snapshot(plan, workspace, false);
    }

    private static void reconcile(Plan plan, Workspace workspace) {
        for (Step step : plan.steps.values()) {
            if ("done".equals(step.status) || !verified(step, workspace)) {
                continue;
            }
            String evidenceId = matchingEvidence(plan, step);
            if (evidenceId != null) {
                step.status = "done";
                step.evidenceCallId = evidenceId;
            }
        }
    }

    private static String matchingEvidence(Plan plan, Step step) {
        List<Map.Entry<String, Evidence>> recorded =
                new ArrayList<Map.Entry<String, Evidence>>(plan.evidence.entrySet());
        for (int index = recorded.size() - 1; index >= 0; index--) {
            Map.Entry<String, Evidence> entry = recorded.get(index);
            String callId = entry.getKey();
            if (usedByAnotherStep(plan, step, callId)) {
                continue;
            }
            Evidence evidence = entry.getValue();
            if (step.verifyPath != null && !evidence.matchesMutation(step.verifyPath)) {
                continue;
            }
            if (step.verifyDirectory != null
                    && !evidence.matchesDirectory(step.verifyDirectory)) {
                continue;
            }
            if (step.absentPath != null && !evidence.matchesRemoval(step.absentPath)) {
                continue;
            }
            return callId;
        }
        return null;
    }

    private static boolean usedByAnotherStep(Plan plan, Step current, String callId) {
        for (Step step : plan.steps.values()) {
            if (step != current && callId.equals(step.evidenceCallId)) {
                return true;
            }
        }
        return false;
    }

    private static ToolResult snapshot(Plan plan, Workspace workspace, boolean markChecked) {
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        int done = 0;
        for (Step step : plan.steps.values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", step.id);
            item.put("description", step.description);
            item.put("status", step.status);
            boolean valid = "done".equals(step.status) && (workspace == null || verified(step, workspace));
            item.put("verified", valid);
            if (step.evidenceCallId != null) {
                item.put("evidenceCallId", step.evidenceCallId);
            }
            putIfPresent(item, "verifyPath", step.verifyPath);
            putIfPresent(item, "verifyDirectory", step.verifyDirectory);
            putIfPresent(item, "absentPath", step.absentPath);
            if (step.minBytes > 0) {
                item.put("minBytes", step.minBytes);
            }
            if (!valid && workspace != null) {
                String error = verificationError(step, workspace);
                if (error != null) {
                    item.put("verificationError", error);
                }
            }
            if (valid) {
                done++;
            }
            steps.add(item);
        }
        boolean complete = !steps.isEmpty() && done == steps.size();
        if (markChecked) {
            plan.checked = complete;
        }
        return ToolResult.success(WorkspaceJson.encode(Map.of(
                "complete", complete,
                "done", done,
                "total", steps.size(),
                "steps", steps)), ToolDisplayType.JSON);
    }

    private static boolean verified(Step step, Workspace workspace) {
        return verificationError(step, workspace) == null;
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static String verificationError(Step step, Workspace workspace) {
        if (step.verifyPath != null) {
            if (!workspace.exists(step.verifyPath)) {
                return "file does not exist: " + step.verifyPath;
            }
            int bytes = workspace.readText(step.verifyPath)
                    .getBytes(StandardCharsets.UTF_8).length;
            if (bytes < step.minBytes) {
                return "file " + step.verifyPath + " has " + bytes
                        + " bytes; requires at least " + step.minBytes;
            }
        }
        if (step.verifyDirectory != null && !workspace.directoryExists(step.verifyDirectory)) {
            return "directory does not exist: " + step.verifyDirectory;
        }
        if (step.verifyDirectory != null && step.minBytes > 0) {
            long bytes = directoryBytes(workspace, step.verifyDirectory);
            if (bytes < step.minBytes) {
                return "directory " + step.verifyDirectory + " contains " + bytes
                        + " file bytes; requires at least " + step.minBytes;
            }
        }
        if (step.absentPath != null && (workspace.exists(step.absentPath)
                || workspace.directoryExists(step.absentPath))) {
            return "source still exists: " + step.absentPath;
        }
        return null;
    }

    private static long directoryBytes(Workspace workspace, String directory) {
        long bytes = 0L;
        for (WorkspaceEntry entry : workspace.entries(directory)) {
            if (entry.getType() == WorkspaceEntry.Type.DIRECTORY) {
                bytes += directoryBytes(workspace, entry.getPath());
            } else {
                bytes += workspace.readText(entry.getPath())
                        .getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return bytes;
    }

    private static String required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return ((String) value).trim();
    }

    private static String optional(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String && !((String) value).trim().isEmpty()
                ? ((String) value).trim() : null;
    }

    private static final class Plan {
        private final Map<String, Step> steps = new LinkedHashMap<String, Step>();
        private final Map<String, Evidence> evidence = new LinkedHashMap<String, Evidence>();
        private boolean checked;
    }

    private static final class Evidence {
        private final String tool;
        private final String op;
        private final String path;
        private final String source;

        private Evidence(ToolCall call) {
            this.tool = call.getName();
            this.op = String.valueOf(call.getArguments().get("op"));
            Object destination = call.getArguments().containsKey("target")
                    ? call.getArguments().get("target") : call.getArguments().get("path");
            this.path = destination instanceof String ? (String) destination : "";
            Object sourcePath = call.getArguments().get("source");
            this.source = sourcePath instanceof String ? (String) sourcePath : "";
        }

        private boolean matchesMutation(String verifyPath) {
            if (!"file.op".equals(tool)
                    || "read".equals(op) || "search".equals(op)
                    || "list".equals(op) || "delete".equals(op)) {
                return false;
            }
            return verifyPath.equals(path);
        }

        private boolean matchesDirectory(String directory) {
            boolean directoryMutation = "directory.manage".equals(tool)
                    && ("create".equals(op) || "copy".equals(op)
                    || "move".equals(op) || "merge".equals(op))
                    && directory.equals(path);
            boolean fileMutation = "file.op".equals(tool)
                    && !"read".equals(op) && !"search".equals(op)
                    && !"list".equals(op) && !"delete".equals(op)
                    && isInside(directory, path);
            return directoryMutation || fileMutation;
        }

        private static boolean isInside(String directory, String candidate) {
            String prefix = directory.endsWith("/") ? directory : directory + "/";
            return candidate.startsWith(prefix);
        }

        private boolean matchesRemoval(String removedPath) {
            return (("file.op".equals(tool) || "directory.manage".equals(tool))
                    && "delete".equals(op) && removedPath.equals(path))
                    || (("file.op".equals(tool) || "directory.manage".equals(tool))
                    && "move".equals(op) && removedPath.equals(source));
        }
    }

    private static final class Step {
        private final String id;
        private String description;
        private String verifyPath;
        private String verifyDirectory;
        private int minBytes;
        private String absentPath;
        private String status = "pending";
        private String evidenceCallId;

        private Step(String id, String description, String verifyPath,
                String verifyDirectory, int minBytes, String absentPath) {
            this.id = id;
            this.description = description;
            this.verifyPath = verifyPath;
            this.verifyDirectory = verifyDirectory;
            this.minBytes = minBytes;
            this.absentPath = absentPath;
        }
    }
}
