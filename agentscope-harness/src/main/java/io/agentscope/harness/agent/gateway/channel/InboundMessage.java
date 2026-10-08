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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized inbound message produced by a {@link Channel} adapter before routing. Carries all
 * identity and context metadata needed by {@link ChannelRouter} to resolve an agent and build a
 * stable session key.
 *
 * <p>Two identity fields are deliberately separate:
 * <ul>
 *   <li>{@link #peer} — the <em>conversation</em> identifier used to construct the session key
 *       (DM user id, group chat id, channel id)
 *   <li>{@link #senderId} — the <em>message sender's</em> identity, used as {@code userId} for
 *       HarnessAgent namespace isolation. In DM scenarios these are equal; in group/channel
 *       scenarios {@code peer.id} is the group and {@code senderId} is the individual author.
 * </ul>
 *
 * <p>{@link #runtimeContext()} is <em>not</em> used for routing and does not participate in session
 * key computation. It is forwarded to the Gateway as the caller merge base for the agent turn.
 *
 * @param channelId identifier of the originating channel (e.g. {@code "chatui"}, {@code "slack"})
 * @param accountId optional multi-account identifier (e.g. Slack app installation id); null for
 *     single-account channels
 * @param peer the conversation peer (DM partner, channel, group, or thread) — used for session key
 * @param senderId optional sender identity for user-level isolation; if null, {@link #peer}'s id is
 *     used as fallback in DM contexts
 * @param parentPeer optional parent peer for thread-nested messages; used for
 *     {@code binding.peer.parent} inheritance
 * @param guild optional guild / server / workspace id (Discord guild, Slack workspace)
 * @param team optional team id (Slack team, MS Teams team)
 * @param roles optional set of role ids the sender holds within the guild; used for
 *     {@code binding.guild+roles} tier evaluation
 * @param messages the actual message content to forward to the agent
 * @param preferredAgentId optional explicit agent override evaluated by {@link ChannelRouter} as a
 *     tier-0 short-circuit before any binding tier. Set this when the caller already knows which
 *     agent should handle the request (e.g. a path-mapped Web UI where the user clicked an agent
 *     card); the router still builds session and outbound context normally so bindings continue to
 *     control {@code sessionScope} and outbound addressing. {@code null} for normal binding-driven
 *     routing.
 * @param runtimeContext optional caller-supplied {@link RuntimeContext} merged into the agent
 *     turn; {@code null} when none. Not part of routing / session identity.
 */
/**
 * {@link Channel} 适配器在路由前生成的归一化入站消息。携带
 * {@link ChannelRouter} 解析智能体、构造稳定会话键所需的全部身份与上下文元数据。
 *
 * <p>两个身份字段被刻意分开：
 * <ul>
 *   <li>{@link #peer} —— <em>会话</em>标识，用于构造会话键
 *       （私聊用户 ID、群聊 ID、频道 ID）
 *   <li>{@link #senderId} —— <em>消息发送者</em>身份，用作 HarnessAgent
 *       命名空间隔离的 {@code userId}。私聊场景两者相等；群组/频道场景
 *       {@code peer.id} 是群组而 {@code senderId} 是具体的作者。
 * </ul>
 *
 * @param channelId 发起通道的标识（例如 {@code "chatui"}、{@code "slack"}）
 * @param accountId 可选的多账号标识（例如 Slack 应用安装 ID）；单账号通道为 null
 * @param peer 会话对端（私聊对象、频道、群组或话题）—— 用于会话键
 * @param senderId 用于用户级隔离的可选发送者身份；为 null 时在私聊上下文
 *     回退使用 {@link #peer} 的 ID
 * @param parentPeer 话题嵌套消息的可选父对端；用于
 *     {@code binding.peer.parent} 继承
 * @param guild 可选的 guild/服务器/工作空间 ID（Discord guild、Slack workspace）
 * @param team 可选的 team ID（Slack team、MS Teams team）
 * @param roles 发送者在 guild 内持有的角色 ID 集合；用于
 *     {@code binding.guild+roles} 层级评估
 * @param messages 要转发给智能体的实际消息内容
 * @param preferredAgentId 可选的显式智能体覆盖，由 {@link ChannelRouter} 作为
 *     第 0 层短路在任何绑定层级之前评估。当调用方已明确知道该由哪个智能体
 *     处理请求时设置（例如路径映射的 Web UI 中用户点击了智能体卡片）；
 *     路由器仍照常构造会话与出站上下文，绑定继续控制 {@code sessionScope}
 *     与出站寻址。常规绑定路由时为 {@code null}。
 */
public record InboundMessage(
        String channelId,
        String accountId,
        Peer peer,
        String senderId,
        Peer parentPeer,
        String guild,
        String team,
        Set<String> roles,
        List<Msg> messages,
        String preferredAgentId,
        RuntimeContext runtimeContext) {

    public InboundMessage {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(messages, "messages");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    /**
     * Single-turn DM with no guild/team/role metadata (typical for direct chat UI use).
     *
     * <p>In DM context {@code senderId} equals {@code peerId} — the conversation partner is also
     * the message author.
     */
    /**
     * 无 guild/team/角色元数据的单回合私聊（典型用于直接聊天 UI）。
     *
     * <p>私聊上下文中 {@code senderId} 等于 {@code peerId}——会话对象
     * 同时也是消息作者。
     */
    public static InboundMessage dm(String channelId, String peerId, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                null,
                null);
    }

    /**
     * Single-turn DM with an explicit {@link #preferredAgentId()} override — the router will use
     * the supplied {@code agentId} as a tier-0 short-circuit instead of evaluating bindings.
     */
    /**
     * 带显式 {@link #preferredAgentId()} 覆盖的单回合私聊——路由器将使用
     * 提供的 {@code agentId} 作为第 0 层短路，而不再评估绑定。
     */
    public static InboundMessage dmFor(
            String channelId, String peerId, String agentId, List<Msg> messages) {
        Objects.requireNonNull(agentId, "agentId");
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                agentId,
                null);
    }

    /**
     * Channel / group message with optional guild context. The {@code senderId} identifies the
     * individual author separately from the {@code roomId} conversation peer.
     */
    /**
     * 带可选 guild 上下文的频道/群组消息。{@code senderId} 与
     * {@code roomId} 会话对端分开标识具体的作者。
     */
    public static InboundMessage channel(
            String channelId, String roomId, String senderId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                senderId,
                null,
                guild,
                null,
                Set.of(),
                List.copyOf(messages),
                null,
                null);
    }

    /**
     * Channel message with optional guild context (no explicit senderId — sender identity unknown,
     * falls back to room peer for isolation).
     */
    /**
     * 带可选 guild 上下文的频道消息（无显式 senderId——发送者身份未知，
     * 隔离时回退使用房间对端）。
     */
    public static InboundMessage channel(
            String channelId, String roomId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                null,
                null,
                guild,
                null,
                Set.of(),
                List.copyOf(messages),
                null,
                null);
    }

    /** Whether this message originates from a direct / DM peer. */
    /** 本消息是否来自私聊/DM 对端。 */
    public boolean isDm() {
        return peer.kind().isDirect();
    }

    /** Whether this message is a thread-nested reply. */
    /** 本消息是否为话题内的嵌套回复。 */
    public boolean isThread() {
        return peer.kind().isThread();
    }

    /** Returns a copy carrying the given {@link RuntimeContext}. */
    public InboundMessage withRuntimeContext(RuntimeContext runtimeContext) {
        return new InboundMessage(
                channelId,
                accountId,
                peer,
                senderId,
                parentPeer,
                guild,
                team,
                roles,
                messages,
                preferredAgentId,
                runtimeContext);
    }

    /** Returns a builder for constructing messages with all optional fields. */
    /** 返回用于构造带全部可选字段消息的构建器。 */
    public static Builder builder(String channelId, Peer peer, List<Msg> messages) {
        return new Builder(channelId, peer, messages);
    }

    /** 入站消息的流式构建器。 */
    public static final class Builder {
        private final String channelId;
        private final Peer peer;
        private final List<Msg> messages;
        private String accountId;
        private String senderId;
        private Peer parentPeer;
        private String guild;
        private String team;
        private Set<String> roles = Set.of();
        private String preferredAgentId;
        private RuntimeContext runtimeContext;

        private Builder(String channelId, Peer peer, List<Msg> messages) {
            this.channelId = channelId;
            this.peer = peer;
            this.messages = messages;
        }

        public Builder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder parentPeer(Peer parentPeer) {
            this.parentPeer = parentPeer;
            return this;
        }

        public Builder guild(String guild) {
            this.guild = guild;
            return this;
        }

        public Builder team(String team) {
            this.team = team;
            return this;
        }

        public Builder roles(Set<String> roles) {
            this.roles = roles != null ? roles : Set.of();
            return this;
        }

        public Builder preferredAgentId(String preferredAgentId) {
            this.preferredAgentId = preferredAgentId;
            return this;
        }

        public Builder runtimeContext(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
            return this;
        }

        public InboundMessage build() {
            return new InboundMessage(
                    channelId,
                    accountId,
                    peer,
                    senderId,
                    parentPeer,
                    guild,
                    team,
                    roles,
                    messages,
                    preferredAgentId,
                    runtimeContext);
        }
    }
}
