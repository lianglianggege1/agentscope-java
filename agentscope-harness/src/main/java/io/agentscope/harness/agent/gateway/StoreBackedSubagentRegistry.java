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

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed {@link SubagentRegistry} backed by a {@link BaseStore}. Because the store is supplied
 * by a {@link io.agentscope.harness.agent.DistributedStore} (Redis / OSS / MySQL), exposure
 * records become visible to every node, so any replica can resolve a {@code subagentId} and
 * re-materialize the subagent.
 *
 * <p>Records are stored under the namespace {@code ["subagents", "exposed"]} keyed by
 * {@code subagentId}. TTL is enforced lazily on {@link #find}: an elapsed record is deleted and
 * reported as absent.
 *
 * <p>This registry deliberately stores only routing/identity metadata. Concurrency safety for the
 * subagent's mutable conversation state is the responsibility of the distributed
 * {@link io.agentscope.core.state.AgentStateStore} (and {@link BaseStore#putIfVersion} where
 * explicit optimistic guarding is desired), not of this registry.
 */
/**
 * 基于 {@link BaseStore} 的分布式 {@link SubagentRegistry}。由于存储由
 * {@link io.agentscope.harness.agent.DistributedStore}（Redis / 对象存储 / MySQL）提供，
 * 暴露记录对所有节点可见，任意副本都能解析 {@code subagentId} 并重建子智能体。
 *
 * <p>记录存储在命名空间 {@code ["subagents", "exposed"]} 下，以
 * {@code subagentId} 为键。TTL 在 {@link #find} 时惰性执行：
 * 已过期的记录会被删除并按不存在处理。
 *
 * <p>本注册表有意只存储路由/身份元数据。子智能体可变会话状态的并发安全
 * 由分布式 {@link io.agentscope.core.state.AgentStateStore} 负责
 * （需要显式乐观锁时可用 {@link BaseStore#putIfVersion}），不属于本注册表的职责。
 */
public final class StoreBackedSubagentRegistry implements SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(StoreBackedSubagentRegistry.class);

    /** 记录存储命名空间：["subagents", "exposed"]。 */
    private static final List<String> NAMESPACE = List.of("subagents", "exposed");
    /** 按父会话撤销时扫描存储的分页大小。 */
    private static final int SCAN_PAGE_SIZE = 1000;

    private final BaseStore store;

    public StoreBackedSubagentRegistry(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** 持久化记录到存储；写入失败仅告警不抛出。 */
    @Override
    public void register(SubagentRecord record) {
        if (record == null || record.subagentId() == null) {
            return;
        }
        try {
            store.put(NAMESPACE, record.subagentId(), record.toMap());
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to persist exposed-subagent record {}: {}",
                    record.subagentId(),
                    e.getMessage());
        }
    }

    /**
     * 从存储读取记录并反序列化；读取失败或记录不存在返回空；
     * 已过期的记录立即撤销并视为不存在。
     */
    @Override
    public Optional<SubagentRecord> find(String subagentId) {
        if (subagentId == null) {
            return Optional.empty();
        }
        StoreItem item;
        try {
            item = store.get(NAMESPACE, subagentId);
        } catch (RuntimeException e) {
            log.warn("Failed to read exposed-subagent record {}: {}", subagentId, e.getMessage());
            return Optional.empty();
        }
        if (item == null || item.value() == null) {
            return Optional.empty();
        }
        SubagentRecord record = SubagentRecord.fromMap(item.value());
        if (record == null) {
            return Optional.empty();
        }
        if (record.isExpired(Instant.now())) {
            revoke(subagentId);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /** 从存储删除单条记录；删除失败仅告警不抛出。 */
    @Override
    public void revoke(String subagentId) {
        if (subagentId == null) {
            return;
        }
        try {
            store.delete(NAMESPACE, subagentId);
        } catch (RuntimeException e) {
            log.warn("Failed to revoke exposed-subagent record {}: {}", subagentId, e.getMessage());
        }
    }

    /**
     * 按父会话撤销：存储不支持按父键检索，这里扫描命名空间首页条目，
     * 逐条反序列化后筛出归属该父会话的记录并撤销。
     */
    @Override
    public void revokeByParentSession(String parentSessionId) {
        if (parentSessionId == null) {
            return;
        }
        try {
            List<StoreItem> items = store.search(NAMESPACE, SCAN_PAGE_SIZE, 0);
            if (items == null) {
                return;
            }
            for (StoreItem item : items) {
                SubagentRecord r = SubagentRecord.fromMap(item.value());
                if (r != null && parentSessionId.equals(r.parentSessionId())) {
                    revoke(r.subagentId());
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to revoke exposed subagents for parent session {}: {}",
                    parentSessionId,
                    e.getMessage());
        }
    }
}
