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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default single-process {@link SubagentRegistry}. Records live only for the JVM's lifetime; this
 * preserves the legacy in-memory exposure behaviour when no distributed store is configured.
 */
/**
 * 默认的单进程 {@link SubagentRegistry}。记录仅在 JVM 生命周期内存活；
 * 在未配置分布式存储时保持旧版的内存暴露行为。
 */
public final class InMemorySubagentRegistry implements SubagentRegistry {

    private final ConcurrentHashMap<String, SubagentRecord> records = new ConcurrentHashMap<>();

    /** 注册记录；记录或 subagentId 为 null 时忽略。 */
    @Override
    public void register(SubagentRecord record) {
        if (record == null || record.subagentId() == null) {
            return;
        }
        records.put(record.subagentId(), record);
    }

    /** 查找记录；命中后校验过期时间，已过期则惰性移除并视为不存在。 */
    @Override
    public Optional<SubagentRecord> find(String subagentId) {
        if (subagentId == null) {
            return Optional.empty();
        }
        SubagentRecord r = records.get(subagentId);
        if (r == null) {
            return Optional.empty();
        }
        if (r.isExpired(Instant.now())) {
            records.remove(subagentId, r);
            return Optional.empty();
        }
        return Optional.of(r);
    }

    /** 撤销单条记录；ID 为 null 时忽略。 */
    @Override
    public void revoke(String subagentId) {
        if (subagentId != null) {
            records.remove(subagentId);
        }
    }

    /** 撤销归属指定父会话的全部记录（遍历过滤）。 */
    @Override
    public void revokeByParentSession(String parentSessionId) {
        if (parentSessionId == null) {
            return;
        }
        records.values().removeIf(r -> parentSessionId.equals(r.parentSessionId()));
    }
}
