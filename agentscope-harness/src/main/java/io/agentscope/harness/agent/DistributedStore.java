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
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.team.TeamClient;
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
 *   <li>{@code agentscope-extensions-cos} — {@code CosDistributedStore}</li>
 *   <li>{@code agentscope-extensions-jdbc} — {@code JdbcDistributedStore}</li>
 *   <li>{@code agentscope-extensions-mongodb} — {@code MongoDistributedStore}</li>
 *   <li>{@code agentscope-extensions-oss} — {@code OssDistributedStore}</li>
 *   <li>{@code agentscope-extensions-redis} — {@code RedisDistributedStore}</li>
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
    /**
     * 创建用于智能体会话状态持久化的{@link AgentStateStore}实例。
     *
     * @return 分布式智能体状态存储器，返回值不可为空
     */
    AgentStateStore agentStateStore();

    /**
     * Creates the {@link BaseStore} for workspace filesystem KV storage.
     *
     * @return a distributed base store; must not be {@code null}
     */
    /**
     * 创建用于工作区文件系统键值存储的{@link BaseStore}实例。
     *
     * @return 分布式基础存储器，返回值不可为空
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
    /**
     * 创建用于沙箱快照持久化的{@link SandboxSnapshotSpec}实例。
     *
     * <p>若存储器支持适用于Docker沙箱快照的二进制大对象存储，可重写该方法。默认返回无操作快照配置类{@link NoopSnapshotSpec}，即不开启快照功能。
     *
     * @return 沙箱快照配置对象，返回值不可为空
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
    /**
     * 创建用于分布式沙箱并发控制的{@link SandboxExecutionGuard}实例。
     *
     * <p>若存储器支持分布式锁，可重写该方法。默认返回空执行守卫，不提供跨节点协同能力。
     *
     * @return 沙箱执行守卫对象，返回值不可为空
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
    /**
     * 创建{@link MessageBus}实例，用于基于收件箱的消息投递与会话事件流推送。
     *
     * <p>若存储组件支持实时传输（例如Redis发布订阅），可重写此方法。该方法默认返回null，
     * 此时框架将降级使用基于文件系统的工作区实现。
     *
     * @return 消息总线实例；若返回null，则启用工作区默认实现
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
    /**
     * 创建用于追踪异步工具执行记录的{@link AsyncToolRegistry}实例。
     *
     * <p>当存储组件支持持久化键值存储时可重写该方法。默认返回null，框架会降级采用工作区配套实现。
     *
     * @return 异步工具注册器；返回null则使用工作区默认实现
     */
    default AsyncToolRegistry asyncToolRegistry() {
        return null;
    }

    /**
     * Creates a {@link TaskRepository} for distributed subagent background tasks.
     *
     * <p>Override this when the store supports hosted task persistence (e.g. control-plane
     * {@code /api/v1/dp/tasks/*}). The default returns {@code null}, which signals HarnessAgent to
     * fall back to a workspace-backed {@code WorkspaceTaskRepository}.
     *
     * @return a task repository, or {@code null} to use the workspace default
     */
    default TaskRepository taskRepository() {
        return null;
    }

    /**
     * Creates a {@link SessionTurnGate} for distributed per-session turn serialization.
     *
     * <p>Override this when the store supports hosted locks (e.g. control-plane
     * {@code /api/v1/dp/locks/*}). The default returns {@code null}, which signals HarnessAgent to
     * use the gateway's built-in {@link io.agentscope.harness.agent.gateway.LocalSessionTurnGate}.
     *
     * <p>When a distributed turn gate is used, configure {@link
     * io.agentscope.core.ReActAgent} {@code conflictPolicy} to {@code FAIL} so concurrent state
     * writes surface as errors rather than silent overwrites.
     *
     * @return a session turn gate, or {@code null} to use the process-local default
     */
    default SessionTurnGate sessionTurnGate() {
        return null;
    }

    /**
     * Creates a {@link TeamClient} for AgentTeams coordination.
     *
     * <p>Override when the store hosts team task/message APIs (control plane) or can back a local
     * CAS client. The default returns {@code null}.
     *
     * @return a team client, or {@code null} when teams mode is unavailable
     */
    default TeamClient teamClient() {
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
    /**
     * 创建构建器，用于整合各类组件以组装{@link DistributedStore}，各组件可取自不同的存储实现。
     *
     * <p>示例：状态与文件使用MySQL，沙箱相关使用Redis
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
     * @return 全新的构建器实例
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for composing a {@link DistributedStore} from individual components.
     */
    /**
     * 用于组装各类组件、构建{@link DistributedStore}实例的构建器。
     */
    final class Builder {

        private AgentStateStore agentStateStore;
        private BaseStore baseStore;
        private SandboxSnapshotSpec sandboxSnapshotSpec;
        private SandboxExecutionGuard sandboxExecutionGuard;
        private MessageBus messageBus;
        private AsyncToolRegistry asyncToolRegistry;
        private TaskRepository taskRepository;
        private SessionTurnGate sessionTurnGate;
        private TeamClient teamClient;

        private Builder() {}

        /**
         * Sets the agent state store component.
         *
         * @param agentStateStore the state store to use
         * @return this builder
         */
        /**
         * 设置智能体状态存储组件。
         *
         * @param agentStateStore 待使用的状态存储器
         * @return 当前构建器对象
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
        /**
         * 设置用于工作区文件系统键值存储的基础存储组件。
         *
         * @param baseStore 要使用的基础存储器
         * @return 当前构建器对象
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
        /**
         * 设置沙箱快照配置。
         *
         * @param sandboxSnapshotSpec 待使用的快照配置对象
         * @return 当前构建器实例
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
        /**
         * 设置用于分布式并发控制的沙箱执行守卫。
         *
         * @param sandboxExecutionGuard 待使用的执行守卫
         * @return 当前构建器对象
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

        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        /**
         * Sets the session turn gate for distributed per-session turn serialization.
         *
         * @param sessionTurnGate the turn gate to use
         * @return this builder
         */
        public Builder sessionTurnGate(SessionTurnGate sessionTurnGate) {
            this.sessionTurnGate = sessionTurnGate;
            return this;
        }

        /** Sets the AgentTeams client. */
        public Builder teamClient(TeamClient teamClient) {
            this.teamClient = teamClient;
            return this;
        }

        /**
         * Builds the composite {@link DistributedStore}.
         *
         * @return a new distributed store composed from the configured components
         * @throws NullPointerException if agentStateStore or baseStore is not set
         */
        /**
         * 组装并生成组合式{@link DistributedStore}实例。
         *
         * @return 由已配置组件组合而成的全新分布式存储器
         * @throws NullPointerException 未配置智能体状态存储器或基础存储器时抛出空指针异常
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
                    agentStateStore,
                    baseStore,
                    snap,
                    guard,
                    messageBus,
                    asyncToolRegistry,
                    taskRepository,
                    sessionTurnGate,
                    teamClient);
        }
    }

    /**
     * A distributed store composed from individually specified components.
     */
    /**
     * 由各个指定组件组合而成的分布式存储器。
     */
    record CompositeDistributedStore(
            AgentStateStore stateStore,
            BaseStore store,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            MessageBus bus,
            AsyncToolRegistry toolRegistry,
            TaskRepository tasks,
            SessionTurnGate turnGate,
            TeamClient teams)
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

        @Override
        public TaskRepository taskRepository() {
            return tasks;
        }

        @Override
        public SessionTurnGate sessionTurnGate() {
            return turnGate;
        }

        @Override
        public TeamClient teamClient() {
            return teams;
        }
    }
}
