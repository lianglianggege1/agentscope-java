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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of {@link Sandbox} instances for the current call.
 *
 * <p>Acquire priority: {@link SandboxContext#getExternalSandbox()} &gt; {@link
 * SandboxContext#getExternalSandboxState()} &gt; persisted {@link SandboxState} &gt; {@link
 * SandboxClient#create}. When a persisted state exists but the resume fails, the fresh-create
 * fallback keeps the persisted snapshot id so the workspace restores from the previous archive
 * instead of silently resetting.
 *
 * <p>When a {@link SandboxExecutionGuard} is configured, the manager acquires an execution
 * {@link SandboxLease} before sandbox resume/create for isolation keys that are present. The
 * lease is carried by the {@link SandboxAcquireResult} and closed by the caller
 * ({@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}) after {@link #release},
 * ensuring the full call window is covered.
 *
 * <p>Priority 1 (external sandbox) and Priority 2 (external sandbox state) bypass the guard,
 * since the caller is managing that sandbox externally.
 */
/**
 * 管理单次调用过程中 {@link Sandbox} 实例的完整生命周期。
 *
 * <p>沙箱获取优先级：{@link SandboxContext#getExternalSandbox()} > {@link
 * SandboxContext#getExternalSandboxState()} > 持久化存储的 {@link SandboxState} > {@link
 * SandboxClient#create}。
 *
 * <p>若已配置 {@link SandboxExecutionGuard}，针对存在隔离标识的场景，管理器会在恢复/新建沙箱前获取执行租约 {@link SandboxLease}。
 * 租约会随 {@link SandboxAcquireResult} 一并返回，并由调用方（{@link io.agentscope.harness.agent.hook.SandboxLifecycleHook}）
 * 在执行 {@link #release} 后释放，保证整个调用周期均持有隔离锁。
 *
 * <p>优先级1（外部传入沙箱）与优先级2（外部沙箱状态）会跳过隔离校验器逻辑，这类沙箱由调用方外部自行管控。
 */
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    /** Sandbox client used to resume from state or create new sandboxes. */
    /**
     * 沙箱客户端，用于从状态恢复沙箱或新建沙箱。
     */
    private final SandboxClient<?> client;

    /** Per-session store for serialized {@link SandboxState}; null when stateless. */
    /**
     * 按会话维度的 {@link SandboxState} 序列化存储；可能为 null（无状态场景）。
     */
    private final SessionSandboxStateStore stateStore;

    /** Owning agent id; used as a fallback isolation-key component when no session is present. */
    /**
     * 所属代理的 id；当没有会话信息时，用作隔离 key 的兜底维度。
     */
    private final String agentId;

    /** Execution guard used to serialize sandbox work for a given isolation scope. */
    /**
     * 沙箱执行隔离器，用于在同一隔离维度上串行化沙箱相关工作。
     */
    private final SandboxExecutionGuard executionGuard;

    /**
     * Construct a SandboxManager with no execution guard.
     * @param client sandbox client
     * @param stateStore per-session state store
     * @param agentId owning agent id
     */
    /**
     * 构造一个不带执行隔离器的 SandboxManager。
     *
     * @param client 沙箱客户端
     * @param stateStore 按会话维度的状态存储
     * @param agentId 所属代理 id
     */
    public SandboxManager(
            SandboxClient<?> client, SessionSandboxStateStore stateStore, String agentId) {
        this(client, stateStore, agentId, SandboxExecutionGuard.noop());
    }

    /**
     * Construct a SandboxManager with a custom execution guard.
     * @param client sandbox client
     * @param stateStore per-session state store
     * @param agentId owning agent id
     * @param executionGuard execution guard (null falls back to a no-op)
     */
    /**
     * 构造一个带自定义执行隔离器的 SandboxManager。
     *
     * @param client 沙箱客户端
     * @param stateStore 按会话维度的状态存储
     * @param agentId 所属代理 id
     * @param executionGuard 执行隔离器（传入 null 时回退到 no-op）
     */
    public SandboxManager(
            SandboxClient<?> client,
            SessionSandboxStateStore stateStore,
            String agentId,
            SandboxExecutionGuard executionGuard) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.executionGuard =
                executionGuard != null ? executionGuard : SandboxExecutionGuard.noop();
    }

    /**
     * Acquire (or reuse) a {@link Sandbox} for the current call, honoring the priority chain
     * documented at class level.
     *
     * <p>For Priorities 3/4 a {@link SandboxLease} is taken from {@link SandboxExecutionGuard}
     * before sandbox resume/create when an isolation scope is resolvable. The lease is bundled
     * into the returned {@link SandboxAcquireResult} and must be released by the caller after
     * {@link #release} runs, so the entire call window stays guarded.
     *
     * @param sandboxContext per-call sandbox configuration (may carry an external sandbox/state)
     * @param runtimeContext per-call runtime context used to derive the isolation scope
     * @return acquisition result containing the sandbox and (for harness-managed paths) the lease
     * @throws Exception if sandbox client operations or guard entry fail
     */
    /**
     * 为本次调用获取（或复用）一个 {@link Sandbox}，遵循类级 javadoc 中描述的优先级链。
     *
     * <p>对于优先级 3/4，当存在可解析的隔离维度时，会在恢复/新建沙箱前向 {@link SandboxExecutionGuard}
     * 申请一个 {@link SandboxLease}。租约会被打包进返回的 {@link SandboxAcquireResult}，
     * 调用方需在 {@link #release} 之后释放，从而保证整个调用周期均处于隔离保护下。
     *
     * @param sandboxContext 本次调用的沙箱配置（可携带外部沙箱/外部状态）
     * @param runtimeContext 本次调用的运行时上下文，用于推导隔离维度
     * @return 包含沙箱实例（以及 harness 托管路径下的租约）的获取结果
     * @throws Exception 沙箱客户端操作或隔离器申请失败时抛出
     */
    public SandboxAcquireResult acquire(
            SandboxContext sandboxContext, RuntimeContext runtimeContext) throws Exception {
        // 优先级 1：调用方已自带 sandbox，绕开隔离器（外部资源由调用方自行管理）
        if (sandboxContext.getExternalSandbox() != null) {
            Sandbox external = sandboxContext.getExternalSandbox();
            log.debug(
                    "[sandbox] Priority 1: using user-managed sandbox: {}",
                    external.getState() != null ? external.getState().getSessionId() : "?");
            return SandboxAcquireResult.userManaged(external);
        }

        // 优先级 2：调用方提供外部状态，按该状态恢复沙箱，同样绕开隔离器
        if (sandboxContext.getExternalSandboxState() != null) {
            Sandbox sandbox = client.resume(sandboxContext.getExternalSandboxState());
            log.debug(
                    "[sandbox] Priority 2: resuming from explicit state: {}",
                    sandboxContext.getExternalSandboxState().getSessionId());
            return SandboxAcquireResult.selfManaged(sandbox);
        }

        // 优先级 3/4：harness 托管路径；存在隔离维度时先申请隔离器租约
        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext.getIsolationScope(), runtimeContext, agentId);

        SandboxLease lease = SandboxLease.noop();
        if (scopeKey.isPresent()) {
            log.debug("[sandbox] Acquiring execution guard for scope {}", scopeKey.get());
            // 进入隔离区：保证同一 scope 下并发调用串行化访问底层沙箱
            lease = executionGuard.tryEnter(scopeKey.get());
        }

        try {
            String persistedSnapshotId = null;
            // 优先级 3：尝试从持久化状态恢复（仅在有 scopeKey 时才查询 store）
            if (scopeKey.isPresent()) {
                try {
                    Optional<String> stateJson = stateStore.load(scopeKey.get());
                    if (stateJson.isPresent()) {
                        log.debug(
                                "[sandbox] Priority 3: resuming from persisted state (scope={})",
                                scopeKey.get());
                        SandboxState state =
                                client.deserializeState(
                                        stateJson.get(), sandboxContext.getSnapshotSpec());
                        // Overwrite stale WorkspaceSpec with current application config
                        if (sandboxContext.getWorkspaceSpec() != null) {
                            state.setWorkspaceSpec(sandboxContext.getWorkspaceSpec().copy());
                        }
                        if (state.getSnapshot() != null) {
                            persistedSnapshotId = state.getSnapshot().getId();
                        }
                        Sandbox sandbox = client.resume(state);
                        return SandboxAcquireResult.selfManaged(sandbox, lease);
                    }
                } catch (Exception e) {
                    // 读取历史状态失败时降级为新建沙箱，避免阻断调用
                    log.warn(
                            "[sandbox] Failed to load persisted state for scope {}, falling through"
                                    + " to fresh create: {}",
                            scopeKey.get(),
                            e.getMessage(),
                            e);
                }
            }

            // 优先级 4：新建沙箱
            log.debug("[sandbox] Priority 4: creating new sandbox");
            // 用户未指定工作区规格时使用默认值，避免传 null 到客户端
            WorkspaceSpec spec =
                    sandboxContext.getWorkspaceSpec() != null
                            ? sandboxContext.getWorkspaceSpec().copy()
                            : new WorkspaceSpec();

            // 创建路径需要 SandboxClient<SandboxClientOptions> 的具体类型签名；
            // 这里 cast 是安全的（acquire 始终由框架自身驱动，client 实际就是该类型）
            @SuppressWarnings("unchecked")
            SandboxClient<SandboxClientOptions> typedClient =
                    (SandboxClient<SandboxClientOptions>) client;
            Sandbox sandbox =
                    typedClient.create(
                            spec,
                            sandboxContext.getSnapshotSpec(),
                            sandboxContext.getClientOptions());
            carryOverPersistedSnapshotId(
                    sandbox, persistedSnapshotId, sandboxContext.getSnapshotSpec());
            return SandboxAcquireResult.selfManaged(sandbox, lease);

        } catch (Exception e) {
            // 获取失败时显式释放租约：调用方不会看到 result，租约需要在这里兜底关闭
            lease.close();
            throw e;
        }
    }

    /**
     * Keeps workspace continuity when a failed resume falls through to a fresh create: points
     * the new sandbox at the snapshot id persisted for this scope, so {@link Sandbox#start()}
     * restores the workspace from the previous archive instead of silently resetting it, and
     * {@link Sandbox#stop()} overwrites that same archive instead of orphaning it. The snapshot
     * is rebuilt from the current spec, so it carries a bound client regardless of how the
     * persisted state deserialized.
     */
    private static void carryOverPersistedSnapshotId(
            Sandbox sandbox, String persistedSnapshotId, SandboxSnapshotSpec snapshotSpec) {
        SandboxState state = sandbox.getState();
        if (persistedSnapshotId == null
                || persistedSnapshotId.isBlank()
                || snapshotSpec == null
                || state == null
                || state.getSnapshot() == null
                || persistedSnapshotId.equals(state.getSnapshot().getId())) {
            return;
        }
        log.info(
                "[sandbox] Resume fell back to fresh create; carrying over snapshot id {} (fresh"
                        + " id was {})",
                persistedSnapshotId,
                state.getSnapshot().getId());
        state.setSnapshot(snapshotSpec.build(persistedSnapshotId));
    }

    /**
     * Release a previously acquired sandbox.
     *
     * <p>For user-managed sandboxes (Priority 1) this is a no-op: the harness does not own the
     * lifecycle and must not stop/shutdown the externally supplied container. Only sandbox owned
     * by the harness (Priorities 2/3/4) is stopped and shutdown.
     *
     * <p>Failures from {@link Sandbox#stop()} or {@link Sandbox#shutdown()} are logged but do
     * not propagate, so a single misbehaving sandbox cannot break the call teardown.
     *
     * @param result the acquisition result returned by {@link #acquire}; null is a no-op
     */
    /**
     * 释放先前获取到的沙箱。
     *
     * <p>对于用户托管的沙箱（优先级 1）该方法为 no-op：harness 不持有其生命周期，不得
     * stop/shutdown 外部传入的容器。仅当沙箱由 harness 自身持有（优先级 2/3/4）时才执行停止与关闭。
     *
     * <p>{@link Sandbox#stop()} 或 {@link Sandbox#shutdown()} 抛出的异常仅记录日志并不上抛，
     * 避免单个沙箱异常破坏整个调用收尾流程。
     *
     * @param result {@link #acquire} 返回的获取结果；传入 null 时为 no-op
     */
    public void release(SandboxAcquireResult result) {
        if (result == null) {
            return;
        }
        Sandbox sandbox = result.getSandbox();
        if (sandbox == null) {
            return;
        }

        // 用户托管的沙箱（优先级 1）由调用方持有生命周期——harness 不得 stop/snapshot/shutdown，
        // 因为调用方依赖该沙箱在多次 acquire/release 之间持续存活
        // （例如跨浏览器路径与多轮对话复用同一容器的注册中心场景）。
        if (!result.isSelfManaged()) {
            return;
        }

        try {
            sandbox.stop();
        } catch (Exception e) {
            // stop 失败不应中断后续 shutdown；记录日志后继续清理
            log.warn("[sandbox] Sandbox stop failed: {}", e.getMessage(), e);
        }

        try {
            sandbox.shutdown();
        } catch (Exception e) {
            log.warn("[sandbox] Sandbox shutdown failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Persist the current sandbox state into the per-session store, so the next acquire for the
     * same isolation scope can resume (Priority 3) instead of creating a fresh sandbox.
     *
     * <p>User-managed sandboxes (Priority 1) are skipped: the registry that supplied them owns
     * its own snapshot policy, and double-tracking the state through our store would conflict.
     *
     * <p>Failures are logged but never propagated: a failed persist must not break the call.
     *
     * @param result acquisition result from {@link #acquire}; null is a no-op
     * @param sandboxContext sandbox context providing the isolation scope
     * @param runtimeContext runtime context used to derive the scope key
     */
    /**
     * 将当前沙箱状态持久化到 per-session 存储，使同一隔离维度的下一次 acquire 能够走"恢复"
     *（优先级 3）路径，而不是创建全新沙箱。
     *
     * <p>用户托管的沙箱（优先级 1）会被跳过：调用方已经自带 snapshot 策略，
     * 我们再写一遍存储会导致状态双轨并可能与调用方策略冲突。
     *
     * <p>任何异常仅记日志并不上抛：持久化失败不能影响本次调用的正常返回。
     *
     * @param result {@link #acquire} 返回的获取结果；传入 null 时为 no-op
     * @param sandboxContext 用于推导隔离维度的沙箱上下文
     * @param runtimeContext 用于推导 scope key 的运行时上下文
     */
    public void persistState(
            SandboxAcquireResult result,
            SandboxContext sandboxContext,
            RuntimeContext runtimeContext) {
        if (result == null || result.getSandbox() == null) {
            return;
        }
        // 用户托管沙箱自带持久化策略（注册中心自行管理生命周期）：
        // 通过 harness 状态存储再写一遍会导致双轨，且可能与调用方的 snapshot 策略冲突。
        if (!result.isSelfManaged()) {
            return;
        }
        SandboxState state = result.getSandbox().getState();
        if (state == null) {
            return;
        }

        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                        runtimeContext,
                        agentId);
        if (scopeKey.isEmpty()) {
            log.debug("[sandbox] No scope key available, skipping state persistence");
            return;
        }

        try {
            String json = client.serializeState(state);
            stateStore.save(scopeKey.get(), json);
            log.debug(
                    "[sandbox] Persisted sandbox state for scope {}: sessionId={}",
                    scopeKey.get(),
                    state.getSessionId());
        } catch (Exception e) {
            // 持久化失败仅记日志，避免中断当前调用
            log.warn("[sandbox] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
    }

    /**
     * Delete the persisted sandbox state for the given isolation scope, so the next acquire will
     * take the create-fresh path (Priority 4) instead of resuming.
     *
     * <p>Typically invoked when a session ends or the caller wants to force a clean slate. A
     * missing scope key or a failed delete is logged but does not propagate.
     *
     * @param sandboxContext sandbox context providing the isolation scope (may be null)
     * @param runtimeContext runtime context used to derive the scope key
     */
    /**
     * 删除指定隔离维度下的持久化沙箱状态，使下一次 acquire 走"新建"路径（优先级 4）而不是恢复。
     *
     * <p>通常在会话结束或调用方希望强制重建时调用。缺少 scope key 或删除失败仅记日志，不上抛。
     *
     * @param sandboxContext 用于推导隔离维度的沙箱上下文（可为 null）
     * @param runtimeContext 用于推导 scope key 的运行时上下文
     */
    public void clearState(SandboxContext sandboxContext, RuntimeContext runtimeContext) {
        Optional<SandboxIsolationKey> scopeKey =
                SandboxIsolationKey.resolve(
                        sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                        runtimeContext,
                        agentId);
        if (scopeKey.isEmpty()) {
            return;
        }

        try {
            stateStore.delete(scopeKey.get());
        } catch (Exception e) {
            // 清理失败不影响调用流程，仅记日志
            log.warn("[sandbox] Failed to clear sandbox state: {}", e.getMessage(), e);
        }
    }
}
