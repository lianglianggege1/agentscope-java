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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable, recovery-oriented descriptor of an exposed subagent. Unlike the in-memory
 * {@code ExposedSession} (which holds a live {@code Agent} reference), a {@code SubagentRecord}
 * stores only the <em>recipe</em> needed to re-materialize and route to the subagent on any node:
 * its type ({@code agentId}), its own conversational session ({@code sessionId}), and lifecycle
 * metadata.
 *
 * <p>The {@code agentId} feeds the agent factory to rebuild the instance; the {@code sessionId} is
 * passed at invocation time so the rebuilt agent loads the correct conversation history from a
 * (distributed) {@code AgentStateStore}.
 *
 * @param subagentId the user-visible handle for direct addressing
 * @param agentId the subagent type identifier (used to re-materialize the agent)
 * @param sessionId the subagent's own session id (used to load its conversational state)
 * @param userId the originating user id, or {@code null} when unknown
 * @param parentSessionId the parent session that exposed this subagent, or {@code null}
 * @param createdAt when the subagent was exposed
 * @param expiresAt optional expiry instant; {@code null} means no TTL
 */
/**
 * 用于持久化、支持故障恢复的已暴露子智能体描述信息。
 * 和内存中的 {@code ExposedSession}（持有活跃 {@code Agent} 引用）不同，
 * {@code SubagentRecord} 仅保存跨节点重建、路由子智能体所需的配置信息：
 * 智能体类型（{@code agentId}）、专属会话标识（{@code sessionId}）以及生命周期元数据。
 *
 * <p>{@code agentId} 提供给智能体工厂重建实例；调用时传入 {@code sessionId}，
 * 重建后的智能体即可从分布式 {@code AgentStateStore} 加载对应的对话历史。
 *
 * @param subagentId 用户可见标识，用于直接寻址该子智能体
 * @param agentId 子智能体类型标识（用于重建智能体实例）
 * @param sessionId 子智能体自身会话ID（用于加载对话状态）
 * @param userId 原始用户ID，未知时为 {@code null}
 * @param parentSessionId 对外暴露该子智能体的父会话ID，可为 {@code null}
 * @param createdAt 子智能体开启暴露的时间
 * @param expiresAt 可选过期时刻；{@code null} 代表无生命周期限制
 */
public record SubagentRecord(
        String subagentId,
        String agentId,
        String sessionId,
        String userId,
        String parentSessionId,
        Instant createdAt,
        Instant expiresAt) {

    /** Whether this record has a TTL that has already elapsed relative to {@code now}. */
    /** 判断该记录是否配置存活时长，且当前时间 {@code now} 已超过过期时刻。 */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now != null && now.isAfter(expiresAt);
    }

    /** Serializes this record to a flat string/number map for {@code BaseStore} persistence. */
    /** 将当前记录序列化为扁平字符串/数值映射，用于 {@code BaseStore} 持久化存储。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("subagentId", subagentId);
        m.put("agentId", agentId);
        m.put("sessionId", sessionId);
        if (userId != null) {
            m.put("userId", userId);
        }
        if (parentSessionId != null) {
            m.put("parentSessionId", parentSessionId);
        }
        if (createdAt != null) {
            m.put("createdAt", createdAt.toEpochMilli());
        }
        if (expiresAt != null) {
            m.put("expiresAt", expiresAt.toEpochMilli());
        }
        return m;
    }

    /** Reconstructs a record from a persisted map, tolerating missing optional fields. */
    /** 从持久化映射数据重建记录，兼容可选字段缺失的场景。 */
    public static SubagentRecord fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new SubagentRecord(
                asString(m.get("subagentId")),
                asString(m.get("agentId")),
                asString(m.get("sessionId")),
                asString(m.get("userId")),
                asString(m.get("parentSessionId")),
                asInstant(m.get("createdAt")),
                asInstant(m.get("expiresAt")));
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private static Instant asInstant(Object v) {
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
