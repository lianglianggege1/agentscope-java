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
package io.agentscope.harness.agent.subagent.task;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Collection;
import java.util.List;

/**
 * Repository for managing background subagent tasks, scoped by session.
 *
 * <p>All operations are scoped to a {@code sessionId} so that tasks from different parent sessions
 * are isolated from one another. Implementations may ignore {@code sessionId} (in-memory stores)
 * or use it to partition durable storage (workspace-backed stores).
 *
 * <p>Every method accepts a {@link RuntimeContext}: implementations that persist task state through
 * a per-user namespaced filesystem must propagate {@code rc} so that writes from concurrent users
 * land in their respective namespaces.
 */
/**
 * 会话隔离的后台子智能体任务仓储，用于管理异步子任务。
 *
 * <p>所有操作均基于 {@code sessionId} 做数据隔离，不同父会话的任务相互独立。
 * 内存型实现可忽略 {@code sessionId}；持久化存储（基于工作区）会使用该标识做数据分区。
 *
 * <p>所有方法入参均携带 {@link RuntimeContext}：若实现采用按用户命名空间隔离的文件系统持久化任务状态，
 * 必须透传上下文对象，保证多用户并发写入的数据落至各自独立命名空间。
 */
public interface TaskRepository {

    /**
     * Retrieve a background task by session and task ID, or {@code null} if not found.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    /**
     * 根据会话ID与任务ID查询后台任务，不存在则返回 {@code null}。
     *
     * @param rc 当前调用运行时上下文，可传入 {@link RuntimeContext#empty()}
     * @param sessionId 父会话隔离标识
     * @param taskId 任务唯一标识
     */
    BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId);

    /**
     * Submit a new background task according to {@link TaskRunSpec}.
     *
     * <p>Implementations should capture {@code rc} so that the background execution thread can
     * persist task state under the originating user's namespace.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param taskId unique identifier for the task
     * @param subAgentId the subagent type executing this task
     * @param sessionId the parent session scope
     * @param spec local supplier execution or remote HTTP task protocol
     * @return the created background task
     */
    /**
     * 根据 {@link TaskRunSpec} 提交一条全新后台任务。
     *
     * <p>实现类需捕获运行时上下文 {@code rc}，保证后台执行线程持久化任务状态时，写入发起用户对应的命名空间。
     *
     * @param rc 当前调用运行时上下文，可传入 {@link RuntimeContext#empty()}
     * @param taskId 任务唯一标识
     * @param subAgentId 执行该任务的子智能体类型ID
     * @param sessionId 父会话隔离标识
     * @param spec 任务执行描述：本地函数执行或远端HTTP任务协议
     * @return 新建完成的后台任务实例
     */
    BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec);

    /**
     * Remove a task from the repository.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    /**
     * 从仓储中删除指定任务。
     *
     * @param rc 当前调用运行时上下文，可传入 {@link RuntimeContext#empty()}
     * @param sessionId 父会话隔离标识
     * @param taskId 任务唯一标识
     */
    void removeTask(RuntimeContext rc, String sessionId, String taskId);

    /** Clear all tasks across all sessions. */
    /** 清空全部会话下的所有任务。 */
    void clear();

    /**
     * List all tracked tasks for the given session, optionally filtered by status.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @param sessionId the parent session scope
     * @param filter if non-null, only return tasks with this status; null returns all tasks
     */
    /**
     * 查询指定会话下所有托管任务，可按任务状态筛选。
     *
     * @param rc 当前调用运行时上下文，可传入 {@link RuntimeContext#empty()}
     * @param sessionId 父会话隔离标识
     * @param filter 非空时仅返回对应状态任务；传 null 则返回该会话全部任务
     */
    Collection<BackgroundTask> listTasks(RuntimeContext rc, String sessionId, TaskStatus filter);

    /**
     * Cancel a running task by session and task ID.
     *
     * @param rc the current call's runtime context; may be {@link RuntimeContext#empty()}
     * @return true if the task was found and cancellation was attempted
     */
    /**
     * 根据会话ID与任务ID终止正在运行的任务。
     *
     * @param rc 当前调用运行时上下文，可传入 {@link RuntimeContext#empty()}
     * @return 找到任务并执行取消操作则返回 true
     */
    boolean cancelTask(RuntimeContext rc, String sessionId, String taskId);

    // ------------------------------------------------------------------------
    // Phase B-3 — push delivery API.
    //
    // Implementations that persist {@link TaskRecord} should override the three methods below to
    // surface "terminal-but-not-yet-delivered" tasks to the parent agent's reasoning loop. The
    // default no-op implementations preserve pull-only semantics for in-memory repositories
    // where the parent process and any task callers share the same JVM and can rely on direct
    // future completion instead.
    // ------------------------------------------------------------------------

    /**
     * Returns terminal-state tasks for the given session whose completions have not yet been
     * pushed back to the parent agent.
     *
     * <p>Implementations should order the result deterministically (typically by completion time
     * ascending) so the parent agent sees deliveries in the order they actually happened.
     *
     * <p>Default returns an empty list — in-memory repositories do not currently support push.
     */
    /**
     * 查询指定会话下所有已终止、且执行结果尚未推送回父智能体的任务。
     *
     * <p>实现需保证结果有序（通常按任务完成时间升序），让父智能体按实际完成顺序接收推送结果。
     *
     * <p>默认实现返回空集合；内存型仓储暂不支持结果推送能力。
     */
    default List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        return List.of();
    }

    /**
     * Stamps the given task as delivered so it does not get pushed again. Idempotent: calling
     * twice has no effect after the first call.
     *
     * <p>Default is a no-op.
     */
    /**
     * 将指定任务标记为已推送，避免重复推送。接口具备幂等性：重复调用仅首次生效。
     *
     * <p>默认空实现，无任何操作。
     */
    default void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        // no-op
    }

    /**
     * Whether the given task has already been delivered to the parent agent. Used by
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#buildTaskSummary} to omit
     * already-delivered terminal tasks from the SYSTEM-prompt summary.
     *
     * <p>Default returns {@code false}.
     */
    /**
     * 判断指定任务是否已推送至父智能体。供{@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#buildTaskSummary}使用，在系统提示摘要中过滤已推送的终止任务。
     *
     * <p>默认实现返回 {@code false}。
     */
    default boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        return false;
    }
}
