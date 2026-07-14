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
package io.agentscope.harness.agent.subagent;

/**
 * Controls how a declared subagent's runtime workspace root is determined.
 *
 * <p>The five-row decision table:
 *
 * <pre>
 * workspacePath  mode      runtime-workspace-root
 * ─────────────────────────────────────────────────────────────────────────────
 * set            ISOLATED  workspacePath  (definition dir is also the runtime root)
 * set            SHARED    mainWorkspace  (definition skills/knowledge ignored)
 * null           ISOLATED  mainWorkspace/agents/&lt;name&gt;/workspace/  (auto-created)
 * null           SHARED    mainWorkspace
 * (general-purpose, always SHARED)       mainWorkspace  (fully mirrors main agent)
 * </pre>
 *
 * <p><b>Phase B-0 — persisted session bucketing:</b> ISOLATED-mode subagents bucket their
 * persisted state by parent ({@code userId},
 * {@code parentSessionId}) when the spawn-time {@link io.agentscope.core.agent.RuntimeContext}
 * carries those fields. The composed key has form
 * {@code {declarationName}[@{parentSessionId}][#{userId}]} and is applied uniformly across
 * {@link io.agentscope.core.state.AgentStateStore} stores (Workspace, Redis, InMemory, custom),
 * because all of them already partition state by {@code (userId, sessionId)}. SHARED-mode is unchanged
 * — those subagents intentionally use the parent's bucket and do not multi-tenant.
 */
/**
 * 控制已定义子智能体运行时工作区根目录的判定规则。
 *
 * <p>五分支决策对照表：
 *
 * <pre>
 * 配置workspacePath  隔离模式  运行时工作区根目录
 * ─────────────────────────────────────────────────────────────────────────────
 * 已配置             ISOLATED  workspacePath（智能体定义目录同时作为运行根目录）
 * 已配置             SHARED   主工作区（定义内的技能、知识库会被忽略）
 * 未配置(null)      ISOLATED  mainWorkspace/agents/&lt;name&gt;/workspace/（自动创建目录）
 * 未配置(null)      SHARED   主工作区
 * 通用场景（固定SHARED）      主工作区（与主智能体共用完整文件资源）
 * </pre>
 */
public enum WorkspaceMode {

    /**
     * The subagent gets its own isolated workspace.
     *
     * <ul>
     *   <li>If {@link SubagentDeclaration#getWorkspacePath()} is set, that path is the runtime
     *       root and also the source for the sysPrompt ({@code AGENTS.md}).
     *   <li>Otherwise the runtime root is auto-created at
     *       {@code mainWorkspace/agents/&lt;name&gt;/workspace/} and the inline body is used as
     *       sysPrompt.
     * </ul>
     */
    /**
     * 子智能体拥有独立隔离的工作区。
     *
     * <ul>
     *   <li>若配置了 {@link SubagentDeclaration#getWorkspacePath()}，该路径即为运行根目录，
     *       同时系统提示词（AGENTS.md）也从该目录读取。
     *   <li>若未配置，则自动创建运行根目录：
     *       {@code mainWorkspace/agents/&lt;name&gt;/workspace/}，
     *       并以子智能体定义内联文本作为系统提示词。
     * </ul>
     */
    ISOLATED,

    /**
     * The subagent shares the main agent's workspace.
     *
     * <ul>
     *   <li>The runtime root is always {@code mainWorkspace}, regardless of
     *       {@link SubagentDeclaration#getWorkspacePath()}.
     *   <li>If {@code workspacePath} is set, its {@code AGENTS.md} is used as the sysPrompt body;
     *       but the definition's {@code skills/}, {@code knowledge/}, and {@code MEMORY.md} are
     *       ignored.
     *   <li>If {@code workspacePath} is absent, the inline body is used as sysPrompt.
     * </ul>
     */
    /**
     * 子智能体与主智能体共用一套工作区。
     *
     * <ul>
     *   <li>无论是否配置 {@link SubagentDeclaration#getWorkspacePath()}，运行根目录固定为主工作区 mainWorkspace。
     *   <li>若配置了 workspacePath：会读取该路径下 AGENTS.md 作为系统提示词；
     *       但该目录下的 skills/、knowledge/、MEMORY.md 资源均不会生效。
     *   <li>若未配置 workspacePath：直接使用子智能体定义内联文本作为系统提示词。
     * </ul>
     */
    SHARED
}
