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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Objects;

/**
 * Routing identity for {@link ChatUiChannel} requests. Replaces the need to understand
 * {@link io.agentscope.harness.agent.gateway.channel.DmScope} by letting the caller express
 * routing intent through simple business identifiers.
 *
 * <ul>
 *   <li>{@code userId} — identifies the user. Maps to
 *       {@link io.agentscope.harness.agent.gateway.MsgContext#userId()} for HarnessAgent
 *       namespace isolation, and is used as the default session key when {@code sessionId} is null.
 *   <li>{@code sessionId} — optional explicit conversation identifier. When provided, different
 *       sessions for the same user are kept separate. When null, one session per user is the
 *       default.
 *   <li>{@code agentId} — optional target agent override for multi-agent setups. When null, the
 *       channel's default agent is used.
 *   <li>{@code runtimeContext} — optional per-call {@link RuntimeContext} merged into the agent
 *       turn (attributes, typed values, force-sync flags, …). Gateway-owned identity fields still
 *       win on conflict.
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // One session per user (most common)
 * chat.send(SendOptions.userId("user-1"), "hello");
 *
 * // Same user, different sessions
 * chat.send(SendOptions.of("user-1", "session-a"), "hello");
 * chat.send(SendOptions.of("user-1", "session-b"), "hello");
 *
 * // Target a specific agent in multi-agent setups
 * chat.send(SendOptions.userId("user-1").withAgentId("support"), "help me");
 *
 * // Pass application context into the agent turn
 * chat.send(
 *     SendOptions.userId("user-1")
 *         .withAttribute("tenant", "acme")
 *         .withRuntimeContext(
 *             RuntimeContext.builder()
 *                 .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
 *                 .build()),
 *     "hello");
 * }</pre>
 *
 * @param userId the user identity (required)
 * @param sessionId optional conversation identifier; null means one session per user
 * @param agentId optional target agent override; null for default routing
 * @param runtimeContext optional caller-supplied runtime context; null when none
 */
/**
 * {@link ChatUiChannel} 请求的路由身份。让调用方通过简单的业务标识表达路由意图，
 * 从而无需理解 {@link io.agentscope.harness.agent.gateway.channel.DmScope}。
 *
 * <ul>
 *   <li>{@code userId} —— 标识用户。映射到
 *       {@link io.agentscope.harness.agent.gateway.MsgContext#userId()}
 *       用于 HarnessAgent 命名空间隔离；当 {@code sessionId} 为 null 时，
 *       同时作为默认会话键。
 *   <li>{@code sessionId} —— 可选的显式会话标识。提供时同一用户的不同会话
 *       相互隔离；为 null 时默认每个用户一个会话。
 *   <li>{@code agentId} —— 多智能体场景下可选的目标智能体覆盖。
 *       为 null 时使用通道的默认智能体。
 * </ul>
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // 每用户一个会话（最常见）
 * chat.send(SendOptions.userId("user-1"), "hello");
 *
 * // 同一用户、不同会话
 * chat.send(SendOptions.of("user-1", "session-a"), "hello");
 * chat.send(SendOptions.of("user-1", "session-b"), "hello");
 *
 * // 多智能体场景下指定目标智能体
 * chat.send(SendOptions.userId("user-1").withAgentId("support"), "help me");
 * }</pre>
 *
 * @param userId 用户身份（必填）
 * @param sessionId 可选的会话标识；null 表示每用户一个会话
 * @param agentId 可选的目标智能体覆盖；null 表示默认路由
 */
public record SendOptions(
        String userId, String sessionId, String agentId, RuntimeContext runtimeContext) {

    /** 紧凑构造器：校验 userId 非 null。 */
    public SendOptions {
        Objects.requireNonNull(userId, "userId");
    }

    /** One session per user — the most common case. */
    /** 每用户一个会话——最常见的场景。 */
    public static SendOptions userId(String userId) {
        return new SendOptions(userId, null, null, null);
    }

    /** Explicit user + session — multiple conversations for the same user. */
    /** 显式用户 + 会话——同一用户的多个会话。 */
    public static SendOptions of(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new SendOptions(userId, sessionId, null, null);
    }

    /** Returns a copy with the given agent id override. */
    /** 返回应用了指定 agentId 覆盖的副本。 */
    public SendOptions withAgentId(String agentId) {
        return new SendOptions(userId, sessionId, agentId, runtimeContext);
    }

    /**
     * Returns a copy that carries the given {@link RuntimeContext} into the agent turn. Replaces
     * any previously attached context.
     */
    public SendOptions withRuntimeContext(RuntimeContext runtimeContext) {
        return new SendOptions(userId, sessionId, agentId, runtimeContext);
    }

    /**
     * Returns a copy with a string attribute merged into the carried {@link RuntimeContext}.
     * Creates a new context from empty when none is present yet.
     */
    public SendOptions withAttribute(String key, Object value) {
        RuntimeContext next = RuntimeContext.builder(runtimeContext).put(key, value).build();
        return new SendOptions(userId, sessionId, agentId, next);
    }

    /**
     * Returns a copy with a typed attribute merged into the carried {@link RuntimeContext}.
     * Creates a new context from empty when none is present yet.
     */
    public <T> SendOptions withAttribute(Class<T> type, T value) {
        RuntimeContext next = RuntimeContext.builder(runtimeContext).put(type, value).build();
        return new SendOptions(userId, sessionId, agentId, next);
    }

    /** The effective session key: sessionId if provided, otherwise userId. */
    /** 生效的会话键：提供 sessionId 时优先使用，否则使用 userId。 */
    String effectiveSessionKey() {
        return sessionId != null ? sessionId : userId;
    }
}
