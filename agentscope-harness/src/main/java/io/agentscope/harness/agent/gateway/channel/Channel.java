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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.Gateway;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Channel adapter — ingests inbound messages from one messaging platform (or a programmatic
 * caller), routes them through {@link ChannelRouter} to resolve the target agent and session, and
 * delegates execution to {@link Gateway}.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #init(Gateway)} — called by the gateway bootstrap to inject the auto-wired
 *       {@link Gateway} before start-up. Implementations should store the gateway and perform any
 *       pre-connection setup. The default is a no-op for channels that receive the gateway at
 *       construction time.
 *   <li>{@link #start()} — connects to the external event source (webhook endpoint, long-poll,
 *       websocket, etc.) and begins dispatching inbound messages. Programmatic channels may
 *       implement this as a no-op.
 *   <li>{@link #stop()} — disconnects and releases resources.
 * </ol>
 *
 * <h2>Message dispatch</h2>
 *
 * Implementations call {@link Gateway#run} with the {@link
 * io.agentscope.harness.agent.gateway.MsgContext} produced by {@link ChannelRouter#resolveRoute},
 * the resolved {@link OutboundAddress}, and any caller {@link
 * io.agentscope.core.agent.RuntimeContext} carried on {@link InboundMessage#runtimeContext()}.
 * The returned {@link Msg} reply is delivered back to the originating platform by the channel
 * adapter.
 *
 * @see ChannelConfig
 * @see ChannelRouter
 * @see Gateway
 */
/**
 * 通道适配器 —— 从某个消息平台（或编程式调用方）接收入站消息，
 * 经 {@link ChannelRouter} 路由解析出目标智能体与会话，
 * 并把执行委托给 {@link Gateway}。
 *
 * <h2>生命周期</h2>
 *
 * <ol>
 *   <li>{@link #init(Gateway)} —— 由网关引导在启动前调用，注入自动装配的
 *       {@link Gateway}。实现应保存网关引用并完成连接前的准备工作。
 *       对于在构造时就拿到网关的通道，默认实现为空操作。
 *   <li>{@link #start()} —— 连接外部事件源（webhook 端点、长轮询、
 *       websocket 等）并开始分发入站消息。编程式通道可实现为空操作。
 *   <li>{@link #stop()} —— 断开连接并释放资源。
 * </ol>
 *
 * <h2>消息分发</h2>
 *
 * 实现类使用 {@link ChannelRouter#resolveRoute} 产生的 {@link
 * io.agentscope.harness.agent.gateway.MsgContext} 调用 {@link Gateway#run}。
 * 返回的 {@link Msg} 回复由通道适配器投递回发起方平台。
 *
 * @see ChannelConfig
 * @see ChannelRouter
 * @see Gateway
 */
public interface Channel {

    /**
     * Logical identifier for this channel (e.g. {@code "chatui"}, {@code "slack"},
     * {@code "discord"}). Must match {@link ChannelConfig#channelId()}.
     */
    /**
     * 本通道的逻辑标识（例如 {@code "chatui"}、{@code "slack"}、
     * {@code "discord"}）。必须与 {@link ChannelConfig#channelId()} 一致。
     */
    String channelId();

    /** Returns the routing configuration for this channel. */
    /** 返回本通道的路由配置。 */
    ChannelConfig config();

    /**
     * Called by the gateway bootstrap before {@link #start()} to supply the auto-wired
     * {@link Gateway}. Implementations that need a gateway but do not receive it at construction
     * time should override this method. The default is a no-op.
     */
    /**
     * 由网关引导在 {@link #start()} 之前调用，用于提供自动装配的 {@link Gateway}。
     * 需要网关但构造时未获得的实现应重写本方法。默认为空操作。
     */
    default void init(Gateway gateway) {}

    /**
     * Connects to the external event source and starts dispatching inbound messages. Programmatic
     * channels may make this a no-op.
     */
    /** 连接外部事件源并开始分发入站消息。编程式通道可实现为空操作。 */
    default void start() {}

    /**
     * Disconnects from the external event source and releases resources. Programmatic channels may
     * make this a no-op.
     */
    /** 断开外部事件源连接并释放资源。编程式通道可实现为空操作。 */
    default void stop() {}

    /**
     * Dispatches a fully constructed {@link InboundMessage} through routing and returns the agent
     * reply. The channel implementation is responsible for:
     *
     * <ol>
     *   <li>Calling {@link ChannelRouter#resolveRoute} to obtain a {@link RouteResult} (which
     *       includes the {@link OutboundAddress} for reply routing)
     *   <li>Passing the {@link RouteResult#context()}, messages, and {@link
     *       RouteResult#outboundAddress()} to {@link Gateway#run}
     * </ol>
     */
    /**
     * 分发一条完整构建好的 {@link InboundMessage} 并返回智能体回复。
     * 通道实现负责：
     *
     * <ol>
     *   <li>调用 {@link ChannelRouter#resolveRoute} 获得 {@link RouteResult}
     *       （其中包含用于回复路由的 {@link OutboundAddress}）
     *   <li>把 {@link RouteResult#context()}、消息与
     *       {@link RouteResult#outboundAddress()} 传给 {@link Gateway#run}
     * </ol>
     */
    Mono<Msg> dispatch(InboundMessage message);

    /**
     * Streaming variant of {@link #dispatch(InboundMessage)}. Returns fine-grained
     * {@link AgentEvent}s instead of a single reply.
     *
     * <p>The default delegates to {@link Gateway#runStream} with the resolved route.
     */
    /**
     * {@link #dispatch(InboundMessage)} 的流式变体，返回细粒度的
     * {@link AgentEvent} 而不是单一回复。
     *
     * <p>默认实现使用解析出的路由委托给 {@link Gateway#runStream}。
     */
    default Flux<AgentEvent> dispatchStream(InboundMessage message) {
        return Flux.error(new UnsupportedOperationException("Streaming dispatch not supported"));
    }

    /**
     * Delivers proactive outbound messages (e.g. subagent completion announces) to this channel's
     * transport. Called by the gateway when an agent produces a reply that needs to be pushed to
     * the originating channel/peer rather than returned synchronously.
     *
     * <p>The default implementation is a no-op, suitable for pull-based channels that do not
     * support proactive push.
     *
     * @param address the delivery target (peer, thread, account context)
     * @param messages the messages to deliver
     */
    /**
     * 向本通道的传输层投递主动出站消息（例如子智能体完成公告）。
     * 当智能体产生需要推送到发起通道/对端（而非同步返回）的回复时，由网关调用。
     *
     * <p>默认实现为空操作，适用于不支持主动推送的拉取型通道。
     *
     * @param address 投递目标（对端、话题、账号上下文）
     * @param messages 要投递的消息
     */
    default void deliver(OutboundAddress address, List<Msg> messages) {}

    /** Delivers one durable notification and returns the provider message ID, not a local ack.
     * Unsupported transports fail explicitly so callers can keep the notification pending.
     */
    default Mono<String> deliverWithReceipt(
            OutboundAddress address, Msg message, String deliveryId) {
        return Mono.error(new UnsupportedOperationException("Provider receipts are not supported"));
    }

    /**
     * Applies a new routing {@link ChannelConfig} (bindings, dmScope, defaultAgentId) without
     * tearing down the channel's transport. Implementations that hold their {@link #config()} in a
     * mutable / volatile reference can override this to support hot reload of bindings.
     *
     * <p>The default implementation returns {@code false} to signal that this channel does not
     * support live config swap. Returning {@code true} means the swap was applied and subsequent
     * inbound messages will route under the new config.
     *
     * @param newConfig the new routing configuration to install (channelId must match
     *     {@link #channelId()})
     * @return {@code true} if the config was applied live; {@code false} if the channel requires a
     *     restart to pick up the new config
     */
    /**
     * 在不拆除通道传输层的情况下应用新的路由 {@link ChannelConfig}
     * （绑定、dmScope、defaultAgentId）。以可变/易失引用持有 {@link #config()}
     * 的实现可重写本方法以支持绑定规则热加载。
     *
     * <p>默认实现返回 {@code false}，表示本通道不支持配置热替换。
     * 返回 {@code true} 表示替换已生效，后续入站消息将按新配置路由。
     *
     * @param newConfig 要安装的新路由配置（channelId 必须与
     *     {@link #channelId()} 匹配）
     * @return 配置已实时生效返回 {@code true}；需要重启才能应用新配置时返回 {@code false}
     */
    default boolean applyRoutingConfig(ChannelConfig newConfig) {
        return false;
    }
}
