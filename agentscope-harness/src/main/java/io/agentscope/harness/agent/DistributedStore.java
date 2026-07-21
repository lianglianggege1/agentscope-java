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
package io.agentscope.harness.agent;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * One-stop configuration for distributed and persistent storage components.
 *
 * <p>Instead of configuring {@link AgentStateStore}, {@link BaseStore},
 * {@link SandboxSnapshotSpec}, and {@link SandboxExecutionGuard} individually,
 * pass a single {@code DistributedStore} to
 * {@link HarnessAgent.Builder#distributedStore(DistributedStore)}:
 *
 * <pre>{@code
 * // Single store — all components from Redis
 * DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *
 * // Mixed stores — MySQL for state/files, Redis for sandbox lock/snapshot
 * DistributedStore store = DistributedStore.builder()
 *     .agentStateStore(mysqlStore.agentStateStore())
 *     .baseStore(mysqlStore.baseStore())
 *     .sandboxSnapshotSpec(redisStore.sandboxSnapshotSpec())
 *     .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
 *     .build();
 *
 * HarnessAgent.builder()
 *     .distributedStore(store)
 *     .filesystem(new RemoteFilesystemSpec()
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>{@code distributedStore} auto-wires {@link AgentStateStore} and sandbox components
 * (snapshot, execution guard). The workspace filesystem mode ({@code RemoteFilesystemSpec},
 * {@code DockerFilesystemSpec}, etc.) is always configured explicitly by the user via
 * {@code .filesystem(...)}.
 *
 * <p>Implementations are provided by extension modules:
 * <ul>
 *   <li>{@code agentscope-extensions-redis} — {@code RedisDistributedStore}</li>
 *   <li>{@code agentscope-extensions-oss} — {@code OssDistributedStore}</li>
 *   <li>{@code agentscope-extensions-mysql} — {@code MysqlDistributedStore}</li>
 * </ul>
 *
 * <p><b>Priority:</b> explicit builder methods ({@code .stateStore()}, {@code .filesystem()})
 * take precedence over {@code distributedStore} for the components they configure.
 */
/**
 * 分布式持久化存储组件一站式配置入口。
 *
 * <p>无需分别单独配置 {@link AgentStateStore}、{@link BaseStore}、
 * {@link SandboxSnapshotSpec}、{@link SandboxExecutionGuard}，
 * 只需传入一个统一的 {@code DistributedStore} 实例至
 * {@link HarnessAgent.Builder#distributedStore(DistributedStore)} 即可完成全套注入：
 *
 * <pre>{@code
 * // 统一存储方案：全部组件均由 Redis 提供底层存储
 * DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *
 * // 混合存储方案：MySQL 存储状态/文件数据，Redis 负责沙箱快照与沙箱执行锁控
 * DistributedStore store = DistributedStore.builder()
 *     .agentStateStore(mysqlStore.agentStateStore())
 *     .baseStore(mysqlStore.baseStore())
 *     .sandboxSnapshotSpec(redisStore.sandboxSnapshotSpec())
 *     .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
 *     .build();
 *
 * HarnessAgent.builder()
 *     .distributedStore(store)
 *     .filesystem(new RemoteFilesystemSpec()
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>传入 {@code distributedStore} 后，框架会自动装配 {@link AgentStateStore}
 * 以及沙箱相关组件（快照、执行锁控）。
 * 工作区文件系统模式（{@code RemoteFilesystemSpec}、{@code DockerFilesystemSpec} 等）
 * 必须由使用者通过 {@code .filesystem(...)} 显式指定，不会由 DistributedStore 自动注入。
 *
 * <p>该接口的实现类由对应扩展模块提供：
 * <ul>
 *   <li>{@code agentscope-extensions-redis} — 提供 {@code RedisDistributedStore}</li>
 *   <li>{@code agentscope-extensions-oss} — 提供 {@code OssDistributedStore}</li>
 *   <li>{@code agentscope-extensions-mysql} — 提供 {@code MysqlDistributedStore}</li>
 * </ul>
 *
 * <p><b>优先级规则：</b>若通过 Builder 直接调用 {@code .stateStore()}、{@code .filesystem()}
 * 这类显式配置方法，其配置优先级高于 {@code distributedStore} 中内置的同组件配置。
 */
public interface DistributedStore {

    /**
     * Creates the {@link AgentStateStore} for agent session state persistence.
     *
     * @return a distributed agent state store; must not be {@code null}
     */
    AgentStateStore agentStateStore();

    /**
     * Creates the {@link BaseStore} for workspace filesystem KV storage.
     *
     * @return a distributed base store; must not be {@code null}
     */
    BaseStore baseStore();

    /**
     * Creates the {@link SandboxSnapshotSpec} for sandbox snapshot persistence.
     *
     * <p>Override this when the store supports binary blob storage suitable for
     * Docker sandbox snapshots. The default returns {@link NoopSnapshotSpec} (no snapshots).
     *
     * @return a sandbox snapshot spec; must not be {@code null}
     */
    default SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new NoopSnapshotSpec();
    }

    /**
     * Creates the {@link SandboxExecutionGuard} for distributed sandbox concurrency control.
     *
     * <p>Override this when the store supports distributed locking. The default returns
     * a no-op guard (no cross-node coordination).
     *
     * @return a sandbox execution guard; must not be {@code null}
     */
    default SandboxExecutionGuard sandboxExecutionGuard() {
        return SandboxExecutionGuard.noop();
    }

    /**
     * Creates a {@link MessageBus} for inbox-based message delivery and session event streaming.
     *
     * <p>Override this when the store supports real-time transport (e.g. Redis Pub/Sub). The
     * default returns {@code null}, which signals HarnessAgent to fall back to a workspace-backed
     * implementation created from the resolved {@code AbstractFilesystem}.
     *
     * @return a message bus, or {@code null} to use the workspace default
     */
    default MessageBus messageBus() {
        return null;
    }

    /**
     * Creates an {@link AsyncToolRegistry} for tracking async tool executions.
     *
     * <p>Override this when the store supports persistent key-value storage. The default returns
     * {@code null}, which signals HarnessAgent to fall back to a workspace-backed implementation.
     *
     * @return an async tool registry, or {@code null} to use the workspace default
     */
    default AsyncToolRegistry asyncToolRegistry() {
        return null;
    }

    /**
     * Creates a builder for composing a {@link DistributedStore} from individual components,
     * potentially sourced from different store implementations.
     *
     * <p>Example — MySQL for state and files, Redis for sandbox:
     * <pre>{@code
     * DistributedStore mysql = MysqlDistributedStore.create(dataSource);
     * DistributedStore redis = RedisDistributedStore.fromJedis(jedis);
     *
     * DistributedStore mixed = DistributedStore.builder()
     *     .agentStateStore(mysql.agentStateStore())
     *     .baseStore(mysql.baseStore())
     *     .sandboxSnapshotSpec(redis.sandboxSnapshotSpec())
     *     .sandboxExecutionGuard(redis.sandboxExecutionGuard())
     *     .build();
     * }</pre>
     *
     * @return a new builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for composing a {@link DistributedStore} from individual components.
     */
    final class Builder {

        private AgentStateStore agentStateStore;
        private BaseStore baseStore;
        private SandboxSnapshotSpec sandboxSnapshotSpec;
        private SandboxExecutionGuard sandboxExecutionGuard;
        private MessageBus messageBus;
        private AsyncToolRegistry asyncToolRegistry;

        private Builder() {}

        /**
         * Sets the agent state store component.
         *
         * @param agentStateStore the state store to use
         * @return this builder
         */
        public Builder agentStateStore(AgentStateStore agentStateStore) {
            this.agentStateStore = agentStateStore;
            return this;
        }

        /**
         * Sets the base store component for workspace filesystem KV.
         *
         * @param baseStore the base store to use
         * @return this builder
         */
        public Builder baseStore(BaseStore baseStore) {
            this.baseStore = baseStore;
            return this;
        }

        /**
         * Sets the sandbox snapshot spec.
         *
         * @param sandboxSnapshotSpec the snapshot spec to use
         * @return this builder
         */
        public Builder sandboxSnapshotSpec(SandboxSnapshotSpec sandboxSnapshotSpec) {
            this.sandboxSnapshotSpec = sandboxSnapshotSpec;
            return this;
        }

        /**
         * Sets the sandbox execution guard for distributed concurrency control.
         *
         * @param sandboxExecutionGuard the execution guard to use
         * @return this builder
         */
        public Builder sandboxExecutionGuard(SandboxExecutionGuard sandboxExecutionGuard) {
            this.sandboxExecutionGuard = sandboxExecutionGuard;
            return this;
        }

        public Builder messageBus(MessageBus messageBus) {
            this.messageBus = messageBus;
            return this;
        }

        public Builder asyncToolRegistry(AsyncToolRegistry asyncToolRegistry) {
            this.asyncToolRegistry = asyncToolRegistry;
            return this;
        }

        /**
         * Builds the composite {@link DistributedStore}.
         *
         * @return a new distributed store composed from the configured components
         * @throws NullPointerException if agentStateStore or baseStore is not set
         */
        public DistributedStore build() {
            Objects.requireNonNull(agentStateStore, "agentStateStore is required");
            Objects.requireNonNull(baseStore, "baseStore is required");
            SandboxSnapshotSpec snap =
                    sandboxSnapshotSpec != null ? sandboxSnapshotSpec : new NoopSnapshotSpec();
            SandboxExecutionGuard guard =
                    sandboxExecutionGuard != null
                            ? sandboxExecutionGuard
                            : SandboxExecutionGuard.noop();
            return new CompositeDistributedStore(
                    agentStateStore, baseStore, snap, guard, messageBus, asyncToolRegistry);
        }
    }

    /**
     * A distributed store composed from individually specified components.
     */
    record CompositeDistributedStore(
            AgentStateStore stateStore,
            BaseStore store,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            MessageBus bus,
            AsyncToolRegistry toolRegistry)
            implements DistributedStore {

        @Override
        public AgentStateStore agentStateStore() {
            return stateStore;
        }

        @Override
        public BaseStore baseStore() {
            return store;
        }

        @Override
        public SandboxSnapshotSpec sandboxSnapshotSpec() {
            return snapshotSpec;
        }

        @Override
        public SandboxExecutionGuard sandboxExecutionGuard() {
            return executionGuard;
        }

        @Override
        public MessageBus messageBus() {
            return bus;
        }

        @Override
        public AsyncToolRegistry asyncToolRegistry() {
            return toolRegistry;
        }
    }
}
