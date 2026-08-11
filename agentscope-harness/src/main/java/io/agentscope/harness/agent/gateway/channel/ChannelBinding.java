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
import java.util.Set;

/**
 * One routing rule that maps a set of inbound-message conditions to a target {@code agentId}.
 *
 * <p>A binding is evaluated by {@link ChannelRouter} at the tier corresponding to the most
 * specific non-null condition field it carries (priority order):
 *
 * <ol>
 *   <li>{@link #peer} — exact peer-key match ({@code "direct:u_123"} or {@code "channel:c_help"})
 *   <li>{@link #parentPeer} — parent-peer key match (thread-parent inheritance)
 *   <li>{@link #guild} + {@link #roles} — guild membership AND at least one role matches
 *   <li>{@link #guild} alone — guild membership (no role constraint)
 *   <li>{@link #team} — team id match
 *   <li>{@link #account} — account id match
 *   <li>{@link #channel} — channel id match
 * </ol>
 *
 * <p>Within a tier, the first binding in {@link ChannelConfig#bindings()} list order that matches
 * wins. Only one condition tier is active per binding: the most specific non-null field determines
 * the tier.
 *
 * <p>The optional {@link #sessionScope} field provides a per-binding session override: when non-null
 * it takes precedence over the channel-level {@link ChannelConfig#dmScope()} for DM session key
 * construction.
 *
 * @param agentId the target agent id when this binding matches (required)
 * @param peer exact peer key to match (e.g. {@code "direct:u_42"}); highest priority
 * @param parentPeer parent-peer key for thread-parent inheritance (e.g. {@code "channel:c_ops"})
 * @param guild guild/server/workspace id to match
 * @param roles role ids to match within the guild (evaluated alongside {@code guild}); if non-empty
 *     the binding is a guild+roles tier binding; if empty it is a guild-only tier binding
 * @param team team id to match
 * @param account account id to match
 * @param channel channel id to match (lower priority; rarely needed inside channel-specific config)
 * @param sessionScope optional per-binding DM session scope override; null means inherit from
 *     {@link ChannelConfig#dmScope()}
 */
/**
 * 一条路由规则：把一组入站消息条件映射到目标 {@code agentId}。
 *
 * <p>绑定由 {@link ChannelRouter} 在其携带的最具体非空条件字段所对应的
 * 层级上评估（优先级顺序）：
 *
 * <ol>
 *   <li>{@link #peer} —— 对端键精确匹配（{@code "direct:u_123"} 或 {@code "channel:c_help"}）
 *   <li>{@link #parentPeer} —— 父对端键匹配（话题父级继承）
 *   <li>{@link #guild} + {@link #roles} —— guild 归属且至少一个角色匹配
 *   <li>仅 {@link #guild} —— guild 归属（无角色约束）
 *   <li>{@link #team} —— team ID 匹配
 *   <li>{@link #account} —— account ID 匹配
 *   <li>{@link #channel} —— channel ID 匹配
 * </ol>
 *
 * <p>同一层级内，按 {@link ChannelConfig#bindings()} 列表顺序取第一个匹配的绑定。
 * 每个绑定只有一个生效的条件层级：最具体的非空字段决定其层级。
 *
 * <p>可选的 {@link #sessionScope} 字段提供按绑定覆盖的会话作用域：
 * 非空时优先于通道级 {@link ChannelConfig#dmScope()} 参与 DM 会话键构造。
 *
 * @param agentId 本绑定命中时的目标智能体 ID（必填）
 * @param peer 要精确匹配的对端键（例如 {@code "direct:u_42"}）；优先级最高
 * @param parentPeer 用于话题父级继承的父对端键（例如 {@code "channel:c_ops"}）
 * @param guild 要匹配的 guild/服务器/工作空间 ID
 * @param roles 在该 guild 内要匹配的角色 ID 集合（与 {@code guild} 一起评估）；
 *     非空时为 guild+roles 层绑定；为空时为 guild 层绑定
 * @param team 要匹配的 team ID
 * @param account 要匹配的 account ID
 * @param channel 要匹配的 channel ID（优先级低；在通道专属配置中很少使用）
 * @param sessionScope 可选的按绑定 DM 会话作用域覆盖；null 表示继承
 *     {@link ChannelConfig#dmScope()}
 */
public record ChannelBinding(
        String agentId,
        String peer,
        String parentPeer,
        String guild,
        Set<String> roles,
        String team,
        String account,
        String channel,
        DmScope sessionScope) {

    public ChannelBinding {
        Objects.requireNonNull(agentId, "agentId");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    /** Matches an exact peer (e.g. a specific DM user or channel id). */
    /** 匹配精确对端（例如特定私聊用户或频道 ID）。 */
    public static ChannelBinding forPeer(String peer, String agentId) {
        return new ChannelBinding(agentId, peer, null, null, Set.of(), null, null, null, null);
    }

    /** Matches messages that are threads under a specific parent peer. */
    /** 匹配位于特定父对端下的话题消息。 */
    public static ChannelBinding forParentPeer(String parentPeer, String agentId) {
        return new ChannelBinding(
                agentId, null, parentPeer, null, Set.of(), null, null, null, null);
    }

    /** Matches any message from a specific guild (server/workspace). */
    /** 匹配来自特定 guild（服务器/工作空间）的任何消息。 */
    public static ChannelBinding forGuild(String guild, String agentId) {
        return new ChannelBinding(agentId, null, null, guild, Set.of(), null, null, null, null);
    }

    /** Matches messages from a guild where the sender holds at least one of the given roles. */
    /** 匹配 guild 内发送者至少持有一个指定角色的消息。 */
    public static ChannelBinding forGuildRoles(String guild, Set<String> roles, String agentId) {
        return new ChannelBinding(agentId, null, null, guild, roles, null, null, null, null);
    }

    /** Matches a specific team id. */
    /** 匹配特定 team ID。 */
    public static ChannelBinding forTeam(String team, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), team, null, null, null);
    }

    /** Matches a specific account id. */
    /** 匹配特定 account ID。 */
    public static ChannelBinding forAccount(String account, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), null, account, null, null);
    }

    /** Matches a specific channel id. */
    /** 匹配特定 channel ID。 */
    public static ChannelBinding forChannel(String channel, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), null, null, channel, null);
    }

    /** Returns a copy of this binding with a per-binding session scope override. */
    /** 返回带按绑定会话作用域覆盖的本绑定副本。 */
    public ChannelBinding withSessionScope(DmScope scope) {
        return new ChannelBinding(
                agentId, peer, parentPeer, guild, roles, team, account, channel, scope);
    }
}
