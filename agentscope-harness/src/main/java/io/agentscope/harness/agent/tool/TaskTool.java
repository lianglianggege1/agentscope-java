/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * Unified tool for background task lifecycle management. Combines task result retrieval,
 * cancellation, and listing into a single tool class.
 *
 * <ul>
 *   <li>{@code task_output} — retrieve result (blocking or non-blocking)
 *   <li>{@code task_cancel} — cancel a running task
 *   <li>{@code task_list} — list all tracked tasks with optional status filter
 * </ul>
 *
 * <p>All operations are scoped to the current parent session ID via {@link RuntimeContext}. The
 * {@link TaskRepository} handles fallback to workspace-persisted records when no local future
 * exists (cross-node or post-restart scenarios).
 */
/**
 * 后台任务生命周期管理统一工具。将任务结果查询、任务取消、任务列表查询整合至同一工具类。
 *
 * <ul>
 *   <li>{@code task_output} — 获取任务结果（支持阻塞/非阻塞模式）
 *   <li>{@code task_cancel} — 终止正在运行的任务
 *   <li>{@code task_list} — 查询所有已追踪任务，可附加状态过滤条件
 * </ul>
 *
 * <p>所有操作通过 {@link RuntimeContext} 限定在当前父会话ID范围内。
 * 当本地不存在任务Future时，{@link TaskRepository} 会降级读取工作区持久化记录，
 * 适配跨节点访问或服务重启后的场景。
 */
public class TaskTool {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final TaskRepository taskRepository;

    public TaskTool(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /*
    @Tool(
            name = "task_output",
            description =
                    "获取后台子智能体任务的输出结果。当调用 agent_spawn 或 agent_send "
                        + "并设置 timeout_seconds=0 时使用。建议设置 block=false，"
                        + "无需等待即可轮询状态。仅在准备阻塞等待结果时使用 block=true（默认值）。"
                        + "请勿在任务刚启动后立刻调用该接口——对话历史中的任务状态存在滞后；"
                        + "务必通过 task_output 或 task_list 获取实时状态。")
    public String taskOutput(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "task_id",
                            description =
                                    "调用 agent_spawn 或 agent_send 且 timeout_seconds=0 时返回的 task_id")
                    String taskId,
            @ToolParam(
                            name = "block",
                            description =
                                    "是否阻塞等待任务完成（默认：true）。轮询状态建议设为 false，避免阻塞。",
                            required = false)
                    Boolean block,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "最大等待时长，单位毫秒（默认：30000，上限：600000）",
                            required = false)
                    Long timeout) { }
     */
    @Tool(
            name = "task_output",
            description =
                    "Retrieve the output of a background subagent task. Use when agent_spawn or"
                        + " agent_send was called with timeout_seconds=0. Prefer block=false to"
                        + " check status without waiting. Only use block=true (the default) when"
                        + " you are ready to wait for the result. Do NOT call this immediately"
                        + " after launching a task — the task status in conversation history is"
                        + " stale; always call task_output or task_list to get the current state.")
    public String taskOutput(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "task_id",
                            description =
                                    "The task_id returned by agent_spawn or agent_send when"
                                            + " timeout_seconds was 0")
                    String taskId,
            @ToolParam(
                            name = "block",
                            description =
                                    "Whether to wait for completion (default: true). Prefer"
                                            + " false for status checks to avoid blocking.",
                            required = false)
                    Boolean block,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "Max wait time in milliseconds (default: 30000, max: 600000)",
                            required = false)
                    Long timeout) {

        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required";
        }

        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        BackgroundTask bgTask = taskRepository.getTask(runtimeContext, sessionId, taskId);
        if (bgTask == null) {
            return "Error: No background task found with ID: "
                    + taskId
                    + ". Use task_list() to see all known tasks for this session.";
        }

        bgTask.updateLastCheckedAt();

        boolean shouldBlock = block == null || block;
        long timeoutMs = timeout != null ? Math.min(timeout, 600_000) : 30_000;

        if (shouldBlock && !bgTask.isCompleted()) {
            // If the task has no local future (cross-node or post-restart), degrade gracefully
            // instead of blocking indefinitely on an incomplete synthetic future.
            if (bgTask.getTaskStatus() == TaskStatus.PENDING
                    || bgTask.getTaskStatus() == TaskStatus.RUNNING) {
                try {
                    bgTask.waitForCompletion(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Error: Wait for task interrupted";
                }
                // After waiting, if still not complete it may be running on another node
                if (!bgTask.isCompleted()) {
                    return "task_id: "
                            + taskId
                            + "\nstatus: running"
                            + "\nnote: Task is running (possibly on another node)."
                            + " Use task_output(block=false) to poll for completion.";
                }
            }
        }

        // If the caller successfully retrieved a terminal result, mark this task delivered so the
        // SubagentsMiddleware push path doesn't re-deliver the same payload on the next reasoning
        // round. Idempotent — if already delivered, this is a no-op. (Phase B-3.)
        if (bgTask.isCompleted() && bgTask.getTaskStatus().isTerminal()) {
            try {
                taskRepository.markDelivered(runtimeContext, sessionId, taskId);
            } catch (RuntimeException ignore) {
                // Marking is best-effort; failure just risks a redundant push, never wrong data.
            }
        }
        return formatTaskDetail(bgTask);
    }

    /*
    @Tool(
            name = "task_cancel",
            description =
                    "取消正在运行的后台任务。用于终止不再需要执行的任务。"
                            + "对已完成的任务无任何作用。")
    public String taskCancel(
            RuntimeContext runtimeContext,
            @ToolParam(name = "task_id", description = "待取消任务的 task_id") String taskId) {}
     */
    @Tool(
            name = "task_cancel",
            description =
                    "Cancel a running background task. Use to stop a task that is no longer"
                            + " needed. Has no effect on already-completed tasks.")
    public String taskCancel(
            RuntimeContext runtimeContext,
            @ToolParam(name = "task_id", description = "The task_id to cancel") String taskId) {

        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required";
        }

        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        BackgroundTask bgTask = taskRepository.getTask(runtimeContext, sessionId, taskId);
        if (bgTask == null) {
            return "Error: No background task found with ID: " + taskId;
        }

        TaskStatus currentStatus = bgTask.getTaskStatus();
        if (currentStatus.isTerminal()) {
            return "task_id: "
                    + taskId
                    + "\nstatus: "
                    + currentStatus.name().toLowerCase()
                    + "\nnote: Task already in terminal state, cannot cancel.";
        }

        taskRepository.cancelTask(runtimeContext, sessionId, taskId);
        return "task_id: " + taskId + "\nstatus: cancelled\nCancellation requested successfully.";
    }

    /*
    @Tool(
            name = "task_list",
            description =
                    "列出当前会话下所有后台任务及其实时状态。"
                        + "数据来源于持久化工作区存储，即使对话压缩或节点迁移后信息依然准确。"
                        + "可按状态筛选（running、completed、failed、cancelled）；不填则查询全部任务。"
                        + "可在对话压缩后通过该工具找回任务ID与任务状态。")
    public String taskList(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "status_filter",
                            description =
                                    "状态筛选条件：running、completed、failed、cancelled；"
                                            + "留空则返回所有任务",
                            required = false)
                    String statusFilter) {}
     */
    @Tool(
            name = "task_list",
            description =
                    "List all background tasks for the current session with their current statuses."
                        + " Reads from durable workspace storage — always accurate even after"
                        + " conversation compaction or node migration. Optionally filter by status"
                        + " (running, completed, failed, cancelled). Use this to recover task IDs"
                        + " and state after compaction.")
    public String taskList(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "status_filter",
                            description =
                                    "Filter by status: running, completed, failed, cancelled, or"
                                            + " omit for all tasks",
                            required = false)
                    String statusFilter) {

        TaskStatus filter = parseStatusFilter(statusFilter);
        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        Collection<BackgroundTask> tasks =
                taskRepository.listTasks(runtimeContext, sessionId, filter);

        if (tasks.isEmpty()) {
            String filterDesc =
                    filter != null ? " with status '" + filter.name().toLowerCase() + "'" : "";
            return "No background tasks tracked" + filterDesc + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(tasks.size()).append(" tracked task(s):\n");
        for (BackgroundTask task : tasks) {
            sb.append("- task_id: ").append(task.getTaskId());
            if (task.getAgentId() != null) {
                sb.append("  agent: ").append(task.getAgentId());
            }
            sb.append("  status: ").append(task.getTaskStatus().name().toLowerCase());
            sb.append("  created: ").append(ISO_FORMATTER.format(task.getCreatedAt()));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static TaskStatus parseStatusFilter(String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter.trim())) {
            return null;
        }
        try {
            return TaskStatus.valueOf(filter.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String formatTaskDetail(BackgroundTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("task_id: ").append(task.getTaskId()).append('\n');
        if (task.getAgentId() != null) {
            sb.append("agent_id: ").append(task.getAgentId()).append('\n');
        }
        sb.append("status: ").append(task.getStatus()).append('\n');
        sb.append("created_at: ").append(ISO_FORMATTER.format(task.getCreatedAt())).append('\n');

        if (task.isCompleted() && task.getResult() != null) {
            sb.append("\nResult:\n").append(task.getResult());
        } else if (task.getError() != null) {
            Exception err = task.getError();
            sb.append("\nError:\n").append(err.getMessage());
            if (err.getCause() != null) {
                sb.append("\nCause: ").append(err.getCause().getMessage());
            }
        } else if (!task.isCompleted()) {
            sb.append("\nTask still running...");
        } else {
            sb.append("\nTask completed with no result.");
        }
        return sb.toString();
    }
}
