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
package io.agentscope.extensions.mysql;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * MySQL/JDBC-backed {@link DistributedStore}.
 *
 * <p>Usage:
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP, Druid, etc.
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(MysqlDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>This configures:
 * <ul>
 *   <li>{@link MysqlAgentStateStore} — agent session state in MySQL</li>
 *   <li>{@link JdbcStore} — workspace filesystem KV in MySQL</li>
 *   <li>{@link JdbcSnapshotSpec} — sandbox snapshots as BLOBs in MySQL</li>
 *   <li>{@link JdbcSandboxExecutionGuard} — distributed lock via MySQL {@code GET_LOCK()}</li>
 * </ul>
 *
 * @deprecated Use {@code io.agentscope.extensions.jdbc.JdbcDistributedStore} from the
 * {@code agentscope-extensions-jdbc} module instead. The new module provides a unified
 * multi-database dialect abstraction that supports MySQL, PostgreSQL, H2, and SQLite
 * with a single aggregated interface. This class is preserved unchanged for backward
 * compatibility and will be removed in a future major release.
 */
@Deprecated(since = "2.1", forRemoval = true)
/**
 * 基于MySQL/JDBC实现的{@link DistributedStore}。
 *
 * <p>使用示例：
 * <pre>{@code
 * DataSource dataSource = ... // 数据源，可选用HikariCP、Druid等连接池
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(MysqlDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>该配置包含以下组件：
 * <ul>
 *   <li>{@link MysqlAgentStateStore} — 用于在MySQL中持久化智能体会话状态</li>
 *   <li>{@link JdbcStore} — 在MySQL中存储工作区文件系统键值对数据</li>
 *   <li>{@link JdbcSnapshotSpec} — 将沙箱快照以二进制大对象(BLOB)形式存入MySQL</li>
 *   <li>{@link JdbcSandboxExecutionGuard} — 依托MySQL内置{@code GET_LOCK()}函数实现分布式锁</li>
 * </ul>
 */
public class MysqlDistributedStore implements DistributedStore {

    private final DataSource dataSource;

    private MysqlDistributedStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Creates a MySQL distributed store.
     *
     * @param dataSource JDBC data source for MySQL
     * @return a new MySQL distributed store
     */
    /**
     * 创建MySQL分布式存储实例。
     *
     * @param dataSource MySQL对应的JDBC数据源
     * @return 全新MySQL分布式存储对象
     */
    public static MysqlDistributedStore create(DataSource dataSource) {
        return new MysqlDistributedStore(dataSource);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new MysqlAgentStateStore(dataSource, true);
    }

    @Override
    public BaseStore baseStore() {
        return JdbcStore.builder(dataSource).initializeSchema(true).build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new JdbcSnapshotSpec(dataSource);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return JdbcSandboxExecutionGuard.builder(dataSource).build();
    }
}
