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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware that manages the sandbox session lifecycle around each agent call.
 *
 * <h2>Pre-{@code next.apply}</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Acquire a session via {@link SandboxManager}</li>
 *   <li>Start the session (4-branch workspace init)</li>
 *   <li>Inject the live session into the {@link SandboxBackedFilesystem} proxy</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>Persist sandbox session state via {@link SandboxManager} and
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore}</li>
 *   <li>Release the session via {@link SandboxManager} (stop + optional shutdown)</li>
 *   <li>Clear the session reference from the filesystem proxy</li>
 * </ol>
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures
 * the agent call result is always returned to the caller even if sandbox cleanup fails.
 */
/**
 * 中间件，在每次智能体调用前后管理沙箱会话的生命周期。
 *
 * <h2>调用前（next.apply 之前）</h2>
 * <ol>
 *   <li>从当前 {@link RuntimeContext} 读取 {@link SandboxContext}</li>
 *   <li>通过 {@link SandboxManager} 获取一个沙箱会话</li>
 *   <li>启动会话（工作区初始化，内部含四分支处理）</li>
 *   <li>将可用会话注入 {@link SandboxBackedFilesystem} 文件系统代理</li>
 * </ol>
 *
 * <h2>调用后（doFinally 阶段）</h2>
 * <ol>
 *   <li>通过 {@link SandboxManager} 与
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore} 持久化沙箱会话状态</li>
 *   <li>通过 {@link SandboxManager} 释放会话（停止会话 + 可选关闭沙箱）</li>
 *   <li>清除文件系统代理中的会话引用</li>
 * </ol>
 *
 * <p>调用后的失败（持久化、释放）只记录日志、不向外传播——保证即使沙箱清理失败，
 * 智能体调用结果也能正常返回给调用方。
 */
public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    /** 沙箱管理器，负责沙箱会话的获取、状态持久化与释放。 */
    private final SandboxManager sandboxManager;

    /** 沙箱文件系统代理，持有当前可用会话，智能体的文件操作经它路由进沙箱。 */
    private final SandboxBackedFilesystem filesystemProxy;

    /**
     * 当前调用持有的沙箱获取结果（包含沙箱实例与租约）。
     * 使用 {@link AtomicReference} 便于在释放时通过 {@code getAndSet(null)} 原子地取出并清空，
     * 防止重复释放。
     */
    private final AtomicReference<SandboxAcquireResult> currentAcquireResult =
            new AtomicReference<>();

    /**
     * @param sandboxManager 沙箱管理器，负责会话获取与释放
     * @param filesystemProxy 沙箱文件系统代理，用于注入/清除当前会话
     */
    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    /**
     * Acquires the sandbox for the current call. Called from
     * {@code ReActAgent.beforeAgentExecution()} to ensure the sandbox is available
     * for both the {@code call()} and {@code streamEvents()} paths.
     *
     * @param ctx the per-call RuntimeContext (must not be null)
     */
    /**
     * 为当前调用获取沙箱。由 {@code ReActAgent.beforeAgentExecution()} 调用，
     * 确保 {@code call()} 与 {@code streamEvents()} 两条执行路径都能使用沙箱。
     *
     * <p>获取成功后依次：启动沙箱 → 注入文件系统代理 → 记录获取结果。
     * 若启动或注入失败，会清理代理引用、释放会话、关闭租约后重新抛出异常；
     * 获取本身失败则记录错误日志并包装为 {@link RuntimeException} 抛出。
     *
     * @param ctx 单次调用的运行上下文（不允许为 null）
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        // 上下文中未配置沙箱上下文时，视为本次调用不需要沙箱
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        try {
            // 获取沙箱会话（含租约）
            SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
            Sandbox sandbox = result.getSandbox();
            try {
                // 启动沙箱（含工作区初始化）并注入文件系统代理供本次调用使用
                sandbox.start();
                filesystemProxy.setSandbox(sandbox);
                currentAcquireResult.set(result);
                log.debug(
                        "[sandbox-mw] Acquired sandbox {}",
                        sandbox.getState() != null ? sandbox.getState().getSessionId() : "?");
            } catch (Exception e) {
                // 启动或注入失败：清理代理引用，回滚释放会话与租约，再向外抛出
                filesystemProxy.setSandbox(null);
                try {
                    sandboxManager.release(result);
                } catch (Exception releaseErr) {
                    log.warn(
                            "[sandbox-mw] Failed to release session after pre-call failure: {}",
                            releaseErr.getMessage(),
                            releaseErr);
                }
                result.getLease().close();
                throw e;
            }
        } catch (Exception e) {
            log.error("[sandbox-mw] Failed to acquire/start sandbox", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Releases the sandbox after the current call. Called from
     * {@code ReActAgent.afterAgentExecution()} to ensure cleanup for both paths.
     *
     * @param ctx the per-call RuntimeContext (captured at acquire time)
     */
    /**
     * 当前调用结束后释放沙箱。由 {@code ReActAgent.afterAgentExecution()} 调用，
     * 确保两条执行路径都会执行清理。
     *
     * <p>清理顺序：原子取出并清空获取结果 → 持久化会话状态 → 释放会话 →
     * 关闭租约 → 清除文件系统代理中的会话引用。
     * 持久化与释放各自的独立兜底，失败仅记录警告日志，不影响调用结果返回。
     *
     * @param ctx 单次调用的运行上下文（获取阶段捕获的同一个上下文）
     */
    public void releaseForCall(RuntimeContext ctx) {
        // 原子取出并置空：保证同一结果只会被释放一次
        SandboxAcquireResult result = currentAcquireResult.getAndSet(null);
        if (result == null) {
            // 本次调用未获取沙箱（或已释放），无需清理
            return;
        }
        SandboxContext sandboxContext = ctx != null ? ctx.get(SandboxContext.class) : null;
        try {
            // 持久化沙箱会话状态，供下次调用恢复现场
            sandboxManager.persistState(result, sandboxContext, ctx);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
        try {
            // 释放会话（停止会话，视配置决定是否关闭沙箱实例）
            sandboxManager.release(result);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to release sandbox session: {}", e.getMessage(), e);
        }
        // 关闭租约并清除代理引用，避免后续误用已释放的沙箱
        result.getLease().close();
        filesystemProxy.setSandbox(null);
    }
}
