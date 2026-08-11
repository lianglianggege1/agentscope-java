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
package io.agentscope.extensions.mysql.snapshot;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import javax.sql.DataSource;

/**
 * Convenience {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}
 * for JDBC-backed snapshot storage.
 *
 * <p>Stores sandbox workspace tar archives as BLOBs in a database table.
 */
/**
 * 便捷化的{@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}实现，
 * 用于基于JDBC的快照存储方案。
 *
 * <p>将沙箱工作空间打包文件以二进制大对象(BLOB)形式存储在数据库表中。
 */
public class JdbcSnapshotSpec extends RemoteSnapshotSpec {

    public JdbcSnapshotSpec(DataSource dataSource) {
        super(new JdbcRemoteSnapshotClient(dataSource, true));
    }

    public JdbcSnapshotSpec(DataSource dataSource, String tableName) {
        super(new JdbcRemoteSnapshotClient(dataSource, tableName, true));
    }
}
