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

import java.util.Collection;

/**
 * Repository for managing background subagent tasks, scoped by session.
 *
 * <p>All operations are scoped to a {@code sessionId} so that tasks from different parent sessions
 * are isolated from one another. Implementations may ignore {@code sessionId} (in-memory stores)
 * or use it to partition durable storage (workspace-backed stores).
 */
/**
 * 后台子智能体任务仓储，基于会话做数据隔离管理。
 *
 * <p>所有操作均以 sessionId 作为作用域，不同父会话的任务相互隔离。
 * 内存型实现可忽略 sessionId；持久化工作区实现则依靠该字段做数据分区存储。
 */
public interface TaskRepository {

    /**
     * Retrieve a background task by session and task ID, or {@code null} if not found.
     *
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    /**
     * 根据会话ID与任务ID查询后台任务，不存在则返回 {@code null}。
     *
     * @param sessionId 父会话隔离标识
     * @param taskId 任务唯一标识
     */
    BackgroundTask getTask(String sessionId, String taskId);

    /**
     * Submit a new background task according to {@link TaskRunSpec}.
     *
     * @param taskId unique identifier for the task
     * @param subAgentId the subagent type executing this task
     * @param sessionId the parent session scope
     * @param spec local supplier execution or remote HTTP task protocol
     * @return the created background task
     */
    /**
     * 根据 {@link TaskRunSpec} 提交一条全新后台任务。
     *
     * @param taskId 任务唯一标识
     * @param subAgentId 执行该任务的子智能体类型ID
     * @param sessionId 父会话隔离标识
     * @param spec 任务执行方案：本地线程执行 或 远程HTTP任务协议
     * @return 已创建的后台任务实例
     */
    BackgroundTask putTask(String taskId, String subAgentId, String sessionId, TaskRunSpec spec);

    /**
     * Remove a task from the repository.
     *
     * @param sessionId the parent session scope
     * @param taskId unique task identifier
     */
    /**
     * 从仓储中移除指定任务。
     *
     * @param sessionId 父会话隔离标识
     * @param taskId 任务唯一标识
     */
    void removeTask(String sessionId, String taskId);

    /** Clear all tasks across all sessions. */
    /** 清空全部会话下所有任务。 */
    void clear();

    /**
     * List all tracked tasks for the given session, optionally filtered by status.
     *
     * @param sessionId the parent session scope
     * @param filter if non-null, only return tasks with this status; null returns all tasks
     */
    /**
     * 查询指定会话下全部托管任务，可按任务状态筛选。
     *
     * @param sessionId 父会话隔离标识
     * @param filter 非空时仅返回匹配该状态的任务；传null则返回会话下所有任务
     */
    Collection<BackgroundTask> listTasks(String sessionId, TaskStatus filter);

    /**
     * Cancel a running task by session and task ID.
     *
     * @return true if the task was found and cancellation was attempted
     */
    /**
     * 根据会话ID与任务ID取消正在运行的任务。
     *
     * @return 找到任务并发起取消请求时返回true
     */
    boolean cancelTask(String sessionId, String taskId);
}
