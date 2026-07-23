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

import java.util.Optional;

/**
 * Durable registry of exposed subagents, keyed by {@code subagentId}.
 *
 * <p>Decouples a subagent's user-facing handle from any single process: the in-memory
 * {@link HarnessGateway} keeps a live-agent cache for the fast same-node path, while this registry
 * persists the {@link SubagentRecord} recipe so that any node (or the same node after a restart)
 * can resolve the handle and re-materialize the agent.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link InMemorySubagentRegistry} — default, single-process, equivalent to the legacy
 *       behaviour.
 *   <li>{@link StoreBackedSubagentRegistry} — backed by a distributed
 *       {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore} (Redis / OSS / MySQL
 *       via {@link io.agentscope.harness.agent.DistributedStore#baseStore()}).
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 */
/**
 * 已对外暴露子智能体的持久化注册表，以 {@code subagentId} 作为键。
 *
 * <p>将子智能体面向用户的访问句柄与进程解耦：内存中的 {@link HarnessGateway}
 * 维护活跃智能体缓存，用于同节点快速访问；而该注册表持久存储 {@link SubagentRecord}
 * 配置信息，任意节点（或重启后的当前节点）均可通过句柄解析并重建智能体实例。
 *
 * <p>实现类：
 *
 * <ul>
 *   <li>{@link InMemorySubagentRegistry} — 默认实现，单进程使用，兼容旧版逻辑。
 *   <li>{@link StoreBackedSubagentRegistry} — 基于分布式
 *       {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore}（Redis / 对象存储 / MySQL，
 *       通过 {@link io.agentscope.harness.agent.DistributedStore#baseStore()} 获取）实现。
 * </ul>
 *
 * <p>所有实现必须保证线程安全。
 */
public interface SubagentRegistry {

    /**
     * Persists an exposure record. Idempotent on {@link SubagentRecord#subagentId()} — re-registering
     * the same id overwrites the previous record.
     *
     * @param record the record to persist (must carry a non-null {@code subagentId})
     */
    /**
     * 持久化一条子智能体暴露记录。以 {@link SubagentRecord#subagentId()} 实现幂等性：
     * 重复注册同一标识会覆盖原有记录。
     *
     * @param record 待持久化记录（必须携带非空 {@code subagentId}）
     */
    void register(SubagentRecord record);

    /**
     * Resolves a subagent handle. Implementations should treat an expired record (see
     * {@link SubagentRecord#isExpired}) as absent and may evict it lazily.
     *
     * @param subagentId the handle to resolve
     * @return the record, or {@link Optional#empty()} if unknown or expired
     */
    /**
     * 解析子智能体访问句柄。实现类应将过期记录（参见 {@link SubagentRecord#isExpired}）
     * 视作不存在，并可采用惰性清理策略移除过期数据。
     *
     * @param subagentId 待解析的访问句柄
     * @return 对应记录；若标识不存在或已过期则返回 {@link Optional#empty()}
     */
    Optional<SubagentRecord> find(String subagentId);

    /** Removes a single exposure record. No-op when the id is unknown. */
    /** 删除单条子智能体暴露记录。标识不存在时不执行任何操作。 */
    void revoke(String subagentId);

    /**
     * Removes all exposure records associated with the given parent session. Used to clean up when a
     * parent conversation ends. Implementations that cannot enumerate by parent may no-op.
     *
     * @param parentSessionId the parent session whose exposed subagents should be revoked
     */
    /**
     * 删除与指定父会话关联的所有暴露记录。用于父会话结束时执行资源清理。
     * 不支持按父会话检索的实现可直接空实现。
     *
     * @param parentSessionId 需要回收其下属暴露子智能体的父会话ID
     */
    default void revokeByParentSession(String parentSessionId) {
        // optional capability
    }
}
