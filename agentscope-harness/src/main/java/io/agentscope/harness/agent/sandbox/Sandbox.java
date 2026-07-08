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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import java.io.InputStream;

/**
 * An active sandbox with a fully isolated workspace.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Acquire via {@link SandboxClient#create} (new) or {@link SandboxClient#resume} (existing)
 *   <li>Call {@link #start()} — initializes or restores the workspace
 *   <li>Use {@link #exec} for command execution, {@link #persistWorkspace}/{@link #hydrateWorkspace}
 *       for archive operations
 *   <li>Call {@link #stop()} — persists the snapshot (does NOT destroy resources)
 *   <li>Call {@link #shutdown()} — destroys backend resources (tmpdir, container)
 *   <li>Or use {@link #close()} which calls stop + shutdown in sequence
 * </ol>
 *
 * <p>The distinction between {@code stop()} and {@code shutdown()} is critical:
 * <ul>
 *   <li>{@code stop()}: persist snapshot only — safe for both self-managed and user-managed
 *       sandboxes</li>
 *   <li>{@code shutdown()}: destroy backend resources — only called on self-managed sandboxes</li>
 * </ul>
 */
/**
 * 拥有完全隔离工作区的运行沙箱实例。
 *
 * <p>生命周期流程：
 * <ol>
 *   <li>通过 {@link SandboxClient#create} 新建沙箱，或 {@link SandboxClient#resume} 恢复已有沙箱
 *   <li>调用 {@link #start()}：初始化或还原沙箱工作区
 *   <li>调用 {@link #exec} 执行命令；调用 {@link #persistWorkspace}/{@link #hydrateWorkspace} 进行归档读写
 *   <li>调用 {@link #stop()}：持久化快照（不会销毁底层资源）
 *   <li>调用 {@link #shutdown()}：销毁底层资源（临时目录、容器等）
 *   <li>也可直接调用 {@link #close()}，内部会依次执行 stop 与 shutdown
 * </ol>
 *
 * <p>需严格区分 {@code stop()} 和 {@code shutdown()} 的行为差异：
 * <ul>
 *   <li>{@code stop()}：仅持久化快照，自托管、用户托管沙箱均可安全调用</li>
 *   <li>{@code shutdown()}：销毁底层资源，仅用于自托管类型沙箱</li>
 * </ul>
 */
public interface Sandbox extends AutoCloseable {

    void start() throws Exception;

    void stop() throws Exception;

    default void shutdown() throws Exception {
        // no-op by default
    }

    @Override
    void close() throws Exception;

    boolean isRunning();

    /**
     * Returns the current serializable state of this sandbox.
     *
     * @return state (may be modified by lifecycle methods)
     */
    /**
     * 获取当前沙箱可序列化状态对象。
     *
     * @return 沙箱状态实例（生命周期相关方法会修改该状态内容）
     */
    SandboxState getState();

    /**
     * Runs a shell command in the sandbox workspace.
     *
     * @param runtimeContext per-call agent context (session, user, attributes); may be {@code null}
     * @param command shell command
     * @param timeoutSeconds max wait; {@code null} for implementation default
     */
    /**
     * 在沙箱工作区内执行Shell命令。
     *
     * @param runtimeContext 单次调用对应的智能体上下文（会话、用户、自定义属性），可为 {@code null}
     * @param command Shell执行命令
     * @param timeoutSeconds 最大等待超时秒数；传 {@code null} 则使用实现默认超时值
     */
    ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception;

    InputStream persistWorkspace() throws Exception;

    void hydrateWorkspace(InputStream archive) throws Exception;
}
