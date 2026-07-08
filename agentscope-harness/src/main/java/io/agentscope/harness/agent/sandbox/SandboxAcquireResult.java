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

/**
 * Result of acquiring a {@link Sandbox} from {@link SandboxManager}.
 *
 * <p>Two ownership modes:
 *
 * <ul>
 *   <li><b>self-managed</b> ({@code selfManaged=true}): the SDK created the sandbox and is
 *       responsible for its full lifecycle — {@code stop()} + {@code shutdown()} are both called
 *       after each agent call.
 *   <li><b>user-managed</b> ({@code selfManaged=false}): the caller injected a pre-existing
 *       sandbox; the SDK only calls {@code stop()} and never calls {@code shutdown()}.
 * </ul>
 *
 * <p>When a {@link SandboxExecutionGuard} is configured, the result also carries the
 * {@link SandboxLease} that was acquired before sandbox resume/create. The harness closes it
 * after {@link SandboxManager#release} completes to cover the full call window.
 */
/**
 * 从 {@link SandboxManager} 获取沙箱后的返回结果。
 *
 * <p>分为两种持有模式：
 *
 * <ul>
 *   <li><b>自托管模式</b>（{@code selfManaged=true}）：由SDK创建沙箱，全权管理完整生命周期；
 *       每次智能体调用结束后会依次执行 {@code stop()} 和 {@code shutdown()}。
 *   <li><b>用户托管模式</b>（{@code selfManaged=false}）：调用方传入已预先存在的沙箱；
 *       SDK仅执行 {@code stop()}，不会调用 {@code shutdown()}。
 * </ul>
 *
 * <p>若配置了 {@link SandboxExecutionGuard}，该结果会携带创建/恢复沙箱前获取到的 {@link SandboxLease} 租约。
 * 执行器会在 {@link SandboxManager#release} 执行完毕后释放租约，完整覆盖整个调用周期。
 */
public final class SandboxAcquireResult {

    private final Sandbox sandbox;
    private final boolean selfManaged;
    private final SandboxLease lease;

    private SandboxAcquireResult(Sandbox sandbox, boolean selfManaged, SandboxLease lease) {
        this.sandbox = sandbox;
        this.selfManaged = selfManaged;
        this.lease = lease != null ? lease : SandboxLease.noop();
    }

    /** Creates a self-managed result with a guard lease (SDK owns the full lifecycle). */
    public static SandboxAcquireResult selfManaged(Sandbox sandbox, SandboxLease lease) {
        return new SandboxAcquireResult(sandbox, true, lease);
    }

    /** Creates a self-managed result with no guard (SDK owns the full lifecycle). */
    public static SandboxAcquireResult selfManaged(Sandbox sandbox) {
        return new SandboxAcquireResult(sandbox, true, SandboxLease.noop());
    }

    /** Creates a user-managed result (caller owns the lifecycle; SDK only calls stop). */
    public static SandboxAcquireResult userManaged(Sandbox sandbox) {
        return new SandboxAcquireResult(sandbox, false, SandboxLease.noop());
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    /** Returns {@code true} if the SDK owns the full sandbox lifecycle. */
    public boolean isSelfManaged() {
        return selfManaged;
    }

    /**
     * Returns the {@link SandboxLease} held for this call, or a no-op lease if no guard was
     * configured. The harness closes this lease after {@link SandboxManager#release} completes.
     */
    public SandboxLease getLease() {
        return lease;
    }
}
