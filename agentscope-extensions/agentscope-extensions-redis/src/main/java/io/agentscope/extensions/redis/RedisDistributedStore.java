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
package io.agentscope.extensions.redis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.extensions.redis.snapshot.RedisSnapshotSpec;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.store.RedisStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis-backed {@link DistributedStore} that configures all distributed storage components
 * with a single Jedis connection.
 *
 * <p>Usage:
 * <pre>{@code
 * JedisPooled jedis = new JedisPooled("redis://localhost:6379");
 * RedisDistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(store)
 *     .filesystem(new RemoteFilesystemSpec(store.baseStore())
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>This provides:
 * <ul>
 *   <li>{@link RedisAgentStateStore} — agent session state (auto-wired by distributedStore)</li>
 *   <li>{@link RedisStore} — workspace filesystem KV (via {@link #baseStore()})</li>
 *   <li>{@link RedisSnapshotSpec} — sandbox snapshot storage (auto-wired for sandbox mode)</li>
 *   <li>{@link RedisSandboxExecutionGuard} — sandbox concurrency lock (auto-wired for sandbox mode)</li>
 * </ul>
 */
/**
 * 基于Redis实现的{@link DistributedStore}，通过单个Jedis连接统一配置全部分布式存储组件。
 *
 * <p>使用示例：
 * <pre>{@code
 * JedisPooled jedis = new JedisPooled("redis://localhost:6379");
 * RedisDistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(store)
 *     .filesystem(new RemoteFilesystemSpec(store.baseStore())
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>该组件提供以下能力：
 * <ul>
 *   <li>{@link RedisAgentStateStore} — 智能体会话状态（由distributedStore自动装配）</li>
 *   <li>{@link RedisStore} — 工作区文件系统键值存储（通过{@link #baseStore()}获取）</li>
 *   <li>{@link RedisSnapshotSpec} — 沙箱快照存储（沙箱模式下自动装配）</li>
 *   <li>{@link RedisSandboxExecutionGuard} — 沙箱并发锁（沙箱模式下自动装配）</li>
 * </ul>
 */
public class RedisDistributedStore implements DistributedStore {

    private final UnifiedJedis jedis;
    private final String keyPrefix;

    private RedisDistributedStore(UnifiedJedis jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope:";
    }

    /**
     * Creates a Redis distributed store from a Jedis client with the default key prefix.
     *
     * @param jedis initialized Jedis client (e.g. {@code new JedisPooled("redis://localhost:6379")})
     * @return a new Redis distributed store
     */
    /**
     * 使用默认键前缀，基于Jedis客户端创建Redis分布式存储实例。
     *
     * @param jedis 已初始化的Jedis客户端（例如：{@code new JedisPooled("redis://localhost:6379")}）
     * @return 全新的Redis分布式存储对象
     */
    public static RedisDistributedStore fromJedis(UnifiedJedis jedis) {
        return new RedisDistributedStore(jedis, null);
    }

    /**
     * Creates a Redis distributed store from a Jedis client with a custom key prefix.
     *
     * @param jedis initialized Jedis client
     * @param keyPrefix prefix for all Redis keys (e.g. {@code "myapp:"})
     * @return a new Redis distributed store
     */
    /**
     * 使用自定义键前缀，基于Jedis客户端创建Redis分布式存储实例。
     *
     * @param jedis 已初始化的Jedis客户端
     * @param keyPrefix 全部Redis键的前缀（例如：{@code "myapp:"}）
     * @return 全新的Redis分布式存储对象
     */
    public static RedisDistributedStore fromJedis(UnifiedJedis jedis, String keyPrefix) {
        return new RedisDistributedStore(jedis, keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return RedisAgentStateStore.builder()
                .jedisClient(jedis)
                .keyPrefix(keyPrefix + "session:")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return new RedisStore(jedis, keyPrefix + "store:");
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new RedisSnapshotSpec(jedis, keyPrefix + "snapshot:", null);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return RedisSandboxExecutionGuard.builder(jedis).keyPrefix(keyPrefix + "guard:").build();
    }
}
