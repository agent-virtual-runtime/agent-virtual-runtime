package com.avr.examples;

import com.avr.api.Message;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** 示例工作台的交付检查：文件任务必须真实改变工作区。 */
final class WorkspaceMutationCheck {
    private static final Pattern ACTION = Pattern.compile(
            "(?i)(写|生成|创建|新建|保存|追加|编辑|修改|更新|迁移|移动|复制|删除|重命名|"
                    + "放到|放进|整理|提交|输出|制作|加入|添加|实现|create|write|move|copy|"
                    + "delete|rename|update|append|save|generate|build)");
    private static final Pattern TARGET = Pattern.compile(
            "(?i)(报告|文件|目录|页面|网页|工作区|文件夹|产物|html|css|javascript|"
                    + "script|workspace|artifact|file|folder|directory|report)");
    private static final Pattern QUESTION = Pattern.compile(
            "(?i)^\\s*(为什么|为何|如何|怎么|什么是|解释|分析.*原因|why|how|what)");
    private static final Pattern CONTINUE = Pattern.compile(
            "(?i)^\\s*(开始执行|继续执行|开始做|继续做|动手|go ahead|proceed)");

    private WorkspaceMutationCheck() {
    }

    static Predicate<Workspace> forRequest(
            String prompt, List<Message> history, Workspace workspace) {
        boolean mutation = requiresPlan(prompt, history);
        if (!mutation) {
            return candidate -> true;
        }
        byte[] before = fingerprint(workspace);
        return candidate -> !Arrays.equals(before, fingerprint(candidate));
    }

    static boolean requiresPlan(String prompt, List<Message> history) {
        boolean mutation = mutationRequested(prompt);
        if (!mutation && CONTINUE.matcher(prompt).find()) {
            for (int index = history.size() - 1; index >= 0; index--) {
                Message previous = history.get(index);
                if (previous.getRole() == Message.Role.USER) {
                    mutation = mutationRequested(previous.getContent());
                    break;
                }
            }
        }
        return mutation;
    }

    static boolean mutationRequested(String prompt) {
        if (QUESTION.matcher(prompt).find()) {
            return false;
        }
        return ACTION.matcher(prompt).find() && TARGET.matcher(prompt).find();
    }

    private static byte[] fingerprint(Workspace workspace) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ArrayDeque<String> pending = new ArrayDeque<String>();
            Set<String> visited = new HashSet<String>();
            pending.add("/workspace");
            while (!pending.isEmpty()) {
                String directory = pending.removeFirst();
                if (!visited.add(directory)) {
                    continue;
                }
                update(digest, "directory:" + directory);
                List<WorkspaceEntry> entries = new ArrayList<WorkspaceEntry>(
                        workspace.entries(directory));
                entries.sort((left, right) -> left.getPath().compareTo(right.getPath()));
                for (WorkspaceEntry entry : entries) {
                    update(digest, entry.getType().name() + ":" + entry.getPath());
                    if (entry.getType() == WorkspaceEntry.Type.DIRECTORY) {
                        pending.add(entry.getPath());
                    } else {
                        update(digest, workspace.readText(entry.getPath()));
                    }
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
