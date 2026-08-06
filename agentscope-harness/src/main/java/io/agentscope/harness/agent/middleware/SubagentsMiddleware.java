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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.tool.AgentGenerateTool;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that provides the managed subagent mechanism.
 *
 * <p>In <strong>default mode</strong> (standalone harness setup), this middleware creates an
 * {@link AgentSpawnTool} backed by a {@link DefaultAgentManager}. In <strong>session mode</strong>
 * (orchestrated via {@code AgentBootstrap}), an external tool (typically {@code SessionsTool})
 * is injected, replacing the default {@link AgentSpawnTool}.
 *
 * <p>Responsibilities:
 *
 * <ol>
 *   <li>Exposes the subagent tool and {@link TaskTool} as agent tools (callers query via
 *       {@link #getTools()} and register them on the toolkit at orchestration time).
 *   <li>On every {@link #onAgent}, reloads subagent declarations from the workspace filesystem
 *       (namespace-scoped) to support per-user subagent isolation.
 *   <li>Prepends rich subagent usage guidance and current async task summary to the leading
 *       SYSTEM message of every {@link ReasoningInput}. Because the framework rebuilds the
 *       SYSTEM message from a frozen base each iteration, this is safe — content never
 *       accumulates across iterations.
 * </ol>
 */
/**
 * 提供托管子智能体机制的中间件。
 *
 * <p><strong>默认模式</strong>（独立 harness 部署）下，本中间件内部创建由
 * {@link DefaultAgentManager} 支撑的 {@link AgentSpawnTool}；
 * <strong>会话模式</strong>（经 {@code AgentBootstrap} 编排）下，注入外部工具
 * （通常为 {@code SessionsTool}）替换默认的 {@link AgentSpawnTool}。
 *
 * <p>职责：
 *
 * <ol>
 *   <li>向智能体工具集暴露子智能体工具与 {@link TaskTool}（调用方通过
 *       {@link #getTools()} 查询，并在编排阶段注册到工具集）。</li>
 *   <li>每次 {@link #onAgent} 时从工作区文件系统（带命名空间隔离）重新加载
 *       子智能体声明，支持按用户隔离的子智能体。</li>
 *   <li>在每个 {@link ReasoningInput} 的首条 SYSTEM 消息前部注入子智能体使用说明
 *       与当前异步任务摘要。由于框架每轮都会基于冻结的基础内容重建 SYSTEM 消息，
 *       这样做是安全的——内容不会跨轮累积。</li>
 * </ol>
 */
public class SubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SubagentsMiddleware.class);

    /** 任务摘要中起始时间的展示格式（UTC，精确到分钟）。 */
    private static final DateTimeFormatter ISO_SHORT =
            DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC);

    /** 任务摘要中最多列出的任务条数，超出部分以省略提示收尾。 */
    private static final int MAX_TASK_SUMMARY_ENTRIES = 10;

    /**
     * Cap on individual task results aggregated into a single {@code <system-reminder>} delivery
     * message. Anything beyond this gets a "... and N more — call task_list()" footer; the LLM
     * can still fetch each one explicitly.
     */
    /**
     * 单条 {@code <system-reminder>} 推送消息中聚合的任务结果条数上限。
     * 超出部分以"... and N more — call task_list()"尾注提示；
     * 大模型仍可逐个显式获取完整结果。
     */
    static final int MAX_DELIVERIES_PER_REMINDER = 10;

   /* private static final String SUBAGENT_SECTION_TEMPLATE =
            """

            ## 子智能体

            你可以使用子智能体工具创建并调度相互隔离的子智能体。
            子智能体属于临时实例，仅在任务执行周期内存活，最终仅返回一份结果。

            ### 智能体工具

            **`%s`** — 创建隔离的子智能体
            - `agent_id`（必填）：待实例化的子智能体标识
            - `task`（可选）：初始指令；不填则创建持久会话
            - `label`（可选）：便于引用的可读名称，后续可通过该标识发送消息
            - `timeout_seconds`：等待时长；0=发后即忘（仅返回 task_id），默认30秒，最大600秒
            - 返回结果始终包含 `agent_key`（不透明句柄），请保存用于后续消息交互

            **`%s`** — 向已创建的子智能体发送后续消息
            - `agent_key`：复制创建结果中 `agent_key:` 后的完整值（以 `agent:` 开头）。
              该值不等于 `agent_id`、`session_id` 或 `task_id`
            - 若创建时指定了 `label`，也可使用标签寻址（与 agent_key 互斥）
            - `message`（必填）：待发送内容
            - `timeout_seconds`：0=发后即忘，大于0则等待回复（默认30秒）

            **`%s`** — 列出当前活跃的子智能体

            ### 任务工具（用于异步/后台任务）

            **`task_output`** — 通过 task_id 获取后台任务执行结果。
            - **极少需要主动调用**。任务完成后，结果会自动以 `<system-reminder>` 区块推送给你，在下一轮推理前可见。
            - 仅当推送的摘要被截断、需要完整结果，或是按需查看指定任务时，使用 `task_output(block=false)`。
            - 尽量避免使用 `block=true`，该模式会阻塞会话流程。

            **`task_cancel`** — 根据 task_id 终止正在运行的后台任务。对已完成任务无效。

            **`task_list`** — 列出所有运行中的后台任务（具备持久能力，会话压缩、实例迁移后数据依然准确）。
            任务推送结果后会从列表中移除。

            ### 后台任务执行流程
            1. 创建子智能体时设置 `timeout_seconds=0` 启用发后即忘模式，响应中将返回 task_id。
            2. **禁止轮询查询**。继续处理其他工作；任务完成后，结果会通过 `<system-reminder>` 自动推送。
            3. 若无待处理工作，可将控制权交还给用户；用户发起新一轮提问后，下一轮推理会加载所有已完成任务结果。

            ### 超时自动升级机制
            同步创建/发送消息触发超时时，任务**不会丢失**，将自动升级为后台任务。
            返回状态 `status: timeout_promoted` 并附带 `task_id`。处理方式与普通异步任务一致：结果会通过 `<system-reminder>` 自动推送。
            **请勿重复发起相同任务**，后台已经在执行。

            ### 可用智能体标识
            %s

            ### 何时使用子智能体
            - 任务复杂、多步骤，能够完整独立委派执行
            - 任务与其他工作互不依赖，可以并行运行
            - 任务需要专注推理或消耗大量上下文，会导致主线上下文膨胀
            - 沙箱隔离有助于提升稳定性（例如代码分析、结构化检索、数据格式化）
            - 仅关心最终输出，不需要查看中间过程（例如调研 → 整合报告）

            ### 不建议使用子智能体
            - 任务逻辑简单（仅少量工具调用、简单查询）
            - 任务完成后仍需要查看中间推理过程
            - 委派执行无法降低token消耗、系统复杂度或上下文切换开销
            - 任务拆分只会增加延迟且没有收益

            ### 子智能体生命周期
            1. **创建** → 提供清晰角色、执行要求与预期输出格式
            2. **运行** → 子智能体自主完成任务
            3. **返回** → 子智能体输出一份结构化结果
            4. **整合** → 将结果吸收、汇总至主线会话

            ### 使用范式
            - **并行执行**：多个任务相互独立时，设置 `timeout_seconds=0` 并发拉起子智能体；等待一段时间后使用 `task_output(block=false)` 收集结果
            - **同步委派**：简单一次性任务使用默认超时同步调用
            - **持久会话**：创建时不传入 task，后续反复调用 send 进行多轮交互
            - **清理过期任务**：使用 task_cancel 终止不再需要的后台任务
            - 子智能体执行结果对用户不可见，务必在最终回复中进行总结
            """;*/

    // @formatter:off
    private static final String SUBAGENT_SECTION_TEMPLATE =
            """

            ## Subagents

            You have access to subagent tools for spawning and coordinating isolated subagents.
            Subagents are ephemeral — they live only for the duration of the task and return a single result.

            ### Agent Tools

            **`%s`** — Spawn an isolated subagent
            - `agent_id` (required): which subagent to instantiate
            - `task` (optional): initial prompt; omit to create a persistent session
            - `label` (optional): human-readable name for referencing via send
            - `timeout_seconds`: wait time; 0=fire-and-forget (returns task_id), default=30, max=600
            - Response always includes `agent_key:` (opaque handle) — save it for follow-up sends

            **`%s`** — Send a follow-up message to an existing subagent
            - `agent_key`: copy the **full value** after `agent_key:` from spawn output (starts with `agent:`). This is NOT `agent_id`, NOT `session_id`, and NOT `task_id`
            - Or use `label` if you set one at spawn (mutually exclusive with agent_key)
            - `message` (required): content to send
            - `timeout_seconds`: 0=fire-and-forget, >0=wait for reply (default: 30)

            **`%s`** — List active subagents

            ### Task Tools (for async/background operations)

            **`task_output`** — Retrieve the result of a background task by task_id.
            - **You rarely need this.** Completed tasks are pushed back to you automatically as a `<system-reminder>` block before your next reasoning step.
            - Use `task_output(block=false)` only when you need the full result and the pushed summary was truncated, or to inspect a specific task on demand.
            - Avoid `block=true`; it serialises the conversation behind the task.

            **`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.

            **`task_list`** — List all in-flight background tasks (durable, accurate across compaction and migration). Completed tasks fall off this list after they're pushed to you.

            ### Background task flow
            1. Spawn with `timeout_seconds=0` to fire-and-forget; the response gives you a task_id.
            2. **Do not poll.** Continue with other work; when the task finishes you'll see a `<system-reminder>` containing its result.
            3. If the agent has nothing useful to do, hand control back to the user — they'll prompt again when ready and the next reasoning round will surface any completions.

            ### Timeout promotion
            When a sync spawn/send exceeds its timeout, the task is **not lost** — it is automatically promoted to a background task. You receive `status: timeout_promoted` with a `task_id`. Treat it like any async task: the result will be pushed back to you automatically as a `<system-reminder>`. Do NOT retry the same task — it is already running in the background.

            ### Available agent ids
            %s

            ### When to use subagents
            - When a task is complex and multi-step, and can be fully delegated in isolation
            - When a task is independent of other tasks and can run in parallel
            - When a task requires focused reasoning or heavy context usage that would bloat the main thread
            - When sandboxing improves reliability (e.g. code analysis, structured searches, data formatting)
            - When you only care about the output, not the intermediate steps (e.g. research → synthesized report)

            ### When NOT to use subagents
            - If the task is trivial (a few tool calls or simple lookup)
            - If you need to see intermediate reasoning or steps after completion
            - If delegating does not reduce token usage, complexity, or context switching
            - If splitting would add latency without benefit

            ### Subagent lifecycle
            1. **Spawn** → Provide clear role, instructions, and expected output format
            2. **Run** → The subagent completes the task autonomously
            3. **Return** → The subagent provides a single structured result
            4. **Reconcile** → Incorporate or synthesize the result into the main thread

            ### Usage patterns
            - **Parallel execution**: Launch multiple subagents concurrently with timeout_seconds=0 when tasks are independent, then collect results with task_output(block=false) after a delay
            - **Sync delegation**: Use default timeout for simple one-shot delegation
            - **Persistent session**: Spawn without a task, then use send for multi-turn interaction
            - **Cancel stale work**: Use task_cancel to stop background tasks that are no longer needed
            - Subagent results are NOT visible to the user — always summarize them in your response
            """;
    // @formatter:on

    /** 编程式注册的基础条目集合（动态重载时以此为基准合并）。 */
    private final List<SubagentEntry> baseEntries;

    /** 当前生效的条目集合（含动态重载结果），volatile 保证多线程可见性。 */
    private volatile List<SubagentEntry> entries;

    /** 子智能体创建工具（默认模式为 AgentSpawnTool，会话模式为外部工具）。 */
    private volatile Object subagentTool;

    /** 任务工具（task_output / task_cancel / task_list）。 */
    private final TaskTool taskTool;

    /** 后台任务仓库，记录子智能体异步任务的状态与结果。 */
    private final TaskRepository taskRepository;

    /** 是否为会话模式（使用外部工具，无内部管理器与动态重载）。 */
    private final boolean isSessionMode;

    /** 工作区文件系统（带命名空间隔离），用于动态重载声明，可为 null（不支持重载）。 */
    private final AbstractFilesystem filesystem;

    /** 主工作区根目录，动态重载声明文件的位置，可为 null。 */
    private final Path mainWorkspace;

    /** 声明转工厂的构建函数，可为 null（不支持重载）。 */
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;

    /** 内部智能体管理器，会话模式下为 null。 */
    private final DefaultAgentManager agentManager;

    /**
     * Optional {@link AgentGenerateTool} for LLM-driven subagent spec generation. Lazy because
     * the spec generator needs a {@link io.agentscope.core.model.Model} instance that callers
     * supply via {@link #enableAgentGenerateTool}; null when not enabled (OOTB default).
     */
    /**
     * 可选 {@link AgentGenerateTool}，用于由大模型驱动生成子智能体定义。
     * 采用懒加载方式，因为该定义生成器需要调用方通过 {@link #enableAgentGenerateTool}
     * 提供 {@link io.agentscope.core.model.Model} 实例；未启用时为 {@code null}（开箱默认值）。
     */
    private volatile AgentGenerateTool agentGenerateTool;

    /**
     * Default mode: creates {@link AgentSpawnTool} + {@link DefaultAgentManager} internally.
     */
    /**
     * 默认模式：内部自动创建 {@link AgentSpawnTool} 与 {@link DefaultAgentManager}。
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder) {
        this.baseEntries = List.copyOf(entries);
        this.entries = this.baseEntries;
        this.isSessionMode = false;
        DefaultAgentManager dam = new DefaultAgentManager(entries, workspaceManager);
        this.agentManager = dam;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.subagentTool = new AgentSpawnTool(dam, taskRepository, 0);
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
    }

    /**
     * Default mode without dynamic reload support.
     */
    /**
     * 默认模式，不支持动态重载。
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager) {
        this(entries, taskRepository, workspaceManager, null, null, null);
    }

    /**
     * AgentStateStore mode: uses the externally provided tool (typically {@code SessionsTool}).
     */
    /**
     * AgentStateStore 模式：使用外部传入的工具（通常为 {@code SessionsTool}）。
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            Object externalSubagentTool,
            TaskRepository taskRepository) {
        this.baseEntries = List.copyOf(entries);
        this.entries = this.baseEntries;
        this.isSessionMode = true;
        this.agentManager = null;
        this.subagentTool = externalSubagentTool;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = null;
        this.mainWorkspace = null;
        this.factoryBuilder = null;
    }

    /**
     * Enables the {@code agent_generate} tool, which lets the LLM author new subagent specs from
     * a description. Off by default — the generator needs a {@link io.agentscope.core.model.Model}
     * and writes to the workspace, so callers opt in explicitly.
     *
     * <p>Only effective in default (non-session) mode, where this middleware owns a
     * {@link DefaultAgentManager}. In session mode the manager is external and this call is a
     * no-op.
     *
     * @param generator a {@link SubagentSpecGenerator} backed by the model that should author specs
     * @return this middleware for chaining
     */
    /**
     * 启用 {@code agent_generate} 工具，允许大模型根据描述生成新的子智能体定义。
     * 默认关闭——该生成器依赖 {@link io.agentscope.core.model.Model}，且会向工作空间写入数据，需调用方显式开启。
     *
     * <p>仅在默认（非会话）模式下生效，该模式下中间件持有 {@link DefaultAgentManager}。
     * 会话模式中管理器由外部提供，调用此方法不会产生任何效果。
     *
     * @param generator 由模型驱动、用于生成定义的 {@link SubagentSpecGenerator}
     * @return 当前中间件实例，支持链式调用
     */
    public SubagentsMiddleware enableAgentGenerateTool(SubagentSpecGenerator generator) {
        if (isSessionMode || agentManager == null) {
            log.debug("enableAgentGenerateTool ignored in session mode (no internal manager)");
            return this;
        }
        this.agentGenerateTool = new AgentGenerateTool(generator, agentManager, filesystem);
        return this;
    }

    /** Returns the {@link TaskRepository} used by this middleware. */
    /** 返回当前中间件所使用的 {@link TaskRepository}。 */
    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    /**
     * Wires a {@link io.agentscope.harness.agent.bus.MessageBus} so that background task completions are
     * pushed to the session inbox and a wakeup signal is enqueued. This enables the
     * {@link io.agentscope.harness.agent.gateway.WakeupDispatcher} to re-trigger idle sessions
     * when subagent work finishes.
     *
     * <p>Registers a {@link WorkspaceTaskRepository.TaskCompletionCallback} on the underlying
     * repository (if it is a {@link WorkspaceTaskRepository}). Safe to call multiple times — each
     * call replaces the previous callback.
     *
     * @param messageBus the application message bus
     * @param agentId the parent agent id (for wakeup routing)
     * @return this middleware for chaining
     */
    /**
     * 装配 {@link io.agentscope.harness.agent.bus.MessageBus}，后台任务完成事件将推送至会话收件箱并排入唤醒信号。
     * 使得 {@link io.agentscope.harness.agent.gateway.WakeupDispatcher} 能够在子智能体任务结束时重新唤醒空闲会话。
     *
     * <p>若底层仓库为 {@link WorkspaceTaskRepository}，将向其注册 {@link WorkspaceTaskRepository.TaskCompletionCallback}。
     * 支持多次调用，每次调用都会覆盖上一次的回调。
     *
     * @param messageBus 应用消息总线
     * @param agentId 父智能体标识（用于唤醒路由分发）
     * @return 当前中间件实例，支持链式调用
     */
    public SubagentsMiddleware wireMessageBus(
            io.agentscope.harness.agent.bus.MessageBus messageBus, String agentId) {
        if (messageBus == null) {
            return this;
        }
        if (taskRepository instanceof WorkspaceTaskRepository wtr) {
            wtr.setCompletionCallback(
                    (rc, taskId, subAgentId, sessionId, result) -> {
                        String userId = rc != null ? rc.getUserId() : null;
                        String hintContent =
                                String.format(
                                        "<system-notification>Background subagent task '%s'"
                                                + " (agent=%s) has completed.\n\nResult:\n\n%s"
                                                + "</system-notification>",
                                        taskId,
                                        subAgentId,
                                        result != null ? result : "(no output)");
                        String hintId = java.util.UUID.randomUUID().toString().replace("-", "");
                        java.util.Map<String, Object> hintPayload =
                                java.util.Map.of(
                                        "type",
                                        "hint",
                                        "id",
                                        hintId,
                                        "hint",
                                        hintContent,
                                        "source",
                                        "subagent_task");
                        messageBus.inboxPush(sessionId, hintPayload).subscribe();
                        messageBus
                                .enqueueWakeup(
                                        userId != null ? userId : "",
                                        sessionId,
                                        agentId != null ? agentId : "")
                                .subscribe(
                                        unused -> {},
                                        err ->
                                                log.warn(
                                                        "Failed to enqueue wakeup after task {}"
                                                                + " completion: {}",
                                                        taskId,
                                                        err.getMessage()));
                        log.info(
                                "Subagent task {} completed, pushed to inbox and enqueued wakeup:"
                                        + " session={}",
                                taskId,
                                sessionId);
                    });
        }
        return this;
    }

    /**
     * Wires a gateway bridge into the internal {@link AgentSpawnTool}, enabling spawned subagents
     * to be exposed as user-addressable threads. Only effective in default (non-session) mode.
     *
     * @param bridge the bridge implementation (typically obtained from
     *     {@link io.agentscope.harness.agent.gateway.GatewayBootstrap#gatewayBridge()})
     * @return this middleware for chaining
     */
    /**
     * 为内部 {@link AgentSpawnTool} 装配网关桥接器，支持创建的子智能体对外暴露为用户可访问会话线程。
     * 仅在默认（非会话）模式下生效。
     *
     * @param bridge 桥接器实现（通常通过
     *     {@link io.agentscope.harness.agent.gateway.GatewayBootstrap#gatewayBridge()} 获取）
     * @return 当前中间件实例，支持链式调用
     */
    public SubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (isSessionMode || agentManager == null) {
            log.debug("setGatewayBridge ignored in session mode (no internal manager)");
            return this;
        }
        // Mutate the bridge on the live tool instead of replacing it: the toolkit binds
        // agent_spawn to the AgentSpawnTool instance returned by getTools() at orchestration
        // time, so a fresh instance here would never be invoked and exposure would silently
        // never fire.
        // 就地修改现有工具实例上的桥，而不是替换实例：工具集在编排阶段已将 agent_spawn
        // 绑定到 getTools() 返回的那个 AgentSpawnTool 实例，此处若新建实例将永远不会被调用，
        // 子智能体暴露功能会静默失效。
        if (this.subagentTool instanceof AgentSpawnTool ast) {
            ast.setGatewayBridge(bridge);
        } else {
            this.subagentTool = new AgentSpawnTool(agentManager, taskRepository, 0, bridge);
        }
        return this;
    }

    /**
     * Returns the internal {@link DefaultAgentManager} that can re-materialize subagents, or
     * {@code null} in session mode (external tool). Used to wire a gateway materializer for
     * cross-node exposed-subagent recovery.
     */
    /**
     * 返回可重新实例化子智能体的内部 {@link DefaultAgentManager}；
     * 会话模式（外部工具模式）下返回 {@code null}。
     * 用于装配网关实例恢复器，实现跨节点暴露子智能体状态恢复。
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * Returns the tool instances this middleware contributes to the agent toolkit. The caller
     * is responsible for registering them on the toolkit at orchestration time.
     *
     * <p>When {@link #enableAgentGenerateTool} has been called, the returned list additionally
     * contains an {@link AgentGenerateTool}.
     */
    /**
     * 返回当前中间件向智能体工具集提供的工具实例。调用方需要在编排阶段将这些工具注册至工具集。
     *
     * <p>若已调用 {@link #enableAgentGenerateTool}，返回列表中将额外包含 {@link AgentGenerateTool}。
     */
    public List<Object> getTools() {
        if (entries.isEmpty()) {
            return List.of();
        }
        AgentGenerateTool gen = this.agentGenerateTool;
        if (gen != null) {
            return List.of(subagentTool, taskTool, gen);
        }
        return List.of(subagentTool, taskTool);
    }

    /** 智能体钩子：每次调用开始时重新加载子智能体声明（支持按用户隔离的动态集合）。 */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        reloadSubagentEntries();
        return next.apply(input);
    }

    /**
     * 推理钩子：每轮推理前向 SYSTEM 消息注入"子智能体使用说明 + 任务摘要"，
     * 并把新完成的后台任务结果以 {@code <system-reminder>} 形式推送给模型。
     *
     * <p>推送采用"先注入、后确认"的安全顺序：仅在推理成功完成后才把任务标记为
     * 已送达，崩溃场景下最多造成下一轮重复推送，不会丢消息。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<SubagentEntry> currentEntries = this.entries;
        if (currentEntries.isEmpty()) {
            return next.apply(input);
        }
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        String sessionId = rc != null ? rc.getSessionId() : null;

        // ---- Phase B-3 push delivery -------------------------------------------------------
        // Drain newly-terminal tasks first so the SYSTEM summary built afterwards can omit them.
        // Only persist to AgentState when the agent is a ReActAgent — other Agent kinds keep
        // the legacy pull-only flow unchanged.
        // ---- B-3 阶段：推送送达 -------------------------------------------------------
        // 先取出新进入终态的任务，这样之后构建的 SYSTEM 摘要可以把它们排除。
        // 仅当智能体为 ReActAgent 时才写入 AgentState——其他类型保持旧的仅拉取流程。
        List<TaskDelivery> pending = this.taskRepository.findPendingDeliveries(rc, sessionId);
        Msg deliveryMsg = null;
        if (!pending.isEmpty() && agent instanceof ReActAgent reAct) {
            deliveryMsg = buildDeliveryReminder(pending);
            try {
                RuntimeContext.resolveAgentState(rc, reAct).contextMutable().add(deliveryMsg);
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to append task delivery reminder to AgentState; "
                                + "will inject for this round only: {}",
                        e.getMessage());
            }
        }

        StringBuilder addition = new StringBuilder();
        addition.append(renderSubagentSection(currentEntries, isSessionMode));
        String taskSummary = buildTaskSummary(this.taskRepository, rc);
        if (taskSummary != null) {
            addition.append(taskSummary);
        }
        List<Msg> rebuilt = prependToSystemMessage(input.messages(), addition.toString());
        if (deliveryMsg != null) {
            // Inject for this round (parallel to the AgentState write — keeps the message visible
            // in the immediate LLM call regardless of when the framework re-derives messages from
            // the state next round).
            // 本轮同时注入（与 AgentState 写入并行——无论框架下一轮何时从状态重建消息，
            // 都能保证该消息在本次 LLM 调用中可见）。
            rebuilt = new ArrayList<>(rebuilt);
            rebuilt.add(deliveryMsg);
        }

        Flux<AgentEvent> downstream =
                next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
        if (!pending.isEmpty()) {
            // Mark delivered ONLY after the inner reasoning completes successfully. Order matters:
            // if we marked before reasoning, a crash mid-call could persist deliveredAt while the
            // AgentState write was never flushed, causing permanent message loss. The reverse
            // (crash AFTER reasoning, BEFORE markDelivered) only causes a redundant re-delivery
            // on the next round — annoying, but safe.
            // 仅在内部推理成功完成后才标记为已送达。顺序很关键：
            // 若在推理前标记，调用中途崩溃可能导致 deliveredAt 已持久化而 AgentState 写入未落盘，
            // 造成消息永久丢失；反过来（推理成功后、标记前崩溃）只会导致下一轮重复推送——
            // 虽然烦人，但是安全的。
            final TaskRepository repoRef = this.taskRepository;
            final RuntimeContext rcRef = rc;
            final String sidRef = sessionId;
            downstream =
                    downstream.doOnComplete(
                            () -> {
                                for (TaskDelivery d : pending) {
                                    try {
                                        repoRef.markDelivered(rcRef, sidRef, d.taskId());
                                    } catch (RuntimeException e) {
                                        log.warn(
                                                "Failed to mark task {} as delivered; "
                                                        + "may re-push next round: {}",
                                                d.taskId(),
                                                e.getMessage());
                                    }
                                }
                            });
        }
        return downstream;
    }

    /**
     * Builds the single aggregated {@code <system-reminder>} message that surfaces newly-terminal
     * background tasks to the LLM. Caps at {@link #MAX_DELIVERIES_PER_REMINDER} entries; the
     * remainder is mentioned in a tail line so the LLM knows to call {@code task_list()} if it
     * wants the full set.
     *
     * <p>Role / metadata mirror {@code TaskReminderMiddleware} so downstream UI and persistence
     * layers can recognise this as system-originated synthetic content rather than user input.
     */
    /**
     * 构建聚合后的 {@code <system-reminder>} 消息，向大模型推送新近完成的后台任务。
     * 消息内条目上限为 {@link #MAX_DELIVERIES_PER_REMINDER}；超出部分会在末尾提示，
     * 大模型如需查看全部任务可调用 {@code task_list()}。
     *
     * <p>角色与元数据和 {@code TaskReminderMiddleware} 保持一致，便于下游界面与持久化层
     * 识别该内容为系统生成消息，而非用户输入。
     */
    static Msg buildDeliveryReminder(List<TaskDelivery> deliveries) {
        int total = deliveries.size();
        int shown = Math.min(total, MAX_DELIVERIES_PER_REMINDER);
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append(total).append(" background subagent task");
        sb.append(total == 1 ? " has" : "s have");
        sb.append(" completed since your last turn. ");
        sb.append("These results are now part of your conversation history.\n\n");
        for (int i = 0; i < shown; i++) {
            TaskDelivery d = deliveries.get(i);
            String state = stateLiteral(d.status());
            sb.append("<task id=\"").append(d.taskId()).append("\" state=\"").append(state);
            sb.append("\"");
            if (d.agentId() != null) {
                sb.append(" agent=\"").append(d.agentId()).append("\"");
            }
            sb.append(">\n");
            switch (d.status()) {
                case COMPLETED -> {
                    sb.append("<task_result>\n");
                    sb.append(d.result() != null ? d.result() : "");
                    sb.append("\n</task_result>\n");
                }
                case FAILED -> {
                    sb.append("<task_error>\n");
                    sb.append(d.errorMessage() != null ? d.errorMessage() : "(no error message)");
                    sb.append("\n</task_error>\n");
                }
                case CANCELLED -> sb.append("Task was cancelled before producing a result.\n");
                default ->
                        sb.append("Task ended in non-terminal state ")
                                .append(d.status())
                                .append('\n');
            }
            sb.append("</task>\n");
        }
        if (total > shown) {
            sb.append("\n... and ")
                    .append(total - shown)
                    .append(" more — call task_list() to inspect.\n");
        }
        sb.append(
                "\nIf you need a single task's full output, call"
                        + " task_output(task_id=..., block=false).\n");
        sb.append("</system-reminder>");
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(sb.toString()).build())
                .metadata(
                        Map.of(
                                Msg.METADATA_SYNTHETIC,
                                true,
                                Msg.METADATA_REMINDER_KIND,
                                "task_delivery"))
                .build();
    }

    /** 把任务状态枚举转换为推送消息中展示的小写字面量（FAILED 显示为 error）。 */
    private static String stateLiteral(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
            default -> status.name().toLowerCase();
        };
    }

    /**
     * Appends the given extra content to the leading SYSTEM message of {@code messages}.
     * If no SYSTEM message is present, a new one is inserted at index 0.
     */
    /**
     * 将指定附加内容追加至消息列表 {@code messages} 首条系统消息尾部。
     * 若不存在系统消息，则在索引0位置新增一条系统消息。
     */
    static List<Msg> prependToSystemMessage(List<Msg> messages, String extra) {
        if (extra == null || extra.isEmpty()) {
            return messages != null ? messages : List.of();
        }
        List<Msg> out = new ArrayList<>(messages != null ? messages.size() : 1);
        if (messages == null || messages.isEmpty() || messages.get(0).getRole() != MsgRole.SYSTEM) {
            out.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(extra).build())
                            .build());
            if (messages != null) {
                out.addAll(messages);
            }
            return out;
        }
        Msg sys = messages.get(0);
        String existing = sys.getTextContent() != null ? sys.getTextContent() : "";
        String merged = existing.isEmpty() ? extra : existing + "\n" + extra;
        Msg newSys =
                Msg.builder()
                        .id(sys.getId())
                        .name(sys.getName())
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(merged).build())
                        .metadata(sys.getMetadata())
                        .timestamp(sys.getTimestamp())
                        .build();
        out.add(newSys);
        out.addAll(messages.subList(1, messages.size()));
        return out;
    }

    /**
     * 从文件系统重新加载子智能体声明并与基础条目合并。
     *
     * <p>仅在默认模式且具备重载条件（文件系统与工厂构建器都已配置）时执行；
     * 与基础条目同名的声明不会覆盖（与 {@link DynamicSubagentsMiddleware} 的
     * 动态优先策略不同），只追加新声明。加载失败仅记录警告，保留旧条目集合。
     */
    private void reloadSubagentEntries() {
        if (filesystem == null || factoryBuilder == null || isSessionMode) {
            return;
        }
        try {
            List<SubagentDeclaration> decls =
                    AgentSpecLoader.loadFromFilesystem(filesystem, mainWorkspace);

            List<SubagentEntry> newEntries = new ArrayList<>(baseEntries);
            for (SubagentDeclaration decl : decls) {
                // 基础条目中已存在同名子智能体时跳过，不覆盖编程式注册
                boolean alreadyExists =
                        baseEntries.stream().anyMatch(e -> e.name().equals(decl.getName()));
                if (!alreadyExists) {
                    newEntries.add(
                            new SubagentEntry(
                                    decl.getName(),
                                    decl.getDescription(),
                                    factoryBuilder.apply(decl),
                                    decl));
                }
            }

            this.entries = List.copyOf(newEntries);
            if (agentManager != null) {
                agentManager.refreshEntries(this.entries);
            }
        } catch (Exception e) {
            log.warn("Failed to reload subagent entries from filesystem: {}", e.getMessage());
        }
    }

    /**
     * Renders the {@code ## Subagents} system-prompt section for the supplied entries. Shared
     * with {@link DynamicSubagentsMiddleware}.
     *
     * <p>Filters out entries whose declaration sets {@code hidden=true} or {@code mode=PRIMARY}:
     * the LLM should not see internal-use subagents (hidden) and cannot spawn primary-only
     * declarations (the {@link io.agentscope.harness.agent.subagent.DefaultAgentManager} rejects
     * such spawns anyway, so advertising them would be a footgun). Programmatic registrations
     * without a declaration ({@code entry.declaration() == null}) are always shown.
     */
    /**
     * 根据传入条目渲染系统提示词中的【## Subagents】段落，供 {@link DynamicSubagentsMiddleware} 共用。
     *
     * <p>过滤满足以下条件的条目：声明中设置 {@code hidden=true} 或 {@code mode=PRIMARY}。
     * 大模型不应感知仅供内部使用的子智能体（hidden），同时不允许创建仅主实例类型的声明；
     * {@link io.agentscope.harness.agent.subagent.DefaultAgentManager} 本身会拦截此类创建请求，
     * 若展示给大模型容易引发误用风险。不存在声明的编程注册条目（{@code entry.declaration() == null}）始终对外展示。
     */
    public static String renderSubagentSection(List<SubagentEntry> entries, boolean isSessionMode) {
        String agentList =
                entries.stream()
                        .filter(SubagentsMiddleware::isVisibleToLlm)
                        .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                        .collect(Collectors.joining("\n"));
        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";
        return String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName, agentList);
    }

    /**
     * Whether an entry should appear in the LLM-facing list of available subagents.
     * Programmatic entries with no declaration default to visible.
     */
    /**
     * 控制该条目是否展示在大模型可见的可用子智能体列表中。
     * 无定义声明的编程式条目默认为可见。
     */
    private static boolean isVisibleToLlm(SubagentEntry e) {
        SubagentDeclaration decl = e.declaration();
        if (decl == null) return true;
        if (decl.isHidden()) return false;
        return decl.getMode() != SubagentDeclaration.Mode.PRIMARY;
    }

    /**
     * Builds a concise summary of in-flight tasks for the current session, or {@code null} if
     * none. Shared with {@link DynamicSubagentsMiddleware}.
     *
     * <p>Phase B-3: already-delivered tasks are filtered out — they live in the conversation
     * history as {@code <system-reminder>} blocks, so re-mentioning them here would (a) waste
     * tokens, (b) push genuinely still-running tasks past the {@link #MAX_TASK_SUMMARY_ENTRIES}
     * cap, and (c) invite the LLM to second-guess the push by re-fetching results.
     */
    /**
     * 生成当前会话内正在执行任务的精简摘要，无任务时返回 {@code null}。
     * 该方法可供 {@link DynamicSubagentsMiddleware} 共用。
     *
     * <p>B-3阶段：已推送完成的任务会被过滤。这类任务已以 {@code <system-reminder>}
     * 节点保存在对话历史中，如果在此处重复列出会产生以下问题：(a) 浪费令牌；
     * (b) 挤占 {@link #MAX_TASK_SUMMARY_ENTRIES} 条目上限，导致真正运行中的任务被截断；
     * (c) 诱导大模型重复拉取结果，质疑系统推送的消息。
     */
    public static String buildTaskSummary(TaskRepository repo, RuntimeContext ctx) {
        if (repo == null) {
            return null;
        }
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        Collection<BackgroundTask> tasks = repo.listTasks(ctx, sessionId, null);
        if (tasks.isEmpty()) {
            return null;
        }

        List<BackgroundTask> visible = new ArrayList<>();
        for (BackgroundTask task : tasks) {
            if (repo.isDelivered(ctx, sessionId, task.getTaskId())) continue;
            visible.add(task);
        }
        if (visible.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n### Async tasks (current session)\n");
        int count = 0;
        for (BackgroundTask task : visible) {
            if (count >= MAX_TASK_SUMMARY_ENTRIES) {
                sb.append("- ... (")
                        .append(visible.size() - MAX_TASK_SUMMARY_ENTRIES)
                        .append(" more — use task_list() to see all)\n");
                break;
            }
            sb.append("- task_id: ").append(task.getTaskId());
            if (task.getAgentId() != null) {
                sb.append("  agent: ").append(task.getAgentId());
            }
            sb.append("  status: ").append(task.getTaskStatus().name().toLowerCase());
            sb.append("  started: ").append(ISO_SHORT.format(task.getCreatedAt()));
            sb.append('\n');
            count++;
        }
        sb.append(
                "(Completed/failed/cancelled tasks are pushed back to you as <system-reminder>"
                        + " blocks, not listed here — no need to call task_output unless you need"
                        + " the full payload.)\n");
        return sb.toString();
    }
}
