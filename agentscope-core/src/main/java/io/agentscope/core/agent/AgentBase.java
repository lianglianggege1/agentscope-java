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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Abstract base class for all agents in the AgentScope framework.
 *
 * <p>This class provides common functionality for agents including basic hook integration,
 * MsgHub subscriber management, interrupt handling, tracing, and state management through StateModule.
 * It does NOT manage memory - that is the responsibility of specific agent implementations like
 * ReActAgent.
 *
 * <p>Design Philosophy:
 * <ul>
 *   <li>AgentBase provides infrastructure (hooks, subscriptions, interrupt, state) but not domain
 *       logic</li>
 *   <li>Memory management is delegated to concrete agents that need it (e.g., ReActAgent)</li>
 *   <li>State management implements StateModule interface</li>
 *   <li>Interrupt mechanism uses reactive patterns: subclasses call checkInterruptedAsync()
 *       at appropriate checkpoints, which propagates InterruptedException through Mono chain</li>
 *   <li>Observe pattern: agents can receive messages without generating a reply</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 * The base lifecycle keeps execution state per subscription. Subclasses define serialization
 * through callSerializationKey; ReActAgent serializes calls within each session. Configure hooks
 * before invoking the agent rather than mutating configuration while calls are running.
 *
 * <p><b>Interrupt Mechanism:</b>
 * <pre>{@code
 * // External call to interrupt
 * agent.interrupt(userMsg);
 *
 * // Inside agent's Mono chain, at checkpoints:
 * return checkInterruptedAsync()
 *     .then(doWork())
 *     .flatMap(result -> checkInterruptedAsync().thenReturn(result));
 *
 * // AgentBase.call() catches the exception:
 * .onErrorResume(error -> {
 *     if (error instanceof InterruptedException) {
 *         return handleInterrupt(context, msg);
 *     }
 *     ...
 * });
 * }</pre>
 */
/**
 * AgentScope 框架中所有代理（Agent）的抽象基类。
 *
 * <p>该类集中提供代理所需的通用基础设施：Hook 接入、MsgHub 订阅管理、中断处理、Tracing
 * 以及通过 StateModule 进行状态管理。基类本身不负责记忆（Memory），记忆由具体的代理实现
 * （如 {@code ReActAgent}）自行管理。
 *
 * <p>设计原则：
 * <ul>
 *   <li>AgentBase 只提供基础设施（hooks、订阅、中断、状态），不承载任何业务域逻辑</li>
 *   <li>记忆管理下沉到真正需要的具体代理中（例如 {@code ReActAgent}）</li>
 *   <li>状态管理遵循 {@link io.agentscope.core.state.StateModule} 接口约定</li>
 *   <li>中断机制基于响应式模式：子类在合适检查点调用 {@code checkInterruptedAsync()}，
 *       中断异常沿 Mono 链向上传播</li>
 *   <li>Observe 模式：允许代理接收消息但不产生回复</li>
 * </ul>
 *
 * <p><b>线程安全：</b>
 * 同一个代理实例并非为并发执行设计。单一实例不应被多个线程同时调用
 *（例如并发触发 {@code call()} 或 {@code stream()}）。hooks 列表在流式执行期间会被修改，
 * 没有任何同步保护，仅在单线程执行同一实例的语境下安全。
 *
 * <p><b>中断机制示例：</b>
 * <pre>{@code
 * // 外部触发中断
 * agent.interrupt(userMsg);
 *
 * // 代理内部 Mono 链上的检查点：
 * return checkInterruptedAsync()
 *     .then(doWork())
 *     .flatMap(result -> checkInterruptedAsync().thenReturn(result));
 *
 * // AgentBase.call() 捕获中断异常：
 * .onErrorResume(error -> {
 *     if (error instanceof InterruptedException) {
 *         return handleInterrupt(context, msg);
 *     }
 *     ...
 * });
 * }</pre>
 */
@SuppressWarnings("deprecation")
public abstract class AgentBase implements Agent {

    private final String agentId;
    private final String name;
    private final String description;

    /** Hooks owned by this agent instance (mutable; modified during streaming). */
    /**
     * 该代理实例自身持有的 Hook 列表（在流式执行期间会被动态修改）。
     */
    private final List<Hook> hooks;

    /** Process-wide system hooks (shared by every agent instance). */
    /**
     * 进程级系统 Hook，被所有代理实例共享（例如 tracing、metrics 等横切关注点）。
     */
    private static final List<Hook> systemHooks = new CopyOnWriteArrayList<>();

    /** Per-MsgHub subscribers; populated when an agent joins a MsgHub. */
    /**
     * 按 MsgHub 维度组织的订阅者列表，代理加入 MsgHub 时填充。
     */
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    private static final Comparator<Hook> HOOK_COMPARATOR = Comparator.comparingInt(Hook::priority);

    /**
     * Per-key call serialization tails. Each entry holds the completion signal of the most recently
     * enqueued call for that key; the next call for the same key chains after it, so calls sharing a
     * key run one-at-a-time (FIFO) while different keys run concurrently. See {@link
     * #callSerializationKey(RuntimeContext)} and {@link #serializeOnKey(Object, Mono)}.
     */
    /**
     * 按 key 维度的调用序列化队列尾部信号。每个 key 对应最近一次入队调用的完成信号；
     * 同一个 key 的下一次调用需串接在该信号之后，因此共享 key 的调用按 FIFO 顺序串行执行，
     * 不同 key 之间则可并发。详见 {@link #callSerializationKey(RuntimeContext)} 与
     * {@link #serializeOnKey(Object, Mono)}。
     */
    private final ConcurrentHashMap<Object, Mono<Void>> callGates = new ConcurrentHashMap<>();

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     */
    /**
     * 仅指定代理名称的构造器（描述为空、Hook 列表为空）。
     *
     * @param name 代理名称
     */
    public AgentBase(String name) {
        this(name, null, List.of());
    }

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     * @param description Agent description
     */
    /**
     * 指定名称和描述的构造器（Hook 列表为空）。
     *
     * @param name 代理名称
     * @param description 代理描述
     */
    public AgentBase(String name, String description) {
        this(name, description, List.of());
    }

    /**
     * @deprecated Use {@link #AgentBase(String, String, List)} instead.
     */
    /**
     * @deprecated 请改用 {@link #AgentBase(String, String, List)}。
     */
    @Deprecated
    public AgentBase(String name, String description, boolean checkRunning, List<Hook> hooks) {
        this(name, description, hooks);
    }

    /**
     * Constructor for AgentBase with hooks.
     *
     * @param name Agent name
     * @param description Agent description
     * @param hooks List of hooks for monitoring/intercepting execution
     */
    /**
     * 指定名称、描述和 Hook 列表的构造器（核心入口）。
     *
     * @param name 代理名称
     * @param description 代理描述
     * @param hooks 监控/拦截执行过程的 Hook 列表
     */
    public AgentBase(String name, String description, List<Hook> hooks) {
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.hooks = new CopyOnWriteArrayList<>(hooks != null ? hooks : List.of());
        this.hooks.addAll(systemHooks);
        sortHooks();
    }

    @Override
    public final String getAgentId() {
        return agentId;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description != null ? description : Agent.super.getDescription();
    }

    /** @deprecated No longer enforced; per-session serialization handles concurrency. */
    /**
     * @deprecated 已不再强制；并发由 per-session 序列化机制处理。
     */
    @Deprecated
    public final boolean isCheckRunning() {
        return false;
    }

    /**
     * Process a list of input messages and generate a response with hook execution.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param msgs Input messages
     * @return Response message
     */
    /**
     * 处理输入消息列表并生成回复（带 Hook 生命周期管理）。
     *
     * <p>在启用 telemetry 后将捕获 tracing 数据。
     *
     * @param msgs 输入消息列表
     * @return 响应消息的 Mono
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs) {
        return callInternal(msgs, null, this::doCall);
    }

    /**
     * Extension point called by every {@code call()} overload, allowing subclasses to wrap the
     * entire invocation in additional middleware (e.g. the {@code onAgent} chain in
     * {@code ReActAgent}).
     *
     * <p>The default implementation attaches {@code context} to the Reactor Context (when
     * non-null) and delegates straight to {@link #runLifecycle}. Subclasses that override this
     * method must eventually invoke {@code runLifecycle(msgs, doCallFn)} to run the standard
     * lifecycle (shutdown guard, serialization gate, pre/post hooks, tracing).
     *
     * @param msgs     input messages
     * @param context  caller-supplied per-call {@link RuntimeContext}, or {@code null}
     * @param doCallFn the concrete call implementation ({@link #doCall} or a structured-output
     *                 variant)
     * @return response message
     */
    /**
     * 每次 {@code call()} 重载都会调用的扩展点，便于子类将整个调用包裹在额外中间件中
     *（如 {@code ReActAgent} 中的 {@code onAgent} 链）。
     *
     * <p>默认实现会把 {@code context} 写入 Reactor Context（非 null 时），并直接委派给
     * {@link #runLifecycle}。覆盖该方法的子类最终必须执行 {@code runLifecycle(msgs, doCallFn)}
     * 以运行标准生命周期（关闭守卫、序列化门控、pre/post Hook、tracing）。
     *
     * @param msgs 输入消息列表
     * @param context 调用方提供的 per-call {@link RuntimeContext}，可为 {@code null}
     * @param doCallFn 具体调用实现（{@link #doCall} 或其结构化输出变体）
     * @return 响应消息
     */
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        Mono<Msg> lifecycle = runLifecycle(msgs, doCallFn);
        return context == null
                ? lifecycle
                : lifecycle.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    /** Lightweight controls for admitted sessions; never retains a RuntimeContext. */
    private final Map<Object, RunControl> runningCalls = new ConcurrentHashMap<>();

    /** Session-level interruption targets only the execution currently admitted for this key. */
    protected final void interruptRunning(Object key, InterruptSource source, Msg message) {
        RunControl control = runningCalls.get(key);
        if (control != null) {
            control.interrupt(source, message);
        }
    }

    /**
     * Reactor Context key carrying the per-call scope object returned by {@link
     * #beforeAgentExecution(List, RuntimeContext, RunControl)}. Agents that maintain per-call state (e.g. {@code ReActAgent}'s
     * {@code CallExecution}) read it back from the Context in {@link #doCall} / {@link
     * #handleInterrupt} so concurrent calls on one instance never share mutable per-call state.
     */
    /**
     * Reactor Context 的 key：承载 {@link #beforeAgentExecution(List, RuntimeContext)} 返回的
     * per-call scope 对象。维护 per-call 状态的代理（如 {@code ReActAgent} 的 {@code CallExecution}）
     * 在 {@link #doCall} 与 {@link #handleInterrupt} 中通过该 key 读回 scope，
     * 从而保证同一实例上的并发调用互不污染 per-call 可变状态。
     */
    public static final String CALL_SCOPE_KEY = "io.agentscope.core.agent.AgentBase.callScope";

    /**
     * Reactor Context key carrying the caller-supplied per-call {@link RuntimeContext}. Set by the
     * {@code call(msgs, context)} / {@code stream(..., context)} / {@code streamEvents(msgs,
     * context)} overloads via {@code contextWrite}, and read back here at call entry so the RC flows
     * per-subscription (concurrency-safe) instead of through a shared instance field.
     */
    /**
     * Reactor Context 的 key：承载调用方提供的 per-call {@link RuntimeContext}。
     * 由 {@code call(msgs, context)} / {@code stream(..., context)} / {@code streamEvents(msgs, context)}
     * 等重载通过 {@code contextWrite} 写入，并在调用入口处读回，使 RC 沿订阅链传递
     *（天然并发安全），而不再依赖共享实例字段。
     */
    public static final String RUNTIME_CONTEXT_KEY =
            "io.agentscope.core.agent.AgentBase.runtimeContext";

    /**
     * Reactor Context key carrying this call's graceful-shutdown {@code requestId} (issued by {@link
     * GracefulShutdownManager#registerRequest}). Threaded per-subscription so shutdown
     * interrupt/save target the exact in-flight call even when one agent instance serves many
     * concurrent calls — keying shutdown tracking by agent id would otherwise collapse concurrent
     * calls into a single entry.
     */
    /**
     * Reactor Context 的 key：承载本次调用的优雅关闭 {@code requestId}
     *（由 {@link GracefulShutdownManager#registerRequest} 签发）。
     * 沿订阅链传递，使 shutdown 中断/保存只作用于正在执行的这一次调用，即使同一代理实例
     * 服务多个并发调用也能精确命中。
     * 注意：若按 agent id 作为 shutdown 跟踪 key，会把同一实例上的并发调用折叠到同一记录中，
     * 造成误中断，因此这里使用 per-call 的 requestId。
     */
    public static final String SHUTDOWN_REQUEST_ID_KEY =
            "io.agentscope.core.agent.AgentBase.shutdownRequestId";

    /**
     * Shared {@code call()} lifecycle: acquire execution, then (inside {@code deferContextual} so
     * the caller-supplied {@link RuntimeContext} is read per-subscription) run {@link
     * #beforeAgentExecution(List, RuntimeContext, RunControl)} (which returns this call's per-call scope), carry
     * that scope on the Reactor Context, and run the preCall → doCall → postCall chain with error
     * handling, releasing execution on terminate.
     */
    /**
     * 共享的 {@code call()} 生命周期：获取执行权后（包裹在 {@code deferContextual} 中以使调用方
     * 传入的 {@link RuntimeContext} 按订阅粒度读取）执行 {@link #beforeAgentExecution(List, RuntimeContext)}
     * 获取本次调用的 per-call scope，将该 scope 挂载到 Reactor Context，然后串接
     * preCall → doCall → postCall 链路并附带错误处理，终止时释放执行权。
     */
    protected Mono<Msg> runLifecycle(List<Msg> msgs, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return Mono.using(
                this::acquireExecution,
                resource -> Mono.deferContextual(cv -> runInContext(msgs, doCallFn, cv)),
                this::releaseExecution,
                true);
    }

    private Mono<Msg> runInContext(
            List<Msg> inputMsgs,
            Function<List<Msg>, Mono<Msg>> doCallFn,
            reactor.util.context.ContextView cv) {
        RuntimeContext rc = cv.getOrDefault(RUNTIME_CONTEXT_KEY, null);
        RunControl supplied = cv.getOrDefault(RunControl.CONTEXT_KEY, null);
        boolean managed = supplied != null && supplied.belongsTo(getAgentId());
        RunControl control = managed ? supplied : new RunControl(getAgentId());
        if (!managed) {
            control.queue();
        }
        // 取出本次调用的序列化 key（null 表示无需串行化）
        Object gateKey = callSerializationKey(rc);
        GracefulShutdownManager shutdown = GracefulShutdownManager.getInstance();
        // 单独为本调用签发一个 shutdown requestId（而非共享的 agent id），
        // 这样同一实例上的并发调用可以独立地中断/保存/注销。
        String requestId = shutdown.registerRequest(this, control);
        Runnable retire =
                () -> {
                    if (gateKey != null) {
                        runningCalls.remove(gateKey, control);
                    }
                    shutdown.unregisterRequest(requestId);
                };
        // Per-subscription private mutable copy: input adjustments from per-call extension
        // points (e.g. onAgentStateReady middlewares) never touch the caller's list.
        List<Msg> msgs = new ArrayList<>(inputMsgs != null ? inputMsgs : List.of());
        // Resolve session state only after admission. Retire before releasing the queue gate so a
        // retry cannot have its new registration removed by the preceding attempt's cleanup.
        // 延迟构造 per-call 生命周期，确保仅在序列化门控允许之后才执行：
        // beforeAgentExecution 会解析/加载会话槽位，必须避免与同一会话的
        // 并发调用产生竞态。
        Mono<Msg> lifecycle =
                Mono.defer(() -> runLifecycleBody(msgs, rc, doCallFn, requestId, control, gateKey))
                        .doOnTerminate(retire)
                        .doOnCancel(retire);
        Mono<Msg> gated = gateKey == null ? lifecycle : serializeOnKey(gateKey, lifecycle);
        return control.guard(gated)
                .singleOrEmpty()
                .doOnSuccess(
                        value -> {
                            if (!managed) control.finish(AgentRun.Status.COMPLETED);
                        })
                .doOnError(
                        error -> {
                            if (!managed) control.finish(AgentRun.Status.FAILED);
                        })
                .doOnCancel(
                        () -> {
                            if (!managed) control.cancel();
                        })
                // 把 shutdown requestId 挂到 Reactor Context，供 doCall 内部取用
                .contextWrite(
                        c ->
                                requestId == null || requestId.isEmpty()
                                        ? c
                                        : c.put(SHUTDOWN_REQUEST_ID_KEY, requestId))
                // Also unregister calls cancelled while queued, before a lifecycle body exists.
                // 任意终止信号都要注销 shutdown request，防止泄漏
                .doFinally(signal -> shutdown.unregisterRequest(requestId));
    }

    private Mono<Msg> runLifecycleBody(
            List<Msg> msgs,
            RuntimeContext rc,
            Function<List<Msg>, Mono<Msg>> doCallFn,
            String requestId,
            RunControl control,
            Object gateKey) {
        GracefulShutdownManager.getInstance().ensureAcceptingRequests();
        if (!control.start()) {
            return Mono.error(
                    new java.util.concurrent.CancellationException("Agent run cancelled"));
        }
        // Register for interruption before beforeAgentExecution: it fires user middleware
        // (onAgentStateReady), and an interrupt arriving in that window must find this control
        // instead of being dropped. Failure cleanup is unchanged — retire removes the entry on
        // any terminal signal, including a synchronous throw from the scope setup.
        if (gateKey != null) {
            runningCalls.put(gateKey, control);
        }
        Object scope = beforeAgentExecution(msgs, rc, control);
        // Bind this call's resolved per-session state to the tracked shutdown request so graceful
        // shutdown interrupts / saves the exact (userId, sessionId) session rather than the agent's
        // no-arg "most-recently-active" accessors.
        // 将本次调用解析出的 per-session 状态绑定到 shutdown request，
        // 使优雅关闭能精确中断/保存指定的 (userId, sessionId) 会话，
        // 而不是命中代理的无参 "最近一次活动" 访问器。
        GracefulShutdownManager.getInstance().bindRequestState(requestId, stateForCall(scope));
        // 主体链路：preCall → doCall → postCall，外层用 TracerRegistry 包一层用于追踪，
        // 出错时通过 createErrorHandler 走专门的恢复路径
        Mono<Msg> body =
                TracerRegistry.get()
                        .callAgent(
                                this,
                                msgs,
                                () ->
                                        notifyPreCall(msgs, scope)
                                                .flatMap(doCallFn)
                                                .flatMap(this::notifyPostCall)
                                                .onErrorResume(
                                                        createErrorHandler(
                                                                control,
                                                                msgs.toArray(new Msg[0]))));
        // 把 scope 挂到 Reactor Context，doCall/handleInterrupt 内部按需取用
        Mono<Msg> scoped =
                scope == null ? body : body.contextWrite(c -> c.put(CALL_SCOPE_KEY, scope));
        // Nested calls own their own control; only the outer execution receives this handle.
        return scoped.contextWrite(c -> c.delete(RunControl.CONTEXT_KEY));
    }

    /**
     * Returns a key that serializes concurrent calls sharing it: calls with an equal, non-null key
     * run one-at-a-time in FIFO order, while calls with different keys (or a {@code null} key) run
     * concurrently. The default returns {@code null} (no serialization). {@code ReActAgent} returns
     * its {@code (userId, sessionId)} slot key so same-session calls do not corrupt shared
     * conversation state while distinct sessions run in parallel.
     *
     * @param rc the caller-supplied per-call {@link RuntimeContext} (may be {@code null})
     * @return the serialization key, or {@code null} to run without serialization
     */
    /**
     * 返回用于并发调用序列化的 key：拥有相同非 null key 的调用按 FIFO 顺序串行执行，
     * 不同 key（或 null key）的调用之间并发。默认返回 {@code null}（不串行化）。
     * {@code ReActAgent} 返回其 {@code (userId, sessionId)} 会话槽 key，使同一会话的调用不会
     * 破坏共享会话状态，而不同会话之间可并行。
     *
     * @param rc 调用方提供的 per-call {@link RuntimeContext}（可为 {@code null}）
     * @return 序列化 key；返回 {@code null} 表示无需串行化
     */
    protected Object callSerializationKey(RuntimeContext rc) {
        return null;
    }

    /**
     * Serializes {@code action} against other actions sharing {@code key}: this call waits for the
     * previously-enqueued call with the same key to terminate before running, then becomes the tail
     * the next same-key call waits on. Releases its slot on any terminal signal (complete, error, or
     * cancel) so a failed/cancelled call never blocks the queue.
     */
    /**
     * 将 {@code action} 与其它共享 {@code key} 的动作串行化：本调用需等待前一个相同 key 调用结束后
     * 才能执行；执行完毕后自身成为该 key 队列的尾部，供下一次同 key 调用等待。
     * 任意终止信号（complete/error/cancel）都会释放占位，避免失败/取消的调用阻塞队列。
     */
    private <T> Mono<T> serializeOnKey(Object key, Mono<T> action) {
        return Mono.defer(
                () -> {
                    // release 作为本调用完成信号，写入 callGates 后下一次同 key 调用会串接在它之后
                    Sinks.Empty<Void> release = Sinks.empty();
                    @SuppressWarnings("unchecked")
                    Mono<Void>[] previous = new Mono[1];
                    Mono<Void> tail =
                            callGates.compute(
                                    key,
                                    (k, existing) -> {
                                        // 取出当前尾部（若有），本次调用需要在其之后执行
                                        previous[0] = existing == null ? Mono.empty() : existing;
                                        // A cancelled middle entry must continue waiting for its
                                        // predecessor. Otherwise
                                        // A -> B(cancelled) -> C could admit C while A still owns
                                        // the session state.
                                        // 把自己的 release 信号注册为新的尾部
                                        return previous[0].then(release.asMono()).cache();
                                    });
                    tail.subscribe(
                            ignored -> {},
                            ignored -> callGates.remove(key, tail),
                            () -> callGates.remove(key, tail));
                    // 在前序调用结束后才开始本调用；
                    // doFinally 释放尾部占位，确保取消/失败时不留死锁
                    return previous[0].then(action).doFinally(signal -> release.tryEmitEmpty());
                });
    }

    /**
     * Process multiple input messages and generate structured output with hook execution.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param msgs Input messages
     * @param structuredOutputClass Class defining the structure of the output
     * @return Response message with structured data in metadata
     */
    /**
     * 处理多条输入消息并生成结构化输出（带 Hook 生命周期）。
     *
     * <p>启用 telemetry 后会捕获 tracing 数据。
     *
     * @param msgs 输入消息列表
     * @param structuredOutputClass 定义输出结构的目标类
     * @return 元数据中包含结构化数据的响应消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        return callInternal(msgs, null, m -> doCall(m, structuredOutputClass));
    }

    /**
     * Process multiple input messages and generate structured output with hook execution.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param msgs Input messages
     * @param schema com.fasterxml.jackson.databind.JsonNode instance defining the structure of the output
     * @return Response message with structured data in metadata
     */
    /**
     * 处理多条输入消息并按 JSON schema 生成结构化输出（带 Hook 生命周期）。
     *
     * <p>启用 telemetry 后会捕获 tracing 数据。
     *
     * @param msgs 输入消息列表
     * @param schema 定义输出结构的 {@code com.fasterxml.jackson.databind.JsonNode}
     * @return 元数据中包含结构化数据的响应消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return callInternal(msgs, null, m -> doCall(m, schema));
    }

    /**
     * Internal implementation for processing multiple input messages.
     * Subclasses must implement their specific logic here.
     *
     * @param msgs Input messages
     * @return Response message
     */
    /**
     * 处理多条输入消息的内部实现：子类必须实现各自的业务逻辑。
     *
     * @param msgs 输入消息列表
     * @return 响应消息
     */
    protected abstract Mono<Msg> doCall(List<Msg> msgs);

    /**
     * Internal implementation for processing multiple messages with structured output.
     * Subclasses that support structured output must override this method.
     * Default implementation throws UnsupportedOperationException.
     *
     * @param msgs Input messages
     * @param structuredOutputClass Class defining the structure
     * @return Response message with structured data in metadata
     */
    /**
     * 处理多条输入消息并生成结构化输出的内部实现。
     * 支持结构化输出的子类必须重写此方法，默认实现抛出 UnsupportedOperationException。
     *
     * @param msgs 输入消息列表
     * @param structuredOutputClass 定义输出结构的目标类
     * @return 元数据中包含结构化数据的响应消息
     */
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + getClass().getSimpleName()));
    }

    /**
     * Internal implementation for processing multiple messages with structured output.
     * Subclasses that support structured output must override this method.
     * Default implementation throws UnsupportedOperationException.
     *
     * @param msgs Input messages
     * @param outputSchema com.fasterxml.jackson.databind.JsonNode instance defining the structure
     * @return Response message with structured data in metadata
     */
    /**
     * 处理多条输入消息并按 JSON schema 生成结构化输出的内部实现。
     * 支持结构化输出的子类必须重写此方法，默认实现抛出 UnsupportedOperationException。
     *
     * @param msgs 输入消息列表
     * @param outputSchema 定义输出结构的 {@code JsonNode}
     * @return 元数据中包含结构化数据的响应消息
     */
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + outputSchema.asText()));
    }

    /**
     * Register a system-wide {@link Hook} that will run for every agent instance.
     * 注册一个进程级系统 Hook，对所有代理实例生效。
     *
     * @param hook 系统 Hook 实例
     */
    public static void addSystemHook(Hook hook) {
        systemHooks.add(hook);
    }

    /**
     * Unregister a previously-added system-wide {@link Hook}.
     * 注销一个进程级系统 Hook。
     *
     * @param hook 待注销的系统 Hook 实例
     */
    public static void removeSystemHook(Hook hook) {
        systemHooks.remove(hook);
    }

    /** @deprecated Subclasses should implement per-session interrupt via RuntimeContext. */
    /**
     * @deprecated 子类应通过 RuntimeContext 实现 per-session 中断。
     */
    @Deprecated
    @Override
    public void interrupt() {}

    /** @deprecated Subclasses should implement per-session interrupt via RuntimeContext. */
    /**
     * @deprecated 子类应通过 RuntimeContext 实现 per-session 中断。
     */
    @Deprecated
    @Override
    public void interrupt(Msg msg) {}

    /** @deprecated Subclasses should implement per-session interrupt via RuntimeContext. */
    /**
     * @deprecated 子类应通过 RuntimeContext 实现 per-session 中断。
     */
    @Deprecated
    public void interrupt(InterruptSource source) {}

    /** @deprecated No longer needed; ReActAgent uses per-execution InterruptControl. */
    /** @deprecated No longer needed; ReActAgent uses per-session InterruptControl. */
    /**
     * @deprecated 已不再需要；{@code ReActAgent} 使用 per-session InterruptControl。
     */
    @Deprecated
    protected Mono<Void> checkInterruptedAsync() {
        return Mono.empty();
    }

    /** @deprecated No-op; interrupt state is owned by the execution. */
    @Deprecated
    protected void resetInterruptFlag() {}

    /**
     * Acquire execution resources for a {@code call()} invocation.
     * Used as the {@code resourceSupplier} in {@link Mono#using} to guarantee that
     * {@link #releaseExecution} is always called on completion, error, or cancellation.
     *
     * @return this agent instance
     */
    /**
     * 为一次 {@code call()} 调用获取执行资源（{@link Mono#using} 的 {@code resourceSupplier}）。
     * 通过该机制保证 {@link #releaseExecution} 必定在完成、错误或取消时被调用。
     *
     * @return 当前的代理实例
     */
    private AgentBase acquireExecution() {
        // 守卫：若进程已进入优雅关闭阶段，则拒绝接受新的请求
        GracefulShutdownManager.getInstance().ensureAcceptingRequests();
        return this;
    }

    private void releaseExecution(AgentBase resource) {
        // 配对 beforeAgentExecution：清理 per-call 状态
        afterAgentExecution();
    }

    /**
     * Create error handler for call() methods.
     * Handles InterruptedException specially and delegates to handleInterrupt,
     * while notifying hooks for other errors.
     *
     * @param originalArgs Original arguments to pass to handleInterrupt
     * @return Function that handles errors appropriately
     */
    /**
     * 为 {@code call()} 系列方法构造统一的错误处理器。
     * 对 {@link InterruptedException} 单独走 {@link #handleInterrupt} 恢复路径，
     * 其它错误则先通知 Hook 再原样上抛。
     *
     * @param control 本次执行独立的中断与取消控制句柄
     * @param originalArgs 传递给 {@link #handleInterrupt} 的原始入参
     * @return 处理错误的函数
     */
    private Function<Throwable, Mono<Msg>> createErrorHandler(
            RunControl control, Msg... originalArgs) {
        return error -> {
            // 中断异常（含被包装在 cause 中）走专门的中断恢复路径
            if (error instanceof InterruptedException
                    || (error.getCause() instanceof InterruptedException)) {
                return handleInterrupt(control.interruption().toContext(), originalArgs);
            }
            // 其它错误先广播给 ErrorEvent Hook，再继续上抛
            return notifyError(error).then(Mono.error(error));
        };
    }

    /** @deprecated No-op stub. */
    /**
     * @deprecated 空操作占位实现。
     */
    @Deprecated
    protected AtomicBoolean getInterruptFlag() {
        return new AtomicBoolean(false);
    }

    /** @deprecated Returns USER. Interrupt source is owned by the execution. */
    /** @deprecated Returns USER. Per-session interrupt source is on AgentState.interruptControl(). */
    /**
     * @deprecated 固定返回 USER。Per-session 中断来源由 AgentState.interruptControl() 管理。
     */
    @Deprecated
    protected InterruptSource getInterruptSource() {
        return InterruptSource.USER;
    }

    /**
     * Observe a message without generating a reply.
     * This allows agents to receive messages from other agents or the environment
     * without responding. It's commonly used in multi-agent collaboration scenarios.
     *
     * <p>Common implementation patterns:
     * <ul>
     *   <li>Stateless agents: Empty implementation if observation is not needed</li>
     *   <li>Stateful agents: Store message in memory/context for use in future calls</li>
     *   <li>Collaborative agents: Update shared knowledge or trigger side effects</li>
     * </ul>
     *
     * @param msg The message to observe
     * @return Mono that completes when observation is done
     */
    /**
     * 观察（observe）一条消息但不产生回复。该机制允许代理接收来自其它代理或环境的消息而无须
     * 显式响应，常见于多代理协作场景。
     *
     * <p>常见实现模式：
     * <ul>
     *   <li>无状态代理：无需观察时返回空实现即可</li>
     *   <li>有状态代理：把消息写入记忆/上下文，供后续 call 使用</li>
     *   <li>协作型代理：更新共享知识或触发副作用</li>
     * </ul>
     *
     * @param msg 待观察的消息
     * @return 观察完成时结束的 Mono
     */
    protected Mono<Void> doObserve(Msg msg) {
        return Mono.empty();
    }

    /**
     * Handle an interruption that occurred during execution.
     * Subclasses must implement this to provide recovery logic based on the interrupt context.
     *
     * <p>Implementation guidance:
     * <ul>
     *   <li>Simple agents: Return a basic interrupt acknowledgment message</li>
     *   <li>Complex agents: Generate a summary including any pending operations or partial results</li>
     *   <li>Stateful agents: Ensure state is saved appropriately before returning</li>
     * </ul>
     *
     * @param context The interrupt context containing metadata about the interruption
     * @param originalArgs The original arguments passed to the call() method (empty, single Msg,
     *     or List)
     * @return Recovery message to return to the user
     */
    /**
     * 处理执行过程中发生的中断。子类必须实现该方法以基于中断上下文提供恢复逻辑。
     *
     * <p>实现指引：
     * <ul>
     *   <li>简单代理：返回基本的中断确认消息即可</li>
     *   <li>复杂代理：生成包含未完成操作或中间结果的摘要</li>
     *   <li>有状态代理：返回前确保状态已被妥善保存</li>
     * </ul>
     *
     * @param context 包含中断元数据的中断上下文
     * @param originalArgs 调用方传给 {@code call()} 的原始入参（空、单条 Msg、或 List）
     * @return 返回给用户的恢复消息
     */
    protected abstract Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs);

    /**
     * Returns the agent's mutable runtime state, or {@code null} if this agent type does not
     * maintain an {@link AgentState}.
     */
    /**
     * 返回代理的可变运行时状态；若本代理类型不维护 {@link AgentState} 则返回 {@code null}。
     *
     * @return 代理状态实例或 {@code null}
     */
    public AgentState getAgentState() {
        return null;
    }

    /**
     * Invoked at the start of a {@code call} / stream-backed call, after {@link
     * #acquireExecution} and before any hooks. {@link io.agentscope.core.ReActAgent} uses this to
     * activate the per-call session slot from the supplied {@link RuntimeContext} and returns the
     * freshly-built per-call scope object, which is carried on the Reactor Context under {@link
     * #CALL_SCOPE_KEY}. Returning the scope here (rather than via a separate accessor) avoids any
     * window in which a concurrent call could overwrite a shared field between construction and
     * capture.
     *
     * @param msgs the messages passed by the caller to {@code call()}
     * @param rc the caller-supplied per-call {@link RuntimeContext}, or {@code null} when none was
     *     provided (read from the Reactor Context, so concurrency-safe)
     * @param control this execution's independent interruption and cancellation control
     * @return this call's per-call scope object, or {@code null} if this agent type keeps none
     */
    /**
     * 在 {@code call()} / 流式调用开始时触发，发生在 {@link #acquireExecution} 之后、所有 Hook 之前。
     * {@link io.agentscope.core.ReActAgent} 通过它从传入的 {@link RuntimeContext} 激活 per-call 会话槽，
     * 并返回新建的 per-call scope 对象（该对象随后挂到 Reactor Context 的 {@link #CALL_SCOPE_KEY}）。
     * 在此处返回 scope（而不是通过单独的访问器）避免了"构造后到捕获之间"的窗口被并发调用覆盖。
     *
     * @param msgs 调用方传给 {@code call()} 的消息列表
     * @param rc 调用方提供的 per-call {@link RuntimeContext}；未提供时为 {@code null}（从 Reactor Context 读取，并发安全）
     * @param control 本次执行独立的中断与取消控制句柄
     * @return 本次调用的 per-call scope 对象；本代理类型不维护 scope 时返回 {@code null}
     */
    protected Object beforeAgentExecution(List<Msg> msgs, RuntimeContext rc, RunControl control) {
        return null;
    }

    /**
     * Invoked in {@code Mono.using} cleanup, before clearing the running state. Pairs with {@link
     * #beforeAgentExecution(List, RuntimeContext, RunControl)}. The default is a no-op.
     */
    /**
     * 在 {@code Mono.using} 的清理阶段触发，发生在清除运行状态之前；与
     * {@link #beforeAgentExecution(List, RuntimeContext)} 配对使用。默认空操作。
     */
    protected void afterAgentExecution() {}

    /**
     * Get the list of hooks for this agent.
     * Protected to allow subclasses to access hooks for custom notification logic.
     *
     * @return List of hooks
     */
    /**
     * 获取本代理的 Hook 列表。
     * 设为 protected 是允许子类访问 hooks 以实现自定义的通知逻辑。
     *
     * @return Hook 列表
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    /**
     * Add a hook to this agent dynamically.
     *
     * <p>Hooks can be added during agent execution to provide temporary functionality.
     * This is commonly used for structured output handling or other short-lived behaviors.
     *
     * @param hook The hook to add
     */
    /**
     * 动态向本代理添加一个 Hook。
     *
     * <p>允许在代理执行期间动态添加 Hook，常用于结构化输出处理或其它短生命周期行为。
     *
     * @param hook 待添加的 Hook
     */
    protected void addHook(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
            // 新加入的 hook 需按优先级重新排序，保持执行顺序
            sortHooks();
        }
    }

    private void sortHooks() {
        // 按 Hook.priority() 升序排序；同优先级维持注册顺序
        this.hooks.sort(HOOK_COMPARATOR);
    }

    /**
     * Remove a hook from this agent dynamically.
     *
     * <p>Hooks should be removed when they are no longer needed to avoid memory leaks
     * and unintended side effects.
     *
     * @param hook The hook to remove
     */
    /**
     * 动态从本代理移除一个 Hook。
     *
     * <p>当 Hook 不再被需要时应当移除，避免内存泄漏和意外的副作用。
     *
     * @param hook 待移除的 Hook
     */
    protected void removeHook(Hook hook) {
        if (hook != null) {
            hooks.remove(hook);
        }
    }

    /**
     * Get hooks sorted by priority (lower value = higher priority).
     * Hooks with the same priority maintain registration order.
     *
     * @return Sorted list of hooks
     */
    /**
     * 获取按优先级排序的 Hook 列表（数值越小优先级越高）。
     * 同优先级的 Hook 维持注册顺序。
     *
     * @return 已排序的 Hook 列表
     */
    public List<Hook> getSortedHooks() {
        return hooks;
    }

    /**
     * Returns the initial system message to seed into {@link PreCallEvent} before hooks run.
     *
     * <p>The default implementation returns {@code null}. Subclasses (e.g. {@code ReActAgent})
     * override this to build a system message from their configured {@code sysPrompt}.
     *
     * @param callScope the per-call scope captured at call entry (may be {@code null}); lets
     *     subclasses resolve the call's {@link RuntimeContext} for system-prompt middlewares without
     *     reading a shared instance field
     * @return the seed system message; empty Mono if none
     */
    /**
     * 返回注入到 {@link PreCallEvent} 中的初始系统消息（在 Hook 执行前生效）。
     *
     * <p>默认返回空 Mono。子类（如 {@code ReActAgent}）覆盖该方法以根据配置的 {@code sysPrompt}
     * 构造系统消息。
     *
     * @param callScope 调用入口处捕获的 per-call scope（可为 {@code null}）；允许子类解析本次调用的
     *     {@link RuntimeContext}，避免直接读取共享实例字段
     * @return 初始系统消息；无则返回空 Mono
     */
    protected Mono<Msg> seedSystemMsg(Object callScope) {
        return Mono.empty();
    }

    /**
     * Returns the {@link AgentState} whose conversation buffer should seed the pre-call memory
     * snapshot for this invocation. The default reads the agent-level {@link #getAgentState()};
     * subclasses with per-call scope (e.g. {@code ReActAgent}) return the scope's session state so
     * the snapshot is concurrency-correct.
     *
     * @param callScope the per-call scope captured at call entry (may be {@code null})
     * @return the state to snapshot, or {@code null} if none
     */
    /**
     * 返回用于本次调用 pre-call 记忆快照的 {@link AgentState}。
     * 默认读取代理级的 {@link #getAgentState()}；拥有 per-call scope 的子类（如 {@code ReActAgent}）
     * 返回该 scope 的会话状态，使快照在并发场景下依然正确。
     *
     * @param callScope 调用入口处捕获的 per-call scope（可为 {@code null}）
     * @return 用于快照的状态实例；无则返回 {@code null}
     */
    protected AgentState stateForCall(Object callScope) {
        return getAgentState();
    }

    /**
     * Called after {@link PreCallEvent} hooks have run, with the final system message value.
     *
     * <p>The default implementation is a no-op. Subclasses (e.g. {@code ReActAgent}) override
     * this to persist the system message into a per-call {@code AtomicReference} so it is
     * available to subsequent events ({@code PreReasoningEvent}, {@code PreSummaryEvent}).
     *
     * @param systemMsg the system message produced by all PreCall hooks (may be null)
     * @param callScope the per-call scope captured at call entry (see {@link #beforeAgentExecution(List, RuntimeContext, RunControl)});
     *     may be {@code null}
     */
    /**
     * 在所有 {@link PreCallEvent} Hook 执行完毕后被调用，传入最终的 system message 值。
     *
     * <p>默认空操作。子类（如 {@code ReActAgent}）覆盖此方法以将系统消息持久化到 per-call 的
     * {@code AtomicReference}，供后续事件（{@code PreReasoningEvent}、{@code PreSummaryEvent}）使用。
     *
     * @param systemMsg 由所有 PreCall Hook 产出的系统消息（可为 null）
     * @param callScope 调用入口处捕获的 per-call scope（参见 {@link #beforeAgentExecution(List, RuntimeContext)}）；可为 {@code null}
     */
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg, Object callScope) {}

    /**
     * Notify all hooks that agent is starting (preCall hook).
     *
     * <p>The event's {@code inputMessages} contains the full message view:
     * a snapshot of the agent's current memory followed by the {@code callArgs} passed to
     * {@code call()}. Hooks may append non-SYSTEM messages to the tail. Injecting
     * {@link MsgRole#SYSTEM} messages via {@code setInputMessages} is forbidden and
     * detected at the end of this method — use {@link PreCallEvent#setSystemMessage} or
     * {@link PreCallEvent#appendSystemContent} instead.
     *
     * <p>After hooks run the system message is handed off via
     * {@link #consumeSystemMsgAfterPreCall(Msg, Object)}, and only the tail (messages beyond the
     * snapshot boundary) is returned for {@code doCall} to add to memory.
     *
     * @param callArgs messages passed by the caller to {@code call()}
     * @return Mono containing the new tail messages that {@code doCall} should add to memory
     */
    /**
     * 通知所有 Hook：代理即将开始执行（preCall Hook）。
     *
     * <p>事件中的 {@code inputMessages} 是完整视图：先放代理当前记忆的快照，再追加调用方传给
     * {@code call()} 的 {@code callArgs}。Hook 可向尾部追加非 SYSTEM 消息。
     * 通过 {@code setInputMessages} 注入 {@link MsgRole#SYSTEM} 消息是被禁止的，
     * 本方法结尾会检查；如需注入系统消息请改用 {@link PreCallEvent#setSystemMessage}
     * 或 {@link PreCallEvent#appendSystemContent}。
     *
     * <p>Hook 执行完成后，系统消息通过 {@link #consumeSystemMsgAfterPreCall(Msg, Object)} 移交；
     * 仅尾部（快照边界之外的新增消息）会返回，供 {@code doCall} 写入记忆。
     *
     * @param callArgs 调用方传给 {@code call()} 的消息列表
     * @return 携带 {@code doCall} 应写入记忆的新增尾部消息的 Mono
     */
    private Mono<List<Msg>> notifyPreCall(List<Msg> callArgs, Object callScope) {
        // 在 Hook 执行前先取一次状态快照（pre-hook 视图），快照取自本次 scope 的状态
        List<Msg> snapshot = List.of();
        AgentState agentState = stateForCall(callScope);
        if (agentState != null) {
            snapshot = agentState.getContext();
        }
        final int snapshotSize = snapshot.size();

        // 构造 Hook 可见的完整输入：snapshot + callArgs
        List<Msg> fullInput = new ArrayList<>(snapshot);
        if (callArgs != null) {
            fullInput.addAll(callArgs);
        }

        PreCallEvent event = new PreCallEvent(this, fullInput);

        Mono<PreCallEvent> result =
                seedSystemMsg(callScope).doOnNext(event::setSystemMessage).thenReturn(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }

        return result.map(
                e -> {
                    // 把最终系统消息转交给 per-call 状态
                    consumeSystemMsgAfterPreCall(e.getSystemMessage(), callScope);

                    // 取出 Hook 在快照边界之后追加的尾部消息
                    List<Msg> currentInput = e.getInputMessages();
                    List<Msg> tail;
                    if (currentInput == null || currentInput.size() <= snapshotSize) {
                        tail = List.of();
                    } else {
                        tail =
                                new ArrayList<>(
                                        currentInput.subList(snapshotSize, currentInput.size()));
                    }

                    // 守卫（仅 ReActAgent）：Hook 不得向尾部注入 SYSTEM 消息，
                    // 因为尾部会被持久化到记忆，SYSTEM 消息会不断累积。
                    // 没有记忆的代理（如 UserAgent）允许合法地把 SYSTEM 消息作为 call 参数。
                    if (AgentBase.this instanceof io.agentscope.core.ReActAgent) {
                        for (Msg msg : tail) {
                            if (msg != null && msg.getRole() == MsgRole.SYSTEM) {
                                throw new IllegalStateException(
                                        "Hooks must not inject SYSTEM messages into"
                                                + " PreCallEvent.inputMessages. Use"
                                                + " event.setSystemMessage() or"
                                                + " event.appendSystemContent() instead.");
                            }
                        }
                    }

                    return tail;
                });
    }

    /**
     * Notify all hooks about completion (postCall hook).
     * After hook notification, broadcasts the message to all subscribers.
     *
     * @param finalMsg Final message
     * @return Mono containing potentially modified final message
     */
    /**
     * 通知所有 Hook：代理调用已完成（postCall Hook）。
     * Hook 通知完成后，再把消息广播给所有订阅者。
     *
     * @param finalMsg 最终消息
     * @return 携带可能被 Hook 修改过的最终消息的 Mono
     */
    private Mono<Msg> notifyPostCall(Msg finalMsg) {
        if (finalMsg == null) {
            return Mono.error(new IllegalStateException("Agent returned null message"));
        }
        PostCallEvent event = new PostCallEvent(this, finalMsg);
        Mono<PostCallEvent> result = Mono.just(event);
        // 按优先级串行执行所有 Hook
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        // Hook 处理后再广播给 MsgHub 订阅者
        return result.map(PostCallEvent::getFinalMessage)
                .flatMap(msg -> broadcastToSubscribers(msg).thenReturn(msg));
    }

    /**
     * Notify all hooks about error.
     *
     * @param error The error
     * @return Mono that completes when all hooks are notified
     */
    /**
     * 把错误广播给所有 ErrorEvent Hook。
     *
     * @param error 待通知的错误
     * @return 全部 Hook 处理完毕后结束的 Mono
     */
    private Mono<Void> notifyError(Throwable error) {
        ErrorEvent event = new ErrorEvent(this, error);
        return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
    }

    /**
     * Remove all subscribers for a specific MsgHub.
     * This method is typically called when a MsgHub is being destroyed or reset.
     * After calling this method, the agent will no longer receive messages from the specified hub.
     *
     * @param hubId MsgHub identifier
     */
    /**
     * 移除指定 MsgHub 的全部订阅者。
     * 通常在 MsgHub 被销毁或重置时调用；调用后本代理不再从该 Hub 接收消息。
     *
     * @param hubId MsgHub 标识
     */
    public void removeSubscribers(String hubId) {
        hubSubscribers.remove(hubId);
    }

    /**
     * Reset the subscriber list for a specific MsgHub.
     * This replaces any existing subscribers for the given hub with the new list.
     * Typically called by MsgHub when the subscription topology changes.
     *
     * @param hubId MsgHub identifier
     * @param subscribers New list of subscribers (will be copied)
     */
    /**
     * 重置指定 MsgHub 的订阅者列表：用新列表覆盖已有订阅者。
     * 通常在 MsgHub 的订阅拓扑发生变化时由 MsgHub 触发。
     *
     * @param hubId MsgHub 标识
     * @param subscribers 新的订阅者列表（会复制以避免外部修改）
     */
    public void resetSubscribers(String hubId, List<AgentBase> subscribers) {
        // 复制入参避免外部后续修改影响内部状态
        hubSubscribers.put(hubId, new ArrayList<>(subscribers));
    }

    /**
     * Check if this agent has any subscribers.
     * Subscribers are agents that will receive messages published through MsgHub.
     *
     * @return True if agent has one or more subscribers
     */
    /**
     * 判断本代理是否存在订阅者。
     * 订阅者是指通过 MsgHub 接收本代理发布消息的代理。
     *
     * @return 存在至少一个订阅者则返回 true
     */
    public boolean hasSubscribers() {
        // 至少存在一个非空订阅者列表才算有订阅者
        return !hubSubscribers.isEmpty()
                && hubSubscribers.values().stream().anyMatch(list -> !list.isEmpty());
    }

    /**
     * Get the total number of subscribers across all MsgHubs.
     * Subscribers are agents that will receive messages published through MsgHub.
     *
     * @return Total count of subscribers
     */
    /**
     * 获取跨所有 MsgHub 的订阅者总数。
     *
     * @return 订阅者总数
     */
    public int getSubscriberCount() {
        return hubSubscribers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Broadcast a message to all subscribers across all MsgHubs.
     * This method is called automatically after each agent call to implement
     * the MsgHub auto-broadcast functionality.
     *
     * @param msg Message to broadcast
     * @return Mono that completes when all subscribers have observed the message
     */
    /**
     * 把消息广播给所有 MsgHub 上的订阅者。
     * 该方法在每次代理调用结束后自动触发，用以实现 MsgHub 的自动广播能力。
     *
     * @param msg 待广播的消息
     * @return 全部订阅者观察完成后结束的 Mono
     */
    private Mono<Void> broadcastToSubscribers(Msg msg) {
        if (hubSubscribers.isEmpty()) {
            return Mono.empty();
        }
        // 扁平化两层 Map.values -> List<AgentBase>，逐个调用 observe
        return Flux.fromIterable(hubSubscribers.values())
                .flatMap(Flux::fromIterable)
                .flatMap(subscriber -> subscriber.observe(msg))
                .then();
    }

    /**
     * Observe a single message without generating a reply.
     * This is the public API that delegates to doObserve implementation.
     *
     * @param msg Message to observe
     * @return Mono that completes when observation is done
     */
    /**
     * 单条消息的观察入口（公开 API），内部委托给 {@link #doObserve}。
     *
     * @param msg 待观察的消息
     * @return 观察完成时结束的 Mono
     */
    @Override
    public final Mono<Void> observe(Msg msg) {
        return doObserve(msg);
    }

    /**
     * Observe multiple messages without generating a reply.
     * This is the public API that delegates to doObserve implementation.
     *
     * @param msgs Messages to observe
     * @return Mono that completes when all observations are done
     */
    /**
     * 多条消息的观察入口（公开 API），逐条调用 {@link #doObserve}。
     *
     * @param msgs 待观察的消息列表
     * @return 全部观察完成后结束的 Mono
     */
    @Override
    public final Mono<Void> observe(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(msgs).flatMap(this::doObserve).then();
    }

    /**
     * Stream with multiple input messages.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     * @deprecated since 2.0.0, for removal. Use {@code ReActAgent#streamEvents(List)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    /**
     * 流式执行：输入多条消息，返回执行过程中的事件流。
     *
     * @param msgs 输入消息列表
     * @param options 流配置选项
     * @return 执行过程中发出的事件 Flux
     * @deprecated 自 2.0.0 起，将被移除。请改用 {@code ReActAgent#streamEvents(List)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return createEventStream(options, () -> call(msgs));
    }

    /**
     * Stream with multiple input messages.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Optional class defining the structure
     * @return Flux of events emitted during execution
     * @deprecated since 2.0.0, for removal. Use {@code ReActAgent#streamEvents(...)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    /**
     * 流式执行（结构化输出 Class 版本）。
     *
     * @param msgs 输入消息列表
     * @param options 流配置选项
     * @param structuredModel 可选，定义输出结构的 Class
     * @return 执行过程中发出的事件 Flux
     * @deprecated 自 2.0.0 起，将被移除。请改用 {@code ReActAgent#streamEvents(...)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return createEventStream(options, () -> call(msgs, structuredModel));
    }

    /**
     * Stream with multiple input messages using a JSON schema.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param schema JSON schema defining the structure of the response
     * @return Flux of events emitted during execution
     * @deprecated since 2.0.0, for removal. Use {@code ReActAgent#streamEvents(...)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    /**
     * 流式执行（结构化输出 JSON Schema 版本）。
     *
     * @param msgs 输入消息列表
     * @param options 流配置选项
     * @param schema 定义响应结构的 JSON Schema
     * @return 执行过程中发出的事件 Flux
     * @deprecated 自 2.0.0 起，将被移除。请改用 {@code ReActAgent#streamEvents(...)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return createEventStream(options, () -> call(msgs, schema));
    }

    /**
     * Helper method to create an event stream with proper hook lifecycle management.
     *
     * <p>This method handles the common logic for streaming events during agent execution,
     * including:
     * <ul>
     *   <li>Creating and registering a temporary StreamingHook</li>
     *   <li>Managing the hook lifecycle (add/remove from hooks list)</li>
     *   <li>Optionally emitting the final agent result as an event</li>
     *   <li>Properly propagating errors and completion signals</li>
     * </ul>
     *
     * @param options Stream configuration options
     * @param callSupplier Supplier that executes the agent call (either single message or list)
     * @return Flux of events emitted during execution
     */
    /**
     * 构造事件流的辅助方法，统一管理 Hook 生命周期。
     *
     * <p>该方法封装代理流式执行期间的公共逻辑：
     * <ul>
     *   <li>创建并注册一个临时的 StreamingHook</li>
     *   <li>管理 Hook 生命周期（加入/移出 hooks 列表）</li>
     *   <li>可选地将最终结果作为事件发出</li>
     *   <li>正确传递错误与完成信号</li>
     * </ul>
     *
     * @param options 流配置选项
     * @param callSupplier 触发代理调用的供应器（单条或多条消息均可）
     * @return 执行过程中发出的事件 Flux
     */
    private Flux<Event> createEventStream(StreamOptions options, Supplier<Mono<Msg>> callSupplier) {
        return Flux.deferContextual(
                ctxView ->
                        Flux.<Event>create(
                                        sink -> {
                                            // 用 sink 构造 StreamingHook，把流式事件全部转给 sink
                                            StreamingHook streamingHook =
                                                    new StreamingHook(sink, options);

                                            // 临时加入 Hook 流
                                            addHook(streamingHook);

                                            // 子代理工具通过该总线把子事件推送到父 sink，无需额外的 Flux 层级
                                            SubagentEventBus bus = sink::next;

                                            // 用 Mono.defer 保证 trace context 透传同时不影响 streaming hook
                                            Disposable callDisposable =
                                                    Mono.defer(() -> callSupplier.get())
                                                            .contextWrite(
                                                                    context ->
                                                                            context.put(
                                                                                            SubagentEventBus
                                                                                                    .CONTEXT_KEY,
                                                                                            bus)
                                                                                    .putAll(
                                                                                            ctxView))
                                                            .doFinally(
                                                                    signalType -> {
                                                                        // 收尾：移除临时 Hook
                                                                        hooks.remove(streamingHook);
                                                                    })
                                                            .subscribe(
                                                                    finalMsg -> {
                                                                        // 按 StreamOptions
                                                                        // 决定是否发出最终结果事件
                                                                        if (options.shouldStream(
                                                                                EventType
                                                                                        .AGENT_RESULT)) {
                                                                            sink.next(
                                                                                    new Event(
                                                                                            EventType
                                                                                                    .AGENT_RESULT,
                                                                                            finalMsg,
                                                                                            true));
                                                                        }
                                                                    },
                                                                    sink::error,
                                                                    sink::complete);
                                            sink.onCancel(callDisposable);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                // 切到 boundedElastic 调度器，避免阻塞事件循环
                                .publishOn(Schedulers.boundedElastic()));
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }
}
