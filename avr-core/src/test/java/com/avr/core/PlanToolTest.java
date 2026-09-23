package com.avr.core;

import com.avr.api.ExecutionContext;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolResult;
import com.avr.core.tool.FileOpTool;
import com.avr.core.tool.DirectoryTool;
import com.avr.core.tool.PlanTool;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanToolTest {
    @Test
    void doesNotAcceptClaimsWithoutMatchingToolEvidenceAndWorkspaceState() {
        MemoryWorkspace workspace = new MemoryWorkspace("plan-test");
        PlanTool plan = new PlanTool();
        ToolContext context = new ToolContext(workspace, ExecutionContext.empty(), "run-1");

        ToolResult created = plan.execute(call("create", Map.of("steps", List.of(
                Map.of("id", "report", "description", "Write report",
                        "verifyPath", "/workspace/report.html", "minBytes", 10)))), context);
        assertTrue(created.isSuccess());
        assertTrue(plan.execute(call("create", Map.of("steps", List.of(
                Map.of("id", "report", "description", "Write report")))), context).isSuccess());
        assertFalse(plan.isComplete("run-1", workspace));

        ToolResult fake = plan.execute(call("update", Map.of("stepId", "report",
                "status", "done")), context);
        assertFalse(fake.isSuccess());

        ToolCall read = new ToolCall("read-1", "file.op", Map.of(
                "op", "list", "path", "/workspace"));
        ToolResult readResult = new FileOpTool().execute(read, context);
        plan.record("run-1", read, readResult);
        ToolResult wrongEvidence = plan.execute(call("update", Map.of(
                "stepId", "report", "status", "done")), context);
        assertFalse(wrongEvidence.isSuccess());

        ToolCall write = new ToolCall("write-1", "file.op", Map.of(
                "op", "write", "path", "/workspace/report.html", "content", "<html>done</html>"));
        ToolResult writeResult = new FileOpTool().execute(write, context);
        plan.record("run-1", write, writeResult);
        ToolResult updated = plan.execute(call("update", Map.of(
                "stepId", "report", "status", "done")), context);
        assertTrue(updated.isSuccess());
        assertTrue(updated.getContent().contains("write-1"));
        assertFalse(plan.isComplete("run-1", workspace));
        assertTrue(plan.execute(call("check", Map.of()), context).isSuccess());
        assertTrue(plan.isComplete("run-1", workspace));

        workspace.delete("/workspace/report.html");
        assertFalse(plan.isComplete("run-1", workspace));
        plan.release("run-1");
        assertFalse(plan.isComplete("run-1", workspace));
    }

    @Test
    void verifiesDirectoryMoveAndRemovedSource() {
        MemoryWorkspace workspace = new MemoryWorkspace("directory-plan");
        workspace.createDirectory("/workspace/source");
        workspace.writeText("/workspace/source/a.txt", "a");
        PlanTool plan = new PlanTool();
        ToolContext context = new ToolContext(workspace, ExecutionContext.empty(), "run-2");
        plan.execute(call("create", Map.of("steps", List.of(Map.of(
                "id", "move", "description", "Move directory",
                "verifyDirectory", "/workspace/destination",
                "absentPath", "/workspace/source")))), context);

        ToolCall move = new ToolCall("move-1", "directory.manage", Map.of(
                "op", "move", "source", "/workspace/source",
                "target", "/workspace/destination"));
        plan.record("run-2", move, new DirectoryTool().execute(move, context));
        assertTrue(plan.execute(call("update", Map.of(
                "stepId", "move", "status", "done")), context).isSuccess());
        plan.execute(call("check", Map.of()), context);
        assertTrue(plan.isComplete("run-2", workspace));
    }

    @Test
    void checkAutomaticallyCompletesStepsFromWorkspaceAndRecordedEvidence() {
        MemoryWorkspace workspace = new MemoryWorkspace("auto-plan");
        PlanTool plan = new PlanTool();
        ToolContext context = new ToolContext(workspace, ExecutionContext.empty(), "run-3");
        plan.execute(call("create", Map.of("steps", List.of(Map.of(
                "id", "report", "description", "Write report",
                "verifyPath", "/workspace/report.html", "minBytes", 10)))), context);

        ToolCall write = new ToolCall("write-auto", "file.op", Map.of(
                "op", "write", "path", "/workspace/report.html",
                "content", "<html>done</html>"));
        plan.record("run-3", write, new FileOpTool().execute(write, context));

        ToolResult checked = plan.execute(call("check", Map.of()), context);
        assertTrue(checked.isSuccess());
        assertTrue(checked.getContent().contains("\"complete\":true"));
        assertTrue(checked.getContent().contains("write-auto"));
        assertTrue(plan.isComplete("run-3", workspace));
    }

    @Test
    void revisesWrongFileConstraintToAggregateDirectoryConstraint() {
        MemoryWorkspace workspace = new MemoryWorkspace("revised-plan");
        workspace.createDirectory("/workspace/report");
        workspace.writeText("/workspace/report/README.md", "index");
        PlanTool plan = new PlanTool();
        ToolContext context = new ToolContext(workspace, ExecutionContext.empty(), "run-4");
        plan.execute(call("create", Map.of("steps", List.of(Map.of(
                "id", "report", "description", "Write a large report",
                "verifyPath", "/workspace/report/README.md", "minBytes", 20)))), context);

        assertFalse(plan.execute(call("update", Map.of(
                "stepId", "report", "status", "done")), context).isSuccess());
        ToolResult revised = plan.execute(call("revise", Map.of(
                "stepId", "report",
                "verifyPath", "",
                "verifyDirectory", "/workspace/report",
                "minBytes", 20)), context);
        assertTrue(revised.isSuccess());
        assertTrue(revised.getContent().contains("verifyDirectory"));

        ToolCall write = new ToolCall("write-chapter", "file.op", Map.of(
                "op", "write", "path", "/workspace/report/chapter.md",
                "content", "This chapter is long enough."));
        plan.record("run-4", write, new FileOpTool().execute(write, context));
        ToolResult checked = plan.execute(call("check", Map.of()), context);

        assertTrue(checked.isSuccess());
        assertTrue(checked.getContent().contains("\"complete\":true"));
        assertTrue(plan.isComplete("run-4", workspace));
    }

    private static ToolCall call(String op, Map<String, Object> arguments) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<String, Object>(arguments);
        values.put("op", op);
        return new ToolCall("plan-" + op, "plan.manage", values);
    }
}
