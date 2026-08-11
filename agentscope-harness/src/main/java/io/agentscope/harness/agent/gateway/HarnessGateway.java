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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default {@link Gateway} implementation. Routes inbound turns by {@link MsgContext#canonicalKey()},
 * serializes concurrent turns per session via {@link SessionTurnGate}, and dispatches to the
 * appropriate registered {@link HarnessAgent}.
 *
 * <p>This implementation provides:
 * <ul>
 *   <li>Multi-agent registry with fallback to a main agent
 *   <li>Stable session mapping: same {@link MsgContext#canonicalKey()} always resolves to the same
 *       session id, giving each logical conversation its own memory
 *   <li>Per-session fair mutual exclusion preventing concurrent turns from racing
 *   <li>Outbound address tracking for proactive delivery (e.g. subagent announces)
 * </ul>
 */
/**
 * {@link Gateway} 的默认实现。按 {@link MsgContext#canonicalKey()} 路由入站回合，
 * 通过 {@link SessionTurnGate} 串行化同一会话的并发回合，
 * 并把消息分派到对应的已注册 {@link HarnessAgent}。
 *
 * <p>该实现提供：
 * <ul>
 *   <li>多智能体注册表，找不到目标时回退到主智能体
 *   <li>稳定的会话映射：相同 {@link MsgContext#canonicalKey()} 始终解析到同一
 *       会话 ID，使每个逻辑会话拥有独立的记忆
 *   <li>按键会话公平互斥，防止并发回合相互竞争
 *   <li>出站地址跟踪，支持主动投递（例如子智能体公告）
 * </ul>
 */
public final class HarnessGateway implements Gateway, WakeupDispatcher.WakeupTarget {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private final ChannelManager channelManager;
    private final MessageBus messageBus;
    private final SessionTurnGate sessionTurnGate = new SessionTurnGate();

    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();
    private volatile String defaultAgentId = null;

    /** canonicalKey → stable session id */
    /** canonicalKey → 稳定会话 ID 的映射 */
    private final ConcurrentHashMap<String, String> sessionMap = new ConcurrentHashMap<>();

    /** Reverse mapping: session id → canonicalKey (for wakeup-driven runs). */
    /** 反向映射：会话 ID → canonicalKey（供唤醒驱动的执行使用）。 */
    private final ConcurrentHashMap<String, String> sessionToGateKey = new ConcurrentHashMap<>();

    /** Live-agent cache for the fast same-node path: subagentId → exposed subagent session. */
    /** 同节点快速路径的活跃智能体缓存：subagentId → 已暴露的子智能体会话。 */
    private final ConcurrentHashMap<String, ExposedSession> exposedSessions =
            new ConcurrentHashMap<>();

    /**
     * Durable registry of exposure recipes. Defaults to {@link InMemorySubagentRegistry} (legacy
     * single-process behaviour); a {@link StoreBackedSubagentRegistry} is injected when a
     * distributed store is configured, enabling cross-node / cross-restart resolution.
     */
    /**
     * 暴露配置的持久化注册表。默认为 {@link InMemorySubagentRegistry}
     * （旧的单进程行为）；配置了分布式存储时会注入
     * {@link StoreBackedSubagentRegistry}，从而支持跨节点/跨重启解析。
     */
    private volatile SubagentRegistry subagentRegistry = new InMemorySubagentRegistry();

    /**
     * Rebuilds a subagent {@link Agent} from its type id when the live instance is not present on
     * this node (cross-node / post-restart). {@code null} disables recovery — only cached live
     * sessions are addressable.
     */
    /**
     * 当本节点不存在活跃实例时（跨节点/重启后），根据类型 ID 重建
     * 子智能体 {@link Agent}。设为 {@code null} 则禁用恢复——
     * 只有缓存中的活跃会话可被寻址。
     */
    private volatile SubagentMaterializer subagentMaterializer;

    /** session id → last outbound address (for proactive push delivery) */
    /** 会话 ID → 最近出站地址（用于主动推送投递） */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySession =
            new ConcurrentHashMap<>();

    private HarnessGateway(ChannelManager channelManager, MessageBus messageBus) {
        this.channelManager = channelManager;
        this.messageBus = messageBus;
    }

    /** Creates a gateway with a channel manager for outbound delivery. */
    /** 创建带通道管理器（用于出站投递）的网关。 */
    public static HarnessGateway create(ChannelManager channelManager) {
        return new HarnessGateway(Objects.requireNonNull(channelManager, "channelManager"), null);
    }

    /** Creates a gateway with a channel manager and message bus. */
    /** 创建同时带通道管理器和消息总线的网关。 */
    public static HarnessGateway create(ChannelManager channelManager, MessageBus messageBus) {
        return new HarnessGateway(
                Objects.requireNonNull(channelManager, "channelManager"), messageBus);
    }

    /** Creates a gateway without outbound delivery support. */
    /** 创建不支持出站投递的网关。 */
    public static HarnessGateway create() {
        return new HarnessGateway(null, null);
    }

    /** Returns the message bus, or null if not configured. */
    /** 返回消息总线；未配置时返回 null。 */
    public MessageBus messageBus() {
        return messageBus;
    }

    /** The channel manager for outbound delivery, or null if not configured. */
    /** 用于出站投递的通道管理器；未配置时返回 null。 */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 绑定主智能体：记录到 mainAgent 引用，同时以其 ID 注册进注册表
     * 并设为默认回退智能体。
     */
    @Override
    public void bindMainAgent(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        mainAgent.set(agent);
        String id = resolveAgentId(agent);
        agentRegistry.put(id, agent);
        defaultAgentId = id;
    }

    /** 注册一个具名智能体到路由注册表（不改变主智能体与默认回退）。 */
    @Override
    public void registerAgent(String agentId, HarnessAgent agent) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(agent, "agent");
        agentRegistry.put(agentId, agent);
    }

    /**
     * Returns the agent registered under {@code agentId}, or null if not found. Exposed for
     * introspection (e.g. enumerate an agent's skill repositories).
     */
    /**
     * 返回以 {@code agentId} 注册的智能体，未找到时返回 null。
     * 对外暴露供内省使用（例如枚举某智能体的技能仓库）。
     */
    public HarnessAgent findAgent(String agentId) {
        if (agentId == null) return null;
        return agentRegistry.get(agentId);
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    /**
     * 入站回合主流程（同步回复变体）：
     * <ol>
     *   <li>上下文为空时回退到默认单会话上下文；</li>
     *   <li>以 canonicalKey 作为回合互斥键，解析（或首次生成）稳定会话 ID；</li>
     *   <li>按 extra 中的 agentId 解析目标智能体，找不到回退主智能体；</li>
     *   <li>记录出站地址（若提供），构造 RuntimeContext（携带 userId、
     *       msgContext、gateKey、outboundAddress）；</li>
     *   <li>在回合锁保护下执行 {@code ha.call}。</li>
     * </ol>
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Mono.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before run(...)"));
        }

        String sessionId = resolveSessionId(gateKey);

        if (outboundAddress != null) {
            lastRouteBySession.put(sessionId, outboundAddress);
        }

        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("gateKey", gateKey)
                        .put("outboundAddress", outboundAddress);
        if (ctx.userId() != null && !ctx.userId().isBlank()) {
            rtcBuilder.userId(ctx.userId());
        }
        RuntimeContext runtimeContext = rtcBuilder.build();

        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    /** 入站回合主流程（流式事件变体）：与 {@link #run} 相同的路由与回合锁逻辑，改为流式订阅。 */
    @Override
    public Flux<AgentEvent> runStream(
            MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Flux.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before runStream(...)"));
        }

        String sessionId = resolveSessionId(gateKey);

        if (outboundAddress != null) {
            lastRouteBySession.put(sessionId, outboundAddress);
        }

        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("gateKey", gateKey)
                        .put("outboundAddress", outboundAddress);
        if (ctx.userId() != null && !ctx.userId().isBlank()) {
            rtcBuilder.userId(ctx.userId());
        }
        RuntimeContext runtimeContext = rtcBuilder.build();

        return withGatedStream(gateKey, () -> ha.streamEvents(messages, runtimeContext));
    }

    /**
     * Delivers proactive outbound messages through the channel manager using the session's last
     * recorded outbound address. Returns false if no route or no channel manager is available.
     */
    /**
     * 通过通道管理器向会话最近记录的出站地址投递主动出站消息。
     * 无可用路由或无通道管理器时返回 false。
     */
    public boolean deliverToSession(String sessionId, List<Msg> messages) {
        if (channelManager == null || sessionId == null || messages == null || messages.isEmpty()) {
            return false;
        }
        OutboundAddress target = lastRouteBySession.get(sessionId);
        if (target == null) {
            log.debug("Cannot deliver to session {}: no outbound address recorded", sessionId);
            return false;
        }
        channelManager.deliver(target, messages);
        return true;
    }

    // -----------------------------------------------------------------
    //  Exposed subagent routing
    // -----------------------------------------------------------------

    /** 已暴露子智能体的内存会话记录：句柄、类型、会话 ID、活跃实例与回复地址。 */
    private record ExposedSession(
            String subagentId,
            String agentId,
            String sessionId,
            Agent agent,
            OutboundAddress replyTo) {}

    /**
     * Exposes a spawned subagent as a user-addressable entry point. Returns the assigned
     * subagentId.
     *
     * @param agentId the subagent type identifier
     * @param sessionId the session id assigned to the subagent
     * @param agent the agent instance
     * @param replyTo the outbound address for delivering replies; may be null
     * @return the subagentId handle for direct addressing
     */
    /**
     * 把已生成的子智能体暴露为用户可寻址的入口，返回分配的 subagentId。
     * 同时写入内存活跃缓存与持久化注册表（注册失败仅告警不中断）。
     *
     * @param agentId 子智能体类型标识
     * @param sessionId 分配给子智能体的会话 ID
     * @param agent 智能体实例
     * @param replyTo 用于投递回复的出站地址；可为 null
     * @return 用于直接寻址的 subagentId 句柄
     */
    public String exposeSubagent(
            String agentId, String sessionId, Agent agent, OutboundAddress replyTo) {
        Objects.requireNonNull(agent, "agent");
        String subagentId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        exposedSessions.put(
                subagentId, new ExposedSession(subagentId, agentId, sessionId, agent, replyTo));
        try {
            subagentRegistry.register(
                    new SubagentRecord(
                            subagentId, agentId, sessionId, null, null, Instant.now(), null));
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to persist exposed-subagent record {}: {}", subagentId, e.getMessage());
        }
        log.debug(
                "Exposed subagent agentId={} sessionId={} as subagentId={}",
                agentId,
                sessionId,
                subagentId);
        return subagentId;
    }

    /** Revokes a previously exposed subagent, removing it from the live cache and the registry. */
    /** 撤销此前暴露的子智能体：同时从活跃缓存与持久化注册表中移除。 */
    public void revokeSubagent(String subagentId) {
        if (subagentId != null) {
            exposedSessions.remove(subagentId);
            try {
                subagentRegistry.revoke(subagentId);
            } catch (RuntimeException e) {
                log.debug("Registry revoke failed for {}: {}", subagentId, e.getMessage());
            }
        }
    }

    /**
     * Installs the durable {@link SubagentRegistry}. Called during gateway wiring; defaults to an
     * in-memory registry when not set.
     */
    /**
     * 安装持久化的 {@link SubagentRegistry}。在网关装配阶段调用；
     * 未设置时默认使用内存注册表。
     */
    public void setSubagentRegistry(SubagentRegistry registry) {
        if (registry != null) {
            this.subagentRegistry = registry;
        }
    }

    /**
     * Installs the {@link SubagentMaterializer} used to rebuild subagents that are not present in
     * the local live cache (cross-node / post-restart recovery).
     */
    /**
     * 安装 {@link SubagentMaterializer}，用于重建不在本地活跃缓存中的子智能体
     * （跨节点/重启后的恢复场景）。
     */
    public void setSubagentMaterializer(SubagentMaterializer materializer) {
        this.subagentMaterializer = materializer;
    }

    /**
     * Resolves an exposed subagent to a runnable session: returns the cached live session when
     * present, otherwise rebuilds it from the durable registry via the configured
     * {@link SubagentMaterializer}. Returns {@code null} when the handle is unknown / expired or
     * cannot be re-materialized.
     */
    /**
     * 把已暴露的子智能体解析为可运行的会话：优先返回缓存中的活跃会话；
     * 不存在时通过配置的 {@link SubagentMaterializer} 依据持久化注册表重建。
     * 句柄未知/已过期或无法重建时返回 {@code null}。
     */
    private ExposedSession resolveExposed(String subagentId) {
        ExposedSession live = exposedSessions.get(subagentId);
        if (live != null) {
            return live;
        }
        SubagentMaterializer materializer = this.subagentMaterializer;
        if (materializer == null) {
            return null;
        }
        Optional<SubagentRecord> recordOpt = subagentRegistry.find(subagentId);
        if (recordOpt.isEmpty()) {
            return null;
        }
        SubagentRecord record = recordOpt.get();
        RuntimeContext.Builder rcb = RuntimeContext.builder().sessionId(record.sessionId());
        if (record.userId() != null && !record.userId().isBlank()) {
            rcb.userId(record.userId());
        }
        Optional<Agent> agentOpt = materializer.materialize(record.agentId(), rcb.build());
        if (agentOpt.isEmpty()) {
            log.warn(
                    "Cannot re-materialize exposed subagent {} (agentId={}): unknown type",
                    subagentId,
                    record.agentId());
            return null;
        }
        ExposedSession rebuilt =
                new ExposedSession(
                        record.subagentId(),
                        record.agentId(),
                        record.sessionId(),
                        agentOpt.get(),
                        null);
        exposedSessions.putIfAbsent(subagentId, rebuilt);
        return exposedSessions.get(subagentId);
    }

    /**
     * 直接路由到已暴露的子智能体（回合锁键为 {@code "subagent:"+subagentId}）；
     * 回复生成后若记录了 replyTo 地址，则同步经通道管理器投递。
     */
    @Override
    public Mono<Msg> runSubagent(String subagentId, List<Msg> messages) {
        ExposedSession session = resolveExposed(subagentId);
        if (session == null) {
            return Mono.error(new IllegalArgumentException("Unknown subagentId: " + subagentId));
        }

        RuntimeContext rtc = RuntimeContext.builder().sessionId(session.sessionId()).build();
        String gateKey = "subagent:" + subagentId;

        return withGatedTurn(gateKey, () -> invokeExposedAgent(session.agent(), messages, rtc))
                .doOnNext(
                        reply -> {
                            if (channelManager != null
                                    && session.replyTo() != null
                                    && reply != null) {
                                channelManager.deliver(session.replyTo(), List.of(reply));
                            }
                        });
    }

    /**
     * 调用已暴露的智能体：按实例类型选择带 RuntimeContext 的调用方式，
     * 普通 Agent 退回无上下文的 {@code call(messages)}。
     */
    private Mono<Msg> invokeExposedAgent(Agent agent, List<Msg> messages, RuntimeContext ctx) {
        if (agent instanceof HarnessAgent ha) {
            return ha.call(messages, ctx);
        }
        if (agent instanceof ReActAgent ra) {
            return ra.call(messages, ctx);
        }
        return agent.call(messages);
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    /** 解析目标智能体：显式 agentId 命中注册表 → 主智能体 → 默认 ID 兜底。 */
    private HarnessAgent resolveAgent(String agentId) {
        if (agentId != null && agentRegistry.containsKey(agentId)) {
            return agentRegistry.get(agentId);
        }
        HarnessAgent def = mainAgent.get();
        if (def != null) {
            return def;
        }
        return defaultAgentId != null ? agentRegistry.get(defaultAgentId) : null;
    }

    /** 提取智能体 ID，空时回退为 {@code "main"}。 */
    private static String resolveAgentId(HarnessAgent ha) {
        String id = ha != null ? ha.getAgentId() : null;
        return (id != null && !id.isBlank()) ? id : "main";
    }

    /** 由回合键确定性生成会话 ID，前缀 {@code "gw-"}，跨节点一致。 */
    private static String generateSessionId(String gateKey) {
        return "gw-" + SessionIdUtils.deterministicHash(gateKey);
    }

    /** 子智能体路由的流式变体：与 {@link #runSubagent} 相同的解析与回合锁，返回事件流。 */
    @Override
    public Flux<AgentEvent> runSubagentStream(String subagentId, List<Msg> messages) {
        ExposedSession session = resolveExposed(subagentId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Unknown subagentId: " + subagentId));
        }

        RuntimeContext rtc = RuntimeContext.builder().sessionId(session.sessionId()).build();
        String gateKey = "subagent:" + subagentId;

        return withGatedStream(gateKey, () -> streamExposedAgent(session.agent(), messages, rtc));
    }

    /** 流式调用已暴露的智能体：不支持流式的类型返回错误流。 */
    private Flux<AgentEvent> streamExposedAgent(
            Agent agent, List<Msg> messages, RuntimeContext ctx) {
        if (agent instanceof HarnessAgent ha) {
            return ha.streamEvents(messages, ctx);
        }
        if (agent instanceof ReActAgent ra) {
            return ra.streamEvents(messages, ctx);
        }
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent type "
                                + agent.getClass().getName()
                                + " does not support streaming"));
    }

    // ------------------------------------------------------------------
    //  Session helpers
    // ------------------------------------------------------------------

    /** 解析（或首次确定性生成）会话 ID，并维护会话 → 回合键的反向映射。 */
    private String resolveSessionId(String gateKey) {
        String sessionId = sessionMap.computeIfAbsent(gateKey, HarnessGateway::generateSessionId);
        sessionToGateKey.put(sessionId, gateKey);
        return sessionId;
    }

    /**
     * Returns {@code true} when the given session is currently inside a gated turn (a run is in
     * progress). Used by {@link WakeupDispatcher} to skip sessions that will naturally drain their
     * inbox on the current reasoning step.
     */
    /**
     * 当指定会话正处于受保护的回合内（有执行在进行）时返回 {@code true}。
     * 供 {@link WakeupDispatcher} 跳过这些会话——当前推理步骤会自然排空其收件箱。
     */
    public boolean isSessionRunning(String sessionId) {
        String gateKey = sessionToGateKey.get(sessionId);
        return gateKey != null && sessionTurnGate.isRunning(gateKey);
    }

    /**
     * Triggers a wakeup-driven run for an idle session. Called by {@link WakeupDispatcher} when a
     * background task completes or a team message arrives. The agent starts a reasoning round with
     * no user input; {@link io.agentscope.harness.agent.middleware.InboxMiddleware} drains the
     * inbox and injects the pending results as context.
     *
     * @param sessionId the session to wake up
     * @return the agent's response, or {@link Mono#empty()} if the session is unknown
     */
    /**
     * 为空闲会话触发一次唤醒驱动的执行。由 {@link WakeupDispatcher} 在后台任务完成
     * 或团队消息到达时调用。智能体在没有用户输入的情况下开始一轮推理；
     * {@link io.agentscope.harness.agent.middleware.InboxMiddleware} 会排空收件箱
     * 并把待处理结果作为上下文注入。
     *
     * <p>无消息总线时直接 {@code call} 空消息列表；有消息总线时改用流式执行，
     * 并把每个事件经 {@link #publishEventToSession} 发布到会话事件流，
     * 最终只取 {@link AgentResultEvent} 中的结果。
     *
     * @param sessionId 要唤醒的会话
     * @return 智能体的响应；会话未知时返回 {@link Mono#empty()}
     */
    public Mono<Msg> runWakeup(String sessionId) {
        String gateKey = sessionToGateKey.get(sessionId);
        if (gateKey == null) {
            log.debug("runWakeup: unknown sessionId={}, skipping", sessionId);
            return Mono.empty();
        }
        HarnessAgent ha = resolveAgent(null);
        if (ha == null) {
            return Mono.empty();
        }
        RuntimeContext rtc =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("gateKey", gateKey)
                        .put("outboundAddress", lastRouteBySession.get(sessionId))
                        .build();

        if (messageBus == null) {
            return withGatedTurn(gateKey, () -> ha.call(List.of(), rtc));
        }

        return withGatedTurn(
                gateKey,
                () ->
                        ha.streamEvents(List.of(), rtc)
                                .doOnNext(event -> publishEventToSession(sessionId, event))
                                .filter(e -> e instanceof AgentResultEvent)
                                .cast(AgentResultEvent.class)
                                .map(AgentResultEvent::getResult)
                                .last());
    }

    /**
     * 把智能体事件序列化为 Map 后经消息总线发布到会话事件流
     * （C 模式日志 + D 模式广播）；序列化或发布失败仅 debug 记录，不影响主流程。
     */
    private void publishEventToSession(String sessionId, AgentEvent event) {
        try {
            Map<String, Object> payload =
                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                            .convertValue(
                                    event,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {});
            messageBus.sessionPublishEvent(sessionId, payload).subscribe();
        } catch (Exception e) {
            log.debug("Failed to publish event to session {}: {}", sessionId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Turn serialization
    // ------------------------------------------------------------------

    /**
     * 回合串行化包装（同步回复）：订阅前阻塞获取回合锁，
     * 完成/取消/出错时（doFinally）释放，执行调度到 boundedElastic 线程池。
     */
    private Mono<Msg> withGatedTurn(String gateKey, Supplier<Mono<Msg>> turn) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        return Mono.defer(turn::get)
                .doOnSubscribe(
                        s -> {
                            try {
                                sessionTurnGate.acquire(gateKey);
                                acquired.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        })
                .doFinally(
                        sig -> {
                            if (acquired.get()) {
                                sessionTurnGate.release(gateKey);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 回合串行化包装（流式事件）：与 {@link #withGatedTurn} 相同的锁语义。 */
    private Flux<AgentEvent> withGatedStream(String gateKey, Supplier<Flux<AgentEvent>> stream) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        return Flux.defer(stream::get)
                .doOnSubscribe(
                        s -> {
                            try {
                                sessionTurnGate.acquire(gateKey);
                                acquired.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        })
                .doFinally(
                        sig -> {
                            if (acquired.get()) {
                                sessionTurnGate.release(gateKey);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
