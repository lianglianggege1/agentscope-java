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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the target {@code agentId} and stable {@link MsgContext} for an {@link InboundMessage}
 * by evaluating {@link ChannelBinding} rules in deterministic priority order.
 *
 * <h2>Binding evaluation tiers (highest to lowest priority)</h2>
 *
 * <ol>
 *   <li><b>explicit</b> — {@link InboundMessage#preferredAgentId()} short-circuit when the caller
 *       has already nominated a specific agent. Bindings are still consulted to determine
 *       {@code sessionScope} and outbound addressing.
 *   <li><b>peer</b> — exact {@link Peer#key()} match (e.g. {@code "direct:u_42"})
 *   <li><b>peer.parent</b> — exact match on the thread-parent peer's key
 *   <li><b>guild + roles</b> — guild id matches AND sender holds at least one of the binding's
 *       roles
 *   <li><b>guild</b> — guild id matches (no role constraint)
 *   <li><b>team</b> — team id matches
 *   <li><b>account</b> — account id matches
 *   <li><b>channel</b> — channel id matches
 *   <li><b>default</b> — {@link ChannelConfig#defaultAgentId()} or {@code globalDefaultAgentId}
 * </ol>
 *
 * <p>Within each tier the first binding in {@link ChannelConfig#bindings()} list order that
 * matches wins.
 *
 * <h2>Session key construction ({@link MsgContext})</h2>
 *
 * After resolving {@code agentId}, the router builds a {@link MsgContext} whose {@link
 * MsgContext#canonicalKey()} produces a stable session key:
 *
 * <ul>
 *   <li>DM + {@link DmScope#MAIN} — {@code channel} field only; all DMs share one session
 *   <li>DM + other scopes — room = peerId (optionally group = accountId)
 *   <li>Thread — room = parentPeer id, threadId = peer id
 *   <li>Non-DM channel/group — room = peer id, group = guild
 * </ul>
 *
 * The resolved {@code agentId} is always included in {@link MsgContext#extra()} under key {@code
 * "agentId"} so that the gateway can pick the correct agent from its registry.
 */
/**
 * 为 {@link InboundMessage} 解析目标 {@code agentId} 与稳定的 {@link MsgContext}，
 * 方法是按确定性的优先级顺序评估 {@link ChannelBinding} 规则。
 *
 * <h2>绑定评估层级（优先级从高到低）</h2>
 *
 * <ol>
 *   <li><b>explicit</b> —— 调用方已指定具体智能体时，以
 *       {@link InboundMessage#preferredAgentId()} 短路。绑定规则仍会参与
 *       决定 {@code sessionScope} 与出站寻址。
 *   <li><b>peer</b> —— {@link Peer#key()} 精确匹配（例如 {@code "direct:u_42"}）
 *   <li><b>peer.parent</b> —— 话题父对端键的精确匹配
 *   <li><b>guild + roles</b> —— guild ID 匹配且发送者至少持有绑定的一个角色
 *   <li><b>guild</b> —— guild ID 匹配（无角色约束）
 *   <li><b>team</b> —— team ID 匹配
 *   <li><b>account</b> —— account ID 匹配
 *   <li><b>channel</b> —— channel ID 匹配
 *   <li><b>default</b> —— {@link ChannelConfig#defaultAgentId()} 或 {@code globalDefaultAgentId}
 * </ol>
 *
 * <p>同一层级内，按 {@link ChannelConfig#bindings()} 列表顺序取第一个匹配的绑定。
 *
 * <h2>会话键构造（{@link MsgContext}）</h2>
 *
 * 解析出 {@code agentId} 后，路由器构建 {@link MsgContext}，其 {@link
 * MsgContext#canonicalKey()} 产生稳定的会话键：
 *
 * <ul>
 *   <li>DM + {@link DmScope#MAIN} —— 仅 {@code channel} 字段；所有私聊共享一个会话
 *   <li>DM + 其他作用域 —— room = peerId（可选 group = accountId）
 *   <li>话题（Thread）—— room = 父对端 ID，threadId = 对端 ID
 *   <li>非 DM 频道/群组 —— room = 对端 ID，group = guild
 * </ul>
 *
 * 解析出的 {@code agentId} 始终写入 {@link MsgContext#extra()} 的
 * {@code "agentId"} 键，使网关能从注册表中选出正确的智能体。
 */
public final class ChannelRouter {

    private final String globalDefaultAgentId;

    /**
     * @param globalDefaultAgentId fallback agent id when no binding and no channel-level default
     *     match; typically the id of the agent registered via
     *     {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent}
     */
    /**
     * @param globalDefaultAgentId 没有任何绑定与通道级默认匹配时的回退智能体 ID；
     *     通常是通过 {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent}
     *     注册的智能体 ID
     */
    public ChannelRouter(String globalDefaultAgentId) {
        this.globalDefaultAgentId = globalDefaultAgentId != null ? globalDefaultAgentId : "main";
    }

    /**
     * Evaluates bindings in priority order and returns a {@link RouteResult} ready for gateway
     * execution.
     *
     * @param config channel-level routing config (bindings + dmScope + channel default agent)
     * @param msg normalized inbound message
     */
    /**
     * 按优先级顺序评估绑定，返回可直接交给网关执行的 {@link RouteResult}。
     *
     * <p>流程：显式指定 agentId 短路 → 绑定层级匹配 → 通道级默认 →
     * 全局默认；随后确定生效的 DmScope（绑定覆盖优先于通道配置），
     * 推导 userId（senderId 优先，DM 场景回退 peer ID），
     * 最后构造 MsgContext 与 OutboundAddress。
     *
     * @param config 通道路由配置（绑定 + dmScope + 通道默认智能体）
     * @param msg 归一化后的入站消息
     */
    public RouteResult resolveRoute(ChannelConfig config, InboundMessage msg) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(msg, "msg");

        String agentId;
        String matchedBy;
        String explicit = msg.preferredAgentId();
        ChannelBinding matchedBinding = findMatchedBinding(config.bindings(), msg);

        if (explicit != null && !explicit.isBlank()) {
            agentId = explicit;
            matchedBy = "explicit";
        } else if (matchedBinding != null) {
            agentId = matchedBinding.agentId();
            matchedBy = detectMatchedTier(config.bindings(), msg);
        } else if (config.defaultAgentId() != null) {
            agentId = config.defaultAgentId();
            matchedBy = "channel-default";
        } else {
            agentId = globalDefaultAgentId;
            matchedBy = "global-default";
        }

        DmScope effectiveScope =
                matchedBinding != null && matchedBinding.sessionScope() != null
                        ? matchedBinding.sessionScope()
                        : config.dmScope();

        String userId =
                msg.senderId() != null
                        ? msg.senderId()
                        : (msg.peer().kind().isDirect() ? msg.peer().id() : null);

        MsgContext context = buildContext(msg, effectiveScope, agentId, userId);
        OutboundAddress outbound = buildOutboundAddress(msg);
        return new RouteResult(agentId, context, matchedBy, outbound);
    }

    // -----------------------------------------------------------------
    //  Binding evaluation - 7 tiers
    //  绑定评估 - 7 个层级
    // -----------------------------------------------------------------

    /** 绑定层级评估顺序（优先级从高到低）。 */
    private static final List<String> TIERS =
            List.of("peer", "parentPeer", "guildRoles", "guild", "team", "account", "channel");

    /** 按层级顺序查找第一个匹配的绑定，全部未命中返回 null。 */
    private ChannelBinding findMatchedBinding(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            ChannelBinding b = findFirstBinding(bindings, msg, tier);
            if (b != null) return b;
        }
        return null;
    }

    /** 诊断用：返回实际命中的层级名称（guildRoles 展示为 "guild+roles"）。 */
    private String detectMatchedTier(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            if (findFirstBinding(bindings, msg, tier) != null) {
                return tier.equals("guildRoles") ? "guild+roles" : tier;
            }
        }
        return "none";
    }

    /** 在指定层级内按列表顺序返回第一个匹配的绑定。 */
    private ChannelBinding findFirstBinding(
            List<ChannelBinding> bindings, InboundMessage msg, String tier) {
        for (ChannelBinding b : bindings) {
            if (matches(b, msg, tier)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 按层级语义判断绑定是否匹配入站消息：
     * peer/parentPeer 精确键匹配；guildRoles 要求 guild 命中且角色有交集；
     * guild 层要求绑定无角色约束；team/account/channel 为 ID 相等。
     */
    private boolean matches(ChannelBinding b, InboundMessage msg, String tier) {
        return switch (tier) {
            case "peer" -> b.peer() != null && b.peer().equals(msg.peer().key());
            case "parentPeer" ->
                    b.parentPeer() != null
                            && msg.parentPeer() != null
                            && b.parentPeer().equals(msg.parentPeer().key());
            case "guildRoles" ->
                    b.guild() != null
                            && !b.roles().isEmpty()
                            && b.guild().equals(msg.guild())
                            && msg.roles().stream().anyMatch(b.roles()::contains);
            case "guild" ->
                    b.guild() != null && b.roles().isEmpty() && b.guild().equals(msg.guild());
            case "team" -> b.team() != null && b.team().equals(msg.team());
            case "account" -> b.account() != null && b.account().equals(msg.accountId());
            case "channel" -> b.channel() != null && b.channel().equals(msg.channelId());
            default -> false;
        };
    }

    // -----------------------------------------------------------------
    //  MsgContext construction from routing result + dmScope
    //  由路由结果 + dmScope 构造 MsgContext
    // -----------------------------------------------------------------

    /**
     * 按对端类型构造 MsgContext：话题 → room=父对端 ID、threadId=对端 ID；
     * DM → 按 DmScope 决定键粒度；其他 → room=对端 ID、group=guild。
     * agentId 始终写入 extra；有 userId 时附加。
     */
    private MsgContext buildContext(
            InboundMessage msg, DmScope dmScope, String agentId, String userId) {
        Map<String, String> extra = Map.of("agentId", agentId);
        String channel = msg.channelId();
        Peer peer = msg.peer();

        MsgContext ctx;
        if (peer.kind().isThread()) {
            String parentRoom = msg.parentPeer() != null ? msg.parentPeer().id() : null;
            ctx = new MsgContext(channel, msg.guild(), parentRoom, peer.id(), null, extra);
        } else if (peer.kind().isDirect()) {
            ctx = buildDmContext(channel, peer.id(), msg.accountId(), dmScope, extra);
        } else {
            ctx = new MsgContext(channel, msg.guild(), peer.id(), null, null, extra);
        }

        return userId != null ? ctx.withUserId(userId) : ctx;
    }

    /**
     * 按 DmScope 决定 DM 会话键粒度：MAIN 全部共享一个会话；
     * PER_PEER/PER_CHANNEL_PEER 以 peerId 为 room；
     * PER_ACCOUNT_CHANNEL_PEER 额外以 accountId 为 group。
     */
    private MsgContext buildDmContext(
            String channel,
            String peerId,
            String accountId,
            DmScope dmScope,
            Map<String, String> extra) {
        return switch (dmScope) {
            case MAIN -> new MsgContext(channel, null, null, null, null, extra);
            case PER_PEER -> new MsgContext(channel, null, peerId, null, null, extra);
            case PER_CHANNEL_PEER -> new MsgContext(channel, null, peerId, null, null, extra);
            case PER_ACCOUNT_CHANNEL_PEER ->
                    new MsgContext(channel, accountId, peerId, null, null, extra);
        };
    }

    // -----------------------------------------------------------------
    //  OutboundAddress construction
    //  出站地址构造
    // -----------------------------------------------------------------

    /**
     * 由入站消息推导回复投递地址：to 为 {@code "channelId:peerId"} 格式；
     * 话题消息额外携带 threadId。
     */
    private OutboundAddress buildOutboundAddress(InboundMessage msg) {
        String to = msg.channelId() + ":" + msg.peer().id();
        String threadId = msg.peer().kind().isThread() ? msg.peer().id() : null;
        return new OutboundAddress(msg.channelId(), msg.accountId(), to, threadId);
    }
}
