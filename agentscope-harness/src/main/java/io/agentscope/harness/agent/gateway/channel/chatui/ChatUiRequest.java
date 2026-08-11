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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Objects;

/**
 * Request object for {@link ChatUiChannel}. Carries the peer identity (optional) and the messages
 * to send to the agent.
 *
 * <p>The {@code peerId} is used by the channel router to build a stable session key:
 *
 * <ul>
 *   <li>If {@code peerId} is null and the channel's DmScope is MAIN, all requests share one
 *       session — convenient for single-user or testing scenarios.
 *   <li>If {@code peerId} is provided, each distinct peer gets its own session (when using
 *       PER_PEER or similar scopes).
 * </ul>
 *
 * @param peerId optional user / peer identifier; null means "no peer" (single-session mode)
 * @param agentId optional explicit agent override; null for normal binding-driven routing
 * @param subagentId optional exposed subagent id for direct subagent routing; null for normal
 *     routing
 * @param messages one or more messages to send
 */
/**
 * {@link ChatUiChannel} 的请求对象。携带对端身份（可选）与要发送给智能体的消息。
 *
 * <p>{@code peerId} 由通道路由器用于构造稳定会话键：
 *
 * <ul>
 *   <li>{@code peerId} 为 null 且通道 DmScope 为 MAIN 时，所有请求共享同一会话——
 *       适合单用户或测试场景。
 *   <li>提供 {@code peerId} 时（在 PER_PEER 等 scope 下），每个不同对端拥有独立会话。
 * </ul>
 *
 * @param peerId 可选的用户/对端标识；null 表示"无对端"（单会话模式）
 * @param agentId 可选的显式智能体覆盖；null 表示走绑定驱动的常规路由
 * @param subagentId 可选的已暴露子智能体 ID，用于直达子智能体路由；null 表示常规路由
 * @param messages 一条或多条待发送的消息
 */
public record ChatUiRequest(String peerId, String agentId, String subagentId, List<Msg> messages) {

    /** 紧凑构造器：校验 messages 非 null 且非空。 */
    public ChatUiRequest {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }

    /** Single user-text message in single-session mode (no peer). */
    /** 单会话模式（无对端）下的单条用户文本消息。 */
    public static ChatUiRequest of(String text) {
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(null, null, null, List.of(userMsg(text)));
    }

    /** Single user-text message associated with a specific peer id. */
    /** 关联特定对端 ID 的单条用户文本消息。 */
    public static ChatUiRequest withPeer(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(peerId, null, null, List.of(userMsg(text)));
    }

    /** Single user-text message targeted at a specific agent. */
    /** 定向到特定智能体的单条用户文本消息。 */
    public static ChatUiRequest forAgent(String peerId, String agentId, String text) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(peerId, agentId, null, List.of(userMsg(text)));
    }

    /** Single user-text message routed directly to an exposed subagent. */
    /** 直接路由到已暴露子智能体的单条用户文本消息。 */
    public static ChatUiRequest toSubagent(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(null, null, subagentId, List.of(userMsg(text)));
    }

    /** Multi-message request without a peer (single-session mode). */
    /** 无对端（单会话模式）的多消息请求。 */
    public static ChatUiRequest of(List<Msg> messages) {
        return new ChatUiRequest(null, null, null, messages);
    }

    /** Multi-message request for a specific peer. */
    /** 特定对端的多消息请求。 */
    public static ChatUiRequest withPeer(String peerId, List<Msg> messages) {
        return new ChatUiRequest(peerId, null, null, messages);
    }

    /** 内部辅助：把纯文本包装为 USER 角色消息。 */
    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
