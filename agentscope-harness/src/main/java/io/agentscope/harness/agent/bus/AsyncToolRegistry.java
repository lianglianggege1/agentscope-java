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
package io.agentscope.harness.agent.bus;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Registry for tracking async tool executions that have been offloaded to the background.
 *
 * <p>When a tool execution exceeds the configured timeout, {@code AsyncToolMiddleware} registers
 * it here. The registry tracks the tool's lifecycle so that:
 * <ul>
 *   <li>On normal completion, the record is updated and the result delivered via inbox</li>
 *   <li>On process crash/restart, stale RUNNING records can be detected and cleaned up</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code WorkspaceAsyncToolRegistry} — single-process, suitable for testing</li>
 * </ul>
 */
/**
 * 用于管理已移交后台执行的异步工具任务的注册器。
 *
 * <p>当工具执行超出配置的超时时间时，{@code AsyncToolMiddleware} 会将任务注册至此处。该注册器统一维护工具任务生命周期，实现以下能力：
 * <ul>
 *   <li>任务正常结束时，更新记录并通过消息收件箱推送执行结果</li>
 *   <li>进程崩溃或重启后，可识别并清理遗留的运行中任务记录</li>
 * </ul>
 *
 * <p>现有实现类：
 * <ul>
 *   <li>{@code WorkspaceAsyncToolRegistry} — 单进程实现，适用于测试场景</li>
 * </ul>
 */
public interface AsyncToolRegistry {

    /**
     * Register a new async tool execution.
     *
     * @param record the async tool record with status {@link AsyncToolRecord#RUNNING}
     */
    /**
     * 注册一条新的异步工具执行记录。
     *
     * @param record 状态为 {@link AsyncToolRecord#RUNNING} 的异步工具记录对象
     */
    Mono<Void> register(AsyncToolRecord record);

    /**
     * Mark an async tool execution as completed.
     *
     * @param id     the async tool record id
     * @param result the tool execution result text
     */
    /**
     * 将异步工具执行任务标记为已完成。
     *
     * @param id     异步工具记录ID
     * @param result 工具执行结果文本
     */
    Mono<Void> complete(String id, String result);

    /**
     * Mark an async tool execution as failed.
     *
     * @param id    the async tool record id
     * @param error the error message
     */
    /**
     * 将异步工具执行任务标记为执行失败。
     *
     * @param id    异步工具记录ID
     * @param error 错误信息
     */
    Mono<Void> fail(String id, String error);

    /**
     * Find async tool records for the given session that have been in RUNNING status longer
     * than the specified TTL. These are likely orphaned due to process crash.
     *
     * @param sessionId the session to check
     * @param ttl       records older than this duration are considered stale
     * @return stale RUNNING records
     */
    /**
     * 查询指定会话下运行时长超过指定存活时间的异步工具记录。
     * 这类记录大概率因进程崩溃成为孤立任务。
     *
     * @param sessionId 待检查的会话ID
     * @param ttl       超过该时长的记录判定为过期
     * @return 状态为运行中的过期任务记录列表
     */
    Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl);

    /**
     * Mark a stale async tool record as timed out. Prevents it from being returned by
     * subsequent {@link #findStale} calls.
     *
     * @param id the async tool record id
     */
    /**
     * 将过期的异步工具记录标记为超时状态，后续调用 {@link #findStale} 方法时将不再查询到该记录。
     *
     * @param id 异步工具记录ID
     */
    Mono<Void> markTimeout(String id);
}
