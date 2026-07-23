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

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;

/**
 * Bridge between {@link io.agentscope.harness.agent.tool.AgentSpawnTool} and the gateway layer.
 * Allows spawned subagents to be exposed as user-addressable entry points without the spawn tool
 * needing to know about channels, routers, or the gateway implementation.
 *
 * <p>When a subagent is exposed, the gateway assigns it a {@code subagentId} that the user can
 * use to send messages directly to that subagent, bypassing normal binding-based routing.
 */
/**
 * {@link io.agentscope.harness.agent.tool.AgentSpawnTool} 与网关层之间的桥接器。
 * 支持将创建出来的子智能体对外暴露为用户可直接访问的入口，
 * 同时无需让创建工具感知通道、路由或网关具体实现。
 *
 * <p>子智能体开启暴露后，网关会分配一个 {@code subagentId}。用户可凭借该标识
 * 直接向此子智能体发送消息，绕过常规的绑定式路由逻辑。
 */
@FunctionalInterface
public interface SubagentGatewayBridge {

    /**
     * Result of exposing a subagent to the user.
     *
     * @param subagentId the user-visible handle for addressing this subagent directly
     */
    /**
     * 将子智能体对外暴露给用户后的返回结果。
     *
     * @param subagentId 用户可见的句柄，用于直接寻址该子智能体
     */
    record ExposeResult(String subagentId) {}

    /**
     * Exposes a spawned subagent as a user-addressable entry point in the gateway.
     *
     * @param agentId the subagent type identifier
     * @param sessionId the session id assigned to the subagent
     * @param agent the agent instance
     * @param replyTo the outbound address for delivering replies back to the user's channel;
     *     may be null if no outbound channel context is available
     * @return the expose result containing the subagentId handle
     */
    /**
     * 将已创建的子智能体在网关中对外暴露为可供用户寻址的接入点。
     *
     * @param agentId 子智能体类型标识
     * @param sessionId 分配给该子智能体的会话ID
     * @param agent 智能体实例
     * @param replyTo 用于将回复推送回用户通道的出站地址；
     *     若无出站通道上下文可为 null
     * @return 包含 subagentId 句柄的暴露结果
     */
    ExposeResult expose(String agentId, String sessionId, Agent agent, OutboundAddress replyTo);
}
