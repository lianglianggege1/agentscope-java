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

import io.agentscope.harness.agent.gateway.MsgContext;

/**
 * Output of {@link ChannelRouter#resolveRoute}: the resolved agent id, the {@link MsgContext} to
 * pass to {@link io.agentscope.harness.agent.gateway.Gateway#run}, a diagnostic {@code matchedBy}
 * label indicating which binding tier (or fallback) produced the result, and the {@link
 * OutboundAddress} for delivering replies back to the originating channel/peer.
 *
 * @param agentId the resolved target agent id
 * @param context the routing context (channel, session scope keys, agentId extra) ready for
 *     gateway execution
 * @param matchedBy human-readable label of the binding tier that matched (e.g. {@code "peer"},
 *     {@code "guild+roles"}, {@code "default"}); useful for debugging and logging
 * @param outboundAddress delivery target for proactive replies (e.g. subagent announces); derived
 *     from the inbound message's channel and peer metadata
 */
/**
 * {@link ChannelRouter#resolveRoute} 的输出：解析出的智能体 ID、要传给
 * {@link io.agentscope.harness.agent.gateway.Gateway#run} 的 {@link MsgContext}、
 * 指示结果来自哪个绑定层级（或回退）的诊断标签 {@code matchedBy}，
 * 以及用于把回复投递回发起通道/对端的 {@link OutboundAddress}。
 *
 * @param agentId 解析出的目标智能体 ID
 * @param context 可直接用于网关执行的路由上下文（通道、会话作用域键、agentId extra）
 * @param matchedBy 命中的绑定层级的可读标签（例如 {@code "peer"}、
 *     {@code "guild+roles"}、{@code "default"}）；便于调试与日志
 * @param outboundAddress 主动回复（例如子智能体公告）的投递目标；
 *     由入站消息的通道与对端元数据推导
 */
public record RouteResult(
        String agentId, MsgContext context, String matchedBy, OutboundAddress outboundAddress) {}
