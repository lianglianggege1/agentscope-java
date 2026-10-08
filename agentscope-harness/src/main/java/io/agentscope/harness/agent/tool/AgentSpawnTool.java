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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.SessionIdUtils;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.RemoteAskPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.protocol.RemoteStreamDetail;
import io.agentscope.harness.agent.subagent.task.AgentProtocolTransport;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.RemoteSubmitContext;
import io.agentscope.harness.agent.subagent.task.RemoteTarget;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Simple subagent tool for agent-internal use. Much lighter than {@code SessionsTool}:
 *
 * <ul>
 *   <li>{@code agent_spawn} — spawn a subagent, run task, return result (sync or async)
 *   <li>{@code agent_send} — send follow-up message to a previously spawned subagent
 *   <li>{@code agent_list} — list active subagents
 * </ul>
 *
 * <p>No sessions, no lanes, no run registry, no announce dispatch. Just "create agent, invoke,
 * return result". Uses {@link DefaultAgentManager} for agent creation and invocation only.
 *
 * <p>Async tasks ({@code timeout_seconds=0}) are submitted to the {@link TaskRepository} scoped
 * by the current session ID from {@link RuntimeContext}. This makes task state visible in
 * workspace storage for cross-node retrieval and recovery after compaction.
 *
 * <h2>Streaming</h2>
 *
 * <p>{@code agent_spawn} and {@code agent_send} return {@link Mono}{@code <String>} so that the
 * framework's reactive tool-invocation pipeline (see {@code ToolMethodInvoker}) can subscribe them
 * within the parent agent's streaming chain. When a {@link SubagentEventBus} is present in the
 * Reactor Context (injected by {@code AgentBase.createEventStream}), every child {@link
 * io.agentscope.core.agent.Event} is forwarded to the parent sink in real time, giving consumers
 * a flattened event stream across the full call hierarchy. When no bus is present (plain {@code
 * call()} mode), execution falls back to the non-streaming {@code invokeAgent} path with no
 * overhead.
 */
/**
 * 供智能体内部使用的简易子智能体工具，相比 {@code SessionsTool} 轻量化很多：
 *
 * <ul>
 *   <li>{@code agent_spawn} — 创建子智能体、执行任务并返回结果（同步或异步）
 *   <li>{@code agent_send} — 向已创建的子智能体发送后续消息
 *   <li>{@code agent_list} — 列出活跃的子智能体
 * </ul>
 *
 * <p>不支持会话、通道、运行注册表与通知分发，仅实现“创建智能体 → 执行调用 → 返回结果”。
 * 仅依托 {@link DefaultAgentManager} 完成智能体创建与调用。
 *
 * <p>异步任务（设置 {@code timeout_seconds=0}）会提交至 {@link TaskRepository}，
 * 作用域取自 {@link RuntimeContext} 中的当前会话ID。任务状态持久在工作空间存储，
 * 支持跨节点读取，并可在会话压缩后完成状态恢复。
 *
 * <h2>流式输出</h2>
 *
 * <p>{@code agent_spawn} 与 {@code agent_send} 返回 {@link Mono}{@code <String>}，
 * 框架响应式工具调用链路（参见 {@code ToolMethodInvoker}）可在父智能体流式链路中订阅。
 * 若Reactor上下文内存在 {@link SubagentEventBus}（由 {@code AgentBase.createEventStream} 注入），
 * 子智能体产生的所有 {@link io.agentscope.core.agent.Event} 将实时转发至父端接收器，
 * 向消费侧提供完整调用链路扁平化事件流。若无事件总线（普通 {@code call()} 调用模式），
 * 将自动切换至无额外开销的非流式 {@code invokeAgent} 执行路径。
 */
public class AgentSpawnTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSpawnTool.class);

    /** 同步等待默认超时：30 秒。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 同步等待超时上限：600 秒。 */
    private static final int MAX_TIMEOUT_SECONDS = 600;

    /** 子智能体最大嵌套创建深度。 */
    private static final int MAX_SPAWN_DEPTH = 3;

    /**
     * {@link RuntimeContext} string key for a per-call override of subagent user-exposure. Put a
     * {@link Boolean} (or its string form) under this key to control exposure for every
     * {@code agent_spawn} in the current call, independent of what the LLM requests.
     *
     * <p>Example:
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .userId("user-1")
     *     .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
     *     .build();
     * }</pre>
     *
     * <p>Resolution precedence (highest first): this context value → the spawned subagent's
     * {@link SubagentDeclaration#getExposeToUser()} policy → the LLM's {@code expose_to_user}
     * argument → {@code false}.
     */
    /**
     * {@link RuntimeContext} 字符串键，用于单次调用强制覆盖子智能体对外暴露策略。
     * 在该键下存入 {@link Boolean}（或布尔字符串），即可控制当前调用内所有
     * {@code agent_spawn} 的暴露行为，不受大模型入参影响。
     *
     * <p>示例：
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .userId("user-1")
     *     .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
     *     .build();
     * }</pre>
     *
     * <p>优先级顺序（从高到低）：上下文该键值 → 被创建子智能体的
     * {@link SubagentDeclaration#getExposeToUser()} 策略 → 大模型传入的 {@code expose_to_user} 参数 → 默认值 {@code false}。
     */
    public static final String CTX_EXPOSE_TO_USER = "agentscope.subagent.expose_to_user";

    /**
     * {@link RuntimeContext} string key for the immutable subagent registry selected by the
     * current parent-agent invocation. {@link
     * io.agentscope.harness.agent.middleware.SubagentsMiddleware} installs a namespace-scoped
     * manager here so concurrent callers never overwrite each other's declarations.
     */
    public static final String CTX_AGENT_MANAGER = "agentscope.subagent.agent_manager";

    /**
     * {@link RuntimeContext} string key holding a {@code Map<String, Object>} of caller-defined
     * attributes to send with every remote subagent submission of the current call, as
     * {@code context.attributes}:
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .sessionId("s-1")
     *     .put(AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES, Map.of("tenant", "acme"))
     *     .build();
     * }</pre>
     *
     * <p>Merged over the subagent's static {@link
     * SubagentDeclaration#getRemoteContextAttributes()}, so a per-call value wins on conflict.
     * Values must be JSON-serializable.
     */
    public static final String CTX_REMOTE_CONTEXT_ATTRIBUTES =
            "agentscope.subagent.remote_context_attributes";

    /**
     * {@link RuntimeContext} string key that forces synchronous subagent execution for the current
     * call. Put a {@link Boolean} (or its string form) under this key to ignore LLM-requested
     * background mode ({@code timeout_seconds=0}) and to disable timeout promotion to a background
     * {@code task_id}.
     *
     * <p>When force-sync is on:
     *
     * <ul>
     *   <li>{@code timeout_seconds=0} is coerced to the default sync timeout (30s), unless {@link
     *       #CTX_FORCE_SYNC_TIMEOUT_SECONDS} supplies an absolute override
     *   <li>If the sync wait exceeds the timeout, the subagent is interrupted and the tool returns
     *       {@code status: timeout} — it is <em>not</em> promoted to an async background task
     * </ul>
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .sessionId("s-1")
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
     *     .build();
     * }</pre>
     *
     * <p>Applies to {@code agent_spawn} and {@code agent_send}. Multiple sync tool calls in one
     * turn still run in parallel by default (Toolkit {@code parallel=true}).
     */
    public static final String CTX_FORCE_SYNC = "agentscope.subagent.force_sync";

    /**
     * Optional {@link RuntimeContext} override for the sync wait (seconds) when {@link
     * #CTX_FORCE_SYNC} is enabled. Accepts an {@link Integer}/{@link Number} or its string form.
     *
     * <p>When present (and force-sync is on), this value fully replaces the LLM's {@code
     * timeout_seconds} for the call. Values {@code <= 0} fall back to the default sync timeout
     * (30s); values above 600 are clamped.
     *
     * <p>Ignored when force-sync is off.
     *
     * <pre>{@code
     * RuntimeContext ctx = RuntimeContext.builder()
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
     *     .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120)
     *     .build();
     * }</pre>
     */
    public static final String CTX_FORCE_SYNC_TIMEOUT_SECONDS =
            "agentscope.subagent.force_sync_timeout_seconds";

    /* 中文译文（当前未启用，保留备查）
    private static final String BG_RESULT_TEMPLATE =
    """
    status: accepted
    task_id: %s
    可使用 task_output(task_id='%s', block=false) 查询状态，\
    task_cancel(task_id='%s') 终止任务，或调用 task_list() 查看全部任务。\
    请勿立即调用 task_output — 当前任务刚刚启动。\
    """;*/

    /** 异步任务受理回执模板：告知 task_id 及查询/取消方式，并提醒不要立即查询。 */
    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output(task_id='%s', block=false) to check status, \
            wait_async_results(task_ids=...) to wait for a chosen group, \
            wait_async_results(wait_all=true) to wait for all current background tasks, \
            task_cancel(task_id='%s') to cancel, or task_list() to see all tasks. \
            Do NOT call task_output immediately — the task has just started.\
            """;

    /** Short poll interval used while waiting for a remote sync task, to detect awaiting_confirm promptly. */
    private static final long REMOTE_CONFIRM_POLL_MS = 1_000L;

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;

    /** 网关桥接器（可变）：用于将子智能体暴露为用户可寻址线程，支持延迟装配。 */
    private volatile SubagentGatewayBridge gatewayBridge;

    private volatile RemoteSubagentTransport remoteTransport = new AgentProtocolTransport();

    /** 已创建子智能体的内存记录：key、类型、会话ID、标签、智能体实例与层级。 */
    private record SpawnedAgent(
            String key, String agentId, String sessionId, String label, Agent agent, int depth) {}

    /** key → 子智能体记录的内存注册表。 */
    private final ConcurrentHashMap<String, SpawnedAgent> agentsByKey = new ConcurrentHashMap<>();

    /** 标签（小写）→ key 的索引，支持按标签寻址。 */
    private final ConcurrentHashMap<String, String> labelToKey = new ConcurrentHashMap<>();

    /**
     * Creates an {@code AgentSpawnTool} that derives the active user-id from each tool call's
     * {@link RuntimeContext}, rather than a shared supplier — this prevents identity races when a
     * single agent instance serves concurrent callers.
     *
     * @param agentManager factory and invoker for subagents
     * @param taskRepository background task store
     * @param parentSpawnDepth current spawn-depth of the parent (0 for top-level main agent)
     */
    /**
     * 创建 {@code AgentSpawnTool} 实例。该工具从每次工具调用的 {@link RuntimeContext}
     * 获取当前用户ID，而非使用共享供应器，以此防止单个智能体实例并发服务多个调用者时出现身份竞争问题。
     *
     * @param agentManager 子智能体工厂与执行器
     * @param taskRepository 后台任务存储器
     * @param parentSpawnDepth 父智能体当前创建层级（顶层主智能体取值为 0）
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager, TaskRepository taskRepository, int parentSpawnDepth) {
        this(agentManager, taskRepository, parentSpawnDepth, null);
    }

    /**
     * Creates an {@code AgentSpawnTool} with an optional gateway bridge for exposing subagents
     * as user-addressable threads.
     *
     * @param agentManager factory and invoker for subagents
     * @param taskRepository background task store
     * @param parentSpawnDepth current spawn-depth of the parent (0 for top-level main agent)
     * @param gatewayBridge optional bridge for thread exposure; null for standalone mode
     */
    /**
     * 创建 {@code AgentSpawnTool} 实例，可传入可选网关桥接器，用于将子智能体对外暴露为用户可直接访问的会话线程。
     *
     * @param agentManager 子智能体工厂与调用执行器
     * @param taskRepository 后台任务存储
     * @param parentSpawnDepth 父智能体当前创建层级（顶层主智能体取值为 0）
     * @param gatewayBridge 用于暴露会话线程的可选桥接器；传入 null 代表独立运行模式
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager,
            TaskRepository taskRepository,
            int parentSpawnDepth,
            SubagentGatewayBridge gatewayBridge) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.taskRepository = taskRepository;
        this.parentSpawnDepth = parentSpawnDepth;
        this.gatewayBridge = gatewayBridge;
    }

    /**
     * Wires (or re-wires) the gateway bridge used to expose subagents as user-addressable threads.
     *
     * <p>Mutating the bridge on the live instance — rather than constructing a replacement — is
     * essential: the toolkit binds {@code agent_spawn} to this exact object at orchestration time,
     * so a replacement tool would never receive calls. The bridge is typically supplied lazily,
     * after the agent is built, when its internal gateway is created (see
     * {@code HarnessAgent#ensureGateway}).
     *
     * @param gatewayBridge the bridge implementation, or {@code null} to disable exposure
     */
    /**
     * 装配（或重新装配）网关桥接器，用于将子智能体对外暴露为用户可寻址会话线程。
     *
     * <p>必须在当前运行实例上修改桥接器，而非新建实例替换：
     * 编排阶段工具集已将 {@code agent_spawn} 绑定至当前对象，替换工具实例将无法接收调用。
     * 桥接器通常采用延迟注入方式，在智能体构建完成、内部网关创建后进行设置（参见
     * {@code HarnessAgent#ensureGateway}）。
     *
     * @param gatewayBridge 桥接器实现；传入 {@code null} 则关闭暴露能力
     */
    public void setGatewayBridge(SubagentGatewayBridge gatewayBridge) {
        this.gatewayBridge = gatewayBridge;
    }

    /** Test-only hook to inject a fake {@link RemoteSubagentTransport} for remote streaming/HITL tests. */
    void setRemoteTransport(RemoteSubagentTransport remoteTransport) {
        this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    }

    /* 中文译文（当前未启用，保留备查）
    @Tool(
            name = "agent_spawn",
            stateInjected = true,
            description =
                    """
                    创建独立子智能体，用于任务委派或后台作业。\
                    每次返回内容以三行信息开头：agent_key（原样传给 agent_send 作为 agent_key）、\
                    agent_id（子智能体类型名称）、session_id（内部标识，不可用作 agent_key）。\
                    同步模式在三行信息下方直接返回应答；异步模式（timeout_seconds=0）额外返回 task_id，\
                    可通过 task_output 查询结果——task_id 不等同于 agent_key。\
                    """)
    public Mono<String> agentSpawn(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(name = "agent_id", description = "待实例化的子智能体标识")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description = "下发给新建子智能体的任务指令或提示词",
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    "可选的可读标签，用于通过 agent_send 引用该子智能体",
                            required = false)
                    String label,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    等待任务结果的最大秒数。0 代表发后即忘，返回 task_id。\
                                    默认值：30，上限：600。\
                                    """,
                            required = false)
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "expose_to_user",
                            description =
                                    """
                                    设为 true 时，用户可通过 thread_id 句柄直接寻址该新建子智能体。\
                                    返回结果中会携带 thread_id。该功能需要预先配置网关桥接器。\
                                    """,
                            required = false)
                    Boolean exposeToUser) { }
    */

    /**
     * {@code agent_spawn}：创建独立子智能体并执行任务。核心流程：
     *
     * <ol>
     *   <li>深度校验（上限 {@code MAX_SPAWN_DEPTH=3}，防止无限嵌套）</li>
     *   <li>解析并创建子智能体；persist=true 时用确定性哈希生成稳定 key，
     *       已存在相同 key 的实例则直接复用</li>
     *   <li>标签唯一性校验与注册，并持久化 spawn 记录供恢复使用</li>
     *   <li>传播规划模式（父在规划模式则子只读）与 DENY 权限规则</li>
     *   <li>按需经网关桥接器将子智能体暴露为用户可寻址线程</li>
     *   <li>无任务直接返回；timeout=0 异步提交 {@link TaskRepository}；
     *       remote 声明走远程同步调用；其余走本地超时提升执行</li>
     * </ol>
     */
    @Tool(
            name = "agent_spawn",
            stateInjected = true,
            description =
                    """
                    Spawn an isolated subagent for delegated or background work. \
                    Every response starts with three lines: agent_key (pass this verbatim to \
                    agent_send as agent_key), agent_id (the subagent type name), and session_id \
                    (internal; do not use as agent_key). Sync mode returns the reply below that; \
                    async (timeout_seconds=0) adds task_id for task_output or wait_async_results; \
                    task_id is NOT agent_key. Multiple sync tool calls in one turn run in parallel \
                    by default; pass a Toolkit with parallel=false to serialize, \
                    or use async tasks for fire-and-forget parallelism.\
                    """)
    public Mono<String> agentSpawn(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(name = "agent_id", description = "Subagent identifier to instantiate")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description = "Task or prompt to send to the spawned agent",
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    "Optional human-readable label for referencing via agent_send",
                            required = false)
                    String label,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for the task result. 0=fire-and-forget, \
                                    returns task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "expose_to_user",
                            description =
                                    """
                                    When true, the spawned subagent becomes directly addressable \
                                    by the user via a thread_id handle. Returns thread_id in the \
                                    response. Requires a gateway bridge to be configured.\
                                    """,
                            required = false)
                    Boolean exposeToUser) {

        log.debug(
                "agent_spawn called: agentId={}, timeoutSeconds={}, task={}",
                agentId,
                timeoutSeconds,
                task);
        int nextDepth = parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            log.warn("agent_spawn depth exceeded: depth={}, max={}", nextDepth, MAX_SPAWN_DEPTH);
            return Mono.just("Error: Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")");
        }
        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);

        Optional<Agent> agentOpt = manager.createAgentIfPresent(agentId, runtimeContext);
        if (agentOpt.isEmpty()) {
            if (manager.isPrimaryOnly(agentId)) {
                return Mono.just(
                        "Error: agent_id '"
                                + agentId
                                + "' is PRIMARY-only and cannot be spawned as a subagent.");
            }
            log.warn("agent_spawn unknown agentId={}, known={}", agentId, manager);
            return Mono.just("Error: Unknown agent_id: " + agentId);
        }
        log.debug("agent_spawn resolved: agentId={}", agentId);
        Agent agent = agentOpt.get();
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = manager.getDeclaration(agentId);
        boolean persist = declOpt.map(SubagentDeclaration::isPersistSession).orElse(false);

        String key;
        String sessionId;
        if (persist) {
            String hash = deterministicHash(parentSessionId, agentId, canonLabel);
            key = "agent:" + agentId + ":" + hash;
            sessionId = "sub-" + hash;
            // Reuse existing agent if same deterministic key was already spawned.
            // 若相同确定性 key 的子智能体已被创建过，直接复用既有实例。
            SpawnedAgent existing = agentsByKey.get(key);
            if (existing != null) {
                propagatePlanMode(
                        parentState, currentUserId, existing.sessionId(), existing.agent());
                propagateParentDenyRules(
                        parentState,
                        currentUserId,
                        existing.sessionId(),
                        existing.agent(),
                        declOpt);
                String spawnInfo = formatSpawnInfo(key, agentId, sessionId, null);
                boolean hasTask = task != null && !task.isBlank();
                if (!hasTask) {
                    return Mono.just(spawnInfo + "\nstatus: accepted (reused)");
                }
                return execSpawnTask(
                        existing,
                        runtimeContext,
                        parentState,
                        spawnInfo,
                        task,
                        timeoutSeconds,
                        declOpt);
            }
        } else {
            key = "agent:" + agentId + ":" + UUID.randomUUID();
            sessionId = "sub-" + UUID.randomUUID();
        }

        // Label uniqueness check — skipped above for persist=true reuse path (already returned).
        // 标签唯一性校验——persist=true 的复用分支已在上方提前返回，无需重复校验。
        if (canonLabel != null && labelToKey.containsKey(canonLabel.toLowerCase())) {
            return Mono.just("Error: Label already in use: " + canonLabel);
        }

        SpawnedAgent spawned =
                new SpawnedAgent(key, agentId, sessionId, canonLabel, agent, nextDepth);
        agentsByKey.put(key, spawned);
        if (canonLabel != null) {
            labelToKey.put(canonLabel.toLowerCase(), key);
        }
        persistSpawnEntry(parentState, key, agentId, sessionId, canonLabel, nextDepth);

        // Propagate plan mode: if parent is in plan mode, force child into read-only mode too.
        // 传播规划模式：父智能体处于规划模式时，强制子智能体进入只读模式。
        propagatePlanMode(parentState, currentUserId, sessionId, agent);

        // Propagate DENY permission rules from parent to child (security boundary inheritance).
        // 将父智能体的 DENY 权限规则继承给子智能体（安全边界继承）。
        propagateParentDenyRules(parentState, currentUserId, sessionId, agent, declOpt);

        // Expose subagent to user via gateway bridge if requested. The effective decision combines
        // (in priority order) a per-call RuntimeContext override, the declaration policy, and the
        // LLM-supplied argument — so application code can force or forbid exposure regardless of
        // what the model decides.
        // 按需经网关桥接器将子智能体暴露给用户。生效的暴露决策按优先级组合：
        // 单次调用的 RuntimeContext 覆盖值 → 声明策略 → 大模型传入参数，
        // 应用侧代码可以强制开启或禁止暴露，不受模型决策影响。
        boolean effectiveExpose = resolveExposeToUser(exposeToUser, declOpt, runtimeContext);
        String subagentId = null;
        if (effectiveExpose && gatewayBridge != null) {
            OutboundAddress replyTo =
                    runtimeContext != null
                            ? runtimeContext.get("outboundAddress", OutboundAddress.class)
                            : null;
            SubagentGatewayBridge.ExposeResult er =
                    gatewayBridge.expose(agentId, sessionId, agent, replyTo);
            subagentId = er.subagentId();
        }

        String spawnInfo = formatSpawnInfo(key, agentId, sessionId, subagentId);
        boolean hasTask = task != null && !task.isBlank();

        if (!hasTask) {
            return withSubagentExposedEvent(
                    Mono.just(spawnInfo + "\nstatus: accepted"),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        boolean forceSync = isForceSync(runtimeContext);
        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(),
                                d.getHeaders(),
                                agentId,
                                capturedTask,
                                buildRemoteSubmitContext(runtimeContext, parentState, d));
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                manager.invokeAgent(
                                                                agent,
                                                                sessionId,
                                                                currentUserId,
                                                                capturedTask,
                                                                runtimeContext)
                                                        .block();
                                        return reply != null ? reply.getTextContent() : "";
                                    } catch (RuntimeException e) {
                                        return "Error: "
                                                + (e.getMessage() != null
                                                        ? e.getMessage()
                                                        : e.getClass().getSimpleName());
                                    }
                                });
            }
            taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);
            return withSubagentExposedEvent(
                    Mono.just(
                            spawnInfo
                                    + "\n"
                                    + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId)),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        if (remote) {
            final String finalTask = task;
            return withSubagentExposedEvent(
                    runRemoteSyncReactive(
                            runtimeContext,
                            parentState,
                            spawnInfo,
                            agentId,
                            parentSessionId,
                            declOpt.get(),
                            finalTask.trim(),
                            timeoutMs,
                            forceSync),
                    subagentId,
                    agentId,
                    sessionId,
                    canonLabel);
        }

        // Sync-local execution with timeout promotion: if the agent doesn't finish within the
        // timeout, its in-flight execution is promoted to an async task instead of being lost —
        // unless force-sync is on, in which case the agent is interrupted and status: timeout
        // is returned.
        // 本地同步执行并带超时提升机制：若智能体未在超时时间内完成，
        // 执行中的任务会被提升为异步任务，而非直接丢弃；force-sync 开启时例外——
        // 智能体会被中断并返回 status: timeout。
        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        final String finalSubagentId = subagentId;
        final String finalLabel = canonLabel;
        return withSubagentExposedEvent(
                execWithTimeoutPromotion(
                        agent,
                        sessionId,
                        currentUserId,
                        finalTask,
                        spawned,
                        runtimeContext,
                        finalSpawnInfo,
                        timeoutMs,
                        agentId,
                        forceSync),
                finalSubagentId,
                agentId,
                sessionId,
                finalLabel);
    }

    /*
    @Tool(
            name = "agent_send",
            stateInjected = true,
            description =
                    """
                    向已创建的子智能体发送消息。使用 agent_spawn 返回结果首行输出的 agent_key（以 agent: 开头），
                    或是创建时指定的 label。请勿传入 agent_id、session_id 或 task_id。
                    timeout_seconds=0 时返回 task_id，可通过 task_output 获取结果。\
                    """)
    public Mono<String> agentSend(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(
                            name = "agent_key",
                            description =
                                    "取自 agent_spawn 输出中 'agent_key: ' 后的完整字符串"
                                        + "（格式 agent:<type>:<uuid>）。并非 agent_id、session_id"
                                        + "或 task_id。与 label 互斥。",
                            required = false)
                    String agentKey,
            @ToolParam(
                            name = "label",
                            description =
                                    "创建子智能体时设置的标签。与 agent_key 互斥。",
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "发送给子智能体的消息内容")
                    String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    等待应答的最大秒数。0=发后即忘，返回 task_id。
                                    默认值：30，上限：600。\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {}
     */
    /**
     * {@code agent_send}：向已创建的子智能体发送后续消息。
     * 通过 agent_key 或 label 二选一定位子智能体（二者互斥）；
     * 内存注册表未命中时尝试从父状态持久化记录恢复。
     * timeout=0 异步提交返回 task_id；remote 声明走远程同步；其余走本地超时提升执行。
     */
    @Tool(
            name = "agent_send",
            stateInjected = true,
            description =
                    """
                    Send a message to an existing subagent. Use the exact string from the \
                    agent_key line of agent_spawn output (starts with agent:), or the label \
                    you set at spawn. Do not pass agent_id, session_id, or task_id here. \
                    timeout_seconds=0 returns task_id for task_output or wait_async_results.\
                    """)
    public Mono<String> agentSend(
            RuntimeContext runtimeContext,
            AgentState parentState,
            @ToolParam(
                            name = "agent_key",
                            description =
                                    "Exact value from agent_spawn's first line after 'agent_key: '"
                                        + " (format agent:<type>:<uuid>). Not agent_id, session_id,"
                                        + " or task_id. Mutually exclusive with label.",
                            required = false)
                    String agentKey,
            @ToolParam(
                            name = "label",
                            description =
                                    "Agent label assigned at spawn time. Mutually exclusive with"
                                            + " agent_key.",
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "Message to send to the subagent")
                    String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for a reply. 0=fire-and-forget, returns \
                                    task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        boolean hasKey = agentKey != null && !agentKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return Mono.just("Error: Provide either agent_key or label, not both.");
        }
        if (!hasKey && !hasLabel) {
            return Mono.just("Error: Either agent_key or label is required.");
        }
        if (message == null || message.isBlank()) {
            return Mono.just("Error: message is required");
        }

        String key;
        if (hasKey) {
            key = agentKey.trim();
        } else {
            key = labelToKey.get(label.trim().toLowerCase());
            if (key == null) {
                key = tryResolveLabelFromState(parentState, label.trim());
            }
            if (key == null) {
                return Mono.just("Error: Unknown label: " + label.trim());
            }
        }

        SpawnedAgent resolved = agentsByKey.get(key);
        if (resolved == null) {
            resolved = tryRestoreFromState(parentState, key, runtimeContext);
        }
        if (resolved == null) {
            return Mono.just("Error: Unknown agent_key: " + key);
        }
        final SpawnedAgent spawned = resolved;

        boolean forceSync = isForceSync(runtimeContext);
        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);
        propagatePlanMode(parentState, currentUserId, spawned.sessionId(), spawned.agent());
        var declOpt = manager.getDeclaration(spawned.agentId());
        propagateParentDenyRules(
                parentState, currentUserId, spawned.sessionId(), spawned.agent(), declOpt);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedMessage = message;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(),
                                d.getHeaders(),
                                spawned.agentId(),
                                capturedMessage,
                                buildRemoteSubmitContext(runtimeContext, parentState, d));
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                manager.invokeAgent(
                                                                spawned.agent(),
                                                                spawned.sessionId(),
                                                                currentUserId,
                                                                capturedMessage,
                                                                runtimeContext)
                                                        .block();
                                        return reply != null ? reply.getTextContent() : "";
                                    } catch (RuntimeException e) {
                                        return "Error: "
                                                + (e.getMessage() != null
                                                        ? e.getMessage()
                                                        : e.getClass().getSimpleName());
                                    }
                                });
            }
            taskRepository.putTask(
                    runtimeContext, taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId));
        }

        if (remote) {
            final String finalMessage = message;
            final String finalKey = key;
            return runRemoteSyncReactive(
                    runtimeContext,
                    parentState,
                    "agent_key: " + finalKey,
                    spawned.agentId(),
                    parentSessionId,
                    declOpt.get(),
                    finalMessage.trim(),
                    timeoutMs,
                    forceSync);
        }

        final String finalKey = key;
        return execWithTimeoutPromotion(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                message.trim(),
                spawned,
                runtimeContext,
                "agent_key: " + finalKey,
                timeoutMs,
                spawned.agentId(),
                forceSync);
    }

    /** {@code agent_list}：列出本智能体创建的所有活跃子智能体，输出 key、类型、标签与层级。 */
    @Tool(name = "agent_list", description = "List active subagents spawned by this agent.")
    public String agentList() {
        if (agentsByKey.isEmpty()) {
            return "No active subagents.";
        }

        StringBuilder sb =
                new StringBuilder("Active subagents (").append(agentsByKey.size()).append("):\n");
        for (SpawnedAgent a : agentsByKey.values()) {
            sb.append("- agent_key: ").append(a.key()).append("\n");
            sb.append("  agent_id: ").append(a.agentId()).append("\n");
            if (a.label() != null) {
                sb.append("  label: ").append(a.label()).append("\n");
            }
            sb.append("  spawn_depth: ").append(a.depth()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private DefaultAgentManager managerFor(RuntimeContext runtimeContext) {
        DefaultAgentManager scoped =
                runtimeContext != null
                        ? runtimeContext.get(CTX_AGENT_MANAGER, DefaultAgentManager.class)
                        : null;
        return scoped != null ? scoped : agentManager;
    }

    /**
     * Activates plan mode on a local child immediately before it can be invoked.
     *
     * <p>This must run for both newly-created and reused children. In particular, a persistent
     * child may have been created while its parent was in build mode and then reused after the
     * parent entered plan mode.
     */
    private static void propagatePlanMode(
            AgentState parentState, String userId, String sessionId, Agent child) {
        if (parentState != null
                && parentState.getPlanModeContext().isPlanActive()
                && child instanceof HarnessAgent harnessChild) {
            harnessChild.enterPlanMode(userId, sessionId);
        }
    }

    /**
     * Returns a {@link Mono} that invokes the local subagent.
     *
     * <p>Three paths, checked in order:
     *
     * <ol>
     *   <li><b>{@code streamEvents()} path</b> — an {@link AgentEventEmitter} is present in the
     *       Reactor Context (set by {@code ReActAgent.streamEvents}). Child events are forwarded
     *       into the parent's {@code Flux<AgentEvent>} via a source-tagging wrapper emitter.
     *   <li><b>{@code stream()} path</b> (deprecated) — a {@link SubagentEventBus} is present.
     *       Child events are forwarded via the bus with {@link EventSource} metadata.
     *   <li><b>Non-streaming path</b> — plain {@code call()}, no event forwarding.
     * </ol>
     *
     * <p><b>Context propagation note:</b> this method returns a {@code Mono} whose
     * {@code deferContextual} is subscribed by {@code ToolMethodInvoker}'s {@code flatMap}, which
     * correctly inherits the Reactor Context from the parent streaming chain. Do NOT call
     * {@code .block()} on this Mono directly inside a tool method that returns {@link String},
     * because {@code block()} creates an isolated subscription that loses the Context.
     */
    /**
     * 返回调用本地子智能体的 {@link Mono}。按优先级依次尝试三条路径：
     *
     * <ol>
     *   <li><b>{@code streamEvents()} 路径</b>——Reactor 上下文中存在 {@link AgentEventEmitter}
     *       （由 {@code ReActAgent.streamEvents} 注入）。子事件经带来源标记的包装发射器
     *       转发到父级 {@code Flux<AgentEvent>}。
     *   <li><b>{@code stream()} 路径</b>（已废弃）——存在 {@link SubagentEventBus}，
     *       子事件携带 {@link EventSource} 元数据经总线转发。
     *   <li><b>非流式路径</b>——普通 {@code call()}，不做事件转发。
     * </ol>
     *
     * <p><b>上下文传播注意：</b>本方法返回的 {@code Mono} 由 {@code ToolMethodInvoker} 的
     * {@code flatMap} 订阅 {@code deferContextual}，可正确继承父级流式链路的 Reactor 上下文。
     * 切勿在返回 {@link String} 的工具方法内直接对该 Mono 调用 {@code .block()}，
     * 因为 {@code block()} 会创建孤立订阅导致上下文丢失。
     */
    private Mono<Msg> execLocalSync(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            SpawnedAgent spawned,
            RuntimeContext parentCtx) {
        return Mono.deferContextual(
                ctxView -> {
                    DefaultAgentManager manager = managerFor(parentCtx);
                    // ── Path 1: streamEvents() — AgentEvent forwarding ──
                    // ── 路径一：streamEvents() —— AgentEvent 事件转发 ──
                    Optional<AgentEventEmitter> emitterOpt = AgentEventEmitter.fromContext(ctxView);
                    if (emitterOpt.isPresent()) {
                        AgentEventEmitter parentEmitter = emitterOpt.get();
                        String sourcePath = buildSourcePath(spawned, parentCtx);
                        String replyId = UUID.randomUUID().toString().replace("-", "");
                        AgentEventEmitter taggedEmitter =
                                event -> parentEmitter.emit(event.withSource(sourcePath));

                        parentEmitter.emit(
                                new AgentStartEvent(spawned.sessionId(), replyId, spawned.agentId())
                                        .withSource(sourcePath));

                        AtomicBoolean endEmitted = new AtomicBoolean();
                        Runnable emitEnd =
                                () -> {
                                    if (endEmitted.compareAndSet(false, true)) {
                                        parentEmitter.emit(
                                                new AgentEndEvent(replyId).withSource(sourcePath));
                                    }
                                };

                        return manager.invokeAgent(agent, sessionId, userId, prompt, parentCtx)
                                .contextWrite(
                                        c ->
                                                c.put(
                                                        AgentEventEmitter.FORWARDING_CONTEXT_KEY,
                                                        taggedEmitter))
                                // Emit before success or error reaches the parent, which may
                                // otherwise complete its event sink before doFinally runs.
                                .doOnSuccess(ignored -> emitEnd.run())
                                .doOnError(ignored -> emitEnd.run())
                                // Preserve best-effort cancellation signaling without emitting a
                                // duplicate if cancellation races with normal termination.
                                .doFinally(
                                        signal -> {
                                            if (signal == SignalType.CANCEL) {
                                                emitEnd.run();
                                            }
                                        });
                    }

                    // ── Path 2: stream() (deprecated) — SubagentEventBus forwarding ──
                    // ── 路径二：stream()（已废弃）—— SubagentEventBus 事件转发 ──
                    if (ctxView.hasKey(SubagentEventBus.CONTEXT_KEY)) {
                        SubagentEventBus bus = ctxView.get(SubagentEventBus.CONTEXT_KEY);
                        EventSource childSource = buildChildSource(spawned, parentCtx);

                        return manager.invokeAgentStream(
                                        agent,
                                        sessionId,
                                        userId,
                                        prompt,
                                        childSource,
                                        StreamOptions.defaults(),
                                        parentCtx)
                                .doOnNext(
                                        e -> {
                                            log.debug(
                                                    "[execLocalSync] forwarding child event to"
                                                            + " bus: type={} msgId={} isLast={}",
                                                    e.getType(),
                                                    e.getMessage().getId(),
                                                    e.isLast());
                                            bus.emit(e);
                                        })
                                .filter(e -> e.isLast() && e.getType() == EventType.AGENT_RESULT)
                                .last()
                                .map(e -> e.getMessage())
                                .switchIfEmpty(
                                        Mono.defer(
                                                () ->
                                                        manager.invokeAgent(
                                                                agent, sessionId, userId, prompt,
                                                                parentCtx)));
                    }

                    // ── Path 3: non-streaming ──
                    // ── 路径三：非流式 ──
                    return manager.invokeAgent(agent, sessionId, userId, prompt, parentCtx);
                });
    }

    /**
     * Executes a local subagent with timeout promotion: if the agent doesn't finish within
     * {@code timeoutMs}, the in-flight execution is promoted to an async background task instead
     * of being cancelled and lost — unless {@code forceSync} is true, in which case the agent is
     * interrupted and {@code status: timeout} is returned with no background promotion.
     *
     * <p>The key mechanism is a {@link CompletableFuture} bridge that decouples execution from
     * observation. The Mono from {@link #execLocalSync} is subscribed with Reactor Context
     * propagation (so streaming events flow during the sync wait period), and its result feeds
     * the bridge. A race future derived from the bridge adds a timeout without cancelling the
     * original:
     *
     * <ul>
     *   <li>Agent finishes before timeout → normal result returned
     *   <li>Timeout fires first (default) → bridge (still running) is registered in {@link
     *       TaskRepository} as an {@link TaskRunSpec.AdoptedTaskRunSpec}, and a {@code task_id} is
     *       returned
     *   <li>Timeout fires first ({@code forceSync}) → agent interrupted, {@code status: timeout},
     *       no {@code task_id}
     *   <li>Agent errors → error message returned
     * </ul>
     */
    /**
     * 以超时提升机制执行本地子智能体：若智能体未在 {@code timeoutMs} 内完成，
     * 执行中的任务将被提升为异步后台任务，而非取消丢弃；
     * 但 {@code forceSync} 为 true 时例外——智能体会被中断并返回 {@code status: timeout}，
     * 不会产生后台任务。
     *
     * <p>核心机制是 {@link CompletableFuture} 桥接器，将执行与观察解耦。
     * {@link #execLocalSync} 返回的 Mono 在传播 Reactor 上下文的前提下被订阅
     *（保证同步等待期间流式事件正常流动），其结果写入桥接器。
     * 由桥接器派生的竞速 Future 附加超时但不取消原任务：
     *
     * <ul>
     *   <li>智能体在超时前完成 → 返回正常结果
     *   <li>超时先触发（默认）→ 将仍在运行的桥接器以 {@link TaskRunSpec.AdoptedTaskRunSpec}
     *       注册进 {@link TaskRepository}，返回 {@code task_id}
     *   <li>超时先触发（{@code forceSync}）→ 中断智能体，返回 {@code status: timeout}，
     *       不产生 {@code task_id}
     *   <li>智能体执行出错 → 返回错误信息
     * </ul>
     */
    private Mono<String> execWithTimeoutPromotion(
            Agent agent,
            String sessionId,
            String userId,
            String task,
            SpawnedAgent spawned,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId,
            boolean forceSync) {

        return Mono.deferContextual(
                parentCtx ->
                        Mono.<String>create(
                                sink -> {
                                    CompletableFuture<Msg> bridge = new CompletableFuture<>();

                                    Mono<Msg> inner =
                                            execLocalSync(
                                                            agent,
                                                            sessionId,
                                                            userId,
                                                            task,
                                                            spawned,
                                                            runtimeContext)
                                                    .contextWrite(
                                                            c ->
                                                                    reactor.util.context.Context.of(
                                                                            parentCtx))
                                                    .doFinally(
                                                            signal -> {
                                                                // Parent subscription was
                                                                // cancelled (outer Mono.timeout,
                                                                // user stop, or upstream Reactor
                                                                // cancel). The fire-and-forget
                                                                // subscribe below detaches the
                                                                // inner execution from the parent
                                                                // lifecycle, so without this
                                                                // doFinally the sub-agent would
                                                                // keep running as an orphan and
                                                                // the eventual sink.success(...)
                                                                // would be a no-op on the
                                                                // already-cancelled sink.
                                                                if (signal == SignalType.CANCEL) {
                                                                    interruptAgent(
                                                                            agent, runtimeContext);
                                                                }
                                                            });

                                    Disposable innerSub =
                                            inner.subscribe(
                                                    bridge::complete,
                                                    bridge::completeExceptionally,
                                                    () -> {
                                                        if (!bridge.isDone()) {
                                                            bridge.complete(null);
                                                        }
                                                    });

                                    // Race against timeout without cancelling the bridge.
                                    // thenApply(m -> m) creates a dependent future so orTimeout
                                    // completes the race copy, not the original bridge.
                                    // 与超时竞速但不取消桥接器。thenApply(m -> m) 创建依赖 Future，
                                    // 使 orTimeout 结束的是竞速副本而非原始桥接器。
                                    CompletableFuture<Msg> race = bridge.thenApply(m -> m);
                                    race.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);

                                    race.whenComplete(
                                            (msg, err) -> {
                                                if (err != null) {
                                                    handleExecError(
                                                            err,
                                                            bridge,
                                                            runtimeContext,
                                                            header,
                                                            timeoutMs,
                                                            agentId,
                                                            sink,
                                                            forceSync,
                                                            innerSub);
                                                } else {
                                                    sink.success(
                                                            header
                                                                    + "\nstatus: ok\nreply:\n"
                                                                    + textOf(msg));
                                                }
                                            });

                                    // Propagate parent cancellation to the inner fire-and-forget
                                    // subscription. Without this, doFinally(CANCEL) above never
                                    // fires because the inner is subscribed independently of the
                                    // parent Mono.create sink.
                                    sink.onCancel(innerSub);
                                }));
    }

    /**
     * Interrupts the sub-agent's reasoning loop when its parent tool-call subscription is
     * cancelled. Mirrors the fix in core {@code SubAgentTool.interruptAgent} (commit
     * {@code 029cc55e}, issue #1783) — see issue #2062 for the harness-side equivalent.
     *
     * <p>Only {@link ReActAgent} exposes {@code interrupt(RuntimeContext)}. Other {@link Agent}
     * implementations are no-ops here; their inner execution will still be disposed by the caller's
     * {@code sink.onCancel(innerSub::dispose)}, which is enough for non-looping agents.
     */
    private void interruptAgent(Agent agent, RuntimeContext ctx) {
        if (agent instanceof ReActAgent ra) {
            ra.interrupt(ctx);
            log.warn(
                    "Sub-agent '{}' (id={}) was interrupted because its parent tool call"
                            + " subscription was cancelled.",
                    ra.getName(),
                    ra.getAgentId());
        }
    }

    /**
     * Handles errors from the race future in {@link #execWithTimeoutPromotion}. Separated to keep
     * the lambda readable — it distinguishes timeout (→ promote, or hard-fail under force-sync)
     * from real errors (→ report).
     */
    /**
     * 处理 {@link #execWithTimeoutPromotion} 中竞速 Future 的异常。
     * 独立成方法以保持 lambda 可读性——区分超时（→ 提升为异步任务）与真实错误（→ 上报）。
     */
    private void handleExecError(
            Throwable err,
            CompletableFuture<Msg> bridge,
            RuntimeContext runtimeContext,
            String header,
            long timeoutMs,
            String agentId,
            reactor.core.publisher.MonoSink<String> sink,
            boolean forceSync,
            Disposable innerSub) {

        Throwable cause = err instanceof CompletionException ? err.getCause() : err;
        if (cause instanceof TimeoutException) {
            if (forceSync) {
                // Hard timeout: dispose triggers doFinally(CANCEL) → interruptAgent; no promote.
                if (innerSub != null && !innerSub.isDisposed()) {
                    innerSub.dispose();
                }
                log.info(
                        "agent_spawn sync timeout after {}ms under force_sync (not promoted):"
                                + " agentId={}",
                        timeoutMs,
                        agentId);
                sink.success(header + "\n" + formatForceSyncTimeout(timeoutMs));
                return;
            }
            String taskId = "task_" + UUID.randomUUID();
            String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
            CompletableFuture<String> textFuture = bridge.thenApply(AgentSpawnTool::textOf);
            taskRepository.putTask(
                    runtimeContext,
                    taskId,
                    agentId,
                    parentSessionId,
                    new TaskRunSpec.AdoptedTaskRunSpec(textFuture));
            log.info(
                    "agent_spawn sync timeout after {}ms, promoted to async: agentId={}, taskId={}",
                    timeoutMs,
                    agentId,
                    taskId);
            sink.success(header + "\n" + formatTimeoutPromoted(taskId, timeoutMs));
        } else {
            Throwable reportable = cause != null ? cause : err;
            String errStr =
                    reportable.getMessage() != null
                            ? reportable.getMessage()
                            : reportable.getClass().getSimpleName();
            log.warn("agent execution failed: agentId={}", agentId, reportable);
            sink.success(header + "\nstatus: error\nerror: " + errStr);
        }
    }

    /** 将 spawn 记录持久化到父状态的 ToolContext，供会话压缩/重启后恢复子智能体。 */
    private void persistSpawnEntry(
            AgentState parentState,
            String key,
            String agentId,
            String sessionId,
            String label,
            int depth) {
        if (parentState == null) {
            return;
        }
        parentState
                .getToolContext()
                .putSpawnEntry(
                        key,
                        new io.agentscope.core.state.ToolContextState.SpawnEntry(
                                key, agentId, sessionId, label, depth));
    }

    /** 内存标签表未命中时，从父状态持久化记录中按标签（大小写不敏感）查找对应 key。 */
    private String tryResolveLabelFromState(AgentState parentState, String label) {
        if (parentState == null) {
            return null;
        }
        String lowerLabel = label.toLowerCase();
        for (io.agentscope.core.state.ToolContextState.SpawnEntry entry :
                parentState.getToolContext().getSpawnRegistry().values()) {
            if (entry.label() != null && entry.label().toLowerCase().equals(lowerLabel)) {
                return entry.key();
            }
        }
        return null;
    }

    /**
     * 从父状态持久化记录中恢复子智能体：按 key 取 SpawnEntry，重建智能体实例，
     * 并回填内存注册表与标签索引。用于会话压缩或重启后内存注册表丢失的场景。
     */
    private SpawnedAgent tryRestoreFromState(
            AgentState parentState, String key, RuntimeContext runtimeContext) {
        if (parentState == null) {
            return null;
        }
        io.agentscope.core.state.ToolContextState.SpawnEntry entry =
                parentState.getToolContext().getSpawnRegistry().get(key);
        if (entry == null) {
            return null;
        }
        Optional<Agent> agentOpt =
                managerFor(runtimeContext).createAgentIfPresent(entry.agentId(), runtimeContext);
        if (agentOpt.isEmpty()) {
            log.warn(
                    "Failed to restore subagent from state: agentId={} not found in registry",
                    entry.agentId());
            return null;
        }
        SpawnedAgent restored =
                new SpawnedAgent(
                        key,
                        entry.agentId(),
                        entry.sessionId(),
                        entry.label(),
                        agentOpt.get(),
                        entry.depth());
        agentsByKey.put(key, restored);
        if (entry.label() != null) {
            labelToKey.put(entry.label().toLowerCase(), key);
        }
        log.info(
                "Restored subagent from persisted state: key={}, agentId={}", key, entry.agentId());
        return restored;
    }

    /** 提取消息文本内容，null 消息返回空串。 */
    private static String textOf(Msg msg) {
        return msg != null ? msg.getTextContent() : "";
    }

    /** 格式化超时提升提示：告知任务转入后台，可用 task_output 查询，切勿重试同一任务。 */
    private static String formatTimeoutPromoted(String taskId, long timeoutMs) {
        return String.format(
                """
                status: timeout_promoted
                task_id: %s
                The task exceeded the %ds sync timeout but is still running in the background. \
                Use task_output(task_id='%s', block=false) to check status, \
                wait_async_results(task_ids=...) when this task is part of a required barrier, \
                or wait — completed tasks are pushed back to you automatically. \
                Do NOT retry the same task.\
                """,
                taskId, timeoutMs / 1000, taskId);
    }

    private static String formatForceSyncTimeout(long timeoutMs) {
        return String.format(
                """
                status: timeout
                The task exceeded the %ds sync timeout and was interrupted because force_sync                 is enabled. Background promotion is disabled for this call.\
                """,
                timeoutMs / 1000);
    }

    /**
     * Builds an {@link EventSource} for a freshly spawned or known subagent. The path is
     * constructed from the parent session ID (or {@code "main"} as fallback) plus the child's
     * {@code agentId}, separated by {@code "/"}.
     */
    /**
     * 为新建或已知子智能体构建 {@link EventSource}。路径由父会话ID（缺省回退 {@code "main"}）
     * 与子智能体 {@code agentId} 以 {@code "/"} 拼接而成。
     */
    private EventSource buildChildSource(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        String path = parentName + "/" + spawned.agentId();
        return EventSource.builder()
                .agentKey(spawned.key())
                .agentId(spawned.agentId())
                .sessionId(spawned.sessionId())
                .depth(spawned.depth())
                .path(path)
                .build();
    }

    /**
     * Builds a source path string for the {@link AgentEvent#withSource} tag. Uses the same
     * parent-session / child-agent-id convention as {@link #buildChildSource}.
     */
    /**
     * 构建用于 {@link AgentEvent#withSource} 标记的来源路径字符串，
     * 采用与 {@link #buildChildSource} 相同的「父会话 / 子智能体ID」约定。
     */
    private String buildSourcePath(SpawnedAgent spawned, RuntimeContext parentCtx) {
        String parentName =
                (parentCtx != null && parentCtx.getSessionId() != null)
                        ? parentCtx.getSessionId()
                        : "main";
        return parentName + "/" + spawned.agentId();
    }

    /**
     * Builds a {@code parentSession/agentId} source path for remote events forwarded into the
     * parent's stream.
     */
    static String buildRemoteSourcePath(String parentSessionId, String agentId) {
        String parent =
                parentSessionId != null && !parentSessionId.isBlank() ? parentSessionId : "main";
        String child = agentId != null && !agentId.isBlank() ? agentId : "remote";
        return parent + "/" + child;
    }

    /**
     * Tags a remote-forwarded {@link AgentEvent} with the parent-visible {@code source} path, the
     * harness {@link AgentEvent#METADATA_TASK_ID}, and {@link
     * AgentEvent#METADATA_PARENT_SESSION_ID} so concurrent calls to the same remote agent stay
     * correlatable to distinct {@code TaskRecord}s and to the parent session that spawned them.
     */
    static AgentEvent tagRemoteForwardedEvent(
            AgentEvent event, String sourcePath, String taskId, String parentSessionId) {
        if (event == null) {
            return null;
        }
        event.withSource(sourcePath);
        if (taskId != null && !taskId.isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_TASK_ID, taskId);
        }
        if (parentSessionId != null && !parentSessionId.isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_PARENT_SESSION_ID, parentSessionId.trim());
        }
        return event;
    }

    /**
     * Builds submission metadata for a remote task (streaming preference, parent identity, denied
     * permission rules, caller-defined attributes).
     */
    private RemoteSubmitContext buildRemoteSubmitContext(
            RuntimeContext runtimeContext, AgentState parentState, SubagentDeclaration decl) {
        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        boolean stream = decl != null && decl.isRemoteStreaming();
        String detail =
                stream
                        ? decl.getRemoteStreamDetail().wireValue()
                        : RemoteStreamDetail.STATUS.wireValue();
        return RemoteSubmitContext.builder().userId(userId).parentSessionId(parentSessionId).stream(
                        stream)
                .detail(detail)
                .denyRules(collectParentDenyRules(parentState, Optional.ofNullable(decl)))
                .attributes(collectRemoteContextAttributes(runtimeContext, decl))
                .build();
    }

    /**
     * Merges the subagent's declared static attributes with the per-call ones from {@link
     * #CTX_REMOTE_CONTEXT_ATTRIBUTES}; per-call entries win on conflict.
     */
    static Map<String, Object> collectRemoteContextAttributes(
            RuntimeContext runtimeContext, SubagentDeclaration decl) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (decl != null && decl.getRemoteContextAttributes() != null) {
            merged.putAll(decl.getRemoteContextAttributes());
        }
        Object perCall =
                runtimeContext != null ? runtimeContext.get(CTX_REMOTE_CONTEXT_ATTRIBUTES) : null;
        if (perCall instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }
        return merged;
    }

    /**
     * Flattens parent DENY rules into wire maps for {@link RemoteSubmitContext}. Returns an empty
     * list when inheritance is disabled or the parent has no DENY rules.
     */
    static List<Map<String, String>> collectParentDenyRules(
            AgentState parentState, Optional<SubagentDeclaration> declaration) {
        boolean inherit =
                declaration.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (!inherit || parentState == null) {
            return List.of();
        }
        PermissionContextState parentPermissions = parentState.getPermissionContext();
        if (parentPermissions == null || parentPermissions.getDenyRules().isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        parentPermissions
                .getDenyRules()
                .forEach(
                        (toolName, rules) -> {
                            for (PermissionRule rule : rules) {
                                Map<String, String> m = new LinkedHashMap<>();
                                m.put("tool_name", rule.toolName());
                                if (rule.ruleContent() != null) {
                                    m.put("rule_content", rule.ruleContent());
                                }
                                m.put("behavior", rule.behavior().name());
                                m.put("source", rule.source());
                                out.add(m);
                            }
                        });
        return out;
    }

    /**
     * Reactive entry for remote sync execution. Captures {@link AgentEventEmitter} from Reactor
     * Context before blocking on the remote task.
     */
    private Mono<String> runRemoteSyncReactive(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs,
            boolean forceSync) {
        return Mono.deferContextual(
                ctxView -> {
                    Optional<AgentEventEmitter> emitterOpt = AgentEventEmitter.fromContext(ctxView);
                    return Mono.fromCallable(
                            () ->
                                    runRemoteSync(
                                            runtimeContext,
                                            parentState,
                                            header,
                                            agentId,
                                            parentSessionId,
                                            decl,
                                            input,
                                            timeoutMs,
                                            emitterOpt.orElse(null),
                                            forceSync));
                });
    }

    /**
     * Submits a remote task through {@link TaskRepository} (for durable state) and blocks until
     * it completes or the timeout elapses.
     *
     * <p>When an {@link AgentEventEmitter} is present and {@link SubagentDeclaration#isRemoteStreaming()}
     * is true, remote events are forwarded into the parent stream. Without an emitter, or when
     * {@link RemoteAskPolicy#DENY} applies, pending remote confirmations are auto-denied via
     * {@link RemoteSubagentTransport#resume}.
     */
    /**
     * 经 {@link TaskRepository} 提交远程任务（获得持久状态）并阻塞等待其完成或超时。
     *
     * <p>存在 {@link AgentEventEmitter} 且 {@link SubagentDeclaration#isRemoteStreaming()} 为 true 时，
     * 远程事件会转发进父级事件流；没有 emitter 或命中 {@link RemoteAskPolicy#DENY} 时，
     * 待确认的远程请求通过 {@link RemoteSubagentTransport#resume} 自动拒绝。
     */
    private String runRemoteSync(
            RuntimeContext runtimeContext,
            AgentState parentState,
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs,
            AgentEventEmitter emitter,
            boolean forceSync) {
        String taskId = "task_" + UUID.randomUUID();
        RemoteSubmitContext submitContext =
                buildRemoteSubmitContext(runtimeContext, parentState, decl);
        TaskRunSpec spec =
                new TaskRunSpec.RemoteTaskRunSpec(
                        decl.getUrl(), decl.getHeaders(), agentId, input, submitContext);
        BackgroundTask bgTask =
                taskRepository.putTask(runtimeContext, taskId, agentId, parentSessionId, spec);

        RemoteTarget target = new RemoteTarget(decl.getUrl(), decl.getHeaders());
        RemoteSubagentTransport transport = this.remoteTransport;
        String sourcePath = buildRemoteSourcePath(parentSessionId, agentId);
        boolean wantStream = emitter != null && decl.isRemoteStreaming();
        AtomicBoolean autoDenied = new AtomicBoolean(false);
        AtomicBoolean resumedEpisode = new AtomicBoolean(false);

        Closeable streamHandle = () -> {};
        try {
            if (wantStream) {
                streamHandle =
                        transport.streamEvents(
                                target,
                                taskId,
                                0L,
                                remoteEvent ->
                                        RemoteEventCodec.toAgentEvent(remoteEvent)
                                                .ifPresent(
                                                        ae ->
                                                                emitter.emit(
                                                                        tagRemoteForwardedEvent(
                                                                                ae,
                                                                                sourcePath,
                                                                                taskId,
                                                                                parentSessionId))));
            }

            long deadlineMs = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
            while (true) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    if (forceSync) {
                        taskRepository.cancelTask(runtimeContext, parentSessionId, taskId);
                        log.info(
                                "agent remote sync timeout after {}ms under force_sync"
                                        + " (cancelled): agentId={}, taskId={}",
                                timeoutMs,
                                agentId,
                                taskId);
                        return header + "\n" + formatForceSyncTimeout(timeoutMs);
                    }
                    return header + "\nstatus: timeout\ntask_id: " + taskId;
                }
                long slice = Math.min(REMOTE_CONFIRM_POLL_MS, remaining);
                boolean done = bgTask.waitForCompletion(slice);

                try {
                    RemoteTaskStatus st = transport.getStatus(target, taskId);
                    if (st.isAwaitingConfirm()) {
                        boolean shouldAutoDeny =
                                emitter == null
                                        || decl.getRemoteAskPolicy() == RemoteAskPolicy.DENY;
                        if (shouldAutoDeny && resumedEpisode.compareAndSet(false, true)) {
                            List<RemotePendingConfirm> pending =
                                    st.pendingConfirms() != null ? st.pendingConfirms() : List.of();
                            List<RemoteConfirmDecision> decisions = new ArrayList<>(pending.size());
                            for (RemotePendingConfirm p : pending) {
                                decisions.add(new RemoteConfirmDecision(p.getToolCallId(), false));
                            }
                            if (!decisions.isEmpty()) {
                                transport.resume(target, taskId, decisions);
                                autoDenied.set(true);
                            }
                        }
                    } else {
                        resumedEpisode.set(false);
                    }
                } catch (Exception e) {
                    log.debug(
                            "Remote status poll during sync wait failed for {}: {}",
                            taskId,
                            e.getMessage());
                }

                if (done) {
                    break;
                }
            }

            TaskStatus ts = bgTask.getTaskStatus();
            if (ts == TaskStatus.FAILED) {
                Exception err = bgTask.getError();
                String msg = err != null ? err.getMessage() : "remote task failed";
                return header + "\nstatus: error\nerror: " + msg;
            }
            if (ts == TaskStatus.CANCELLED) {
                return header + "\nstatus: cancelled\ntask_id: " + taskId;
            }
            String result = bgTask.getResult();
            StringBuilder sb = new StringBuilder(header).append("\nstatus: ok");
            if (autoDenied.get()) {
                sb.append("\nnote: remote tool confirmation(s) were auto-denied");
            }
            sb.append("\nreply:\n").append(result != null ? result : "");
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("agent remote sync interrupted: agentId={}", agentId);
            return header + "\nstatus: error\nerror: interrupted";
        } finally {
            try {
                streamHandle.close();
            } catch (IOException e) {
                log.debug("Closing remote event stream failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Resolves the effective user-exposure decision for a spawn.
     *
     * <p>Precedence (highest first):
     *
     * <ol>
     *   <li>A per-call {@link RuntimeContext} value under {@link #CTX_EXPOSE_TO_USER} — lets the
     *       embedding application force/forbid exposure for the whole call.
     *   <li>The spawned subagent's {@link SubagentDeclaration#getExposeToUser()} policy — a static
     *       per-type default; {@code null} means "no opinion".
     *   <li>The LLM-supplied {@code expose_to_user} tool argument.
     *   <li>{@code false} when none of the above expresses an opinion.
     * </ol>
     */
    /**
     * 解析 spawn 生效的用户暴露决策。优先级（从高到低）：
     *
     * <ol>
     *   <li>单次调用的 {@link RuntimeContext} 中 {@link #CTX_EXPOSE_TO_USER} 键值——
     *       允许宿主应用对整个调用强制开启/禁止暴露。
     *   <li>子智能体声明的 {@link SubagentDeclaration#getExposeToUser()} 策略——
     *       按类型的静态默认值；{@code null} 表示无意见。
     *   <li>大模型传入的 {@code expose_to_user} 工具参数。
     *   <li>以上均无意见时取 {@code false}。
     * </ol>
     */
    private static boolean resolveExposeToUser(
            Boolean llmParam, Optional<SubagentDeclaration> declOpt, RuntimeContext ctx) {
        if (ctx != null) {
            Boolean override = asBoolean(ctx.get(CTX_EXPOSE_TO_USER));
            if (override != null) {
                return override;
            }
        }
        Boolean declPolicy = declOpt.map(SubagentDeclaration::getExposeToUser).orElse(null);
        if (declPolicy != null) {
            return declPolicy;
        }
        return Boolean.TRUE.equals(llmParam);
    }

    /** Coerces a context value (Boolean or its string form) to a tri-state Boolean. */
    /** 将上下文值（Boolean 或布尔字符串）转换为三态 Boolean，无法识别返回 null。 */
    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    /** 解析生效超时毫秒数：null 用默认值，<=0 返回 0（异步），否则钳制到上限后转毫秒。 */
    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    /**
     * Whether {@link #CTX_FORCE_SYNC} is enabled on the current call.
     */
    static boolean isForceSync(RuntimeContext ctx) {
        if (ctx == null) {
            return false;
        }
        return Boolean.TRUE.equals(asBoolean(ctx.get(CTX_FORCE_SYNC)));
    }

    /**
     * Resolves the effective sync wait for the current call.
     *
     * <p>Precedence under force-sync (highest first):
     *
     * <ol>
     *   <li>{@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS} when present — absolute app override
     *   <li>LLM {@code timeout_seconds}, with {@code 0} coerced to the default sync timeout
     * </ol>
     *
     * <p>Without force-sync, behavior matches {@link #resolveTimeoutMs} (including async {@code
     * 0}).
     */
    static long resolveEffectiveTimeoutMs(Integer timeoutSeconds, RuntimeContext ctx) {
        boolean forceSync = isForceSync(ctx);
        if (forceSync) {
            Integer override = forceSyncTimeoutSeconds(ctx);
            if (override != null) {
                int seconds = override <= 0 ? DEFAULT_TIMEOUT_SECONDS : override;
                return (long) Math.min(seconds, MAX_TIMEOUT_SECONDS) * 1_000;
            }
        }
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        if (forceSync && timeoutMs == 0L) {
            return (long) DEFAULT_TIMEOUT_SECONDS * 1_000;
        }
        return timeoutMs;
    }

    /** Reads {@link #CTX_FORCE_SYNC_TIMEOUT_SECONDS}; {@code null} means "no override". */
    static Integer forceSyncTimeoutSeconds(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        return asPositiveOrZeroInt(ctx.get(CTX_FORCE_SYNC_TIMEOUT_SECONDS));
    }

    /** Coerces a context value ({@link Number} or numeric string) to Integer; blank/invalid → null. */
    private static Integer asPositiveOrZeroInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Wraps a {@code Mono<String>} to emit a {@link SubagentExposedEvent} into the parent's event
     * stream when {@code subagentId} is non-null. When subagentId is null (no expose), returns the
     * original Mono unchanged.
     */
    /**
     * 包装 {@code Mono<String>}：当 {@code subagentId} 非空时，向父级事件流发送
     * {@link SubagentExposedEvent}；为 null（未暴露）时原样返回源 Mono。
     */
    private static Mono<String> withSubagentExposedEvent(
            Mono<String> source,
            String subagentId,
            String agentId,
            String sessionId,
            String label) {
        if (subagentId == null) {
            return source;
        }
        return Mono.deferContextual(
                ctx -> {
                    AgentEventEmitter.fromContext(ctx)
                            .ifPresent(
                                    emitter ->
                                            emitter.emit(
                                                    new SubagentExposedEvent(
                                                            subagentId,
                                                            agentId,
                                                            sessionId,
                                                            label)));
                    return source;
                });
    }

    /** 格式化 spawn 结果头三行信息（key/类型/会话ID），暴露成功时附加 subagent_id 与状态行。 */
    private static String formatSpawnInfo(
            String key, String agentId, String sessionId, String subagentId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent_key: ").append(key).append("\n");
        sb.append("agent_id: ").append(agentId).append("\n");
        sb.append("session_id: ").append(sessionId);
        if (subagentId != null) {
            sb.append("\nsubagent_id: ").append(subagentId);
            sb.append("\nstatus: exposed (user can send messages directly via subagent_id)");
        }
        return sb.toString();
    }

    /**
     * Executes a task against a previously spawned (or reused) subagent. Factored out of
     * {@code agentSpawn} to handle the deterministic-key reuse path without duplicating the
     * sync/async/remote dispatch logic.
     */
    /**
     * 对已创建（或复用）的子智能体执行任务。从 {@code agentSpawn} 中抽出，
     * 用于确定性 key 复用路径，避免重复编写同步/异步/远程分发逻辑。
     */
    private Mono<String> execSpawnTask(
            SpawnedAgent spawned,
            RuntimeContext runtimeContext,
            AgentState parentState,
            String spawnInfo,
            String task,
            Integer timeoutSeconds,
            Optional<SubagentDeclaration> declOpt) {
        boolean forceSync = isForceSync(runtimeContext);
        long timeoutMs = resolveEffectiveTimeoutMs(timeoutSeconds, runtimeContext);
        String currentUserId = runtimeContext != null ? runtimeContext.getUserId() : null;
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        DefaultAgentManager manager = managerFor(runtimeContext);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(),
                                d.getHeaders(),
                                spawned.agentId(),
                                capturedTask,
                                buildRemoteSubmitContext(runtimeContext, parentState, d));
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                manager.invokeAgent(
                                                                spawned.agent(),
                                                                spawned.sessionId(),
                                                                currentUserId,
                                                                capturedTask,
                                                                runtimeContext)
                                                        .block();
                                        return reply != null ? reply.getTextContent() : "";
                                    } catch (RuntimeException e) {
                                        return "Error: "
                                                + (e.getMessage() != null
                                                        ? e.getMessage()
                                                        : e.getClass().getSimpleName());
                                    }
                                });
            }
            taskRepository.putTask(
                    runtimeContext, taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(
                    spawnInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId));
        }

        if (remote) {
            final String finalTask = task;
            return runRemoteSyncReactive(
                    runtimeContext,
                    parentState,
                    spawnInfo,
                    spawned.agentId(),
                    parentSessionId,
                    declOpt.get(),
                    finalTask.trim(),
                    timeoutMs,
                    forceSync);
        }

        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        return execWithTimeoutPromotion(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                finalTask,
                spawned,
                runtimeContext,
                finalSpawnInfo,
                timeoutMs,
                spawned.agentId(),
                forceSync);
    }

    /**
     * Derives a deterministic 12-char hex hash from (parentSessionId, agentId, label). Same inputs
     * always produce the same key, enabling subagent state recovery across parent calls.
     */
    /**
     * 根据(parentSessionId、agentId、label)生成固定12位十六进制哈希值。
     * 相同输入始终产出相同键值，支持父会话多次调用时恢复子智能体状态。
     */
    static String deterministicHash(String parentSessionId, String agentId, String label) {
        String parent = parentSessionId != null ? parentSessionId : "anon";
        return label != null
                ? SessionIdUtils.deterministicHash(parent, agentId, label)
                : SessionIdUtils.deterministicHash(parent, agentId);
    }

    /**
     * 将父智能体权限上下文中的全部 DENY 规则复制到子智能体权限引擎。
     * 以此强制安全边界：父被显式拒绝的操作，子同样被拒绝。
     *
     * <p>声明中 {@code inheritParentPermissions} 为 false 时跳过继承；
     * 子智能体为 {@link HarnessAgent} 时写入其内部 delegate，普通
     * {@link ReActAgent} 则直接写入自身。
     */
    private static void propagateParentDenyRules(
            AgentState parentState,
            String userId,
            String childSessionId,
            Agent childAgent,
            Optional<SubagentDeclaration> declaration) {
        boolean inherit =
                declaration.map(SubagentDeclaration::isInheritParentPermissions).orElse(true);
        if (!inherit || parentState == null) {
            return;
        }

        PermissionContextState parentPermissions = parentState.getPermissionContext();
        if (parentPermissions == null || parentPermissions.getDenyRules().isEmpty()) {
            return;
        }

        if (childAgent instanceof HarnessAgent harnessAgent) {
            mergeParentDenyRulesIntoSlot(
                    userId, childSessionId, harnessAgent.getDelegate(), parentPermissions);
        } else if (childAgent instanceof ReActAgent reactAgent) {
            mergeParentDenyRulesIntoSlot(userId, childSessionId, reactAgent, parentPermissions);
        }
    }

    private static void mergeParentDenyRulesIntoSlot(
            String userId,
            String childSessionId,
            ReActAgent child,
            PermissionContextState parentPermissions) {
        PermissionContextState childPermissions =
                child.getAgentState(userId, childSessionId).getPermissionContext();
        PermissionContextState merged = mergeParentDenyRules(childPermissions, parentPermissions);
        if (!merged.equals(childPermissions)) {
            child.replacePermissionContext(userId, childSessionId, merged);
        }
    }

    /**
     * Adds parent DENY rules without widening the child's configured permissions.
     *
     * <p>A trivial child uses the legacy lightweight permission path, where a tool-level
     * {@code PASSTHROUGH} is allowed. Adding the first DENY rule makes the context non-trivial and
     * activates the full engine; {@link PermissionMode#BYPASS} preserves that prior fallback while
     * explicit DENY rules still take precedence.
     */
    private static PermissionContextState mergeParentDenyRules(
            PermissionContextState child, PermissionContextState parent) {
        PermissionContextState.Builder merged =
                PermissionContextState.builder()
                        .mode(child.isTrivial() ? PermissionMode.BYPASS : child.getMode());

        child.getWorkingDirectories().forEach(merged::addWorkingDirectory);
        child.getAllowRules()
                .forEach(
                        (toolName, rules) ->
                                rules.forEach(rule -> merged.addAllowRule(toolName, rule)));

        Map<String, List<PermissionRule>> denyRules = new LinkedHashMap<>();
        child.getDenyRules()
                .forEach((toolName, rules) -> denyRules.put(toolName, new ArrayList<>(rules)));
        parent.getDenyRules()
                .forEach(
                        (toolName, rules) -> {
                            List<PermissionRule> targetRules =
                                    denyRules.computeIfAbsent(
                                            toolName, ignored -> new ArrayList<>());
                            for (PermissionRule rule : rules) {
                                if (!targetRules.contains(rule)) {
                                    targetRules.add(rule);
                                }
                            }
                        });
        denyRules.forEach(
                (toolName, rules) -> rules.forEach(rule -> merged.addDenyRule(toolName, rule)));

        child.getAskRules()
                .forEach(
                        (toolName, rules) ->
                                rules.forEach(rule -> merged.addAskRule(toolName, rule)));
        return merged.build();
    }
}
