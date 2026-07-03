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
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

/**
 * 用于控制智能体状态在多次调用时的隔离与共享机制。
 *
 * <p>该枚举是标准的隔离作用域定义，沙箱文件存储后端{@link io.agentscope.harness.agent.sandbox.SandboxContext}
 * 与远程文件存储配置{@link RemoteFilesystemSpec}均采用这套定义。
 *
 * <p><b>沙箱运行规则</b>：作用域会决定持久化、加载`_sandbox.json`状态文件所使用的唯一标识键。
 * 若多次调用解析出的作用域标识键完全相同，则会串行复用同一个沙箱；每次调用都会基于上一次持久保存的状态继续运行。
 *
 * <p><b>存储命名空间规则</b>：作用域为远程文件存储{@link RemoteFilesystem}提供命名空间前缀，
 * 用来将文件路由至共享键值存储；不同作用域会生成不同前缀，以此控制哪些调用能够读取同一套存储文件。
 *
 * <p>作用域可选类型：
 * <ul>
 *   <li>{@link #SESSION} — 按会话隔离状态，默认选项</li>
 *   <li>{@link #USER} — 同一用户的全部会话共享状态</li>
 *   <li>{@link #AGENT} — 同一个智能体下，所有用户、所有会话共享状态</li>
 *   <li>{@link #GLOBAL} — 在同一个工作目录/存储实例内全局共享状态</li>
 * </ul>
 *
 * <p><b>并发说明</b>：沙箱模式下仅支持串行复用快照，并非实时共享运行容器。
 * 同一作用域下的并发调用会各自启动独立容器；仅在调用结束后，才会统一合并至最新持久化快照。
 */
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
 *   <li>{@link #SESSION} – isolated per session; the default.</li>
 *   <li>{@link #USER} – shared across all sessions of the same user.</li>
 *   <li>{@link #AGENT} – shared across all users and sessions of the same agent.</li>
 *   <li>{@link #GLOBAL} – globally shared within the same workspace/store instance.</li>
 * </ul>
 *
 * <p><b>Concurrency note:</b> for sandbox mode this is sequential-reuse sharing, not
 * live-instance sharing. Concurrent calls at the same scope each get their own running container;
 * they converge on the last persisted snapshot at the end of the call.
 */
public enum IsolationScope {

    /**
     * Isolate by session identifier.
     *
     * <p>This is the default behavior. Each distinct session gets its own sandbox state /
     * store namespace.  If no session key is present in the
     * {@link io.agentscope.core.agent.RuntimeContext}, state lookup is skipped and a fresh
     * sandbox is created (or a default store namespace is used).
     */
    /**
     * 按会话ID隔离状态。
     *
     * <p>该模式为默认行为。每个独立会话拥有专属沙箱状态与存储命名空间。
     * 若运行上下文 {@link io.agentscope.core.agent.RuntimeContext} 中不存在会话标识，
     * 系统不会读取历史状态，会全新创建沙箱（或使用默认存储命名空间）。
     */
    SESSION,

    /**
     * Share across all sessions belonging to the same
     * {@link io.agentscope.core.agent.RuntimeContext#getUserId() userId}.
     *
     * <p>If {@code userId} is blank, a warning is logged and state lookup / namespace resolution
     * degrades to the default (fresh sandbox create, or an anonymous-user namespace).
     */
    /**
     * 同一用户ID下的所有会话共享状态，用户ID取自
     * {@link io.agentscope.core.agent.RuntimeContext#getUserId()}。
     *
     * <p>若用户ID为空，系统会打印警告日志，状态读取与命名空间解析逻辑降级为默认行为：
     * 新建空白沙箱，或使用匿名用户专属命名空间。
     */
    USER,

    /**
     * Share across all users and sessions of the same agent (identified by agent name).
     *
     * <p>The agent name is fixed at build time and is always available; this scope never
     * degrades due to a missing context field.
     */
    /**
     * 同一智能体（以智能体名称作为唯一标识）下的所有用户、所有会话共享状态。
     *
     * <p>智能体名称在构建实例时固定指定，始终可获取；该作用域不会因上下文缺失字段而降级处理。
     */
    AGENT,

    /**
     * One shared state / namespace globally within the same workspace store instance.
     *
     * <p>Use with care: all agents and users that share the same store will compete to write
     * the global slot.
     */
    /**
     * 在同一个工作目录存储实例内全局共用一套状态与命名空间。
     *
     * <p>谨慎使用：所有共用该存储的智能体与用户会争抢写入全局存储位。
     */
    GLOBAL
}
