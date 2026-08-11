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
package io.agentscope.harness.agent.gateway;

import java.util.Map;
import java.util.Objects;

/**
 * Routing context for inbound turns (direct API, channel adapter, group/room/thread). Used by
 * the gateway to map stable conversation keys to session ids.
 *
 * <p>The {@link #userId} field carries the message sender's identity for multi-tenant namespace
 * isolation in {@link io.agentscope.harness.agent.HarnessAgent}. It is derived from
 * {@link io.agentscope.harness.agent.gateway.channel.InboundMessage#senderId()} and does
 * <em>not</em> participate in {@link #canonicalKey()} computation — the same user's conversations
 * always map to the same session key regardless of how userId is set.
 *
 * @param channel logical channel name (e.g. slack, discord, web)
 * @param group optional group / team / workspace id
 * @param room optional room / channel id
 * @param threadId optional thread / topic id
 * @param threadTs optional provider-specific thread timestamp or message anchor
 * @param extra additional key/value pairs for adapters
 * @param userId optional authenticated user identity for HarnessAgent namespace isolation
 */
/**
 * 入站回合（直接 API、通道适配器、群组/房间/话题）的路由上下文。
 * 网关用它把稳定的会话键映射到会话 ID。
 *
 * <p>{@link #userId} 字段携带消息发送者身份，用于
 * {@link io.agentscope.harness.agent.HarnessAgent} 的多租户命名空间隔离。
 * 它取自 {@link io.agentscope.harness.agent.gateway.channel.InboundMessage#senderId()}，
 * <em>不参与</em> {@link #canonicalKey()} 的计算——同一用户的会话无论
 * userId 如何设置，始终映射到相同的会话键。
 *
 * @param channel 逻辑通道名（例如 slack、discord、web）
 * @param group 可选的群组/团队/工作空间 ID
 * @param room 可选的房间/频道 ID
 * @param threadId 可选的话题（thread）ID
 * @param threadTs 可选的提供方专属话题时间戳或消息锚点
 * @param extra 提供给适配器的附加键值对
 * @param userId 用于 HarnessAgent 命名空间隔离的可选已认证用户身份
 */
public record MsgContext(
        String channel,
        String group,
        String room,
        String threadId,
        String threadTs,
        Map<String, String> extra,
        String userId) {

    public MsgContext {
        extra = extra != null ? Map.copyOf(extra) : Map.of();
    }

    /**
     * Convenience constructor without {@code userId} (backwards-compatible for callsites that do
     * not carry user identity).
     */
    /** 不带 {@code userId} 的便捷构造器（兼容不携带用户身份的调用点）。 */
    public MsgContext(
            String channel,
            String group,
            String room,
            String threadId,
            String threadTs,
            Map<String, String> extra) {
        this(channel, group, room, threadId, threadTs, extra, null);
    }

    /** Default single-conversation context (no channel metadata, no userId). */
    /** 默认的单会话上下文（无通道元数据、无 userId）。 */
    public static MsgContext defaultContext() {
        return new MsgContext("default", null, null, null, null, Map.of(), null);
    }

    /** Returns a copy of this context with the given {@code userId} set. */
    /** 返回设置了指定 {@code userId} 的本上下文副本。 */
    public MsgContext withUserId(String userId) {
        return new MsgContext(channel, group, room, threadId, threadTs, extra, userId);
    }

    /** Stable key for session routing: same logical conversation maps to the same gateway session id. */
    /** 会话路由的稳定键：同一逻辑会话始终映射到相同的网关会话 ID。 */
    public String canonicalKey() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Objects.requireNonNullElse(channel, "default"));
        if (group != null && !group.isBlank()) {
            sb.append("|g:").append(group.trim());
        }
        if (room != null && !room.isBlank()) {
            sb.append("|r:").append(room.trim());
        }
        if (threadId != null && !threadId.isBlank()) {
            sb.append("|t:").append(threadId.trim());
        }
        if (threadTs != null && !threadTs.isBlank()) {
            sb.append("|ts:").append(threadTs.trim());
        }
        if (!extra.isEmpty()) {
            extra.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    sb.append("|x:")
                                            .append(e.getKey())
                                            .append('=')
                                            .append(e.getValue()));
        }
        return sb.toString();
    }
}
