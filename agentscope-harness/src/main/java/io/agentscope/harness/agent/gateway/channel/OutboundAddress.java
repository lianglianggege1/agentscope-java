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
package io.agentscope.harness.agent.gateway.channel;

import java.util.Objects;

/**
 * Delivery target for outbound (proactive) messages. Constructed by {@link ChannelRouter} during
 * inbound routing and stored by the gateway as the session's "last route" so that proactive replies
 * (e.g. subagent completion announces) can be delivered back to the correct channel and peer.
 *
 * @param channelId the channel adapter to deliver through (e.g. {@code "chatui"}, {@code "slack"})
 * @param accountId optional multi-account identifier (nullable for single-account channels)
 * @param to delivery address in {@code "channel:peerId"} format (e.g. {@code "telegram:12345"})
 * @param threadId optional thread context for threaded replies (nullable)
 */
/**
 * 出站（主动）消息的投递目标。由 {@link ChannelRouter} 在入站路由时构造，
 * 并由网关存为该会话的"最近路由"，使主动回复（例如子智能体完成公告）
 * 能投递回正确的通道与对端。
 *
 * @param channelId 用于投递的通道适配器（例如 {@code "chatui"}、{@code "slack"}）
 * @param accountId 可选的多账号标识（单账号通道可为 null）
 * @param to {@code "channel:peerId"} 格式的投递地址（例如 {@code "telegram:12345"}）
 * @param threadId 可选的话题上下文，用于话题内回复（可为 null）
 */
public record OutboundAddress(String channelId, String accountId, String to, String threadId) {

    public OutboundAddress {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(to, "to");
    }

    /** Creates an address for a direct (non-threaded) message. */
    /** 创建非话题的直接消息地址。 */
    public static OutboundAddress direct(String channelId, String to) {
        return new OutboundAddress(channelId, null, to, null);
    }

    /** Creates an address with account context. */
    /** 创建带账号上下文的地址。 */
    public static OutboundAddress withAccount(String channelId, String accountId, String to) {
        return new OutboundAddress(channelId, accountId, to, null);
    }
}
