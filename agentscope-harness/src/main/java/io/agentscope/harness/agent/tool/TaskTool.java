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
 * 统一后台任务生命周期管理工具，将任务结果查询、任务取消、任务列表查询整合至同一工具类。
 *
 * <ul>
 *   <li>task_output — 获取任务执行结果（支持阻塞/非阻塞两种模式）
 *   <li>task_cancel — 终止正在运行的任务
 *   <li>task_list — 查询所有托管任务，可按状态筛选
 * </ul>
 *
 * <p>所有操作均以当前运行上下文 {@link RuntimeContext} 的父会话ID作为隔离作用域。
 * 若本地不存在任务执行句柄，{@link TaskRepository} 会从持久化到工作区的任务记录中兜底读取，
 * 适配跨节点部署、服务重启后的任务恢复场景。
 */
public class TaskTool {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final TaskRepository taskRepository;

    public TaskTool(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /*
    "获取后台子智能体任务的执行输出。当 agent_spawn / agent_send 设置 timeout_seconds=0 时使用本工具。"
            + "推荐设置 block=false 仅查询状态、不阻塞等待；仅当你准备好等待结果时再使用默认 block=true。"
            + "禁止在任务刚发起后立刻调用该工具——对话历史里的任务状态存在延迟，必须通过 task_output 或 task_list 获取实时状态。"
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
//                            调用 agent_spawn 或 agent_send 并传入 timeout_seconds=0 时返回的任务ID
                            description =
                                    "The task_id returned by agent_spawn or agent_send when"
                                            + " timeout_seconds was 0")
                    String taskId,
            @ToolParam(
                            name = "block",
//                            是否阻塞等待任务完成（默认true）。查询状态建议设为false，避免阻塞
                            description =
                                    "Whether to wait for completion (default: true). Prefer"
                                            + " false for status checks to avoid blocking.",
                            required = false)
                    Boolean block,
            @ToolParam(
                            name = "timeout",
//                            最大等待毫秒数（默认30000，上限600000）
                            description =
                                    "Max wait time in milliseconds (default: 30000, max: 600000)",
                            required = false)
                    Long timeout) {

        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required";
        }

        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        BackgroundTask bgTask = taskRepository.getTask(sessionId, taskId);
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

        return formatTaskDetail(bgTask);
    }

    @Tool(
            name = "task_cancel",
//            取消正在运行的后台任务。用于终止不再需要执行的任务；对已完成的任务无任何作用。
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
        // 根据会话与任务ID查询任务
        BackgroundTask bgTask = taskRepository.getTask(sessionId, taskId);
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

        taskRepository.cancelTask(sessionId, taskId);
        return "task_id: " + taskId + "\nstatus: cancelled\nCancellation requested successfully.";
    }

    /*
    "列出当前会话下所有后台任务及其实时状态。数据读取自持久化工作区存储，"
    + "对话压缩、节点迁移后数据依旧准确；可按需按状态筛选（运行中、已完成、失败、已取消）。"
    + "对话压缩丢失任务记录后，可通过本工具恢复任务ID与任务状态。"
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
        Collection<BackgroundTask> tasks = taskRepository.listTasks(sessionId, filter);

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
