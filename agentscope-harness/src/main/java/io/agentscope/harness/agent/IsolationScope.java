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
package io.agentscope.harness.agent;

import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.util.List;

/**
 * Controls how agent state is isolated and shared across calls.
 *
 * <p>This enum is the canonical isolation-scope definition used by both the sandbox filesystem
 * backend ({@link io.agentscope.harness.agent.sandbox.SandboxContext}) and the remote filesystem
 * backend ({@link RemoteFilesystemSpec}).
 *
 * <p><b>Sandbox semantics</b>: the scope determines which key is used when persisting and loading
 * {@code _sandbox.json} state. Calls that resolve to the <em>same</em> scope key will
 * sequentially reuse the same sandbox (each call resumes the persisted state from the previous
 * one).
 *
 * <p><b>Store namespace semantics</b>: the scope determines the namespace prefix used by
 * {@link RemoteFilesystem} when routing files to the shared
 * key-value store. Different scopes produce different namespace prefixes, controlling which calls
 * share the same view of stored files.
 *
 * <p>Scope selection:
 * <ul>
 *   <li>{@link #USER} – shared across all sessions of the same user; the default. When
 *       {@code userId} is absent, falls back to {@link #SESSION}.</li>
 *   <li>{@link #SESSION} – isolated per session.</li>
 *   <li>{@link #AGENT} – shared across all users and sessions of the same agent.</li>
 *   <li>{@link #GLOBAL} – globally shared within the same workspace/store instance.</li>
 * </ul>
 *
 * <p><b>Concurrency note:</b> for sandbox mode this is sequential-reuse sharing, not
 * live-instance sharing. Concurrent calls at the same scope each get their own running container;
 * they converge on the last persisted snapshot at the end of the call.
 */
/**
 * 管控智能体状态在多次调用间的隔离与共享规则。
 *
 * <p>该枚举是标准的作用域隔离定义，同时适用于沙箱文件系统后端（{@link io.agentscope.harness.agent.sandbox.SandboxContext}）与远程文件系统后端（{@link RemoteFilesystemSpec}）。
 *
 * <p><b>沙箱语义</b>：作用域决定持久化、读取`_sandbox.json`状态时使用的键。解析到**同一作用域键**的调用会串行复用同一个沙箱，每次调用都会从上一次持久化的状态继续执行。
 *
 * <p><b>存储命名空间语义</b>：作用域决定{@link RemoteFilesystem}向共享键值存储分发文件时所用的命名空间前缀。不同作用域对应不同前缀，以此控制不同调用对存储文件的共享视图。
 *
 * <p>作用域选项说明：
 * <ul>
 *   <li>{@link #USER} — 同一用户下所有会话共享，为默认值；未传入用户ID时自动降级为{@link #SESSION}。</li>
 *   <li>{@link #SESSION} — 单会话隔离。</li>
 *   <li>{@link #AGENT} — 同一智能体下所有用户、所有会话共享。</li>
 *   <li>{@link #GLOBAL} — 同一工作区/存储实例内全局共享。</li>
 * </ul>
 *
 * <p><b>并发注意事项</b>：沙箱模式下仅为串行复用共享，并非运行实例实时共享。同一作用域的并发调用会各自独立启动容器执行，调用结束后统一收敛至最新持久化快照。
 */
public enum IsolationScope {

    /**
     * Isolate by session identifier.
     *
     * <p>Each distinct session gets its own sandbox state / store namespace. If no session key
     * is present in the {@link io.agentscope.core.agent.RuntimeContext}, state lookup is
     * skipped and a fresh sandbox is created (or a default store namespace is used).
     */
    /**
     * 依据会话标识符进行隔离。
     *
     * <p>每个独立会话拥有专属沙箱状态与存储命名空间。若{@link io.agentscope.core.agent.RuntimeContext}中不存在会话标识，将不再读取历史状态，新建空白沙箱（或使用默认存储命名空间）。
     */
    SESSION,

    /**
     * Share across all sessions belonging to the same
     * {@link io.agentscope.core.agent.RuntimeContext#getUserId() userId}.
     *
     * <p>This is the default scope. If {@code userId} is absent, resolution falls back to
     * {@link #SESSION} using the session identifier instead.
     */
    /**
     * 同一用户ID（{@link io.agentscope.core.agent.RuntimeContext#getUserId()}）下的全部会话共用状态。
     *
     * <p>该作用域为默认配置。若未携带用户ID，则降级为SESSION模式，改用会话标识划分隔离范围。
     */
    USER,

    /**
     * Share across all users and sessions of the same agent (identified by agent name).
     *
     * <p>The agent name is fixed at build time and is always available; this scope never
     * degrades due to a missing context field.
     */
    /**
     * 同一智能体（以智能体名称标识）下所有用户与会话共享状态。
     *
     * <p>智能体名称在构建阶段就已确定且始终有效，该作用域不会因上下文字段缺失而降级。
     */
    AGENT,

    /**
     * One shared state / namespace globally within the same workspace store instance.
     *
     * <p>Use with care: all agents and users that share the same store will compete to write
     * the global slot.
     */
    /**
     * 在同一个工作区存储实例内全局共用一套状态与命名空间。
     *
     * <p>谨慎使用：共用该存储的全部智能体与用户会抢占全局写入权限。
     */
    GLOBAL;

    /**
     * Creates a {@link NamespaceFactory} that derives the filesystem namespace prefix from the
     * {@link io.agentscope.core.agent.RuntimeContext} according to this scope.
     *
     * <ul>
     *   <li>{@link #USER} — prefix with {@code userId}; falls back to {@code sessionId} when
     *       {@code userId} is absent.
     *   <li>{@link #SESSION} — prefix with {@code sessionId}.
     *   <li>{@link #AGENT} / {@link #GLOBAL} — no prefix (workspace is already per-agent).
     * </ul>
     *
     * @return a namespace factory consistent with this scope
     */
    /**
     * 创建{@link NamespaceFactory}实例，按照当前作用域规则，从运行上下文{@link io.agentscope.core.agent.RuntimeContext}解析生成文件系统命名空间前缀。
     *
     * <ul>
     *   <li>{@link #USER} — 前缀使用用户ID；无用户ID时改用会话ID作为前缀。
     *   <li>{@link #SESSION} — 前缀使用会话ID。
     *   <li>{@link #AGENT}、{@link #GLOBAL} — 不额外添加前缀（工作区本身已按智能体隔离）。
     * </ul>
     *
     * @return 适配当前作用域的命名空间工厂
     */
    public NamespaceFactory toNamespaceFactory() {
        return switch (this) {
            case USER ->
                    rc -> {
                        if (rc == null) {
                            return List.of();
                        }
                        String uid = rc.getUserId();
                        if (uid != null && !uid.isBlank()) {
                            return List.of(uid);
                        }
                        String sid = rc.getSessionId();
                        return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
                    };
            case SESSION ->
                    rc -> {
                        if (rc == null) {
                            return List.of();
                        }
                        String sid = rc.getSessionId();
                        return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
                    };
            case AGENT, GLOBAL -> rc -> List.of();
        };
    }
}
