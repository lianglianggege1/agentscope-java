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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Gateway-style entrypoint: route inbound turns by {@link MsgContext}, and bind the primary {@link
 * HarnessAgent} for announce delivery.
 *
 * <p>Implementations should serialize turns per logical session (fair wait) so injected announce
 * runs and user/channel {@link #run} calls do not race the same {@code HarnessAgent} turn.
 *
 * <p>Callers may supply a {@link RuntimeContext} that is merged into the agent turn. Gateway-owned
 * identity fields ({@code sessionId}, {@code userId}, {@code outboundAddress}, …) always win on
 * conflict.
 */
/**
 * 网关式入口：按 {@link MsgContext} 路由入站回合，并绑定主 {@link HarnessAgent}
 * 用于公告（announce）消息投递。
 *
 * <p>实现类应按逻辑会话对回合进行串行化（公平等待），以保证注入的公告回合
 * 与用户/通道的 {@link #run} 调用不会在同一 {@code HarnessAgent} 回合上发生竞争。
 */
public interface Gateway {

    /**
     * Binds the primary harness agent after construction. The agent is registered under its
     * {@link HarnessAgent#getAgentId()} for routing by {@link #run}.
     */
    /**
     * 在构造完成后绑定主 harness 智能体。该智能体以其
     * {@link HarnessAgent#getAgentId()} 注册，供 {@link #run} 路由使用。
     */
    void bindMainAgent(HarnessAgent agent);

    /**
     * Registers a named harness agent for routing. {@link #run} will resolve the target agent by
     * the {@code agentId} supplied in {@link MsgContext#extra()} (key {@code "agentId"}), falling
     * back to the agent registered via {@link #bindMainAgent}.
     */
    /**
     * 注册一个具名的 harness 智能体参与路由。{@link #run} 会按
     * {@link MsgContext#extra()} 中提供的 {@code agentId}（键为 {@code "agentId"}）
     * 解析目标智能体，找不到时回退到通过 {@link #bindMainAgent} 注册的主智能体。
     */
    default void registerAgent(String agentId, HarnessAgent agent) {}

    /** Inbound turn (direct API or channel adapter). */
    /** 入站回合（直接 API 调用或通道适配器）。 */
    Mono<Msg> run(MsgContext context, List<Msg> messages);

    /**
     * Inbound turn with outbound address. The {@code outboundAddress} is recorded as the session's
     * "last route" so that proactive outbound messages (e.g. subagent announces) can be delivered
     * back to the originating channel/peer.
     *
     * @param context routing context for session key resolution
     * @param messages the inbound messages
     * @param outboundAddress the delivery target for proactive replies; may be null
     */
    /**
     * 带出站地址的入站回合。{@code outboundAddress} 会被记录为该会话的
     * "最近路由"，使主动出站消息（例如子智能体公告）能够投递回发起请求的通道/对端。
     *
     * @param context 用于解析会话键的路由上下文
     * @param messages 入站消息
     * @param outboundAddress 主动回复的投递目标；可为 null
     */
    default Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return run(context, messages, outboundAddress, null);
    }

    /**
     * Inbound turn with outbound address and an optional caller {@link RuntimeContext}.
     *
     * <p>The caller context is merged into the turn; gateway identity fields take precedence. The
     * default implementation ignores {@code callerContext} and delegates to {@link
     * #run(MsgContext, List)}.
     *
     * @param context routing context for session key resolution
     * @param messages the inbound messages
     * @param outboundAddress the delivery target for proactive replies; may be null
     * @param callerContext optional application context to merge; may be null
     */
    default Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext) {
        return run(context, messages, outboundAddress, callerContext, null);
    }

    /**
     * Inbound turn with full Channel metadata for {@link
     * io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextResolver}.
     *
     * @param inboundMessage optional inbound envelope passed to the resolver; may be null
     */
    default Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage) {
        return run(context, messages);
    }

    /** Single-message convenience. */
    /** 单条消息便捷入口。 */
    default Mono<Msg> run(MsgContext context, Msg message) {
        return run(context, List.of(message));
    }

    /** Streaming variant of {@link #run(MsgContext, List)}. Returns fine-grained events. */
    /** {@link #run(MsgContext, List)} 的流式变体，返回细粒度事件。 */
    default Flux<AgentEvent> runStream(MsgContext context, List<Msg> messages) {
        return runStream(context, messages, null, null);
    }

    /** Streaming variant of {@link #run(MsgContext, List, OutboundAddress)}. */
    /** {@link #run(MsgContext, List, OutboundAddress)} 的流式变体。 */
    default Flux<AgentEvent> runStream(
            MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return runStream(context, messages, outboundAddress, null);
    }

    /**
     * Streaming variant of {@link #run(MsgContext, List, OutboundAddress, RuntimeContext)}.
     *
     * <p>The default signals unsupported streaming.
     */
    default Flux<AgentEvent> runStream(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext) {
        return runStream(context, messages, outboundAddress, callerContext, null);
    }

    /**
     * Streaming variant of {@link #run(MsgContext, List, OutboundAddress, RuntimeContext,
     * InboundMessage)}.
     */
    default Flux<AgentEvent> runStream(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage) {
        return Flux.error(new UnsupportedOperationException("Streaming not supported"));
    }

    /**
     * Routes messages directly to an exposed subagent session identified by {@code subagentId},
     * bypassing normal binding-based routing. Returns the subagent's reply.
     *
     * @param subagentId the handle returned by
     *     {@link SubagentGatewayBridge.ExposeResult#subagentId()}
     * @param messages the messages to send to the subagent
     */
    /**
     * 将消息直接路由到由 {@code subagentId} 标识的已暴露子智能体会话，
     * 绕过常规的基于绑定的路由。返回子智能体的回复。
     *
     * @param subagentId 由 {@link SubagentGatewayBridge.ExposeResult#subagentId()}
     *     返回的句柄
     * @param messages 要发送给子智能体的消息
     */
    default Mono<Msg> runSubagent(String subagentId, List<Msg> messages) {
        return Mono.error(new UnsupportedOperationException("Subagent routing not supported"));
    }

    /** Streaming variant of {@link #runSubagent(String, List)}. */
    /** {@link #runSubagent(String, List)} 的流式变体。 */
    default Flux<AgentEvent> runSubagentStream(String subagentId, List<Msg> messages) {
        return Flux.error(new UnsupportedOperationException("Subagent streaming not supported"));
    }
}
