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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default {@link Channel} implementation for direct Chat UI interactions: no external transport,
 * no webhook, no websocket — the caller submits a {@link ChatUiRequest} programmatically and
 * receives the agent reply reactively.
 *
 * <p>Suitable for:
 * <ul>
 *   <li>Embedded web chat UIs that send HTTP requests to the harness
 *   <li>CLI tools and integration tests that need a typed API to the agent
 *   <li>Single-agent single-session scenarios where the default {@link DmScope#MAIN} collapses all
 *       conversations into one session
 * </ul>
 *
 * <h2>Obtaining an instance</h2>
 *
 * The recommended way is through {@link
 * io.agentscope.harness.agent.gateway.GatewayBootstrap#chatUiChannel()}:
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder().model(model)
 *     .agent("main", b -> b.name("assistant").sysPrompt("..."))
 *     .build();
 * ChatUiChannel chat = gw.chatUiChannel();
 * chat.send("Hello!").block();
 * }</pre>
 */
/**
 * 面向直接 Chat UI 交互的默认 {@link Channel} 实现：无外部传输层、无 webhook、
 * 无 websocket——调用方以编程方式提交 {@link ChatUiRequest}，
 * 并以响应式方式接收智能体回复。
 *
 * <p>适用场景：
 * <ul>
 *   <li>向 harness 发送 HTTP 请求的嵌入式 Web 聊天 UI
 *   <li>需要类型化 API 访问智能体的 CLI 工具与集成测试
 *   <li>默认 {@link DmScope#MAIN} 把所有会话收敛为单一会话的
 *       单智能体单会话场景
 * </ul>
 *
 * <h2>获取实例</h2>
 *
 * 推荐通过 {@link io.agentscope.harness.agent.gateway.GatewayBootstrap#chatUiChannel()}：
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder().model(model)
 *     .agent("main", b -> b.name("assistant").sysPrompt("..."))
 *     .build();
 * ChatUiChannel chat = gw.chatUiChannel();
 * chat.send("Hello!").block();
 * }</pre>
 */
public final class ChatUiChannel implements Channel {

    public static final String CHANNEL_ID = "chatui";

    private volatile Gateway gateway;
    private volatile ChannelConfig config;
    private final ChannelRouter router;

    /** 主动出站消息缓冲区（deliver 入队、pollOutbound 出队）。 */
    private final ConcurrentLinkedQueue<OutboundEnvelope> outboundQueue =
            new ConcurrentLinkedQueue<>();

    private ChatUiChannel(Gateway gateway, ChannelConfig config) {
        this.gateway = gateway;
        this.config = Objects.requireNonNull(config, "config");
        this.router = new ChannelRouter(null);
    }

    // -----------------------------------------------------------------
    //  Factories
    // -----------------------------------------------------------------

    /** Creates a Chat UI channel with the default {@link DmScope#MAIN} config. */
    /** 创建使用默认 {@link DmScope#MAIN} 配置的 Chat UI 通道。 */
    public static ChatUiChannel create() {
        return new ChatUiChannel(null, ChannelConfig.of(CHANNEL_ID));
    }

    /** Creates a Chat UI channel with an explicit config. */
    /** 创建使用显式配置的 Chat UI 通道。 */
    public static ChatUiChannel create(ChannelConfig config) {
        return new ChatUiChannel(null, config);
    }

    /** Creates a per-peer Chat UI channel. Each distinct peerId gets its own session. */
    /** 创建按对端隔离的 Chat UI 通道。每个不同的 peerId 拥有独立会话。 */
    public static ChatUiChannel perPeer() {
        ChannelConfig cfg = ChannelConfig.builder(CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        return new ChatUiChannel(null, cfg);
    }

    /** Creates a Chat UI channel with a pre-wired gateway. */
    /** 创建已接入网关的 Chat UI 通道。 */
    public static ChatUiChannel create(Gateway gateway) {
        return new ChatUiChannel(
                Objects.requireNonNull(gateway, "gateway"), ChannelConfig.of(CHANNEL_ID));
    }

    /** Creates a Chat UI channel with a pre-wired gateway and explicit config. */
    /** 创建已接入网关且使用显式配置的 Chat UI 通道。 */
    public static ChatUiChannel create(Gateway gateway, ChannelConfig config) {
        return new ChatUiChannel(Objects.requireNonNull(gateway, "gateway"), config);
    }

    // -----------------------------------------------------------------
    //  Channel lifecycle
    // -----------------------------------------------------------------

    /** 注入网关；构造时已提供网关则保留原引用。 */
    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    /** 编程式通道无外部传输层，启动为空操作。 */
    @Override
    public void start() {}

    /** 编程式通道无外部传输层，停止为空操作。 */
    @Override
    public void stop() {}

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    /** 支持配置热替换：channelId 匹配时直接替换 volatile 引用并返回 true。 */
    @Override
    public boolean applyRoutingConfig(ChannelConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig");
        if (!CHANNEL_ID.equals(newConfig.channelId())) {
            return false;
        }
        this.config = newConfig;
        return true;
    }

    /** 分发入站消息：路由解析后委托网关执行，并携带出站地址。 */
    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        RouteResult route = router.resolveRoute(config, message);
        return resolveGateway()
                .run(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message);
    }

    /**
     * Returns the {@link RouteResult} this channel would produce for {@code message} without
     * dispatching it. Useful for pre-computing the session key before sending.
     */
    /**
     * 返回本通道对 {@code message} 将产生的 {@link RouteResult} 但不实际分发。
     * 适合在发送前预计算会话键。
     */
    public RouteResult previewRoute(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        return router.resolveRoute(config, message);
    }

    /** 主动出站消息入队缓冲，由调用方通过 {@link #pollOutbound()} 拉取。 */
    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages != null && !messages.isEmpty()) {
            outboundQueue.add(new OutboundEnvelope(address, messages));
        }
    }

    /**
     * Drains and returns all buffered proactive outbound messages. Returns an empty list if no
     * messages are pending.
     */
    /**
     * 排空并返回所有缓冲的主动出站消息。无待处理消息时返回空列表。
     */
    public List<OutboundEnvelope> pollOutbound() {
        List<OutboundEnvelope> result = new ArrayList<>();
        OutboundEnvelope e;
        while ((e = outboundQueue.poll()) != null) {
            result.add(e);
        }
        return result;
    }

    /** Returns the number of buffered proactive outbound messages. */
    /** 返回缓冲的主动出站消息数量。 */
    public int outboundQueueSize() {
        return outboundQueue.size();
    }

    // -----------------------------------------------------------------
    //  Convenience send APIs
    // -----------------------------------------------------------------

    /** Sends a plain-text message in single-session mode (no peer id). */
    /** 以单会话模式（无对端 ID）发送纯文本消息。 */
    public Mono<Msg> send(String text) {
        return send(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /**
     * Sends a pre-built {@link Msg} (text or multimodal) in single-session mode.
     *
     * <pre>{@code
     * Msg multimodal = Msg.builder()
     *         .role(MsgRole.USER)
     *         .content(
     *                 TextBlock.builder().text("Describe this").build(),
     *                 ImageBlock.builder()
     *                         .source(URLSource.builder().url(imageUrl).build())
     *                         .build())
     *         .build();
     * chat.send(multimodal).block();
     * }</pre>
     */
    public Mono<Msg> send(Msg message) {
        Objects.requireNonNull(message, "message");
        return send(ChatUiRequest.of(List.of(message)));
    }

    /**
     * Sends one or more pre-built {@link Msg}s in single-session mode. Use this for multimodal
     * content or multi-part user turns.
     */
    public Mono<Msg> send(List<Msg> messages) {
        return send(ChatUiRequest.of(requireMessages(messages)));
    }

    /** Sends a plain-text message from a specific peer. */
    /** 以特定对端身份发送纯文本消息。 */
    public Mono<Msg> send(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return send(ChatUiRequest.withPeer(peerId, text));
    }

    /** Sends a pre-built {@link Msg} from a specific peer. */
    public Mono<Msg> send(String peerId, Msg message) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(message, "message");
        return send(ChatUiRequest.withPeer(peerId, List.of(message)));
    }

    /** Sends one or more pre-built {@link Msg}s from a specific peer. */
    public Mono<Msg> send(String peerId, List<Msg> messages) {
        Objects.requireNonNull(peerId, "peerId");
        return send(ChatUiRequest.withPeer(peerId, requireMessages(messages)));
    }

    /**
     * Sends a plain-text message with explicit routing identity. The {@link SendOptions} determines
     * the user identity and session key directly — no {@link DmScope} configuration required.
     *
     * @see SendOptions#userId(String)
     * @see SendOptions#of(String, String)
     */
    /**
     * 以显式路由身份发送纯文本消息。{@link SendOptions} 直接决定用户身份
     * 与会话键——无需配置 {@link DmScope}。
     *
     * @see SendOptions#userId(String)
     * @see SendOptions#of(String, String)
     */
    public Mono<Msg> send(SendOptions options, String text) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).name(options.userId()).textContent(text).build();
        return dispatchWithOptions(options, List.of(msg));
    }

    /**
     * Sends a pre-built {@link Msg} with explicit routing identity. Prefer this overload for
     * multimodal content (images, audio, …) while still using {@link SendOptions} for session
     * routing.
     */
    public Mono<Msg> send(SendOptions options, Msg message) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(message, "message");
        return dispatchWithOptions(options, List.of(message));
    }

    /**
     * Sends one or more pre-built {@link Msg}s with explicit routing identity. Useful for
     * multimodal or multi-part user turns.
     */
    public Mono<Msg> send(SendOptions options, List<Msg> messages) {
        Objects.requireNonNull(options, "options");
        return dispatchWithOptions(options, requireMessages(messages));
    }

    /** Sends a message directly to an exposed subagent, bypassing normal routing. */
    /** 直接向已暴露的子智能体发送消息，绕过常规路由。 */
    public Mono<Msg> sendToSubagent(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return resolveGateway().runSubagent(subagentId, List.of(msg));
    }

    /** Sends a pre-built {@link Msg} directly to an exposed subagent. */
    public Mono<Msg> sendToSubagent(String subagentId, Msg message) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(message, "message");
        return resolveGateway().runSubagent(subagentId, List.of(message));
    }

    /** Sends one or more pre-built {@link Msg}s directly to an exposed subagent. */
    public Mono<Msg> sendToSubagent(String subagentId, List<Msg> messages) {
        Objects.requireNonNull(subagentId, "subagentId");
        return resolveGateway().runSubagent(subagentId, requireMessages(messages));
    }

    /** Sends a structured {@link ChatUiRequest} and returns the agent reply reactively. */
    /** 发送结构化的 {@link ChatUiRequest}，以响应式方式返回智能体回复。 */
    public Mono<Msg> send(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.subagentId() != null) {
            return resolveGateway().runSubagent(request.subagentId(), request.messages());
        }
        return dispatch(buildInbound(request));
    }

    // -----------------------------------------------------------------
    //  Streaming send APIs
    // -----------------------------------------------------------------

    /** 分发入站消息的流式变体：路由解析后委托网关流式执行。 */
    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        RouteResult route = router.resolveRoute(config, message);
        return resolveGateway()
                .runStream(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message);
    }

    /** Streaming variant of {@link #send(String)}. Returns fine-grained {@link AgentEvent}s. */
    /** {@link #send(String)} 的流式变体，返回细粒度 {@link AgentEvent}。 */
    public Flux<AgentEvent> sendStream(String text) {
        return sendStream(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /** Streaming variant of {@link #send(Msg)}. */
    public Flux<AgentEvent> sendStream(Msg message) {
        Objects.requireNonNull(message, "message");
        return sendStream(ChatUiRequest.of(List.of(message)));
    }

    /** Streaming variant of {@link #send(List)}. */
    public Flux<AgentEvent> sendStream(List<Msg> messages) {
        return sendStream(ChatUiRequest.of(requireMessages(messages)));
    }

    /** Streaming variant of {@link #send(String, String)}. */
    /** {@link #send(String, String)} 的流式变体。 */
    public Flux<AgentEvent> sendStream(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return sendStream(ChatUiRequest.withPeer(peerId, text));
    }

    /** Streaming variant of {@link #send(String, Msg)}. */
    public Flux<AgentEvent> sendStream(String peerId, Msg message) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(message, "message");
        return sendStream(ChatUiRequest.withPeer(peerId, List.of(message)));
    }

    /** Streaming variant of {@link #send(String, List)}. */
    public Flux<AgentEvent> sendStream(String peerId, List<Msg> messages) {
        Objects.requireNonNull(peerId, "peerId");
        return sendStream(ChatUiRequest.withPeer(peerId, requireMessages(messages)));
    }

    /** Streaming variant of {@link #send(SendOptions, String)}. */
    /** {@link #send(SendOptions, String)} 的流式变体。 */
    public Flux<AgentEvent> sendStream(SendOptions options, String text) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).name(options.userId()).textContent(text).build();
        return dispatchStreamWithOptions(options, List.of(msg));
    }

    /** Streaming variant of {@link #send(SendOptions, Msg)}. */
    public Flux<AgentEvent> sendStream(SendOptions options, Msg message) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(message, "message");
        return dispatchStreamWithOptions(options, List.of(message));
    }

    /** Streaming variant of {@link #send(SendOptions, List)}. */
    public Flux<AgentEvent> sendStream(SendOptions options, List<Msg> messages) {
        Objects.requireNonNull(options, "options");
        return dispatchStreamWithOptions(options, requireMessages(messages));
    }

    /** Streaming variant of {@link #sendToSubagent(String, String)}. */
    /** {@link #sendToSubagent(String, String)} 的流式变体。 */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return resolveGateway().runSubagentStream(subagentId, List.of(msg));
    }

    /** Streaming variant of {@link #sendToSubagent(String, Msg)}. */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, Msg message) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(message, "message");
        return resolveGateway().runSubagentStream(subagentId, List.of(message));
    }

    /** Streaming variant of {@link #sendToSubagent(String, List)}. */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, List<Msg> messages) {
        Objects.requireNonNull(subagentId, "subagentId");
        return resolveGateway().runSubagentStream(subagentId, requireMessages(messages));
    }

    /** Streaming variant of {@link #send(ChatUiRequest)}. */
    /** {@link #send(ChatUiRequest)} 的流式变体。 */
    public Flux<AgentEvent> sendStream(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.subagentId() != null) {
            return resolveGateway().runSubagentStream(request.subagentId(), request.messages());
        }
        return dispatchStream(buildInbound(request));
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    private static List<Msg> requireMessages(List<Msg> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        return messages;
    }

    /** 按 SendOptions 构造上下文与出站地址后委托网关执行（同步回复）。 */
    private Mono<Msg> dispatchWithOptions(SendOptions options, List<Msg> messages) {
        MsgContext ctx = buildContextFromOptions(options);
        OutboundAddress outbound = buildOutboundFromOptions(options);
        return resolveGateway().run(ctx, messages, outbound, options.runtimeContext());
    }

    /** 按 SendOptions 构造上下文与出站地址后委托网关流式执行。 */
    private Flux<AgentEvent> dispatchStreamWithOptions(SendOptions options, List<Msg> messages) {
        MsgContext ctx = buildContextFromOptions(options);
        OutboundAddress outbound = buildOutboundFromOptions(options);
        return resolveGateway().runStream(ctx, messages, outbound, options.runtimeContext());
    }

    /**
     * 由 SendOptions 构造 MsgContext：会话键取 effectiveSessionKey（sessionId
     * 优先、否则 userId）作为 room；agentId 优先取 options，其次通道默认；
     * userId 附加到上下文。
     */
    private MsgContext buildContextFromOptions(SendOptions options) {
        String room = options.effectiveSessionKey();
        String agentId = options.agentId();
        if (agentId == null) {
            agentId = config.defaultAgentId();
        }
        Map<String, String> extra = agentId != null ? Map.of("agentId", agentId) : Map.of();
        return new MsgContext(CHANNEL_ID, null, room, null, null, extra)
                .withUserId(options.userId());
    }

    /** 由 SendOptions 构造出站地址：to 为 {@code "chatui:<会话键>"}。 */
    private OutboundAddress buildOutboundFromOptions(SendOptions options) {
        return OutboundAddress.direct(CHANNEL_ID, CHANNEL_ID + ":" + options.effectiveSessionKey());
    }

    /** 解析网关引用；未接入网关时抛出带使用指引的异常。 */
    private Gateway resolveGateway() {
        Gateway g = gateway;
        if (g == null) {
            throw new IllegalStateException(
                    "ChatUiChannel has no gateway. Use GatewayBootstrap.chatUiChannel(),"
                            + " or construct with ChatUiChannel.create(gateway).");
        }
        return g;
    }

    /**
     * 把 {@link ChatUiRequest} 转换为 {@link InboundMessage}：
     * peerId 存在时以其作为对端身份，否则使用 {@code "__anonymous__"} 匿名对端；
     * agentId 存在时走 {@code dmFor} 显式指定目标智能体，否则走常规 {@code dm} 路由。
     */
    private InboundMessage buildInbound(ChatUiRequest request) {
        String peerId = request.peerId();
        String agentId = request.agentId();
        if (peerId != null && !peerId.isBlank()) {
            if (agentId != null) {
                return InboundMessage.dmFor(CHANNEL_ID, peerId.trim(), agentId, request.messages());
            }
            return InboundMessage.dm(CHANNEL_ID, peerId.trim(), request.messages());
        }
        if (agentId != null) {
            return InboundMessage.dmFor(CHANNEL_ID, "__anonymous__", agentId, request.messages());
        }
        return InboundMessage.dm(CHANNEL_ID, "__anonymous__", request.messages());
    }
}
