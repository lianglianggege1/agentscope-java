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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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
 * 供智能体内部使用的轻量子智能体工具，相比 SessionsTool 轻量化很多：
 *
 * <ul>
 *   <li>agent_spawn — 创建子智能体、执行任务并返回结果（支持同步/异步）
 *   <li>agent_send — 向已创建的子智能体发送后续交互消息
 *   <li>agent_list — 查询当前活跃的所有子智能体
 * </ul>
 *
 * <p>该工具不维护独立会话、任务通道、运行注册中心与事件分发能力，仅提供「创建智能体 → 执行调用 → 返回结果」基础能力。
 * 仅依赖 {@link DefaultAgentManager} 完成智能体的创建与调用。
 *
 * <p>异步任务（timeout_seconds=0）会提交至 {@link TaskRepository}，并以当前运行上下文 {@link RuntimeContext} 的会话ID作为隔离作用域。
 * 任务状态持久化至工作区存储，支持跨节点读取，也可在上下文压缩后恢复任务。
 *
 * <h2>流式输出能力</h2>
 *
 * <p>agent_spawn 和 agent_send 返回 {@link Mono}{@code <String>}，框架响应式工具调用链路（ToolMethodInvoker）可在父智能体的流式链路中订阅返回流。
 * 若 Reactor 上下文内存在 {@link SubagentEventBus}（由 AgentBase.createEventStream 注入），所有子智能体产生的 {@link io.agentscope.core.agent.Event} 事件会实时转发至父级事件接收器，
 * 对外提供扁平化、完整调用链路的统一事件流。
 * 若无事件总线（普通 call() 调用模式），执行逻辑自动降级为无流式开销的 invokeAgent 同步调用分支。
 */
public class AgentSpawnTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSpawnTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_SPAWN_DEPTH = 3;

    private static final String BG_RESULT_TEMPLATE =
            """
                    status: accepted
                    task_id: %s
                    Use task_output(task_id='%s', block=false) to check status, \
                    task_cancel(task_id='%s') to cancel, or task_list() to see all tasks. \
                    Do NOT call task_output immediately — the task has just started.\
                    """;

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;
    private final Supplier<String> userIdSupplier;

    private record SpawnedAgent(
            String key, String agentId, String sessionId, String label, Agent agent, int depth) {
    }

    private final ConcurrentHashMap<String, SpawnedAgent> agentsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> labelToKey = new ConcurrentHashMap<>();

    /**
     * Creates an {@code AgentSpawnTool} with a supplier for the parent agent's current user-id.
     *
     * @param agentManager     factory and invoker for subagents
     * @param taskRepository   background task store
     * @param parentSpawnDepth current spawn-depth of the parent (0 for top-level main agent)
     * @param userIdSupplier   provides the parent's current user-id at spawn time (may return null)
     */
    public AgentSpawnTool(
            DefaultAgentManager agentManager,
            TaskRepository taskRepository,
            int parentSpawnDepth,
            Supplier<String> userIdSupplier) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.taskRepository = taskRepository;
        this.parentSpawnDepth = parentSpawnDepth;
        this.userIdSupplier = userIdSupplier != null ? userIdSupplier : () -> null;
    }

    @Tool(
            name = "agent_spawn",
            description =
                    """
                            创建隔离的子智能体，用于任务委派或后台异步执行。
                            工具返回内容固定以三行信息开头：
                            agent_key（原样传给 agent_send 作为标识）、
                            agent_id（子智能体类型名称）、
                            session_id（内部会话标识，不可当作 agent_key 使用）。
                            同步模式下三行下方直接返回任务结果；
                            异步模式（timeout_seconds=0）会额外返回 task_id，配合 task_output 获取结果；
                            task_id 不能等同于 agent_key。
                            """)
    public Mono<String> agentSpawn(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agent_id", description = "待实例化的子智能体标识")
            String agentId,
            @ToolParam(
                    name = "task",
                    description = "下发给新建子智能体的指令/提示词，非必填",
                    required = false)
            String task,
            @ToolParam(
                    name = "label",
                    description =
                            "可选、易读的自定义标签，用于 agent_send 快速寻址子智能体，非必填",
                    required = false)
            String label,
            @ToolParam(
                    name = "timeout_seconds",
                    description =
                            """
                                    等待任务执行结果的最大秒数。
                                    传0代表「发后即忘」异步模式，仅返回task_id；
                                    默认30秒，上限600秒，非必填。
                                    """,
                    required = false)
            Integer timeoutSeconds) {

        System.err.println(
                "[agentSpawn] called agentId="
                        + agentId
                        + " timeoutSeconds="
                        + timeoutSeconds
                        + " task="
                        + task);
        int nextDepth = parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            System.err.println("[agentSpawn] depth exceeded");
            return Mono.just("Error: Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")");
        }
        if (!agentManager.hasAgent(agentId)) {
            System.err.println(
                    "[agentSpawn] unknown agentId=" + agentId + " known=" + agentManager);
            return Mono.just("Error: Unknown agent_id: " + agentId);
        }
        System.err.println("[agentSpawn] hasAgent=true, proceeding");

        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        if (canonLabel != null && labelToKey.containsKey(canonLabel.toLowerCase())) {
            return Mono.just("Error: Label already in use: " + canonLabel);
        }

        Agent agent = agentManager.createAgent(agentId);
        String key = "agent:" + agentId + ":" + UUID.randomUUID();
        String sessionId = "sub-" + UUID.randomUUID();
        String currentUserId = userIdSupplier.get();

        SpawnedAgent spawned =
                new SpawnedAgent(key, agentId, sessionId, canonLabel, agent, nextDepth);
        agentsByKey.put(key, spawned);
        if (canonLabel != null) {
            labelToKey.put(canonLabel.toLowerCase(), key);
        }

        String spawnInfo = formatSpawnInfo(key, agentId, sessionId);
        boolean hasTask = task != null && !task.isBlank();

        if (!hasTask) {
            return Mono.just(spawnInfo + "\nstatus: accepted");
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = agentManager.getDeclaration(agentId);
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(), d.getHeaders(), agentId, capturedTask);
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                agentManager
                                                        .invokeAgent(
                                                                agent,
                                                                sessionId,
                                                                currentUserId,
                                                                capturedTask)
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
            taskRepository.putTask(taskId, agentId, parentSessionId, spec);
            return Mono.just(
                    spawnInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId));
        }

        if (remote) {
            final String finalTask = task;
            return Mono.fromCallable(
                    () ->
                            runRemoteSync(
                                    spawnInfo,
                                    agentId,
                                    parentSessionId,
                                    declOpt.get(),
                                    finalTask.trim(),
                                    timeoutMs));
        }

        // Sync-local execution. Returns Mono<String> so that ToolMethodInvoker's flatMap
        // propagates the Reactor Context into the deferContextual below.
        final String finalTask = task.trim();
        final String finalSpawnInfo = spawnInfo;
        return execLocalSync(agent, sessionId, currentUserId, finalTask, spawned, runtimeContext)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(
                        reply -> {
                            String text = reply != null ? reply.getTextContent() : "";
                            return finalSpawnInfo + "\nstatus: ok\nreply:\n" + text;
                        })
                .onErrorResume(
                        e -> {
                            String err =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            log.warn("agent_spawn execute failed: agentId={}", agentId, e);
                            return Mono.just(finalSpawnInfo + "\nstatus: error\nerror: " + err);
                        });
    }

    @Tool(
            name = "agent_send",
            description =
                    """
                    向已创建的子智能体发送消息。传入 agent_spawn 输出首行完整 agent_key（以 agent: 开头），
                    或创建时自定义的 label 标签。禁止传入 agent_id、session_id、task_id。
                    timeout_seconds 设为0时返回 task_id，可通过 task_output 查询异步结果。
                    """)
    public Mono<String> agentSend(
            RuntimeContext runtimeContext,
            @ToolParam(
                    name = "agent_key",
                    description =
                            "从 agent_spawn 返回第一行「agent_key:」后复制的完整标识，格式 agent:<类型>:<uuid>。" +
                            "不可填 agent_id、session_id、task_id，与 label 二选一互斥",
                    required = false)
            String agentKey,
            @ToolParam(
                    name = "label",
                    description = "创建子智能体时自定义的标签，与 agent_key 二选一互斥",
                    required = false)
            String label,
            @ToolParam(name = "message", description = "发给子智能体的交互消息")
            String message,
            @ToolParam(
                    name = "timeout_seconds",
                    description =
                            """
                            等待子智能体回复的最大秒数。0=发后即忘异步模式，返回task_id。
                            默认30秒，最大600秒，非必填。
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
                return Mono.just("Error: Unknown label: " + label.trim());
            }
        }

        SpawnedAgent spawned = agentsByKey.get(key);
        if (spawned == null) {
            return Mono.just("Error: Unknown agent_key: " + key);
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        String currentUserId = userIdSupplier.get();
        String parentSessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        var declOpt = agentManager.getDeclaration(spawned.agentId());
        boolean remote = declOpt.map(SubagentDeclaration::isRemote).orElse(false);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedMessage = message;
            TaskRunSpec spec;
            if (remote) {
                SubagentDeclaration d = declOpt.get();
                spec =
                        new TaskRunSpec.RemoteTaskRunSpec(
                                d.getUrl(), d.getHeaders(), spawned.agentId(), capturedMessage);
            } else {
                spec =
                        new TaskRunSpec.LocalTaskRunSpec(
                                () -> {
                                    try {
                                        Msg reply =
                                                agentManager
                                                        .invokeAgent(
                                                                spawned.agent(),
                                                                spawned.sessionId(),
                                                                currentUserId,
                                                                capturedMessage)
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
            taskRepository.putTask(taskId, spawned.agentId(), parentSessionId, spec);
            return Mono.just(String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId));
        }

        if (remote) {
            final String finalMessage = message;
            final String finalKey = key;
            return Mono.fromCallable(
                    () ->
                            runRemoteSync(
                                    "agent_key: " + finalKey,
                                    spawned.agentId(),
                                    parentSessionId,
                                    declOpt.get(),
                                    finalMessage.trim(),
                                    timeoutMs));
        }

        final String finalKey = key;
        return execLocalSync(
                spawned.agent(),
                spawned.sessionId(),
                currentUserId,
                message.trim(),
                spawned,
                runtimeContext)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(
                        reply -> {
                            String text = reply != null ? reply.getTextContent() : "";
                            return "agent_key: " + finalKey + "\nstatus: ok\nreply:\n" + text;
                        })
                .onErrorResume(
                        e -> {
                            String err =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            log.warn("agent_send failed: key={}", finalKey, e);
                            return Mono.just("Error: " + err);
                        });
    }

    @Tool(name = "agent_list", description = "列出当前智能体已创建且存活的所有子智能体")
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

    /**
     * Returns a {@link Mono} that invokes the local subagent.
     *
     * <p>When the parent is in {@code stream()} mode, a {@link SubagentEventBus} is present in
     * the Reactor Context (propagated by {@code AgentBase.createEventStream}). In that case every
     * child event is forwarded to the parent sink in real time via the bus, giving the upstream
     * consumer a flat, ordered event stream across the full call hierarchy.
     *
     * <p>When no bus is present (plain {@code call()} path), execution falls back to the
     * non-streaming {@code invokeAgent} call with no overhead.
     *
     * <p><b>Context propagation note:</b> this method returns a {@code Mono} whose
     * {@code deferContextual} is subscribed by {@code ToolMethodInvoker}'s {@code flatMap}, which
     * correctly inherits the Reactor Context from the parent streaming chain. Do NOT call
     * {@code .block()} on this Mono directly inside a tool method that returns {@link String},
     * because {@code block()} creates an isolated subscription that loses the Context.
     */
    /**
     * 返回用于调用本地子智能体的 {@link Mono} 响应式流。
     *
     * <p>若父智能体以流式 stream() 模式运行，Reactor 上下文内会存在 {@link SubagentEventBus}
     * （由 AgentBase.createEventStream 传递）。此时子智能体产生的所有事件会通过事件总线实时转发至父级接收器，
     * 上游调用方可以获取整条调用链路扁平化、有序的完整事件流。
     *
     * <p>若不存在事件总线（普通 call() 非流式调用链路），逻辑自动降级为无事件开销的普通 invokeAgent 同步调用。
     *
     * <p><b>上下文传递注意事项：</b>该方法返回 Mono，内部 deferContextual 会由 ToolMethodInvoker 的 flatMap 订阅，
     * 能够正确继承父级流式链路携带的 Reactor 上下文。
     * 禁止在返回 String 类型的工具方法内部直接对该 Mono 调用 .block()；
     * block() 会创建独立订阅，导致上下文丢失。
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
                    if (!ctxView.hasKey(SubagentEventBus.CONTEXT_KEY)) {
                        // Non-streaming path — identical to previous behaviour.
                        System.err.println(
                                "[execLocalSync] NO BUS in context → non-streaming path"
                                        + " agentId="
                                        + spawned.agentId());
                        return agentManager.invokeAgent(agent, sessionId, userId, prompt);
                    }

                    System.err.println(
                            "[execLocalSync] BUS FOUND in context → streaming path agentId="
                                    + spawned.agentId());
                    SubagentEventBus bus = ctxView.get(SubagentEventBus.CONTEXT_KEY);
                    EventSource childSource = buildChildSource(spawned, parentCtx);

                    return agentManager
                            .invokeAgentStream(
                                    agent,
                                    sessionId,
                                    userId,
                                    prompt,
                                    childSource,
                                    StreamOptions.defaults())
                            .doOnNext(
                                    e -> {
                                        log.debug(
                                                "[execLocalSync] forwarding child event to bus:"
                                                        + " type={} msgId={} isLast={}",
                                                e.getType(),
                                                e.getMessage().getId(),
                                                e.isLast());
                                        bus.emit(e);
                                    })
                            .filter(e -> e.isLast() && e.getType() == EventType.AGENT_RESULT)
                            .last()
                            .map(e -> e.getMessage())
                            // If AGENT_RESULT was not included in StreamOptions, the filter above
                            // yields empty; fall back to a plain invokeAgent to get the final Msg.
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    agentManager.invokeAgent(
                                                            agent, sessionId, userId, prompt)));
                });
    }

    /**
     * Builds an {@link EventSource} for a freshly spawned or known subagent. The path is
     * constructed from the parent session ID (or {@code "main"} as fallback) plus the child's
     * {@code agentId}, separated by {@code "/"}.
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
     * Submits a remote task through {@link TaskRepository} (for durable state) and blocks until
     * it completes or the timeout elapses.
     *
     * <p>Using the repository ensures the task is visible to {@code task_list} and survives
     * conversation compaction, just like async remote tasks do.
     */
    private String runRemoteSync(
            String header,
            String agentId,
            String parentSessionId,
            SubagentDeclaration decl,
            String input,
            long timeoutMs) {
        String taskId = "task_" + UUID.randomUUID();
        TaskRunSpec spec =
                new TaskRunSpec.RemoteTaskRunSpec(decl.getUrl(), decl.getHeaders(), agentId, input);
        BackgroundTask bgTask = taskRepository.putTask(taskId, agentId, parentSessionId, spec);
        try {
            boolean done = bgTask.waitForCompletion(timeoutMs);
            if (!done) {
                return header + "\nstatus: timeout\ntask_id: " + taskId;
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
            return header + "\nstatus: ok\nreply:\n" + (result != null ? result : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("agent remote sync interrupted: agentId={}", agentId);
            return header + "\nstatus: error\nerror: interrupted";
        }
    }

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    private static String formatSpawnInfo(String key, String agentId, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent_key: ").append(key).append("\n");
        sb.append("agent_id: ").append(agentId).append("\n");
        sb.append("session_id: ").append(sessionId);
        return sb.toString();
    }
}
