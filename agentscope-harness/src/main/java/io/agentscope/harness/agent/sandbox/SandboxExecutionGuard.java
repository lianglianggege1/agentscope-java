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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.harness.agent.IsolationScope;

/**
 * Pluggable concurrency guard for sandbox execution slots.
 *
 * <p>A guard controls how many concurrent executions are allowed for a given
 * {@link SandboxIsolationKey}. The default {@link #noop()} imposes no restriction, preserving
 * existing behaviour.
 *
 * <p>This extension point is primarily useful for {@link IsolationScope#USER},
 * {@link IsolationScope#AGENT} and {@link IsolationScope#GLOBAL} scopes, where multiple
 * concurrent callers could otherwise race on the same persistent state slot (last write wins).
 * Providing a guard serialises such callers without requiring changes to the surrounding
 * infrastructure.
 *
 * <p>Implementations may use any backend — JVM semaphores, Redis {@code SET NX} leases,
 * ZooKeeper, database advisory locks, etc. — and must be thread-safe.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * SandboxExecutionGuard guard = key -> {
 *     redisClient.set(key.toString(), token, SetArgs.Builder.nx().px(30_000));
 *     return () -> redisClient.eval(LUA_RELEASE_SCRIPT, key.toString(), token);
 * };
 *
 * HarnessAgent.builder()
 *     .filesystem(new DockerFilesystemSpec()
 *         .isolationScope(IsolationScope.AGENT)
 *         .executionGuard(guard))
 *     ...
 *     .build();
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The harness calls {@link #tryEnter} before sandbox acquire/resume and closes the returned
 * {@link SandboxLease} after {@link SandboxManager#release} completes, so the guard covers the
 * full call window: {@code acquire → start → (call) → stop → release → lease.close()}.
 */
/**
 * 可插拔的沙箱执行槽并发隔离器。
 *
 * <p>隔离器用于控制同一 {@link SandboxIsolationKey} 允许的并发执行数量。默认无实现 {@link #noop()} 不做任何并发限制，保持原有执行逻辑。
 *
 * <p>该扩展点主要适用于 {@link IsolationScope#USER}、{@link IsolationScope#AGENT}、{@link IsolationScope#GLOBAL} 隔离维度；
 * 这类场景下多并发调用会争抢同一份持久化状态（存在后写入覆盖先写入的问题）。接入隔离器即可串行化并发请求，无需改动上层基础设施。
 *
 * <p>实现层可选用任意存储后端：JVM信号量、Redis {@code SET NX} 租约、ZooKeeper、数据库排它锁等，且必须保证线程安全。
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * SandboxExecutionGuard guard = key -> {
 *     redisClient.set(key.toString(), token, SetArgs.Builder.nx().px(30_000));
 *     return () -> redisClient.eval(LUA_RELEASE_SCRIPT, key.toString(), token);
 * };
 *
 * HarnessAgent.builder()
 *     .filesystem(new DockerFilesystemSpec()
 *         .isolationScope(IsolationScope.AGENT)
 *         .executionGuard(guard))
 *     ...
 *     .build();
 * }</pre>
 *
 * <h2>生命周期流程</h2>
 *
 * <p>执行器在获取/恢复沙箱前调用 {@link #tryEnter}；在 {@link SandboxManager#release} 执行完成后关闭返回的 {@link SandboxLease}。
 * 隔离锁完整覆盖整个调用生命周期：{@code acquire → start → (业务调用) → stop → release → lease.close()}。
 */
@FunctionalInterface
public interface SandboxExecutionGuard {

    /**
     * Acquires the execution right for the given isolation key, blocking until the slot becomes
     * available or the calling thread is interrupted.
     *
     * <p>The returned {@link SandboxLease} must be closed to release the slot. The harness handles
     * this automatically; callers do not need to close the lease explicitly.
     *
     * @param key the isolation key that identifies the sandbox slot to protect
     * @return a lease that releases the execution right when closed
     * @throws InterruptedException if interrupted while waiting for the slot
     */
    SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException;

    /**
     * Returns the default no-op guard: execution is always allowed immediately and the returned
     * {@link SandboxLease} is a no-op. This is the built-in default — no configuration required.
     */
    static SandboxExecutionGuard noop() {
        return NoopSandboxExecutionGuard.INSTANCE;
    }

    /** Singleton no-op implementation. */
    final class NoopSandboxExecutionGuard implements SandboxExecutionGuard {

        static final NoopSandboxExecutionGuard INSTANCE = new NoopSandboxExecutionGuard();

        private NoopSandboxExecutionGuard() {}

        @Override
        public SandboxLease tryEnter(SandboxIsolationKey key) {
            return SandboxLease.noop();
        }
    }
}
