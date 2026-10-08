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
package io.agentscope.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RunControl;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.LegacyHookDispatcher;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.memory.AgentStateMemoryView;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConcurrentSessionModificationException;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.LegacyStateLoader;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.core.util.MessageUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/**
 * ReAct (Reasoning and Acting) Agent implementation.
 *
 * <p>ReAct is an agent design pattern that combines reasoning (thinking and planning) with acting
 * (tool execution) in an iterative loop. The agent alternates between these two phases until it
 * either completes the task or reaches the maximum iteration limit.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Reactive Streaming:</b> Uses Project Reactor for non-blocking execution
 *   <li><b>Hook System:</b> Extensible hooks for monitoring and intercepting agent execution
 *   <li><b>HITL Support:</b> Human-in-the-loop via stopAgent() in PostReasoningEvent/PostActingEvent
 *   <li><b>Structured Output:</b> per-call {@code generate_response} tool provides type-safe output
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
 *
 * // Create a model (requires dependency: agentscope-extensions-model-dashscope)
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen-plus")
 *     .build();
 *
 * // Create a toolkit with tools
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(new MyToolClass());
 *
 * // Build the agent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .sysPrompt("You are a helpful assistant.")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .maxIters(10)
 *     .build();
 *
 * // Use the agent
 * Msg response = agent.call(Msg.builder()
 *     .name("user")
 *     .role(MsgRole.USER)
 *     .content(TextBlock.builder().text("What's the weather?").build())
 *     .build()).block();
 * }</pre>
 *
 * <p><b>Concurrency:</b> Calls sharing a {@code (userId, sessionId)} slot are serialized;
 * distinct slots can execute concurrently. Runtime contexts are passed through each call's
 * execution scope, middleware parameters, and tool parameters, never through a shared current
 * context accessor. Custom tools, hooks, and middlewares must also be safe for concurrent use.
 */
/**
 * ReAct（推理与行动）智能体实现 —— 本框架最核心的智能体类。
 *
 * <p>ReAct 模式把"推理"（思考与规划）和"行动"（工具执行）放进一个迭代循环：
 * 智能体在两个阶段之间交替，直到完成任务或达到最大迭代次数（maxIters）。
 *
 * <p><b>核心特性：</b>
 * <ul>
 *   <li><b>响应式流：</b>基于 Project Reactor 实现非阻塞执行，call() 返回 Mono，
 *       streamEvents() 返回 Flux；</li>
 *   <li><b>钩子系统：</b>通过 Pre/Post Reasoning/Acting/Summary 钩子监控和介入执行过程；</li>
 *   <li><b>人在回路（HITL）：</b>可在 PostReasoningEvent/PostActingEvent 中调用
 *       stopAgent() 暂停，权限引擎 ASKING 时也会暂停等待用户确认；</li>
 *   <li><b>结构化输出：</b>优先用模型原生 response_format，失败回退到按调用合成的
 *       {@code generate_response} 工具，提供类型安全的输出。</li>
 * </ul>
 *
 * <p><b>主流程调用链：</b>
 * {@code call(msgs) → buildAgentStream → runLifecycle → doCall → doCallInner
 * → coreAgent → executeIteration → reasoning → acting → executeIteration(iter+1)…}
 *
 * <p><b>槽位（slot）模型：</b>状态按 {@code (userId, sessionId)} 组合键分槽缓存
 * （stateCache / permissionEngineCache）。配置了 {@link AgentStateStore} 时，
 * 每次调用都从存储重新加载状态，保证分布式部署下不会读到过期的本地缓存。
 *
 * <p><b>线程安全：</b>{@code ReActAgent} <em>不是</em>线程安全的。单个实例同一时刻
 * 只能处理一个 {@code call()}，并发调用会抛出 {@link IllegalStateException}。
 * Web 服务等并发场景应通过工厂方法为每个请求创建独立实例。
 * {@link io.agentscope.core.model.Model}、{@link io.agentscope.core.tool.Toolkit}
 * （作为模板——{@code build()} 时会深拷贝）和 {@link io.agentscope.core.state.AgentStateStore}
 * 都可以安全地在多个实例间共享。
 */
@SuppressWarnings("deprecation")
public class ReActAgent extends AgentBase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final GracefulShutdownManager shutdownManager =
            GracefulShutdownManager.getInstance();

    /** Tool name used for the per-call structured-output {@code generate_response} tool. */
    /** 按调用合成的结构化输出工具 {@code generate_response} 的固定工具名。 */
    public static final String STRUCTURED_OUTPUT_TOOL_NAME = "generate_response";

    /**
     * @deprecated Permission HITL no longer uses a Reactor Sink. Confirm results are now
     *     delivered via a second {@code agent.call(msgs)} carrying a {@link ConfirmResult}
     *     payload — see {@code applyConfirmResults}. This constant is retained as a
     *     compile-time marker and will be removed in a future release.
     */
    /**
     * @deprecated 权限 HITL 已不再使用 Reactor Sink。确认结果现在通过携带
     *     {@link ConfirmResult} 负载的第二次 {@code agent.call(msgs)} 传入
     *     （见 {@code applyConfirmResults}）。此常量仅作为编译期标记保留，
     *     将在未来版本移除。
     */
    @Deprecated
    public static final String CONFIRM_SINK_KEY = "io.agentscope.core.ReActAgent.confirmSink";

    // ==================== Core Dependencies ====================
    // ==================== 核心依赖（构造后不可变） ====================

    /** 系统提示词（构建期配置的原始值，实际下发前会经过中间件加工）。 */
    private final String sysPrompt;

    /** 主模型实例，可安全跨实例共享。 */
    private final Model model;

    /** ReAct 循环最大迭代次数，超出后进入 summarizing 总结阶段。 */
    private final int maxIters;

    /** 模型调用的执行配置（重试、超时等）。 */
    private final ExecutionConfig modelExecutionConfig;

    /** 工具调用的执行配置（重试、超时等）。 */
    private final ExecutionConfig toolExecutionConfig;

    /** 构建期配置的默认生成选项（温度、response_format 等），每次推理时与动态选项合并。 */
    private final GenerateOptions generateOptions;

    /**
     * Agent-owned toolkit (a deep copy made at {@code build()} time, isolated per agent instance).
     * Shared across this agent's concurrent calls; per-call structured-output tools are NOT
     * registered here — they live on the per-call {@link CallExecution} scope.
     */
    /**
     * 智能体自有的工具集（{@code build()} 时深拷贝而来，实例间相互隔离）。
     * 在本智能体的多次调用之间共享；但"按调用"的结构化输出工具<em>不会</em>
     * 注册到这里——它们挂在按调用的 {@link CallExecution} 作用域上。
     */
    private final Toolkit toolkit;

    /** Default active groups captured after builder-time toolkit configuration is complete. */
    private final List<String> initialActiveToolGroups;

    /** 工具执行上下文（传递给工具运行的附加环境信息）。 */
    private final ToolExecutionContext toolExecutionContext;

    /** 中间件链（不可变列表，GracefulShutdownMiddleware 固定排在最前）。 */
    private final List<MiddlewareBase> middlewares;

    /**
     * Per-extension-point participants, grouped once at construction from {@link #middlewares}
     * (stable filter, onion order preserved). Immutable and shared across concurrent calls;
     * later {@link MiddlewareBase#activePoints()} changes have no effect.
     */
    private final Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> groupedMiddlewares;

    /** 是否启用"悬空工具调用恢复"：恢复时自动为缺少结果的工具调用补合成错误结果。 */
    private final boolean enablePendingToolRecovery;

    // ==================== Persistence ====================
    // ==================== 持久化 ====================

    /** 状态存储；为 null 时状态仅保存在内存槽位缓存中。 */
    private final AgentStateStore stateStore;

    /**
     * Policy applied when an optimistic-concurrency save of {@code agent_state} conflicts.
     * Defaults to {@link ConflictPolicy#OVERWRITE} (legacy last-writer-wins).
     */
    private final ConflictPolicy conflictPolicy;

    /**
     * Builder-time fallback {@code sessionId}, used only when a call does not supply a
     * {@code sessionId} via its {@link RuntimeContext}. Each call still picks its own active slot
     * (see {@link #activateSlotForContext(RuntimeContext)}); this is only the fallback when RC
     * carries no per-call session identity.
     */
    /**
     * 构建期的兜底 {@code sessionId}，仅当调用未通过 {@link RuntimeContext} 提供
     * {@code sessionId} 时使用。每次调用仍会选定自己的活动槽位
     * （见 {@link #activateSlotForContext(RuntimeContext)}）；
     * 这里只是 RC 中缺少按调用会话身份时的兜底值。
     */
    private final String defaultSessionId;

    /**
     * Builder-time permission template, applied to every freshly-created slot. Nullable.
     */
    /** 构建期的权限上下文模板，应用于每个新创建的槽位。可为 null。 */
    private final PermissionContextState initialPermissionContext;

    // ==================== 2.0 Core Fields ====================
    // ==================== 2.0 核心运行时字段 ====================

    /** Cache of state per {@code (userId, sessionId)} slot key. */
    /** 按 {@code (userId, sessionId)} 槽位键缓存的智能体状态。 */
    private final ConcurrentHashMap<String, AgentState> stateCache = new ConcurrentHashMap<>();

    /**
     * Optimistic-concurrency version observed for each slot (parallel to {@link #stateCache}).
     * Updated on load and on successful CAS save. Absent entries mean {@link
     * AgentStateStore#UNVERSIONED}.
     */
    private final ConcurrentHashMap<String, Long> slotVersions = new ConcurrentHashMap<>();

    /** Count of CAS conflicts observed during agent_state saves (metric / diagnostics). */
    private final AtomicLong stateConflictCount = new AtomicLong();

    /**
     * Per-slot permission engine cache: runtime-added ASK rules accumulate within the owning
     * slot rather than leaking across users / sessions.
     */
    /**
     * 按槽位缓存的权限引擎：运行时新增的 ASK 规则只累积在所属槽位内，
     * 不会泄漏到其他用户/会话。
     */
    private final ConcurrentHashMap<String, PermissionEngine> permissionEngineCache =
            new ConcurrentHashMap<>();

    /** 模型配置（重试次数、回退模型），供观测/导出使用。 */
    private final ModelConfig modelConfig;

    /** ReAct 配置（maxIters、拒绝即停），供观测/导出使用。 */
    private final ReactConfig reactConfig;

    /**
     * Reactor Context key carrying the {@code streamEvents} event sink into the underlying
     * {@code call()} subscription. The sink is read in {@link #doCall(List)} and bound to the
     * freshly-built {@link CallExecution#eventSink} for that subscription, so concurrent
     * {@code streamEvents} invocations on one agent each carry their own sink (no shared instance
     * field, no race).
     */
    /**
     * Reactor Context 键：把 {@code streamEvents} 的事件 sink 传递进底层
     * {@code call()} 订阅。sink 在 {@link #doCall(List)} 中被读取并绑定到该订阅
     * 新建的 {@link CallExecution#eventSink} 上，因此同一智能体上并发的
     * {@code streamEvents} 各自携带独立的 sink（无共享实例字段，无竞态）。
     */
    private static final String EVENT_SINK_KEY = "io.agentscope.core.ReActAgent.eventSink";

    /** Synthetic reminder injected when looping back to reasoning for an empty final response. */
    private static final String EMPTY_RESPONSE_REMINDER_TEXT =
            "<system-reminder>Your previous reply had empty content - the full answer was written"
                    + " to the reasoning channel only. Reply again and write the final answer into"
                    + " the content channel.</system-reminder>";

    /** 1.x 遗留钩子分发器：把新式钩子事件桥接到旧式钩子接口。 */
    @SuppressWarnings("deprecation")
    private final LegacyHookDispatcher hookDispatcher;

    // ==================== Constructor ====================
    // ==================== 构造器 ====================

    /**
     * 私有构造器：只能经由 {@link Builder#build()} 创建。
     *
     * @param builder      构建器（收集全部配置）
     * @param agentToolkit 智能体专用工具集（build() 中已深拷贝并完成 rebind）
     */
    private ReActAgent(Builder builder, Toolkit agentToolkit) {
        super(builder.name, builder.description, new ArrayList<>(builder.hooks));

        this.toolkit = agentToolkit != null ? agentToolkit : new Toolkit();
        this.initialActiveToolGroups = List.copyOf(this.toolkit.getActiveGroups());
        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.generateOptions = builder.generateOptions;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.enablePendingToolRecovery = builder.enablePendingToolRecovery;
        List<MiddlewareBase> mws = new ArrayList<>();
        // 优雅停机中间件固定插在最前面，保证所有用户中间件都在其保护范围内
        mws.add(new GracefulShutdownMiddleware(shutdownManager));
        mws.addAll(builder.middlewares);
        this.middlewares = List.copyOf(mws);
        this.groupedMiddlewares = groupMiddlewares(this.middlewares);

        this.stateStore = builder.stateStore;
        this.conflictPolicy =
                builder.conflictPolicy != null ? builder.conflictPolicy : ConflictPolicy.OVERWRITE;
        // 兜底会话 ID：未显式配置时用智能体名，再兜底为 "ReActAgent"
        this.defaultSessionId =
                builder.defaultSessionId != null && !builder.defaultSessionId.isBlank()
                        ? builder.defaultSessionId
                        : (builder.name != null ? builder.name : "ReActAgent");
        this.initialPermissionContext = builder.permissionContext;

        this.modelConfig = assembleModelConfig(builder);
        this.reactConfig = assembleReactConfig(builder);
        this.hookDispatcher = new LegacyHookDispatcher(this);

        if (this.stateStore != null) {
            shutdownManager.bindStateSaver(
                    this,
                    // The saver receives the precise per-(userId, sessionId) AgentState bound to
                    // the interrupted request, so persist that session directly rather than the
                    // instance "last-active" CallExecution (which is wrong under concurrency).
                    // On CAS conflict the session was taken over elsewhere — log and skip
                    // overwrite.
                    // saver 收到的是与被中断请求精确绑定的按 (userId, sessionId) 的
                    // AgentState，因此直接持久化该会话，而不是实例级"最近活动"的
                    // CallExecution（并发场景下后者是错误的）。
                    agentState -> {
                        String uid = agentState.getUserId();
                        String sid = agentState.getSessionId();
                        String slot = slotKey(uid, sid);
                        long expected =
                                slotVersions.getOrDefault(slot, AgentStateStore.UNVERSIONED);
                        long newVersion =
                                stateStore.saveIfVersion(
                                        uid, sid, "agent_state", agentState, expected);
                        if (newVersion == AgentStateStore.UNVERSIONED
                                && stateStore.supportsVersioning()
                                && expected != AgentStateStore.UNVERSIONED) {
                            stateConflictCount.incrementAndGet();
                            log.warn(
                                    "Shutdown state save skipped due to concurrent modification"
                                            + " (userId={}, sessionId={}, expectedVersion={})",
                                    uid,
                                    sid,
                                    expected);
                        } else if (newVersion != AgentStateStore.UNVERSIONED) {
                            slotVersions.put(slot, newVersion);
                        }
                    });
        }
    }

    /**
     * Internal slot identifier — {@code (userId or "__anon__") + "/" + sessionId}.
     * Not part of the public API.
     */
    /**
     * 内部槽位标识符 —— {@code (userId 或 "__anon__") + "/" + sessionId}。
     * 不属于公共 API。userId 为空时用 "__anon__" 占位。
     */
    private static String slotKey(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return (userId == null || userId.isBlank() ? "__anon__" : userId) + "/" + sessionId;
    }

    /** Reverse of {@link #slotKey}: the parsed {@code (userId, sessionId)} pair. */
    /** {@link #slotKey} 的逆操作：从槽位键解析出 {@code (userId, sessionId)} 对。 */
    private record SlotRef(String userId, String sessionId) {
        static SlotRef parse(String slotKey) {
            int slash = slotKey.lastIndexOf('/');
            String u = slotKey.substring(0, slash);
            String s = slotKey.substring(slash + 1);
            return new SlotRef("__anon__".equals(u) ? null : u, s);
        }
    }

    /**
     * Initial agent-state load for a specific {@code (userId, sessionId)} slot. Tries (in order):
     * the configured {@link AgentStateStore} for an {@code agent_state} entry, the v1 legacy
     * session keys ({@code memory_messages} + {@code toolkit_activeGroups}) via
     * {@link LegacyStateLoader}, and finally a fresh state if neither yields anything.
     *
     * @return state paired with the store version ({@code 0} when absent on a versioning backend,
     *     {@link AgentStateStore#UNVERSIONED} when the backend does not version)
     */
    /**
     * 按 {@code (userId, sessionId)} 槽位首次加载智能体状态。依次尝试（三级回退）：
     * <ol>
     *   <li>从配置的 {@link AgentStateStore} 读取 {@code agent_state} 条目；</li>
     *   <li>通过 {@link LegacyStateLoader} 读取 v1 遗留会话键
     *       （{@code memory_messages} + {@code toolkit_activeGroups}）；</li>
     *   <li>两者都没有则创建全新状态。</li>
     * </ol>
     */
    private static VersionedState<AgentState> loadOrCreateAgentStateForSlot(
            AgentStateStore stateStore,
            String userId,
            String sessionId,
            PermissionContextState permCtx,
            String agentId,
            List<String> initialActiveToolGroups) {
        AgentState fresh = freshState(permCtx, agentId, userId, sessionId, initialActiveToolGroups);
        if (stateStore == null) {
            return new VersionedState<>(fresh, AgentStateStore.UNVERSIONED);
        }
        VersionedState<AgentState> versioned =
                stateStore.getVersioned(userId, sessionId, "agent_state", AgentState.class);
        if (versioned.isPresent()) {
            return versioned;
        }
        LegacyStateLoader.LegacyLoadResult legacy =
                LegacyStateLoader.loadFromLegacySessionWithPresence(stateStore, userId, sessionId);
        if (legacy.found()) {
            // Legacy keys have no version; treat as create-if-absent baseline.
            long version = stateStore.supportsVersioning() ? 0L : AgentStateStore.UNVERSIONED;
            return new VersionedState<>(legacy.state(), version);
        }
        long version = stateStore.supportsVersioning() ? 0L : AgentStateStore.UNVERSIONED;
        return new VersionedState<>(fresh, version);
    }

    /** 创建全新（无历史）的智能体状态：会话 ID 缺省回退到 agentId，权限模板可选。 */
    private static AgentState freshState(
            PermissionContextState permCtx,
            String agentId,
            String userId,
            String sessionId,
            List<String> initialActiveToolGroups) {
        AgentState.Builder asb =
                AgentState.builder().sessionId(sessionId != null ? sessionId : agentId);
        if (userId != null) {
            asb.userId(userId);
        }
        if (permCtx != null) {
            asb.permissionContext(permCtx);
        }
        ToolContextState.Builder toolContext = ToolContextState.builder();
        if (initialActiveToolGroups != null) {
            initialActiveToolGroups.forEach(toolContext::addActivatedGroup);
        }
        asb.toolContext(toolContext.build());
        return asb.build();
    }

    /**
     * Persist the current {@link AgentState} via the configured {@link AgentStateStore}, or {@code
     * Mono.empty()} when no AgentStateStore was provided. Uses optimistic concurrency when the
     * store supports versioning.
     */
    /**
     * 通过配置的 {@link AgentStateStore} 持久化当前 {@link AgentState}；
     * 未配置存储时返回 {@code Mono.empty()}。写入前先把工具集的 activeGroups
     * 同步进状态，写入操作调度到 boundedElastic 避免阻塞主流程。
     */
    private Mono<Void> saveStateToSession(CallExecution scope) {
        if (stateStore == null) {
            return Mono.empty();
        }
        SlotRef ref = SlotRef.parse(scope.slotKey);
        AgentState toSave = scope.state;
        return Mono.<Void>fromRunnable(
                        () -> {
                            long newVersion =
                                    persistAgentStateCas(
                                            ref.userId,
                                            ref.sessionId,
                                            scope.slotKey,
                                            toSave,
                                            scope.loadedVersion,
                                            scope.loadedContextSize);
                            if (newVersion != AgentStateStore.UNVERSIONED) {
                                scope.loadedVersion = newVersion;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Persist the safe conversation state accumulated before a failed call and rethrow the original
     * failure. Incomplete model chunks are only held by the per-iteration accumulator, so they are
     * deliberately not added to {@link AgentState} or persisted here.
     *
     * <p>{@link InterruptedException} is intentionally skipped: an interrupt is already handled end
     * to end by {@link #handleInterrupt}, which first reconciles any dangling tool_use produced
     * during reasoning (synthesizing error results for pending tool calls) and only then persists.
     * Saving here would race ahead of that reconciliation and persist an inconsistent intermediate
     * state (a tool_use with no matching tool result), so the interrupt is allowed to propagate
     * untouched.
     */
    private <T> Mono<T> saveStateAfterCallFailure(CallExecution scope, Throwable callFailure) {
        if (ExceptionUtils.containsInterruptedException(callFailure)) {
            return Mono.error(callFailure);
        }
        return saveStateToSession(scope)
                .onErrorResume(
                        saveFailure -> {
                            if (saveFailure != callFailure) {
                                callFailure.addSuppressed(saveFailure);
                            }
                            log.warn(
                                    "Failed to persist agent state after a call failure; preserving"
                                            + " the original failure",
                                    saveFailure);
                            return Mono.empty();
                        })
                .then(Mono.error(callFailure));
    }

    /**
     * CAS-aware persist of {@code agent_state}. Applies {@link #conflictPolicy} on conflict.
     *
     * @return the new store version, or {@link AgentStateStore#UNVERSIONED} when the backend does
     *     not version
     */
    private long persistAgentStateCas(
            String userId,
            String sessionId,
            String slot,
            AgentState toSave,
            long expectedVersion,
            int loadedContextSize) {
        if (!stateStore.supportsVersioning() || expectedVersion == AgentStateStore.UNVERSIONED) {
            if (stateStore.supportsVersioning()) {
                // Unconditional write through the versioning API: the store returns the
                // version assigned to THIS write (JDBC #3220, in-memory likewise), so
                // slotVersions can no longer capture a concurrent writer's version between
                // the write and a separate version read.
                long written =
                        stateStore.saveIfVersion(
                                userId,
                                sessionId,
                                "agent_state",
                                toSave,
                                AgentStateStore.UNVERSIONED);
                if (written != AgentStateStore.UNVERSIONED) {
                    slotVersions.put(slot, written);
                    return written;
                }
                // Defensive only: a versioning backend returning UNVERSIONED for an
                // unconditional write violates the AgentStateStore contract.
                stateStore.save(userId, sessionId, "agent_state", toSave);
            } else {
                stateStore.save(userId, sessionId, "agent_state", toSave);
            }
            return AgentStateStore.UNVERSIONED;
        }

        long newVersion =
                stateStore.saveIfVersion(userId, sessionId, "agent_state", toSave, expectedVersion);
        if (newVersion != AgentStateStore.UNVERSIONED) {
            slotVersions.put(slot, newVersion);
            return newVersion;
        }

        stateConflictCount.incrementAndGet();
        switch (conflictPolicy) {
            case OVERWRITE -> {
                long overwritten =
                        stateStore.saveIfVersion(
                                userId,
                                sessionId,
                                "agent_state",
                                toSave,
                                AgentStateStore.UNVERSIONED);
                if (overwritten != AgentStateStore.UNVERSIONED) {
                    slotVersions.put(slot, overwritten);
                    log.warn(
                            "agent_state CAS conflict — OVERWRITE applied"
                                    + " (userId={}, sessionId={}, expectedVersion={})",
                            userId,
                            sessionId,
                            expectedVersion);
                    return overwritten;
                }
                stateStore.save(userId, sessionId, "agent_state", toSave);
                log.warn(
                        "agent_state CAS conflict — OVERWRITE applied"
                                + " (userId={}, sessionId={}, expectedVersion={})",
                        userId,
                        sessionId,
                        expectedVersion);
                return AgentStateStore.UNVERSIONED;
            }
            case FAIL ->
                    throw new ConcurrentSessionModificationException(
                            userId, sessionId, "agent_state", expectedVersion);
            case APPEND_MERGE -> {
                VersionedState<AgentState> latest =
                        stateStore.getVersioned(userId, sessionId, "agent_state", AgentState.class);
                AgentState baseline =
                        latest.isPresent()
                                ? latest.value()
                                : freshState(
                                        initialPermissionContext,
                                        getAgentId(),
                                        userId,
                                        sessionId,
                                        initialActiveToolGroups);
                List<Msg> local = toSave.getContext();
                if (loadedContextSize >= 0 && loadedContextSize < local.size()) {
                    List<Msg> appended = local.subList(loadedContextSize, local.size());
                    baseline.contextMutable().addAll(appended);
                }
                baseline.setPermissionContext(toSave.getPermissionContext());
                long merged =
                        stateStore.saveIfVersion(
                                userId, sessionId, "agent_state", baseline, latest.version());
                if (merged == AgentStateStore.UNVERSIONED) {
                    throw new ConcurrentSessionModificationException(
                            userId, sessionId, "agent_state", latest.version());
                }
                slotVersions.put(slot, merged);
                stateCache.put(slot, baseline);
                log.info(
                        "agent_state CAS conflict — APPEND_MERGE succeeded"
                                + " (userId={}, sessionId={})",
                        userId,
                        sessionId);
                return merged;
            }
            default ->
                    throw new IllegalStateException("Unknown conflict policy: " + conflictPolicy);
        }
    }

    /**
     * Per-call slot activation. Reads {@code (userId, sessionId)} from the given RuntimeContext
     * (falling back to {@link #defaultSessionId} when absent), and atomically swaps the active
     * {@code #state} + {@code #permissionEngine} to that slot's cached entries.
     *
     * <p>When a {@link AgentStateStore} is configured the state is always reloaded from the store
     * at the beginning of each call so that distributed deployments (where the same sessionId may
     * drift across machines) see the latest persisted state rather than a stale local cache entry.
     * The per-call cost of one store read is negligible compared to the LLM round-trip.
     *
     * <p>Safe to call from {@code beforeAgentExecution} only — caller must hold the
     * {@code AgentBase.acquireExecution} lock.
     */
    /**
     * 按调用激活槽位。从给定 RuntimeContext 读取 {@code (userId, sessionId)}
     * （缺省时回退 {@link #defaultSessionId}），并把活动的状态与权限引擎
     * 原子性地切换到该槽位的缓存条目，最后构造本次调用的 {@link CallExecution} 作用域。
     *
     * <p>配置了 {@link AgentStateStore} 时，每次调用开头都从存储<b>重新加载</b>状态，
     * 使分布式部署（同一 sessionId 可能在不同机器间漂移）总能读到最新持久化状态，
     * 而不是过期的本地缓存。相对 LLM 往返，每次调用多读一次存储的开销可忽略。
     *
     * <p>只允许从 {@code beforeAgentExecution} 调用——调用方必须持有
     * {@code AgentBase.acquireExecution} 锁。
     */
    private CallExecution activateSlotForContext(RuntimeContext ctx) {
        // 解析会话身份：RC 缺失时回退构建期默认会话 ID
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        String uid = ctx != null ? ctx.getUserId() : null;
        String slot = slotKey(uid, sid);
        final String finalUid = uid;
        final String finalSid = sid;
        AgentState loaded;
        long loadedVersion = AgentStateStore.UNVERSIONED;
        if (stateStore != null) {
            // 有存储：每次都从存储重载并刷新本地缓存（分布式一致性优先）
            VersionedState<AgentState> versioned =
                    loadOrCreateAgentStateForSlot(
                            stateStore,
                            finalUid,
                            finalSid,
                            initialPermissionContext,
                            getAgentId(),
                            initialActiveToolGroups);
            loaded = versioned.value();
            loadedVersion = versioned.version();
            stateCache.put(slot, loaded);
            slotVersions.put(slot, loadedVersion);
        } else {
            // 无存储：首次访问时创建并缓存（进程内会话连续性）
            loaded =
                    stateCache.computeIfAbsent(
                            slot,
                            k ->
                                    loadOrCreateAgentStateForSlot(
                                                    null,
                                                    finalUid,
                                                    finalSid,
                                                    initialPermissionContext,
                                                    getAgentId(),
                                                    initialActiveToolGroups)
                                            .value());
        }
        PermissionEngine loadedEngine;
        if (stateStore != null) {
            // 有存储：权限引擎也随状态重建（ASK 规则等随持久化状态恢复）
            loadedEngine = new PermissionEngine(loaded.getPermissionContext());
            permissionEngineCache.put(slot, loadedEngine);
        } else {
            loadedEngine =
                    permissionEngineCache.computeIfAbsent(
                            slot, k -> new PermissionEngine(loaded.getPermissionContext()));
        }
        return new CallExecution(loaded, loadedEngine, slot, loadedVersion);
    }

    // ==================== Config assembly helpers ====================
    // ==================== 配置组装辅助方法 ====================

    /** 组装模型配置：扁平化的重试次数（缺省用默认值）+ 回退模型。 */
    private static ModelConfig assembleModelConfig(Builder b) {
        int retries = b.flatMaxRetries != null ? b.flatMaxRetries : ModelConfig.DEFAULT_MAX_RETRIES;
        return new ModelConfig(retries, b.flatFallbackModel, b.flatFailoverListener);
    }

    /** 组装 ReAct 配置：maxIters + "权限拒绝即停"开关（缺省用默认值）。 */
    private static ReactConfig assembleReactConfig(Builder b) {
        boolean stop =
                b.flatStopOnReject != null
                        ? b.flatStopOnReject
                        : ReactConfig.DEFAULT_STOP_ON_REJECT;
        return new ReactConfig(b.maxIters, stop);
    }

    // ==================== RuntimeContext ====================
    // ==================== 生命周期钩子（由 AgentBase 模板方法驱动） ====================

    /**
     * 调用串行化键：按 {@code (userId, sessionId)} 槽位串行。
     * 同一会话的调用共享缓存的 AgentState / 会话历史，必须逐个执行；
     * 不同会话的调用则可并行。
     */
    @Override
    protected Object callSerializationKey(RuntimeContext rc) {
        // Serialize calls per (userId, sessionId) slot: same-session calls share cached AgentState
        // /
        // conversation history, so they must run one-at-a-time; distinct sessions run in parallel.
        // 按 (userId, sessionId) 槽位串行化调用：同会话调用共享缓存的 AgentState /
        // 会话历史，必须逐个执行；不同会话可并行。
        String sid = rc != null ? rc.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        String uid = rc != null ? rc.getUserId() : null;
        return slotKey(uid, sid);
    }

    /**
     * 调用前置钩子：激活槽位、把按调用的 AgentState 挂到 RuntimeContext、
     * 绑定钩子上下文、重置本会话残留的中断信号。
     * 返回的 CallExecution 是本次调用的权威执行作用域（随 Reactor Context 传递）。
     */
    @Override
    protected Object beforeAgentExecution(List<Msg> msgs, RuntimeContext rc, RunControl control) {
        RuntimeContext ctx = rc;
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }
        // Per-call: resolve the (userId, sessionId) slot carried by the RuntimeContext (falling
        // back to the builder-time default when absent) and build a fresh per-call scope bound to
        // that slot's cached state / permissionEngine. The returned reference is the authoritative
        // per-call scope (carried on the Reactor Context); the instance field is only a
        // side-channel default for out-of-call accessors.
        // 按调用：解析 RuntimeContext 携带的 (userId, sessionId) 槽位（缺失时回退构建期
        // 默认值），并构造绑定该槽位缓存状态/权限引擎的全新按调用作用域。
        // 返回的引用是权威的按调用作用域（挂在 Reactor Context 上传递）；
        // 实例字段只是调用外访问器的旁路默认值。
        CallExecution scope = activateSlotForContext(ctx);
        // Expose the call-scoped AgentState on the RuntimeContext so middlewares / tools resolve
        // the active session's state via rc.getAgentState() (call-scoped, concurrency-safe)
        // rather than agent.getAgentState() (not call-scoped under concurrency).
        // 把按调用的 AgentState 暴露在 RuntimeContext 上，让中间件/工具通过
        // rc.getAgentState()（按调用作用域、并发安全）解析活动会话的状态，
        // 而不是用 agent.getAgentState()（并发下不按调用作用域）。
        ctx.setAgentState(scope.state);
        // State is ready: invoke the onAgentStateReady extension point while this call's input can
        // still be adjusted before it enters the pipeline (pre-call hooks, memory, reasoning).
        onAgentStateReady(ctx, scope.state, msgs);
        // Seed per-call state onto the active execution scope. The system message is initialised
        // by consumeSystemMsgAfterPreCall; the event sink (if any) is bound in doCall() from the
        // per-subscription Reactor Context carried by streamEvents.
        // 在执行作用域上初始化按调用状态。系统消息由 consumeSystemMsgAfterPreCall 初始化；
        // 事件 sink（如有）在 doCall() 中从 streamEvents 携带的按订阅 Reactor Context 绑定。
        scope.rc = ctx;
        // Per-call tool request config (immutable tool difference, external tools + merge mode).
        // The shared toolkit field is never copied and never mutated; the per-call difference is
        // carried on the scope's toolRequestConfig and composed with the shared toolkit on demand.
        ToolRequestConfig requestConfig = ctx.getToolRequestConfig();
        scope.toolRequestConfig = requestConfig != null ? requestConfig : ToolRequestConfig.NONE;
        scope.activeToolkit = this.toolkit;
        scope.systemMsg = null;
        scope.interruption = control.interruption();
        return scope;
    }

    /**
     * Invokes {@link MiddlewareBase#onAgentStateReady} for every {@code ON_AGENT_STATE_READY}
     * participant ({@link #middlewaresAt}) in list order (= {@code order()} descending). No
     * isolation: an exception propagates to the caller unchanged and the remaining middlewares
     * are not invoked. {@code msgs} is the per-subscription private mutable copy, so in-place
     * adjustments apply to the rest of the call only.
     */
    private void onAgentStateReady(RuntimeContext ctx, AgentState state, List<Msg> msgs) {
        for (MiddlewareBase mw :
                middlewaresAt(MiddlewareBase.ExtensionPoint.ON_AGENT_STATE_READY)) {
            mw.onAgentStateReady(this, ctx, state, msgs);
        }
    }

    /**
     * 生成初始系统消息：原始系统提示词经过 onSystemPrompt 中间件链加工后
     * 包装为 SystemMessage；加工结果为空则返回 null（本次调用不带系统消息）。
     */
    @Override
    protected Mono<Msg> seedSystemMsg(Object callExectution) {
        RuntimeContext rc = callExectution instanceof CallExecution ce ? ce.rc : null;
        String base = sysPrompt != null ? sysPrompt.trim() : "";
        return applySystemPromptMiddlewares(base, rc)
                .filter(prompt -> !prompt.isEmpty())
                .map(
                        prompt ->
                                SystemMessage.builder()
                                        .name("system")
                                        .content(TextBlock.builder().text(prompt).build())
                                        .build());
    }

    /** 从调用作用域取出按调用的 AgentState；非 CallExecution 时回退到实例级状态。 */
    @Override
    protected AgentState stateForCall(Object callScope) {
        return callScope instanceof CallExecution ce ? ce.state : getAgentState();
    }

    /** Groups middlewares per extension point; {@code null} declarations count as full set. */
    private static Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> groupMiddlewares(
            List<MiddlewareBase> middlewares) {
        Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> grouped =
                new EnumMap<>(MiddlewareBase.ExtensionPoint.class);
        for (MiddlewareBase mw : middlewares) {
            Set<MiddlewareBase.ExtensionPoint> active =
                    Objects.requireNonNullElse(
                            mw.activePoints(), EnumSet.allOf(MiddlewareBase.ExtensionPoint.class));
            for (MiddlewareBase.ExtensionPoint point : MiddlewareBase.ExtensionPoint.values()) {
                if (active.contains(point)) {
                    grouped.computeIfAbsent(point, p -> new ArrayList<>()).add(mw);
                }
            }
        }
        grouped.replaceAll((point, participants) -> List.copyOf(participants));
        return Collections.unmodifiableMap(grouped);
    }

    /**
     * Returns the middlewares active at the given extension point, in onion-chain order.
     *
     * <p>The list is an immutable construction-time snapshot and is empty when no middleware
     * participates at this point.
     *
     * @param point the extension point
     * @return immutable participant list, never {@code null}
     */
    public List<MiddlewareBase> middlewaresAt(MiddlewareBase.ExtensionPoint point) {
        return groupedMiddlewares.getOrDefault(point, List.of());
    }

    /** 依次应用所有中间件的 onSystemPrompt 加工系统提示词。 */
    private Mono<String> applySystemPromptMiddlewares(String prompt, RuntimeContext ctx) {
        List<MiddlewareBase> participants =
                middlewaresAt(MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT);
        if (participants.isEmpty()) {
            return Mono.just(prompt);
        }
        // 把中间件串成 flatMap 链依次加工提示词
        Mono<String> result = Mono.just(prompt);
        for (MiddlewareBase mw : participants) {
            result = result.flatMap(p -> mw.onSystemPrompt(this, ctx, p));
        }
        return result;
    }

    /** PreCall 之后消费系统消息：挂到执行作用域，并把工具集状态同步进 AgentState。 */
    @Override
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg, Object callScope) {
        CallExecution ce = (CallExecution) callScope;
        ce.systemMsg = systemMsg;
    }

    /**
     * 合并构建期的 {@link #toolExecutionContext} 与调用传入的 RuntimeContext：
     * 两者都有工具执行上下文时做合并，否则原样返回（或包装一个仅含构建期上下文的 RC）。
     */
    private RuntimeContext buildMergedRuntimeContext(RuntimeContext run) {
        if (run == null) {
            if (toolExecutionContext != null) {
                return RuntimeContext.builder().toolExecutionContext(toolExecutionContext).build();
            }
            return RuntimeContext.empty();
        }
        if (toolExecutionContext != null) {
            return RuntimeContext.builder(run)
                    .toolExecutionContext(
                            ToolExecutionContext.merge(
                                    run.asToolExecutionContext(), toolExecutionContext))
                    .build();
        }
        return run;
    }

    /**
     * Calls the agent with a per-call {@link RuntimeContext} (metadata for hooks and tools, not
     * persisted).
     */
    /**
     * 携带按调用 {@link RuntimeContext} 调用智能体
     * （为钩子和工具提供元数据，不会被持久化）。
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext context) {
        return callInternal(msgs, context, this::doCall);
    }

    /** 带结构化输出（Java 类描述结构）+ 按调用 RuntimeContext 的调用重载。 */
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass, RuntimeContext context) {
        return callInternal(msgs, context, m -> doCall(m, structuredOutputClass));
    }

    /** 带结构化输出（JSON Schema 描述结构）+ 按调用 RuntimeContext 的调用重载。 */
    public Mono<Msg> call(List<Msg> msgs, JsonNode outputSchema, RuntimeContext context) {
        return callInternal(msgs, context, m -> doCall(m, outputSchema));
    }

    /**
     * Calls the agent with a plain text input and per-call {@link RuntimeContext}.
     *
     * @param text    input text (wrapped into a {@link UserMessage})
     * @param context per-call runtime context
     * @return response message
     */
    /**
     * 纯文本输入 + 按调用 {@link RuntimeContext} 的便捷调用。
     *
     * @param text    输入文本（包装为 {@link UserMessage}）
     * @param context 按调用运行时上下文
     * @return 响应消息
     */
    public Mono<Msg> call(String text, RuntimeContext context) {
        return call(List.of(new UserMessage(text)), context);
    }

    /**
     * Calls the agent with a plain text input, structured output class, and per-call
     * {@link RuntimeContext}.
     *
     * @param text                 input text (wrapped into a {@link UserMessage})
     * @param structuredOutputClass class defining the structure
     * @param context              per-call runtime context
     * @return response message with structured data in metadata
     */
    /**
     * 纯文本输入 + 结构化输出类 + 按调用 {@link RuntimeContext} 的便捷调用。
     *
     * @param text                  输入文本（包装为 {@link UserMessage}）
     * @param structuredOutputClass 定义输出结构的类
     * @param context               按调用运行时上下文
     * @return 响应消息（结构化数据放在 metadata 中）
     */
    public Mono<Msg> call(String text, Class<?> structuredOutputClass, RuntimeContext context) {
        return call(List.of(new UserMessage(text)), structuredOutputClass, context);
    }

    /**
     * Attaches the caller-supplied {@link RuntimeContext} to the Reactor Context of the given
     * publisher under {@link AgentBase#RUNTIME_CONTEXT_KEY}, so the call lifecycle reads it
     * per-subscription (concurrency-safe) rather than from a shared instance field.
     */
    /**
     * 把调用方提供的 {@link RuntimeContext} 以 {@link AgentBase#RUNTIME_CONTEXT_KEY}
     * 挂到给定 publisher 的 Reactor Context 上，使调用生命周期按订阅读取它
     * （并发安全），而不是从共享的实例字段读取。
     */
    private Mono<Msg> withRuntimeContext(Mono<Msg> mono, RuntimeContext context) {
        return context == null ? mono : mono.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    /** {@link #withRuntimeContext(Mono, RuntimeContext)} 的 Flux 版本。 */
    private Flux<Event> withRuntimeContext(Flux<Event> flux, RuntimeContext context) {
        return context == null ? flux : flux.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    // ==================== Interrupt (per-session) ====================
    // ==================== 中断（按会话定位） ====================

    /**
     * @deprecated Use {@link #interrupt(String, String)} with explicit userId/sessionId.
     */
    /** @deprecated 请改用带显式 userId/sessionId 的 {@link #interrupt(String, String)}。 */
    @Deprecated
    @Override
    public void interrupt() {
        interrupt(null, defaultSessionId);
    }

    /** @deprecated Use {@link #interrupt(String, String, Msg)} with explicit userId/sessionId. */
    /** @deprecated 请改用带显式 userId/sessionId 的 {@link #interrupt(String, String, Msg)}。 */
    @Deprecated
    @Override
    public void interrupt(Msg msg) {
        interrupt(null, defaultSessionId, msg);
    }

    /** @deprecated Use {@link #interrupt(String, String)} with explicit userId/sessionId. */
    /** @deprecated 请改用带显式 userId/sessionId 的 {@link #interrupt(String, String)}。 */
    @Deprecated
    @Override
    public void interrupt(InterruptSource source) {
        interruptRunning(slotKey(null, defaultSessionId), source, null);
    }

    /**
     * Interrupts the in-flight call identified by the given {@link RuntimeContext}.
     * Uses {@code ctx.getUserId()} and {@code ctx.getSessionId()} to locate the session.
     *
     * @param ctx the runtime context identifying the session to interrupt
     */
    /**
     * 中断由给定 {@link RuntimeContext} 标识的进行中调用。
     * 使用 {@code ctx.getUserId()} 和 {@code ctx.getSessionId()} 定位会话。
     *
     * @param ctx 标识待中断会话的运行时上下文
     */
    public void interrupt(RuntimeContext ctx) {
        interrupt(ctx, null);
    }

    /**
     * Interrupts the in-flight call identified by the given {@link RuntimeContext} with an
     * associated user message.
     *
     * @param ctx the runtime context identifying the session to interrupt
     * @param msg optional user message to attach to the interrupt signal
     */
    /**
     * 中断由给定 {@link RuntimeContext} 标识的进行中调用，并附带一条用户消息。
     *
     * @param ctx 标识待中断会话的运行时上下文
     * @param msg 可选的、附在中断信号上的用户消息（USER 源中断时作为后续恢复消息）
     */
    public void interrupt(RuntimeContext ctx, Msg msg) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        interruptRunning(slotKey(uid, sid), InterruptSource.USER, msg);
    }

    /**
     * Interrupts the in-flight call for a specific {@code (userId, sessionId)} session.
     *
     * @param userId the user id ({@code null} = anonymous / single-tenant)
     * @param sessionId the session id
     */
    /**
     * 中断指定 {@code (userId, sessionId)} 会话的进行中调用。
     *
     * @param userId    用户 ID（{@code null} = 匿名/单租户）
     * @param sessionId 会话 ID
     */
    public void interrupt(String userId, String sessionId) {
        interrupt(userId, sessionId, null);
    }

    /**
     * Interrupts the in-flight call for a specific {@code (userId, sessionId)} session with an
     * associated user message.
     */
    /** 中断指定 {@code (userId, sessionId)} 会话的进行中调用，并附带一条用户消息。 */
    public void interrupt(String userId, String sessionId, Msg msg) {
        interruptRunning(slotKey(userId, sessionId), InterruptSource.USER, msg);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} (and overloads that
     *     accept {@link RuntimeContext} via {@code call()}-driven lifecycle) for the
     *     fine-grained {@code AgentEvent} stream.
     */
    /**
     * @deprecated 2.0.0 起废弃，将被移除。请改用 {@link #streamEvents(List)}
     *     获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options), context);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    /** @deprecated 2.0.0 起废弃，将被移除。请改用 {@link #streamEvents(List)}。 */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs,
            StreamOptions options,
            Class<?> structuredModel,
            RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options, structuredModel), context);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    /** @deprecated 2.0.0 起废弃，将被移除。请改用 {@link #streamEvents(List)}。 */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options, schema), context);
    }

    // ==================== Shared agent-stream core ====================
    // ==================== call() 与 streamEvents() 共享的事件流核心 ====================

    /**
     * Overrides the base-class hook so that every {@code call()} variant — including structured
     * output and context-bearing overloads — runs through the same {@link #buildAgentStream} core
     * as {@code streamEvents()}.  This guarantees that the {@code onAgent} middleware chain fires
     * on <em>all</em> invocation paths, not only on the streaming path.
     *
     * <p>The result is extracted from the {@link AgentResultEvent} emitted by
     * {@link #buildAgentStream} before {@link AgentEndEvent}.
     */
    /**
     * 重写基类钩子：让所有 {@code call()} 变体——包括结构化输出和携带上下文的
     * 重载——都走与 {@code streamEvents()} 相同的 {@link #buildAgentStream} 核心。
     * 这保证了 {@code onAgent} 中间件链在<em>所有</em>调用路径上都会触发，
     * 而不仅仅是流式路径。
     *
     * <p>最终结果从 {@link #buildAgentStream} 在 {@link AgentEndEvent} 之前发射的
     * {@link AgentResultEvent} 中提取。
     */
    @Override
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        // 只取最后一个 AgentResultEvent 中的结果消息，其余事件丢弃
        return buildAgentStream(msgs, context, doCallFn)
                .filter(e -> e instanceof AgentResultEvent)
                .cast(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .takeLast(1)
                .next();
    }

    /**
     * Single implementation shared by both {@code call()} (via {@link #callInternal}) and
     * {@code streamEvents()}.
     *
     * <p>The stream is bookended by {@link AgentStartEvent} / {@link AgentEndEvent}, wraps the
     * full {@link AgentBase#runLifecycle} (shutdown guard, serialization gate, pre/post hooks,
     * tracing), and emits {@link AgentResultEvent} carrying the final {@link Msg} immediately
     * before {@link AgentEndEvent}.  The {@code onAgent} middleware chain is applied exactly
     * once around this core.
     *
     * @param msgs      input messages
     * @param context   caller-supplied per-call {@link RuntimeContext}, or {@code null}
     * @param doCallFn  the concrete call implementation ({@link #doCall} or a structured-output
     *                  variant) passed straight through to {@link AgentBase#runLifecycle}
     * @return event stream covering the full agent invocation lifecycle
     */
    /**
     * {@code call()}（经由 {@link #callInternal}）与 {@code streamEvents()} 共享的唯一实现。
     *
     * <p>事件流以 {@link AgentStartEvent} / {@link AgentEndEvent} 夹逼，
     * 内部包裹完整的 {@link AgentBase#runLifecycle}（停机守卫、串行化闸门、
     * 前置/后置钩子、追踪），并在 {@link AgentEndEvent} 之前发射携带最终
     * {@link Msg} 的 {@link AgentResultEvent}。{@code onAgent} 中间件链
     * 只围绕该核心应用一次。
     *
     * @param msgs     输入消息
     * @param context  调用方提供的按调用 {@link RuntimeContext}，可为 {@code null}
     * @param doCallFn 具体调用实现（{@link #doCall} 或其结构化输出变体），
     *                 直接透传给 {@link AgentBase#runLifecycle}
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    private Flux<AgentEvent> buildAgentStream(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        // 本次调用的回复 ID（贯穿 AgentStartEvent / AgentEndEvent）
        String replyId = UUID.randomUUID().toString().replace("-", "");
        Function<AgentInput, Flux<AgentEvent>> core =
                input ->
                        Flux.<AgentEvent>create(
                                sink -> {
                                    // 生命周期起点：先发射 AgentStartEvent
                                    sink.next(new AgentStartEvent(null, replyId, getName()));
                                    // 捕获订阅方的 Reactor Context，稍后透传给内部订阅
                                    reactor.util.context.Context subscriberCtx =
                                            reactor.util.context.Context.of(sink.contextView());

                                    // Call runLifecycle directly — NOT call() — to avoid the
                                    // onAgent chain being applied a second time.
                                    // 直接调 runLifecycle 而不是 call()，
                                    // 避免 onAgent 中间件链被应用第二次。
                                    Mono<Msg> lifecycle = runLifecycle(input.msgs(), doCallFn);
                                    if (context != null) {
                                        lifecycle =
                                                lifecycle.contextWrite(
                                                        c -> c.put(RUNTIME_CONTEXT_KEY, context));
                                    }
                                    // Do not install AgentEventEmitter.CONTEXT_KEY when the
                                    // deprecated stream() → SubagentEventBus path is driving
                                    // this invocation. On that path AgentSpawnTool reads
                                    // SubagentEventBus.CONTEXT_KEY to forward child events;
                                    // installing CONTEXT_KEY here would cause execLocalSync to
                                    // take the AgentEvent path instead of the bus path, routing
                                    // child events into this Flux's internal sink where they get
                                    // filtered out by callInternal before reaching the caller.
                                    // 当本次调用由废弃的 stream() → SubagentEventBus 路径驱动时，
                                    // 不要安装 AgentEventEmitter.CONTEXT_KEY。该路径上
                                    // AgentSpawnTool 读取 SubagentEventBus.CONTEXT_KEY 转发子事件；
                                    // 这里若安装 CONTEXT_KEY 会让 execLocalSync 走 AgentEvent 路径
                                    // 而非 bus 路径，把子事件路由进本 Flux 的内部 sink，
                                    // 再被 callInternal 过滤掉，调用方就收不到了。
                                    boolean isSubagentBusPath =
                                            subscriberCtx.hasKey(SubagentEventBus.CONTEXT_KEY);
                                    Disposable lifecycleDisposable =
                                            lifecycle
                                                    // 把事件 sink 经 Reactor Context 传给
                                                    // doCall()，供其绑定到 CallExecution
                                                    .contextWrite(c -> c.put(EVENT_SINK_KEY, sink))
                                                    .contextWrite(
                                                            c ->
                                                                    isSubagentBusPath
                                                                            ? c
                                                                            : c.put(
                                                                                    AgentEventEmitter
                                                                                            .CONTEXT_KEY,
                                                                                    (AgentEventEmitter)
                                                                                            sink
                                                                                                    ::next))
                                                    .doFinally(
                                                            signal -> {
                                                                // 生命周期终点：无论成败都补发
                                                                // AgentEndEvent 并完成流
                                                                sink.next(
                                                                        new AgentEndEvent(replyId));
                                                                sink.complete();
                                                            })
                                                    // 把订阅方的 Context 透传进来
                                                    // （EVENT_SINK_KEY 等键才能被下游读取）
                                                    .contextWrite(subscriberCtx)
                                                    .subscribe(
                                                            finalMsg ->
                                                                    sink.next(
                                                                            new AgentResultEvent(
                                                                                    finalMsg)),
                                                            sink::error);
                                    // 下游取消订阅时同步取消内部生命周期订阅
                                    sink.onCancel(lifecycleDisposable);
                                },
                                FluxSink.OverflowStrategy.BUFFER);
        return MiddlewareChain.build(
                        middlewaresAt(MiddlewareBase.ExtensionPoint.ON_AGENT),
                        this,
                        context,
                        MiddlewareBase::onAgent,
                        core)
                .apply(new AgentInput(msgs == null ? List.of() : msgs));
    }

    /**
     * Prepare a single-use event execution handle without starting the call. Adopts the context's
     * runId ({@code run.runId() == ctx.getRunId()}). A null context is equivalent to {@link
     * RuntimeContext#empty()} for the whole call chain — the reactive context always carries a
     * (possibly empty) context.
     */
    public AgentRun<AgentEvent> prepareRun(List<Msg> msgs, RuntimeContext context) {
        RuntimeContext effective = context != null ? context : RuntimeContext.empty();
        return AgentRun.create(
                getAgentId(), effective.getRunId(), () -> streamEvents(msgs, effective));
    }

    /**
     * Prepare a single-use reply execution handle without starting the call. Adopts the context's
     * runId ({@code run.runId() == ctx.getRunId()}). A null context is equivalent to {@link
     * RuntimeContext#empty()} for the whole call chain — the reactive context always carries a
     * (possibly empty) context.
     */
    public AgentRun<Msg> prepareCall(List<Msg> msgs, RuntimeContext context) {
        RuntimeContext effective = context != null ? context : RuntimeContext.empty();
        return AgentRun.create(getAgentId(), effective.getRunId(), () -> call(msgs, effective));
    }

    // ==================== streamEvents public API ====================
    // ==================== streamEvents 公共 API ====================

    /**
     * Stream fine-grained {@link AgentEvent}s from the full agent lifecycle.
     *
     * <p>Both {@code call()} and {@code streamEvents()} share the same internal
     * {@link #buildAgentStream} core, so the {@code onAgent} middleware chain fires on all paths.
     * The stream includes {@link AgentResultEvent} (carrying the final {@link Msg}) immediately
     * before {@link AgentEndEvent}.
     *
     * @param msgs input messages
     * @return event stream covering the full agent invocation lifecycle
     */
    /**
     * 以细粒度 {@link AgentEvent} 流输出完整的智能体生命周期。
     *
     * <p>{@code call()} 与 {@code streamEvents()} 共享同一个内部
     * {@link #buildAgentStream} 核心，因此 {@code onAgent} 中间件链在所有路径都会触发。
     * 事件流在 {@link AgentEndEvent} 之前包含携带最终 {@link Msg} 的 {@link AgentResultEvent}。
     *
     * @param msgs 输入消息
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, (RuntimeContext) null);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single input message.
     *
     * @param msg input message
     * @return event stream covering the full agent invocation lifecycle
     */
    /** 单条输入消息的细粒度事件流便捷重载。 */
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg));
    }

    /**
     * Stream fine-grained {@link AgentEvent}s with a caller-supplied {@link RuntimeContext}.
     *
     * <p>Delegates directly to {@link #buildAgentStream} — the same core used by {@code call()}.
     * Concurrent invocations do not share any state; each subscription gets its own event sink and
     * lifecycle execution.
     *
     * @param msgs input messages
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    /**
     * 携带调用方 {@link RuntimeContext} 的细粒度事件流。
     *
     * <p>直接委托给 {@link #buildAgentStream}——与 {@code call()} 相同的核心。
     * 并发调用不共享任何状态；每个订阅拥有独立的事件 sink 与生命周期执行。
     *
     * @param msgs    输入消息
     * @param context 要传播进调用的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
        return buildAgentStream(msgs, context, this::doCall);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single input message with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param msg input message
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    /** 单条输入消息 + RuntimeContext 的细粒度事件流便捷重载。 */
    public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext context) {
        return streamEvents(List.of(msg), context);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a plain text input.
     *
     * @param text input text (wrapped into a {@link UserMessage})
     * @return event stream covering the full agent invocation lifecycle
     */
    /** 纯文本输入的细粒度事件流便捷重载（文本包装为 {@link UserMessage}）。 */
    public Flux<AgentEvent> streamEvents(String text) {
        return streamEvents(new UserMessage(text));
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a plain text input with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param text    input text (wrapped into a {@link UserMessage})
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    /** 纯文本输入 + RuntimeContext 的细粒度事件流便捷重载。 */
    public Flux<AgentEvent> streamEvents(String text, RuntimeContext context) {
        return streamEvents(new UserMessage(text), context);
    }

    // ==================== Protected API ====================
    // ==================== 受保护/内部 API ====================

    /**
     * Resolves the per-call {@link CallExecution} from the Reactor Context (carried by the shared
     * {@code call()} lifecycle). Falls back to the instance {@link #exec} when absent (e.g. legacy
     * paths that do not flow through {@code call()}), so concurrent calls each operate on their own
     * scope.
     */
    /**
     * 从 Reactor Context 解析按调用的 {@link CallExecution}（由共享的 {@code call()}
     * 生命周期携带）。缺失时回退到实例级 {@link #exec}（例如不走 {@code call()} 的
     * 遗留路径），保证并发调用各自操作自己的作用域。
     */
    private CallExecution scopeFrom(reactor.util.context.ContextView cv) {
        Object scope = cv.getOrDefault(CALL_SCOPE_KEY, null);
        if (!(scope instanceof CallExecution ce)) {
            throw new IllegalStateException(
                    "No CallExecution in Reactor Context — scopeFrom called outside call"
                            + " lifecycle");
        }
        return ce;
    }

    /**
     * 普通（非结构化）调用的实际执行入口：
     * <ol>
     *   <li>从 Reactor Context 解析本次调用的 CallExecution 作用域；</li>
     *   <li>绑定事件出口——优先 streamEvents 传入的 FluxSink；
     *       父智能体工具转发场景下改用转发式 emitter；两者都没有则尝试转发上下文；</li>
     *   <li>执行 {@code doCallInner}（ReAct 循环），完成后把状态持久化回存储。</li>
     * </ol>
     */
    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // When a forwarding emitter is present, the parent's tool is routing child
                    // events through a source-tagging wrapper. Skip the direct FluxSink so
                    // publishEvent() uses the forwarding emitter instead.
                    // 存在转发式 emitter 时，说明父智能体的工具正通过带来源标记的包装器
                    // 路由子事件。此时跳过直接的 FluxSink，
                    // 让 publishEvent() 改走转发式 emitter。
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }
                    // ReAct 循环结束后把最新状态写回存储，再返回结果消息
                    return scope.doCallInner(msgs)
                            .onErrorResume(error -> saveStateAfterCallFailure(scope, error))
                            .flatMap(result -> saveStateToSession(scope).thenReturn(result))
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    saveStateToSession(scope)
                                                            .then(Mono.<Msg>empty())));
                });
    }

    // ==================== Structured output (per-call) ====================
    // ==================== 结构化输出（按调用） ====================

    /** 结构化输出重载（Java 类定义结构）：委托给 doStructuredCall。 */
    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return doStructuredCall(msgs, structuredOutputClass, null);
    }

    /** 结构化输出重载（JSON Schema 定义结构）：委托给 doStructuredCall。 */
    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return doStructuredCall(msgs, null, outputSchema);
    }

    /**
     * Structured-output call dispatcher. Routes to the native path (model's {@code response_format})
     * when the model supports it, otherwise falls back to the synthetic {@code generate_response}
     * tool approach.
     */
    /**
     * 结构化输出调度器。模型支持原生结构化输出时走原生路径
     * （{@code response_format}）；否则回退到合成 {@code generate_response} 工具方案。
     * 即使走原生路径，运行时出错也会自动回退到合成工具路径。
     */
    private Mono<Msg> doStructuredCall(List<Msg> msgs, Class<?> targetClass, JsonNode schemaDesc) {
        // 参数互斥校验：两者必须且只能提供其一
        if (targetClass == null && schemaDesc == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "Either targetClass or schemaDesc must be provided"));
        }
        if (targetClass != null && schemaDesc != null) {
            return Mono.error(
                    new IllegalArgumentException("Cannot provide both targetClass and schemaDesc"));
        }
        // 统一转换为 JSON Schema（从 Java 类或 JsonNode 生成）
        Map<String, Object> jsonSchema =
                targetClass != null
                        ? JsonSchemaUtils.generateSchemaFromClass(targetClass)
                        : JsonSchemaUtils.generateSchemaFromJsonNode(schemaDesc);
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    List<String> activeGroups = scope.state.getToolContext().getActivatedGroups();
                    // 注册了工具时要求模型同时支持"带工具的原生结构化输出"
                    boolean hasTools =
                            !scope.activeToolkit
                                    .getToolSchemas(activeGroups, scope.toolRequestConfig)
                                    .isEmpty();
                    boolean useNative =
                            hasTools
                                    ? model.supportsNativeStructuredOutputWithTools()
                                    : model.supportsNativeStructuredOutput();
                    if (useNative) {
                        return doNativeStructuredCall(msgs, jsonSchema)
                                .onErrorResume(
                                        e -> {
                                            // 原生路径失败：记录警告后回退到合成工具路径
                                            log.warn(
                                                    "Native structured output failed ({}) — falling"
                                                            + " back to synthetic tool path",
                                                    e.getMessage() != null
                                                            ? e.getMessage()
                                                            : e.getClass().getSimpleName());
                                            return doFallbackStructuredCall(msgs, jsonSchema);
                                        });
                    }
                    return doFallbackStructuredCall(msgs, jsonSchema);
                });
    }

    /**
     * Native structured-output path: passes the schema via {@code response_format} in
     * {@link GenerateOptions}. The model returns structured JSON as text content and the
     * ReAct loop terminates naturally (no synthetic tool needed).
     */
    /**
     * 原生结构化输出路径：通过 {@link GenerateOptions} 的 {@code response_format}
     * 传递 Schema。模型以文本内容返回结构化 JSON，ReAct 循环自然终止
     * （无需合成工具）。出错时由调用方回滚上下文并回退到 fallback 路径。
     */
    private Mono<Msg> doNativeStructuredCall(List<Msg> msgs, Map<String, Object> jsonSchema) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // 事件出口绑定逻辑与 doCall 相同（FluxSink → 转发式 emitter）
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }

                    // 设置原生 response_format（json_schema + strict），
                    // 推理时会合并进 GenerateOptions 下发给模型
                    scope.nativeResponseFormat =
                            ResponseFormat.jsonSchema(
                                    JsonSchema.builder()
                                            .name(STRUCTURED_OUTPUT_TOOL_NAME)
                                            .schema(jsonSchema)
                                            .strict(true)
                                            .build());

                    // 记录调用前上下文长度，失败时用于回滚
                    int contextSizeBefore = scope.state.contextMutable().size();

                    return scope.doCallInner(msgs)
                            .flatMap(
                                    result -> {
                                        // 成功后解析文本中的 JSON 并放入 metadata，再持久化
                                        Msg out = wrapNativeStructuredResult(result);
                                        return saveStateToSession(scope).thenReturn(out);
                                    })
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    saveStateToSession(scope)
                                                            .then(Mono.<Msg>empty())))
                            .doOnError(
                                    e -> {
                                        // 出错回滚：移除本次调用追加的上下文消息，
                                        // 并清空 response_format，
                                        // 让 fallback 路径从干净的上下文重试
                                        List<Msg> ctx = scope.state.contextMutable();
                                        while (ctx.size() > contextSizeBefore) {
                                            ctx.remove(ctx.size() - 1);
                                        }
                                        scope.nativeResponseFormat = null;
                                    });
                });
    }

    /**
     * Fallback structured-output path: injects a {@code generate_response} synthetic tool and
     * an instruction hint. When the model calls the tool, the loop stops naturally via
     * {@code PostActingEvent.stopAgent()}.
     */
    /**
     * 回退结构化输出路径：注入按调用的 {@code generate_response} 合成工具
     * （挂在 CallExecution 作用域而非共享工具集）和指令提示。
     * 模型调用该工具后，循环通过 {@code PostActingEvent.stopAgent()} 自然停止；
     * 结束时收集 usage/thinking、压缩过程消息并提取结构化结果。
     */
    private Mono<Msg> doFallbackStructuredCall(List<Msg> msgs, Map<String, Object> jsonSchema) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // 事件出口绑定逻辑与 doCall 相同
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }

                    // 按调用创建合成工具（不注册进共享 toolkit）
                    scope.soTool = createStructuredOutputTool(jsonSchema);

                    return scope.doCallInner(msgs)
                            .onErrorResume(error -> saveStateAfterCallFailure(scope, error))
                            .flatMap(
                                    result -> {
                                        Msg out = result;
                                        // soCompleted 表示模型已调用 generate_response：
                                        // 收集过程消息上的 usage 与 thinking，
                                        // 压缩掉结构化输出相关的过程消息，
                                        // 提取结构化结果并合并元数据
                                        if (scope.soCompleted && scope.soResultMsg != null) {
                                            ChatUsage aggregatedUsage =
                                                    collectAggregatedUsage(scope.state);
                                            ThinkingBlock aggregatedThinking =
                                                    collectLastThinking(scope.state);
                                            compressStructuredOutputContext(scope.state);
                                            Msg extracted =
                                                    extractStructuredResult(scope.soResultMsg);
                                            if (extracted != null) {
                                                out =
                                                        mergeCollectedMetadata(
                                                                extracted,
                                                                aggregatedUsage,
                                                                aggregatedThinking);
                                            }
                                            // 最终结果消息追加回上下文
                                            scope.state.contextMutable().add(out);
                                        }
                                        return saveStateToSession(scope).thenReturn(out);
                                    })
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    saveStateToSession(scope)
                                                            .then(Mono.<Msg>empty())));
                });
    }

    /**
     * 包装原生路径结果：把文本内容解析为 JSON 对象，
     * 放入 metadata 的 STRUCTURED_OUTPUT 键；解析失败则原样返回。
     */
    private Msg wrapNativeStructuredResult(Msg result) {
        if (result == null) {
            return null;
        }
        String text = result.getTextContent();
        if (text == null || text.isBlank()) {
            return result;
        }
        try {
            Object parsed =
                    io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(text, Object.class);
            Map<String, Object> metadata =
                    new HashMap<>(result.getMetadata() != null ? result.getMetadata() : Map.of());
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, parsed);
            return Msg.builderForRole(result.getRole())
                    .id(result.getId())
                    .name(result.getName())
                    .content(result.getContent())
                    .metadata(metadata)
                    .timestamp(result.getTimestamp())
                    .usage(result.getUsage())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse native structured output as JSON: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Remove structured-output-related messages from the conversation context and append
     * the final response.
     */
    /**
     * 从会话上下文中移除结构化输出相关的过程消息
     * （提醒消息、generate_response 的工具调用与结果消息），避免污染后续会话。
     */
    private void compressStructuredOutputContext(AgentState agentState) {
        List<Msg> contextMutable = agentState.contextMutable();
        List<Msg> original = new ArrayList<>(contextMutable);
        contextMutable.clear();
        for (Msg msg : original) {
            if (!isStructuredOutputRelated(msg)) {
                contextMutable.add(msg);
            }
        }
    }

    /**
     * 判断消息是否属于"结构化输出过程消息"：
     * 带 STRUCTURED_OUTPUT_REMINDER 标记、包含 generate_response 的工具调用块，
     * 或工具结果块全部来自 generate_response。
     */
    private boolean isStructuredOutputRelated(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null
                && Boolean.TRUE.equals(
                        metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER))) {
            return true;
        }
        if (msg.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(tu -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tu.getName()))) {
            return true;
        }
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        return !results.isEmpty()
                && results.stream()
                        .allMatch(tr -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tr.getName()));
    }

    /** 汇总结构化输出过程消息（助手角色）上的 token 用量与耗时，无用量时返回 null。 */
    private ChatUsage collectAggregatedUsage(AgentState agentState) {
        int totalInput = 0;
        int totalOutput = 0;
        int totalCached = 0;
        int totalCacheCreation = 0;
        int totalReasoning = 0;
        int totalToolUsePrompt = 0;
        double totalTime = 0;
        boolean hasUsage = false;
        for (Msg msg : agentState.getContext()) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ChatUsage usage = msg.getChatUsage();
                if (usage != null) {
                    hasUsage = true;
                    totalInput += usage.getInputTokens();
                    totalOutput += usage.getOutputTokens();
                    totalCached += usage.getCachedTokens();
                    totalCacheCreation += usage.getCacheCreationTokens();
                    totalReasoning += usage.getReasoningTokens();
                    totalToolUsePrompt += usage.getToolUsePromptTokens();
                    totalTime += usage.getTime();
                }
            }
        }
        return hasUsage
                ? ChatUsage.builder()
                        .inputTokens(totalInput)
                        .outputTokens(totalOutput)
                        .cachedTokens(totalCached)
                        .cacheCreationTokens(totalCacheCreation)
                        .reasoningTokens(totalReasoning)
                        .toolUsePromptTokens(totalToolUsePrompt)
                        .time(totalTime)
                        .build()
                : null;
    }

    /** 取结构化输出过程消息中最后一个思考块（ThinkingBlock），无则返回 null。 */
    private ThinkingBlock collectLastThinking(AgentState agentState) {
        ThinkingBlock last = null;
        for (Msg msg : agentState.getContext()) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ThinkingBlock tb = msg.getFirstContentBlock(ThinkingBlock.class);
                if (tb != null) {
                    last = tb;
                }
            }
        }
        return last;
    }

    /**
     * Build the per-call {@code generate_response} tool that captures the model's structured
     * response. The tool only stores the raw response payload; schema validation is performed by
     * the tool executor before this is invoked.
     */
    /**
     * 构建按调用的 {@code generate_response} 工具，用于捕获模型的结构化响应。
     * 工具本身只保存原始响应负载；Schema 校验在调用前由工具执行器完成。
     * 参数结构为 {@code {"response": <用户Schema>}}，并把 Schema 内部的
     * $defs/definitions 提升到参数根层级（JSON Schema 引用要求）。
     */
    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {
        return new AgentTool() {
            @Override
            public String getName() {
                return STRUCTURED_OUTPUT_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return "Generate the final structured response. Call this function when"
                        + " you have all the information needed to provide a complete answer.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> params = new HashMap<>();
                params.put("type", "object");

                // Shallow-copy the inner schema so we can safely hoist $defs to the outer params
                // root without mutating the shared `schema` instance.
                Map<String, Object> innerSchema = new HashMap<>(schema);
                Map<String, Object> hoistedDefs = new HashMap<>();
                hoistDefsKey(innerSchema, "$defs", hoistedDefs);
                hoistDefsKey(innerSchema, "definitions", hoistedDefs);

                params.put("properties", Map.of("response", innerSchema));
                params.put("required", List.of("response"));
                if (!hoistedDefs.isEmpty()) {
                    params.put("$defs", hoistedDefs);
                }
                return params;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(
                        () -> {
                            Object responseData = param.getInput().get("response");
                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    contentText = JsonUtils.getJsonCodec().toJson(responseData);
                                } catch (Exception e) {
                                    contentText = responseData.toString();
                                }
                            }
                            log.debug("Structured output generated: {}", contentText);

                            Msg responseMsg =
                                    AssistantMessage.builder()
                                            .name(getName())
                                            .content(TextBlock.builder().text(contentText).build())
                                            .metadata(
                                                    responseData != null
                                                            ? Map.of("response", responseData)
                                                            : Map.of())
                                            .build();

                            Map<String, Object> toolMetadata = new HashMap<>();
                            toolMetadata.put("success", true);
                            toolMetadata.put("response_msg", responseMsg);

                            return ToolResultBlock.of(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Successfully generated response.")
                                                    .build()),
                                    toolMetadata);
                        });
            }
        };
    }

    /** Extract the structured result message from the {@code generate_response} tool result. */
    /** 从 {@code generate_response} 的工具结果消息中提取结构化结果消息。 */
    private Msg extractStructuredResult(Msg hookResultMsg) {
        if (hookResultMsg == null) {
            return null;
        }
        List<ToolResultBlock> toolResults = hookResultMsg.getContentBlocks(ToolResultBlock.class);
        for (ToolResultBlock result : toolResults) {
            if (result.getMetadata() != null
                    && Boolean.TRUE.equals(result.getMetadata().get("success"))
                    && result.getMetadata().containsKey("response_msg")) {
                Object responseMsgObj = result.getMetadata().get("response_msg");
                Msg responseMsg = toMsg(responseMsgObj);
                if (responseMsg != null) {
                    return extractResponseData(responseMsg);
                }
            }
        }
        return hookResultMsg;
    }

    /**
     * Restores a {@link Msg} from a metadata value.
     *
     * <p>After a JSON persistence round-trip (agent session save/load) the typed message
     * stored in {@code ToolResultBlock.metadata["response_msg"]} is restored as a {@code LinkedHashMap},
     * so it is converted back to its typed form here.
     *
     * @param value the raw metadata value
     * @return the typed message, or {@code null} if it cannot be restored
     */
    private static Msg toMsg(Object value) {
        if (value instanceof Msg msg) {
            return msg;
        }
        if (value instanceof Map<?, ?>) {
            try {
                return JsonUtils.getJsonCodec().convertValue(value, Msg.class);
            } catch (RuntimeException e) {
                log.warn("Failed to restore response_msg from tool result metadata", e);
                return null;
            }
        }
        return null;
    }

    /** 把响应消息 metadata 里的 "response" 键规范化为 STRUCTURED_OUTPUT 键。 */
    private Msg extractResponseData(Msg responseMsg) {
        if (responseMsg.getMetadata() != null
                && responseMsg.getMetadata().containsKey("response")) {
            Object responseData = responseMsg.getMetadata().get("response");
            Map<String, Object> metadata = new HashMap<>(responseMsg.getMetadata());
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, responseData);
            metadata.remove("response");
            return Msg.builderForRole(responseMsg.getRole())
                    .name(responseMsg.getName())
                    .content(responseMsg.getContent())
                    .metadata(metadata)
                    .build();
        }
        return responseMsg;
    }

    /** Merge aggregated {@link ChatUsage} / {@link ThinkingBlock} into the final message. */
    /** 把汇总的 {@link ChatUsage} / {@link ThinkingBlock} 合并进最终消息（思考块前置）。 */
    private Msg mergeCollectedMetadata(Msg msg, ChatUsage chatUsage, ThinkingBlock thinking) {
        Map<String, Object> metadata =
                new HashMap<>(msg.getMetadata() != null ? msg.getMetadata() : Map.of());
        if (chatUsage != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        List<ContentBlock> newContent;
        if (thinking != null) {
            newContent = new ArrayList<>();
            newContent.add(thinking);
            if (msg.getContent() != null) {
                newContent.addAll(msg.getContent());
            }
        } else {
            newContent = msg.getContent();
        }

        return Msg.builderForRole(msg.getRole())
                .id(msg.getId())
                .name(msg.getName())
                .content(newContent)
                .metadata(metadata)
                .timestamp(msg.getTimestamp())
                .usage(chatUsage)
                .build();
    }

    /** Hoist {@code $defs}/{@code definitions} from a nested schema up to the params root. */
    /** 把嵌套 Schema 中的 {@code $defs}/{@code definitions} 提升到参数根层级。 */
    @SuppressWarnings("unchecked")
    private static void hoistDefsKey(
            Map<String, Object> innerSchema, String key, Map<String, Object> target) {
        Object raw = innerSchema.remove(key);
        if (raw instanceof Map<?, ?> defs && !defs.isEmpty()) {
            target.putAll((Map<String, Object>) defs);
        }
    }

    /**
     * Per-call execution scope: holds the active {@code (userId, sessionId)} slot's mutable
     * {@link AgentState} + {@link PermissionEngine} + slot key, and hosts the entire ReAct
     * reasoning loop. Non-static inner class so the loop references the enclosing agent's
     * immutable config ({@code model}, {@code toolkit}, {@code middlewares}, …) and lifecycle
     * helpers directly. Built per-call by {@link #activateSlotForContext(RuntimeContext)}.
     */
    /**
     * 按调用的执行作用域：持有活动 {@code (userId, sessionId)} 槽位的可变
     * {@link AgentState} + {@link PermissionEngine} + 槽位键，并承载整个 ReAct
     * 推理循环。非静态内部类，因此循环可以直接引用外层智能体的不可变配置
     * （{@code model}、{@code toolkit}、{@code middlewares} 等）与生命周期辅助方法。
     * 由 {@link #activateSlotForContext(RuntimeContext)} 按调用构建。
     *
     * <p>核心方法导览：
     * {@code doCallInner}（入口路由）→ {@code coreAgent/resumeAgent}
     * → {@code executeIteration} → {@code reasoning}（推理）→ {@code acting}（行动）
     * → 递归下一轮；另有 {@code summarizing}（超限总结）与权限门控 {@code evaluatePermissions}。
     */
    final class CallExecution {
        private static final String PERMISSION_DENIED_BY_USER = "Permission denied by user";
        private static final String PERMISSION_DENIED_BY_RULES = "Permission denied by rules";

        InterruptControl interruption = new InterruptControl();

        /** 本槽位的可变智能体状态（会话上下文、工具上下文、中断控制器等）。 */
        AgentState state;

        /** 本槽位的权限引擎（运行时 ASK 规则只在本槽位内累积）。 */
        PermissionEngine permissionEngine;

        /** 槽位键：(userId, sessionId) 组合。 */
        String slotKey;

        /**
         * Store version observed when this call loaded {@link #state}. Used for CAS on save.
         * {@link AgentStateStore#UNVERSIONED} when the backend does not version.
         */
        long loadedVersion;

        /** Context size at load time; used by {@link ConflictPolicy#APPEND_MERGE}. */
        int loadedContextSize;

        /**
         * Per-call system message, propagated across PreCallEvent → PreReasoningEvent /
         * PreSummaryEvent. Owned by a single logical execution: seeded to {@code null} at call
         * entry ({@code #beforeAgentExecution(List)}) and set by
         * {@link #consumeSystemMsgAfterPreCall(Msg, Object)}.
         */
        /**
         * 按调用的系统消息，贯穿 PreCallEvent → PreReasoningEvent / PreSummaryEvent。
         * 归单次逻辑执行所有：调用入口处置 {@code null}（{@code beforeAgentExecution}），
         * 由 {@link #consumeSystemMsgAfterPreCall(Msg, Object)} 赋值。
         */
        Msg systemMsg;

        /**
         * Per-call event sink for {@code streamEvents}. Bound in {@link ReActAgent#doCall(List)}
         * from the per-subscription Reactor Context ({@code EVENT_SINK_KEY}) and read by
         * {@link #publishEvent}.
         */
        /**
         * {@code streamEvents} 的按调用事件 sink。在 {@link ReActAgent#doCall(List)} 中
         * 从按订阅的 Reactor Context（{@code EVENT_SINK_KEY}）绑定，
         * 由 {@link #publishEvent} 读取。
         */
        FluxSink<AgentEvent> eventSink;

        /**
         * External event emitter for child-agent event forwarding. When a parent's tool (e.g.
         * {@code agent_spawn}) injects a forwarding emitter via
         * {@link AgentEventEmitter#FORWARDING_CONTEXT_KEY}, this child agent uses it to push
         * events into the parent's {@code streamEvents()} stream with source tagging. Takes
         * effect only when {@link #eventSink} is null.
         */
        /**
         * 子智能体事件转发用的外部事件发射器。当父智能体的工具（如 {@code agent_spawn}）
         * 通过 {@link AgentEventEmitter#FORWARDING_CONTEXT_KEY} 注入转发式 emitter 时，
         * 本子智能体用它把事件带来源标记地推进父智能体的 {@code streamEvents()} 流。
         * 仅在 {@link #eventSink} 为 null 时生效。
         */
        AgentEventEmitter externalEventEmitter;

        /**
         * The call's {@link RuntimeContext} (caller-supplied metadata for hooks / tools). Set once
         * at call entry; read directly by the reasoning loop instead of the instance-level
         * {@code rc} so the loop is self-contained per call.
         */
        /**
         * 本次调用的 {@link RuntimeContext}（调用方为钩子/工具提供的元数据）。
         * 调用入口一次性赋值；推理循环直接读它而非实例级 {@code rc}，
         * 使循环按调用自包含。
         */
        RuntimeContext rc;

        /**
         * Stable per-call reference to the agent's shared {@code toolkit}, resolved once in
         * {@code beforeAgentExecution}. The toolkit is never copied and never mutated per call;
         * per-call tool differences live on {@link #toolRequestConfig} and are composed with this
         * toolkit on demand, so concurrent calls on the same agent never observe each other's
         * differences. All toolkit access in this scope reads {@code activeToolkit} for uniformity.
         */
        Toolkit activeToolkit;

        /**
         * Per-call tool request config ({@link ToolRequestConfig#NONE} = use the shared toolkit
         * as-is). Carries the immutable per-call tool difference (external tools + merge mode);
         * the shared {@link #activeToolkit} is never copied or mutated.
         */
        ToolRequestConfig toolRequestConfig = ToolRequestConfig.NONE;

        /**
         * Per-call structured-output tool (the {@code generate_response} tool). Non-null only for
         * fallback structured-output calls (when the model does not support native structured
         * output). Lives on this scope rather than the shared toolkit so concurrent
         * structured-output calls do not collide.
         */
        /**
         * 按调用的结构化输出工具（{@code generate_response}）。仅在回退路径
         * （模型不支持原生结构化输出）时非空。挂在本作用域而非共享工具集上，
         * 使并发的结构化输出调用互不冲突。
         */
        AgentTool soTool;

        /** Set to {@code true} when the {@code generate_response} tool completes successfully. */
        /** {@code generate_response} 工具成功完成时置 {@code true}。 */
        boolean soCompleted;

        /** The tool result message from the successful {@code generate_response} call. */
        /** {@code generate_response} 成功调用产生的工具结果消息。 */
        Msg soResultMsg;

        /** Placeholder sentence written to the tool_result of a returnDirect tool. */
        private static final String RETURN_DIRECT_PLACEHOLDER =
                "Tool call completed. The result has been presented to the user as the final output"
                        + " of this turn.";

        /** Native structured-output format set on the per-call scope for native-path calls. */
        /** 原生路径调用时，在按调用作用域上设置的原生结构化输出格式（response_format）。 */
        ResponseFormat nativeResponseFormat;

        CallExecution(AgentState state, PermissionEngine permissionEngine, String slotKey) {
            this(state, permissionEngine, slotKey, AgentStateStore.UNVERSIONED);
        }

        CallExecution(
                AgentState state,
                PermissionEngine permissionEngine,
                String slotKey,
                long loadedVersion) {
            this.state = state;
            this.permissionEngine = permissionEngine;
            this.slotKey = slotKey;
            this.loadedVersion = loadedVersion;
            this.loadedContextSize = state != null ? state.getContext().size() : 0;
        }

        /**
         * Reads this execution's own signal; queued calls never share cancellation state.
         */
        /**
         * 按调用的中断检查点：读取本次调用的会话级 {@link InterruptControl}
         * （挂在其 {@link AgentState} 上），因此定向的
         * {@code interrupt(userId, sessionId)} 只中止匹配会话的进行中调用，
         * 不影响同一智能体上其他并发调用。在 ReAct 循环各关键节点被调用。
         */
        private Mono<Void> checkInterrupted() {
            return Mono.defer(
                    () ->
                            interruption.isInterrupted()
                                    ? Mono.error(
                                            new InterruptedException("Agent execution interrupted"))
                                    : Mono.empty());
        }

        /**
         * ReAct 执行入口路由（在 doCall 绑定事件出口之后调用）：
         * <ol>
         *   <li>优雅停机去重：上次因停机被中断的会话，丢弃客户端重复发来的相同输入；</li>
         *   <li>悬空工具恢复：为缺少结果的 pending 工具调用补合成错误结果；</li>
         *   <li>无 pending 工具 → 常规路径 addToContext + coreAgent；</li>
         *   <li>有 ASKING 状态的工具 → 必须携带 ConfirmResult 才能 resumeAgent，
         *       否则抛出详细说明的 IllegalStateException；</li>
         *   <li>有 pending 工具无输入 → 直接 resumeAgent 执行待执行工具；</li>
         *   <li>有 pending 工具 + 用户提供的工具结果 → 校验入库后继续。</li>
         * </ol>
         */
        private Mono<Msg> doCallInner(List<Msg> msgs) {
            // Graceful-shutdown deduplication: if the agent's session was previously interrupted
            // by shutdown, the client is likely retrying with the same user prompt that already
            // exists in memory. Discard the duplicate input so the agent resumes purely from its
            // saved memory context.
            // 优雅停机去重：如果该会话之前因停机被中断，客户端很可能带着已存在于
            // 记忆中的相同用户提示重试。丢弃重复输入，让智能体纯粹从
            // 已保存的记忆上下文恢复。
            if (shutdownManager.checkAndClearShutdownInterruptedForState(state)) {
                log.info(
                        "Detected shutdown-interrupted session for agent {}, discarding duplicate"
                                + " input",
                        getName());
                msgs = List.of();
            }

            Set<String> pendingIds = MessageUtils.pendingToolUseIds(state.contextMutable());

            // No pending tools -> normal processing
            // 无待执行工具 → 常规流程：输入入上下文，进入 ReAct 循环
            if (pendingIds.isEmpty()) {
                addToContext(msgs);
                return coreAgent();
            }

            // Permission HITL: if any pending tool is ASKING, the caller MUST supply
            // ConfirmResults (via Msg.METADATA_CONFIRM_RESULTS) before we can proceed.
            // 权限人在回路：只要存在 ASKING 状态的待执行工具，调用方必须通过
            // Msg.METADATA_CONFIRM_RESULTS 提供确认结果才能继续。
            List<ToolUseBlock> asking = askingToolCalls();
            if (!asking.isEmpty()) {
                // 应用批准/拒绝结果后恢复执行
                validateAndAcceptConfirmResults(msgs, asking);
                return resumeAgent();
            }

            // Pending-tool-call recovery: auto-patch orphaned pending tool calls with synthetic
            // error results so the agent can continue instead of crashing. This must happen after
            // the permission HITL flow so ASKING tool calls are handled by confirmation first.
            // 悬空工具调用恢复：自动为孤立的 pending 工具调用补合成错误结果，
            // 让智能体继续执行而不是崩溃。
            if (enablePendingToolRecovery) {
                maybePatchPendingToolCalls(msgs, pendingIds);
                pendingIds = MessageUtils.pendingToolUseIds(state.contextMutable());
                if (pendingIds.isEmpty()) {
                    addToContext(msgs);
                    return coreAgent();
                }
            }

            // Has pending tools but no input -> resume (execute pending tools directly)
            // 有待执行工具但无新输入 → 直接恢复执行（立即执行待执行工具）
            if (msgs == null || msgs.isEmpty()) {
                return resumeAgent();
            }

            // Has pending tools + input -> check if user provided tool results
            // 有待执行工具 + 有输入 → 检查用户是否直接提供了工具结果
            List<ToolResultBlock> providedResults =
                    msgs.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();

            if (!providedResults.isEmpty()) {
                // User provided tool results -> validate and add. Correlate the pending
                // tool_use batch with the provided results BEFORE persisting, so a
                // returnDirect short-circuit (same predicate as the in-framework path) can
                // persist the placeholder-shaped tool_result directly. Only a batch resolved
                // entirely by this resume short-circuits; a partially resolved batch (across
                // earlier resumes, or mixed with framework-executed tools) is always fed
                // back to the model.
                // 用户提供了工具结果 → 校验并写入上下文，视剩余 pending 决定恢复/重新推理
                List<ToolUseBlock> pendingBatch =
                        MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
                List<ToolUseBlock> originalBatch =
                        MessageUtils.extractRecentToolCalls(state.contextMutable(), getName());
                String externalReplyId =
                        resolvePendingRequestReplyId(
                                Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> externalPairs =
                        pairProvidedToolResults(pendingBatch, providedResults);
                boolean returnDirectResume =
                        !originalBatch.isEmpty()
                                && pendingBatch.size() == originalBatch.size()
                                && externalPairs.size() == pendingBatch.size()
                                && externalPairs.stream().allMatch(this::isReturnDirectToolCall);
                // Validate and publish from the caller's original messages so the
                // ExternalExecutionResultEvent carries the real external results;
                // only what lands in context is placeholder-shaped on a short-circuit.
                validateAndAddToolResults(
                        msgs,
                        returnDirectResume ? placeholderToolResultMsgs(msgs) : msgs,
                        pendingIds);
                if (returnDirectResume) {
                    return Mono.just(finalizeReturnDirect(externalPairs, externalReplyId));
                }
                return !MessageUtils.pendingToolUseIds(state.contextMutable()).isEmpty()
                        ? resumeAgent()
                        : coreAgent();
            }

            // Recovery was disabled and user did not provide tool results — unrecoverable.
            // 恢复功能被禁用且用户未提供工具结果 —— 无法恢复，直接抛错。
            throw new IllegalStateException(
                    "Pending tool calls exist without results. "
                            + "Enable enablePendingToolRecovery or provide tool results. "
                            + "Pending IDs: "
                            + pendingIds);
        }

        /**
         * Pull all {@link ConfirmResult}s out of the {@link Msg#METADATA_CONFIRM_RESULTS} metadata
         * key across the incoming message list.
         */
        /**
         * 从输入消息列表的 {@link Msg#METADATA_CONFIRM_RESULTS} 元数据键中
         * 提取全部 {@link ConfirmResult}（第二次 call 恢复 HITL 时携带）。
         */
        @SuppressWarnings("unchecked")
        private List<ConfirmResult> extractConfirmResults(List<Msg> msgs) {
            if (msgs == null || msgs.isEmpty()) {
                return List.of();
            }
            List<ConfirmResult> collected = new ArrayList<>();
            for (Msg m : msgs) {
                Object raw =
                        m.getMetadata() == null
                                ? null
                                : m.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof ConfirmResult cr) {
                            collected.add(cr);
                        }
                    }
                }
            }
            return collected;
        }

        /**
         * Validate and accept a permission-HITL resume payload against the currently ASKING tool
         * calls.
         *
         * <p>Permission HITL resumes with one or more confirmations for currently ASKING tool
         * calls. Confirmations may cover a subset of ASKING calls, but no result may reference a
         * stale or unrelated tool call. Once accepted, the normalized results are applied to agent
         * state and the correlated resume event is emitted.
         */
        private void validateAndAcceptConfirmResults(List<Msg> msgs, List<ToolUseBlock> asking) {
            List<ConfirmResult> results = extractConfirmResults(msgs);
            if (results.isEmpty()) {
                // 未携带确认结果：抛出带详细恢复指引的异常
                String pendingSummary =
                        asking.stream()
                                .map(t -> t.getName() + " (id=" + t.getId() + ")")
                                .collect(Collectors.joining(", "));
                throw new IllegalStateException(
                        "Agent is paused for human-in-the-loop confirmation: the following"
                                + " tool call(s) are in ASKING state and need your approval"
                                + " before the agent can continue: ["
                                + pendingSummary
                                + "]. This call supplied no confirmation, so it cannot"
                                + " proceed.\n"
                                + "To resume, send a follow-up message that carries a"
                                + " List<ConfirmResult> under the metadata key \""
                                + Msg.METADATA_CONFIRM_RESULTS
                                + "\", e.g.:\n"
                                + "    UserMessage.builder()\n"
                                + "        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,\n"
                                + "            List.of(new ConfirmResult(true, toolCall))))\n"
                                + "        .build();\n"
                                + "Tip: capture the ToolUseBlocks from the"
                                + " RequireUserConfirmEvent emitted when the agent paused.\n"
                                + "If you did NOT expect a pending confirmation here, a"
                                + " previous run most likely paused on one of these tool calls"
                                + " and persisted that state under the same (agentId,"
                                + " sessionId); start a fresh session, clear the persisted"
                                + " state, or use an in-memory state store to begin clean.");
            }

            Set<String> expectedIds =
                    asking.stream()
                            .map(ToolUseBlock::getId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> providedIds = new LinkedHashSet<>();
            List<ConfirmResult> normalized = new ArrayList<>();

            for (ConfirmResult result : results) {
                if (result == null || result.getToolCall() == null) {
                    throw new IllegalStateException(
                            "ConfirmResult and ConfirmResult.toolCall must not be null.");
                }
                ToolUseBlock toolCall = result.getToolCall();
                String toolCallId = toolCall.getId();
                if (toolCallId == null || toolCallId.isEmpty()) {
                    throw new IllegalStateException("ConfirmResult.toolCall.id must not be empty.");
                }
                if (!providedIds.add(toolCallId)) {
                    throw new IllegalStateException(
                            "Duplicate ConfirmResult for tool call ID: " + toolCallId);
                }
                if (!expectedIds.contains(toolCallId)) {
                    throw new IllegalStateException(
                            "ConfirmResult references non-ASKING tool call ID: "
                                    + toolCallId
                                    + ". Expected: "
                                    + expectedIds);
                }
                normalized.add(result);
            }

            String replyId = resolvePendingRequestReplyId(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID);
            if (replyId.isEmpty()) {
                replyId = UUID.randomUUID().toString().replace("-", "");
                log.warn("Missing confirmation reply id; generated fallback {}", replyId);
            }
            publishEvent(new UserConfirmResultEvent(replyId, normalized));
            clearPendingRequestReplyId(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID);

            applyConfirmResults(normalized, replyId);
        }

        /** Resolve the reply id for the pending HITL request stored on the last assistant message. */
        private String resolvePendingRequestReplyId(String metadataKey) {
            Msg requestMsg = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (requestMsg == null || requestMsg.getMetadata() == null) {
                return "";
            }
            Object raw = requestMsg.getMetadata().get(metadataKey);
            return raw instanceof String s ? s : "";
        }

        /**
         * Persist the reply id for a pending HITL request on the live assistant message.
         *
         * <p>The assistant message owns the paused {@link ToolUseBlock}s, so storing the correlation
         * metadata there lets the next call recover it from session state.
         */
        private void persistPendingRequestReplyId(String metadataKey, String replyId) {
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>(lastAssistant.getMetadata());
            metadata.put(metadataKey, replyId);
            MessageUtils.replaceLastMessageByRole(
                    state.contextMutable(),
                    MsgRole.ASSISTANT,
                    lastAssistant.withMetadata(metadata));
        }

        /** Remove HITL correlation metadata after the resume payload is accepted. */
        private void clearPendingRequestReplyId(String metadataKey) {
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null || lastAssistant.getMetadata() == null) {
                return;
            }
            if (!lastAssistant.getMetadata().containsKey(metadataKey)) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>(lastAssistant.getMetadata());
            metadata.remove(metadataKey);
            MessageUtils.replaceLastMessageByRole(
                    state.contextMutable(),
                    MsgRole.ASSISTANT,
                    lastAssistant.withMetadata(metadata));
        }

        /**
         * Apply user confirmation results to the ASKING tool calls in context.
         *
         * <p>For each result:
         * <ul>
         *   <li>{@code confirmed == true}: replace the ASKING ToolUseBlock with the (possibly
         *       modified) one from the result, set state to {@link ToolCallState#ALLOWED}, and
         *       register any attached {@link PermissionRule}s with the engine.</li>
         *   <li>{@code confirmed == false}: write a DENIED {@link ToolResultBlock} to context so
         *       the tool will no longer be pending on resume, using the user-supplied
         *       {@link ConfirmResult#getReason() reason} when present, and publish the complete
         *       tool-result event lifecycle.</li>
         * </ul>
         */
        /**
         * 把用户确认结果应用到上下文中 ASKING 状态的工具调用。
         *
         * <p>对每条确认结果：
         * <ul>
         *   <li>{@code confirmed == true}：用结果中（可能被用户修改过的）ToolUseBlock
         *       替换 ASKING 块，状态提升为 {@link ToolCallState#ALLOWED}，
         *       并把附带的 {@link PermissionRule} 注册进权限引擎（"记住我的选择"）；</li>
         *   <li>{@code confirmed == false}：向上下文写入 DENIED 状态的
         *       {@link ToolResultBlock}，使该工具在恢复时不再是 pending。</li>
         * </ul>
         */
        private void applyConfirmResults(List<ConfirmResult> results, String replyId) {
            // Replace ASKING ToolUseBlocks with possibly-modified ones from the user, and
            // promote them to ALLOWED. Collect denied ones for separate handling.
            // 用用户可能修改过的 ToolUseBlock 替换 ASKING 块并提升为 ALLOWED；
            // 被拒绝的单独收集处理。
            List<Map.Entry<ToolUseBlock, String>> deniedToolCalls = new ArrayList<>();
            Map<String, ToolUseBlock> replacements = new HashMap<>();
            for (ConfirmResult r : results) {
                ToolUseBlock target = r.getToolCall();
                if (target == null) {
                    continue;
                }
                if (r.isConfirmed()) {
                    // 批准：替换工具调用块并升级为 ALLOWED
                    replacements.put(target.getId(), target.withState(ToolCallState.ALLOWED));
                    if (r.getRules() != null) {
                        // 用户附带的权限规则注册进引擎（如"允许同类操作"）
                        for (PermissionRule rule : r.getRules()) {
                            if (rule != null) {
                                permissionEngine.addRule(rule);
                            }
                        }
                    }
                } else {
                    String reason = r.getReason();
                    deniedToolCalls.add(
                            Map.entry(
                                    target,
                                    reason == null || reason.isBlank()
                                            ? PERMISSION_DENIED_BY_USER
                                            : reason));
                }
            }
            // 就地替换上下文中对应助手消息里的工具调用块
            MessageUtils.replaceToolUseBlocks(state.contextMutable(), replacements);
            // 拒绝：为每个被拒工具写入 DENIED 结果消息
            for (Map.Entry<ToolUseBlock, String> entry : deniedToolCalls) {
                ToolUseBlock denied = entry.getKey();
                String reasonText = entry.getValue();
                ToolResultBlock deniedResult =
                        ToolResultBlock.text(reasonText)
                                .withIdAndName(denied.getId(), denied.getName())
                                .withState(ToolResultState.DENIED);
                Msg deniedMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                deniedResult, denied, getName());
                state.contextMutable().add(deniedMsg);
                deniedToolResultEvents(denied, replyId, reasonText).forEach(this::publishEvent);
            }
        }

        /** Build the complete DENIED tool-result lifecycle for any permission-denial path. */
        private List<AgentEvent> deniedToolResultEvents(
                ToolUseBlock toolCall, String replyId, String reason) {
            return List.of(
                    new ToolResultStartEvent(replyId, toolCall.getId(), toolCall.getName()),
                    new ToolResultTextDeltaEvent(
                            replyId, toolCall.getId(), toolCall.getName(), reason),
                    new ToolResultEndEvent(
                            replyId, toolCall.getId(), toolCall.getName(), ToolResultState.DENIED));
        }

        /**
         * 悬空工具调用补丁：上次调用因中断/崩溃留下了没有结果的工具调用时，
         * 为每个 pending 工具自动补一条合成错误结果，让模型知情并继续。
         * 用户本次已自带工具结果时跳过（以用户提供的为准）。
         */
        private void maybePatchPendingToolCalls(List<Msg> msgs, Set<String> pendingIds) {
            if (pendingIds.isEmpty()) {
                return;
            }
            if (msgs == null || msgs.isEmpty()) {
                return;
            }
            // 用户输入中已包含工具结果 → 不自动补，交给 validateAndAddToolResults
            boolean userProvidedResults =
                    msgs.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
            if (userProvidedResults) {
                return;
            }
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null) {
                return;
            }
            // 从最后一条助手消息中筛出仍未有结果的工具调用
            List<ToolUseBlock> pendingToolCalls =
                    lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                            .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                            .filter(toolUse -> toolUse.getState() != ToolCallState.ASKING)
                            .toList();
            if (pendingToolCalls.isEmpty()) {
                return;
            }
            log.warn(
                    "Pending tool calls detected without results, auto-generating error results."
                            + " Pending IDs: {}",
                    pendingToolCalls.stream().map(ToolUseBlock::getId).toList());
            for (ToolUseBlock toolCall : pendingToolCalls) {
                // 逐个补合成错误结果消息
                ToolResultBlock errorResult =
                        ToolResultBlock.error(
                                toolCall.getId(),
                                "Previous tool execution failed or was interrupted. Tool: "
                                        + toolCall.getName());
                Msg toolResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                errorResult, toolCall, getName());
                state.contextMutable().add(toolResultMsg);
                log.info(
                        "Auto-generated error result for pending tool call: {} ({})",
                        toolCall.getName(),
                        toolCall.getId());
            }
        }

        /**
         * Synthesize error {@link ToolResultBlock}s for pending tool calls in the last assistant
         * message that have no matching results.
         *
         * <p>Called from {@link ReActAgent#handleInterrupt} before the recovery message is
         * appended, so that an interrupt signalled between reasoning (which writes the assistant
         * {@code tool_use}) and acting (which would produce the tool results) does not persist a
         * {@link ToolUseBlock} with no matching {@link ToolResultBlock}. Without this, the next
         * resumed run inherits an inconsistent context and providers may return an empty response.
         *
         * <p>Must run <em>before</em> the recovery message is added, otherwise that recovery
         * message becomes the last assistant message and pending-tool detection no longer
         * detects the pending calls.
         */
        private void synthesizeErrorResultsForPendingToolCalls() {
            List<ToolUseBlock> pendingToolCalls =
                    MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
            if (pendingToolCalls.isEmpty()) {
                return;
            }
            for (ToolUseBlock toolCall : pendingToolCalls) {
                ToolResultBlock errorResult =
                        ToolResultBlock.error(
                                toolCall.getId(),
                                "Tool execution was interrupted before it could run. Tool: "
                                        + toolCall.getName());
                Msg toolResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                errorResult, toolCall, getName());
                state.contextMutable().add(toolResultMsg);
                log.info(
                        "Synthesized interrupted-result for pending tool call: {} ({})",
                        toolCall.getName(),
                        toolCall.getId());
            }
        }

        /**
         * 事件发布统一出口：优先推给本次调用的 streamEvents sink；
         * 没有 sink 但有外部转发 emitter（子智能体场景）则走转发器；两者皆无则丢弃。
         */
        private void publishEvent(AgentEvent event) {
            FluxSink<AgentEvent> sink = eventSink;
            if (sink != null) {
                sink.next(event);
            } else if (externalEventEmitter != null) {
                externalEventEmitter.emit(event);
            }
        }

        /**
         * Validate input messages when there are pending tool calls, then add to context.
         *
         * <p>Validation rules:
         * <ul>
         *   <li>Empty input: no-op (will proceed to acting)</li>
         *   <li>No tool results: throw error</li>
         *   <li>Has tool results: validate IDs match pending, no duplicates</li>
         *   <li>Partial results + text content: throw error (text only allowed when all tools
         *       completed)</li>
         * </ul>
         *
         * <p>Validation and the published {@link ExternalExecutionResultEvent} always reflect
         * {@code msgs} as the caller supplied them; {@code msgsToPersist} is what lands in
         * context and may differ only on a returnDirect short-circuit, where the tool results
         * are replaced with placeholder-shaped copies.
         *
         * @param msgs The input messages to validate and publish
         * @param msgsToPersist The messages to add to context (may be placeholder-substituted)
         * @param pendingIds The set of pending tool use IDs
         * @throws IllegalStateException if validation fails
         */
        /**
         * 存在待执行工具调用时校验输入消息并加入上下文。校验规则：
         * 空输入直接放行；无工具结果抛错；结果 ID 必须匹配 pending 且不重复；
         * 只提供部分结果时不允许夹带文本内容（文本只能在全部工具完成后出现）。
         */
        private void validateAndAddToolResults(
                List<Msg> msgs, List<Msg> msgsToPersist, Set<String> pendingIds) {
            if (msgs == null || msgs.isEmpty()) {
                return;
            }

            List<ToolResultBlock> results =
                    msgs.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();

            if (results.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot add messages without tool results when pending tool calls exist. "
                                + "Pending IDs: "
                                + pendingIds);
            }

            // Check for duplicate IDs
            Set<String> providedIds = new HashSet<>();
            for (ToolResultBlock r : results) {
                if (!providedIds.add(r.getId())) {
                    throw new IllegalStateException("Duplicate tool result ID: " + r.getId());
                }
            }

            // Check all provided IDs match pending IDs
            Set<String> invalidIds =
                    providedIds.stream()
                            .filter(id -> !pendingIds.contains(id))
                            .collect(Collectors.toSet());
            if (!invalidIds.isEmpty()) {
                throw new IllegalStateException(
                        "Invalid tool result IDs: " + invalidIds + ". Expected: " + pendingIds);
            }

            // Check for non-ToolResultBlock content
            boolean hasTextContent =
                    msgs.stream()
                            .flatMap(m -> m.getContent().stream())
                            .anyMatch(block -> !(block instanceof ToolResultBlock));

            // If only partial results provided, text content is not allowed
            boolean isPartialResults = !providedIds.containsAll(pendingIds);
            if (isPartialResults && hasTextContent) {
                throw new IllegalStateException(
                        "Cannot include text content when providing partial tool results. "
                                + "Provided: "
                                + providedIds
                                + ", Pending: "
                                + pendingIds);
            }
            String replyId =
                    resolvePendingRequestReplyId(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
            if (!replyId.isEmpty()) {
                publishEvent(new ExternalExecutionResultEvent(replyId, results));
                clearPendingRequestReplyId(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
            }
            state.contextMutable().addAll(msgsToPersist);
        }

        /**
         * Correlate the pending tool_use batch (in declaration order) with the tool results
         * supplied by the caller, aligned by id. Returns only the pairs that have a matching
         * result; a partial supply yields fewer pairs than the batch.
         */
        private List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairProvidedToolResults(
                List<ToolUseBlock> pendingBatch, List<ToolResultBlock> providedResults) {
            Map<String, ToolResultBlock> resultsById = new HashMap<>();
            for (ToolResultBlock result : providedResults) {
                resultsById.put(result.getId(), result);
            }
            List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs = new ArrayList<>();
            for (ToolUseBlock toolUse : pendingBatch) {
                ToolResultBlock result = resultsById.get(toolUse.getId());
                if (result != null) {
                    pairs.add(Map.entry(toolUse, result));
                }
            }
            return pairs;
        }

        /**
         * Build placeholder-shaped copies of the caller-supplied messages for a returnDirect
         * short-circuit: every {@link ToolResultBlock}'s output is replaced with the
         * returnDirect placeholder sentence (id/name preserved, state normalized via {@link
         * #determineToolResultState(ToolResultBlock)} like the in-framework path) while
         * non-tool-result content passes through unchanged. New {@link Msg}s are built — the
         * caller's objects are never mutated — keeping the context invariant {@code [tool_use,
         * tool_result(placeholder), assistant(full result)]}.
         */
        private List<Msg> placeholderToolResultMsgs(List<Msg> msgs) {
            List<Msg> replaced = new ArrayList<>(msgs.size());
            for (Msg msg : msgs) {
                replaced.add(
                        msg.withContent(
                                msg.getContent().stream()
                                        .map(this::placeholderToolResultContent)
                                        .toList()));
            }
            return replaced;
        }

        private ContentBlock placeholderToolResultContent(ContentBlock block) {
            if (!(block instanceof ToolResultBlock result)) {
                return block;
            }
            return placeholderResultBlock(
                    result.getId(), result.getName(), determineToolResultState(result));
        }

        /**
         * Add messages to the agent state context if not null.
         *
         * @param msgs The messages to add
         */
        /** 把输入消息追加进智能体状态上下文（null 时跳过）。 */
        private void addToContext(List<Msg> msgs) {
            if (msgs != null) {
                state.contextMutable().addAll(msgs);
            }
        }

        // ==================== Core ReAct Loop ====================
        // ==================== ReAct 核心循环 ====================

        /**
         * Entry point for a fresh agent invocation: kicks off the ReAct loop at iteration 0.
         */
        /** 全新调用的入口：从第 0 轮迭代启动 ReAct 循环。 */
        private Mono<Msg> coreAgent() {
            return executeIteration(0);
        }

        /**
         * Resume entry point when pending tool calls remain from a previous turn:
         * jumps directly into the acting phase without another reasoning step.
         */
        /**
         * 恢复入口：上一轮留下了待执行的工具调用时，跳过推理直接进入
         * acting 阶段执行这些工具。
         */
        private Mono<Msg> resumeAgent() {
            return acting(0);
        }

        /** 单轮迭代：进入第 iter 轮推理（推理结束后自行决定是否进入 acting）。 */
        private Mono<Msg> executeIteration(int iter) {
            return reasoning(iter, false);
        }

        /**
         * Execute the reasoning phase.
         *
         * <p>This method streams from the model, accumulates chunks, notifies hooks, and
         * decides whether to continue to acting or return early (HITL stop, gotoReasoning, or finished).
         *
         * @param iter Current iteration number
         * @param ignoreMaxIters If true, skip maxIters check (for gotoReasoning)
         * @return Mono containing the final result message
         */
        /**
         * 执行推理阶段 —— ReAct 循环的"思考"半边。
         *
         * <p>流程：maxIters 检查 → 中断检查 → PreReasoning 钩子 → 组装
         * GenerateOptions（合并原生 response_format）与工具列表（追加按调用的
         * generate_response）→ onReasoning 中间件链包裹 reasoningStream
         * → 流结束后构建最终消息；中间件发出 RequestStopEvent 时短路返回
         * （消息先持久化以便下次恢复）；中断异常时按停机策略保留/丢弃部分推理。
         * 最后交给 runPostReasoningPipeline 决定继续 acting 还是提前返回。
         *
         * @param iter           当前迭代轮次
         * @param ignoreMaxIters 为 true 时跳过 maxIters 检查（gotoReasoning 场景）
         * @return 包含最终结果消息的 Mono
         */
        private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
            // Check maxIters unless ignoreMaxIters is set
            // 达到最大迭代次数 → 进入总结阶段（除非 ignoreMaxIters）
            if (!ignoreMaxIters && iter >= maxIters) {
                return summarizing();
            }

            // 本次推理的块累积器（文本/思考/工具调用增量汇总为最终消息）
            ReasoningContext context = new ReasoningContext(getName());

            return checkInterrupted()
                    .then(
                            hookDispatcher.firePreReasoning(
                                    state.contextMutable(), systemMsg, model.getModelName()))
                    .flatMap(
                            event -> {
                                // 生成选项：钩子事件可覆盖，否则用构建期默认值
                                GenerateOptions options =
                                        event.getEffectiveGenerateOptions() != null
                                                ? event.getEffectiveGenerateOptions()
                                                : buildGenerateOptions();
                                // 原生结构化输出路径：把 response_format 合并进选项
                                if (nativeResponseFormat != null && soTool == null) {
                                    options =
                                            GenerateOptions.mergeOptions(
                                                    GenerateOptions.builder()
                                                            .responseFormat(nativeResponseFormat)
                                                            .build(),
                                                    options);
                                }
                                // 组装模型输入：系统消息前置
                                List<Msg> modelInput =
                                        MessageUtils.prependSystemMessage(
                                                event.getInputMessages(), event.getSystemMessage());
                                // 工具列表：按当前激活的工具组取 Schema
                                List<ToolSchema> tools =
                                        activeToolkit.getToolSchemas(
                                                state.getToolContext().getActivatedGroups(),
                                                toolRequestConfig);
                                // Per-call structured-output tool: expose generate_response to the
                                // model for this call only (not registered on the shared toolkit).
                                // 按调用的结构化输出工具：仅本次调用向模型暴露
                                // generate_response（不注册进共享工具集）。
                                if (soTool != null) {
                                    tools = new ArrayList<>(tools);
                                    tools.add(
                                            ToolSchema.builder()
                                                    .name(soTool.getName())
                                                    .description(soTool.getDescription())
                                                    .parameters(soTool.getParameters())
                                                    .strict(soTool.getStrict())
                                                    .outputSchema(soTool.getOutputSchema())
                                                    .build());
                                }
                                // 推理核心：真正的模型调用流
                                Function<ReasoningInput, Flux<AgentEvent>> reasoningCore =
                                        ri ->
                                                reasoningStream(
                                                        context,
                                                        ri.messages(),
                                                        ri.tools(),
                                                        ri.options());
                                // 用 onReasoning 中间件链包裹推理核心
                                // （压缩、收件箱、计划模式等中间件在此介入）
                                Flux<AgentEvent> stream =
                                        MiddlewareChain.build(
                                                        middlewaresAt(
                                                                MiddlewareBase.ExtensionPoint
                                                                        .ON_REASONING),
                                                        ReActAgent.this,
                                                        rc,
                                                        MiddlewareBase::onReasoning,
                                                        reasoningCore)
                                                .apply(
                                                        new ReasoningInput(
                                                                modelInput, tools, options));
                                // Track any RequestStopEvent emitted by middlewares while still
                                // exhausting the stream. Publish at the outer reasoning boundary
                                // so events added by onReasoning middlewares are forwarded too.
                                // exhausting the stream (publishEvent already fires on each event).
                                // 跟踪中间件发出的 RequestStopEvent，同时仍把流跑完
                                // （publishEvent 已对每个事件做过发布）。
                                AtomicReference<RequestStopEvent> stopRequested =
                                        new AtomicReference<>();
                                return stream.doOnNext(this::publishEvent)
                                        .doOnNext(
                                                ev -> {
                                                    if (ev instanceof RequestStopEvent rs) {
                                                        stopRequested.compareAndSet(null, rs);
                                                    }
                                                })
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            Msg finalMsg =
                                                                    context.buildFinalMessage();
                                                            RequestStopEvent rs =
                                                                    stopRequested.get();
                                                            if (rs != null && finalMsg != null) {
                                                                // Persist the reasoning message
                                                                // before
                                                                // returning so the next call can
                                                                // resume
                                                                // from pending tool calls.
                                                                // 返回前先持久化推理消息，
                                                                // 使下次调用能从 pending
                                                                // 工具调用恢复。
                                                                state.contextMutable()
                                                                        .add(finalMsg);
                                                                return Mono.just(
                                                                        finalMsg.withGenerateReason(
                                                                                rs
                                                                                        .getGenerateReason()));
                                                            }
                                                            return Mono.justOrEmpty(finalMsg);
                                                        }));
                            })
                    .onErrorResume(
                            InterruptedException.class,
                            error -> {
                                // 被中断：按停机策略决定保留还是丢弃已累积的部分推理
                                Msg msg = context.buildFinalMessage();
                                if (msg != null) {
                                    boolean discard =
                                            interruption.getSource() == InterruptSource.SYSTEM
                                                    && shutdownManager
                                                                    .getConfig()
                                                                    .partialReasoningPolicy()
                                                            == PartialReasoningPolicy.DISCARD;
                                    if (!discard) {
                                        state.contextMutable().add(msg);
                                    }
                                }
                                return Mono.error(error);
                            })
                    .flatMap(
                            msg -> {
                                // Short-circuit: middleware requested stop during reasoning. The
                                // msg
                                // is already persisted to context and tagged with the correct
                                // GenerateReason; skip legacy postReasoning hook.
                                // 短路：中间件在推理期间请求了停止。消息已持久化到上下文
                                // 并打上正确的 GenerateReason；跳过遗留的 postReasoning 钩子。
                                if (msg.getGenerateReason()
                                                == GenerateReason.MIDDLEWARE_STOP_REQUESTED
                                        || msg.getGenerateReason()
                                                == GenerateReason.PERMISSION_ASKING) {
                                    return Mono.just(msg);
                                }
                                return runPostReasoningPipeline(msg, iter);
                            });
        }

        /**
         * 推理后管线：PostReasoning 钩子 → 依次判断
         * HITL 停止（stopAgent）/ gotoReasoning（钩子要求重新推理）/
         * 任务完成（无工具调用）/ 继续进入 acting 阶段。
         */
        @SuppressWarnings("deprecation")
        private Mono<Msg> runPostReasoningPipeline(Msg msg, int iter) {
            return hookDispatcher
                    .firePostReasoning(msg, model.getModelName())
                    .flatMap(
                            event -> {
                                Msg eventMsg = event.getReasoningMessage();
                                if (eventMsg != null) {
                                    // 钩子可能改写/替换了推理消息，以事件中的为准入库
                                    state.contextMutable().add(eventMsg);
                                }

                                // HITL stop
                                // 人在回路停止：钩子调用了 stopAgent()
                                if (event.isStopRequested()) {
                                    if (eventMsg == null) {
                                        return Mono.empty();
                                    }
                                    return Mono.just(
                                            eventMsg.withGenerateReason(
                                                    GenerateReason.REASONING_STOP_REQUESTED));
                                }

                                // gotoReasoning requested (e.g., by a PostReasoning hook)
                                // 钩子要求回到推理（如注入纠正消息后重新生成）
                                if (event.isGotoReasoningRequested()) {
                                    List<Msg> gotoMsgs = event.getGotoReasoningMsgs();
                                    if (gotoMsgs != null) {
                                        state.contextMutable().addAll(gotoMsgs);
                                    }
                                    // ignoreMaxIters=true：gotoReasoning 不受迭代上限约束
                                    return reasoning(iter + 1, true);
                                }

                                // Check finish conditions
                                // 完成判定：消息中没有工具调用即任务结束
                                if (isFinished(eventMsg)) {
                                    return Mono.justOrEmpty(eventMsg);
                                }

                                // Empty final response: no tool calls and no visible content
                                // (e.g. a reasoning model that wrote its whole answer into the
                                // reasoning channel and left the content channel empty). Loop
                                // back to reasoning with a synthetic reminder.
                                if (!MessageUtils.hasToolCalls(eventMsg)) {
                                    log.warn(
                                            "Final response has no visible content (empty reply),"
                                                    + " model: {}, iter: {}",
                                            model.getModelName(),
                                            iter);
                                    state.contextMutable().add(buildEmptyResponseReminder());
                                }

                                // Continue to acting
                                // 未完成 → 检查中断后进入行动阶段
                                return checkInterrupted().then(acting(iter));
                            })
                    .switchIfEmpty(
                            Mono.defer(
                                    () -> {
                                        // No message was produced
                                        // 模型未产生任何消息
                                        return Mono.justOrEmpty((Msg) null);
                                    }));
        }

        /**
         * Stream fine-grained {@link AgentEvent}s from a model call during reasoning.
         *
         * <p>Emits: {@link ModelCallStartEvent} → block start/delta/end events → {@link
         * ModelCallEndEvent}. The provided {@link ReasoningContext} is used to accumulate chunks
         * (for building the final {@link Msg}) and to notify legacy {@link Hook}s.
         *
         * @param context   reasoning context for chunk accumulation
         * @param messages  the messages to send to the model
         * @param tools     the tool schemas available
         * @param options   generation options
         * @return event stream from a single model call
         */
        /**
         * 推理期间单次模型调用的细粒度事件流。
         *
         * <p>发射顺序：{@link ModelCallStartEvent} → 块的 start/delta/end 事件
         * → {@link ModelCallEndEvent}。{@link ReasoningContext} 用于累积 chunk
         * （构建最终 {@link Msg}）并通知遗留 {@link Hook}。
         * 外层再包一层 onModelCall 中间件链，每个事件同步 publishEvent 外发。
         */
        Flux<AgentEvent> reasoningStream(
                ReasoningContext context,
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {

            Function<ModelCallInput, Flux<AgentEvent>> modelCallCore =
                    mci -> modelCallStream(context, mci, true);

            StringBuilder transformedText = new StringBuilder();
            AtomicBoolean sawTransformedTextDelta = new AtomicBoolean(false);
            return MiddlewareChain.build(
                            middlewaresAt(MiddlewareBase.ExtensionPoint.ON_MODEL_CALL),
                            ReActAgent.this,
                            rc,
                            MiddlewareBase::onModelCall,
                            modelCallCore)
                    .apply(new ModelCallInput(messages, tools, options, modelForCall()))
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent textDelta) {
                                    sawTransformedTextDelta.set(true);
                                    if (textDelta.getDelta() != null) {
                                        transformedText.append(textDelta.getDelta());
                                    }
                                }
                            })
                    .doOnTerminate(
                            () -> {
                                if (sawTransformedTextDelta.get()
                                        || !context.getAccumulatedText().isEmpty()) {
                                    context.replaceAccumulatedText(transformedText.toString());
                                }
                            });
        }

        /**
         * 模型调用事件流的实际产生者：
         * ModelCallStartEvent → 逐 chunk 处理（累积进 ReasoningContext、
         * 通知遗留 ReasoningChunk 钩子、生成块级 start/delta 事件）
         * → flushAll 收尾未关闭的块 → ModelCallEndEvent。
         * 每个 chunk 前都执行一次中断检查（流式可中断）。
         */
        private Flux<AgentEvent> modelCallStream(
                ReasoningContext context, ModelCallInput mci, boolean withToolEvents) {

            String replyId = UUID.randomUUID().toString().replace("-", "");
            // 块生命周期状态机：跟踪 text/thinking/toolCall 块的开启与闭合
            ModelCallBlockLifecycle blockLifecycle = new ModelCallBlockLifecycle(replyId);

            Flux<AgentEvent> modelEvents =
                    mci.model().stream(mci.messages(), mci.tools(), mci.options())
                            // 每个 chunk 前检查中断信号
                            .concatMap(chunk -> checkInterrupted().thenReturn(chunk))
                            .concatMap(
                                    chunk ->
                                            Flux.deferContextual(
                                                    parentCtx -> {
                                                        // chunk 累积进推理上下文，
                                                        // 派生的消息通知遗留 ReasoningChunk 钩子
                                                        List<Msg> chunkMsgs =
                                                                context.processChunk(chunk);
                                                        for (Msg msg : chunkMsgs) {
                                                            hookDispatcher
                                                                    .fireReasoningChunk(
                                                                            msg,
                                                                            context,
                                                                            mci.model()
                                                                                    .getModelName())
                                                                    // 透传父级 Reactor Context，
                                                                    // 防止钩子内部订阅断链
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        }

                                                        // 按 chunk 中的内容块生成对应事件
                                                        List<AgentEvent> events = new ArrayList<>();
                                                        for (ContentBlock block :
                                                                chunk.getContent()) {
                                                            emitBlockEvents(
                                                                    block,
                                                                    context,
                                                                    blockLifecycle,
                                                                    withToolEvents,
                                                                    events);
                                                        }
                                                        return Flux.fromIterable(events);
                                                    }));

            // 收尾：冲刷未闭合的块事件 + ModelCallEndEvent（携带用量统计）
            Flux<AgentEvent> endEvents =
                    Flux.defer(
                            () -> {
                                List<AgentEvent> events = new ArrayList<>();
                                blockLifecycle.flushAll(events);
                                events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                                return Flux.fromIterable(events);
                            });

            return Flux.concat(Flux.just(new ModelCallStartEvent(replyId)), modelEvents, endEvents);
        }

        /**
         * 按内容块类型生成流式事件：文本/思考/工具调用各自的 start（幂等开启）
         * 与 delta 事件；块结束由 flushAll 统一补发 end 事件。
         */
        private void emitBlockEvents(
                ContentBlock block,
                ReasoningContext context,
                ModelCallBlockLifecycle blockLifecycle,
                boolean withToolEvents,
                List<AgentEvent> events) {

            if (block instanceof TextBlock tb) {
                blockLifecycle.startText(events);
                if (tb.getText() != null && !tb.getText().isEmpty()) {
                    events.add(
                            new TextBlockDeltaEvent(
                                    blockLifecycle.replyId,
                                    blockLifecycle.currentTextBlockId(),
                                    tb.getText()));
                }
            } else if (block instanceof ThinkingBlock tb) {
                blockLifecycle.startThinking(events);
                if (tb.getThinking() != null && !tb.getThinking().isEmpty()) {
                    events.add(
                            new ThinkingBlockDeltaEvent(
                                    blockLifecycle.replyId,
                                    blockLifecycle.currentThinkingBlockId(),
                                    tb.getThinking()));
                }
            } else if (withToolEvents && block instanceof ToolUseBlock tub) {
                String toolId = resolveToolCallId(tub, context);
                String toolName = tub.getName();
                blockLifecycle.startToolCall(toolId, toolName, events);
                if (tub.getContent() != null && !tub.getContent().isEmpty()) {
                    events.add(
                            new ToolCallDeltaEvent(
                                    blockLifecycle.replyId,
                                    toolId != null ? toolId : "",
                                    toolName,
                                    tub.getContent()));
                }
            } else if (withToolEvents
                    && block instanceof ToolResultBlock trb
                    && trb.isServerTool()) {
                blockLifecycle.flushServerToolCall(trb.getId(), events);
                events.addAll(serverToolResultEvents(trb, blockLifecycle.replyId));
            }
        }

        /**
         * Builds the complete tool-result lifecycle for a provider-executed tool result.
         *
         * <p>Only the server-tool marker is attached to events; the provider-specific raw result
         * remains on the final message and is not duplicated into the event stream.
         */
        private List<AgentEvent> serverToolResultEvents(ToolResultBlock result, String replyId) {
            String toolId = result.getId();
            String toolName = result.getName();
            Map<String, Object> eventMetadata = Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true);

            List<AgentEvent> events = new ArrayList<>();
            events.add(
                    new ToolResultStartEvent(replyId, toolId, toolName)
                            .withMetadata(eventMetadata));

            for (ContentBlock block : result.getOutput()) {
                if (block instanceof TextBlock tb) {
                    events.add(
                            new ToolResultTextDeltaEvent(replyId, toolId, toolName, tb.getText())
                                    .withMetadata(eventMetadata));
                } else {
                    events.add(
                            new ToolResultDataDeltaEvent(replyId, toolId, toolName, block)
                                    .withMetadata(eventMetadata));
                }
            }

            events.add(
                    new ToolResultEndEvent(
                                    replyId, toolId, toolName, determineToolResultState(result))
                            .withMetadata(eventMetadata));

            return events;
        }

        /**
         * Tracks block lifecycle within one model-call subscription.
         *
         * <p>The model stream is consumed through {@code concatMap}, but the state holders keep the
         * previous thread-safe shape because model providers may deliver chunk content
         * unpredictably. Each contiguous text or thinking segment receives its own block ID so its
         * start, delta, and end events can be correlated independently.
         */
        /**
         * 单次模型调用订阅内的块生命周期状态机。
         *
         * <p>模型流经 {@code concatMap} 串行消费，但状态持有者仍保持线程安全形态，
         * 因为模型提供方推送 chunk 的顺序不可预测。该助手只在冲刷挂起的
         * end 事件时改变状态，不改变块标识或事件负载。
         * 规则：开启文本块前先关闭思考块；开启工具调用前关闭所有已开启的块；
         * "__" 前缀的内部工具不发 start 事件。
         */
        private final class ModelCallBlockLifecycle {
            private final String replyId;

            /** 文本块是否已发过 start 事件（CAS 保证幂等）。 */
            private final AtomicBoolean textStarted = new AtomicBoolean(false);

            private final AtomicLong textSegmentSequence = new AtomicLong(0);
            private final AtomicReference<String> currentTextBlockId = new AtomicReference<>();

            /** 思考块是否已发过 start 事件（CAS 保证幂等）。 */
            private final AtomicBoolean thinkingStarted = new AtomicBoolean(false);

            private final AtomicLong thinkingSegmentSequence = new AtomicLong(0);
            private final AtomicReference<String> currentThinkingBlockId = new AtomicReference<>();

            /** 已发 start 事件的工具调用：id → 工具名。 */
            private final Map<String, String> startedToolCalls = new ConcurrentHashMap<>();

            private ModelCallBlockLifecycle(String replyId) {
                this.replyId = replyId;
            }

            /** 开启文本块（先冲刷思考块的 end 事件）。 */
            private void startText(List<AgentEvent> events) {
                flushThinking(events);
                if (textStarted.compareAndSet(false, true)) {
                    long segment = textSegmentSequence.incrementAndGet();
                    String blockId = segment == 1 ? "text" : "text-" + segment;
                    currentTextBlockId.set(blockId);
                    events.add(new TextBlockStartEvent(replyId, blockId));
                }
            }

            private String currentTextBlockId() {
                return currentTextBlockId.get();
            }

            /** 开启思考块（幂等）。 */
            private void startThinking(List<AgentEvent> events) {
                if (thinkingStarted.compareAndSet(false, true)) {
                    long segment = thinkingSegmentSequence.incrementAndGet();
                    String blockId = segment == 1 ? "thinking" : "thinking-" + segment;
                    currentThinkingBlockId.set(blockId);
                    events.add(new ThinkingBlockStartEvent(replyId, blockId));
                }
            }

            private String currentThinkingBlockId() {
                return currentThinkingBlockId.get();
            }

            /** 开启工具调用块：先冲刷所有已开启的块，"__" 前缀内部工具不对外发事件。 */
            private void startToolCall(String toolId, String toolName, List<AgentEvent> events) {
                if (toolId == null || startedToolCalls.containsKey(toolId)) {
                    return;
                }
                flushText(events);
                flushThinking(events);
                flushAllToolCalls(events);
                boolean visibleTool = toolName != null && !toolName.startsWith("__");
                if (visibleTool && startedToolCalls.putIfAbsent(toolId, toolName) == null) {
                    events.add(new ToolCallStartEvent(replyId, toolId, toolName));
                }
            }

            private void flushServerToolCall(String toolId, List<AgentEvent> events) {
                String toolName = startedToolCalls.remove(toolId);
                if (toolName != null) {
                    events.add(new ToolCallEndEvent(replyId, toolId, toolName));
                }
            }

            /** 若文本块已开启则补发 end 事件。 */
            private void flushText(List<AgentEvent> events) {
                if (textStarted.compareAndSet(true, false)) {
                    String blockId = currentTextBlockId.getAndSet(null);
                    events.add(new TextBlockEndEvent(replyId, blockId));
                }
            }

            /** 若思考块已开启则补发 end 事件。 */
            private void flushThinking(List<AgentEvent> events) {
                if (thinkingStarted.compareAndSet(true, false)) {
                    String blockId = currentThinkingBlockId.getAndSet(null);
                    events.add(new ThinkingBlockEndEvent(replyId, blockId));
                }
            }

            /** 为所有已开启的工具调用补发 end 事件并清空。 */
            private void flushAllToolCalls(List<AgentEvent> events) {
                for (Map.Entry<String, String> tc : startedToolCalls.entrySet()) {
                    events.add(new ToolCallEndEvent(replyId, tc.getKey(), tc.getValue()));
                }
                startedToolCalls.clear();
            }

            /** 调用结束时统一冲刷所有未闭合块。 */
            private void flushAll(List<AgentEvent> events) {
                flushText(events);
                flushThinking(events);
                flushAllToolCalls(events);
            }
        }

        /** 解析工具调用 ID：chunk 中缺失时回退到累积器中的完整工具调用。 */
        private String resolveToolCallId(ToolUseBlock tub, ReasoningContext context) {
            if (tub.getId() != null && !tub.getId().isEmpty()) {
                return tub.getId();
            }
            ToolUseBlock accumulated = context.getAccumulatedToolCall(null);
            return accumulated != null ? accumulated.getId() : null;
        }

        /**
         * Execute the acting phase.
         *
         * <p>This method executes only pending tools (those without results in context),
         * notifies hooks for successful tool results, and decides whether to continue iteration
         * or return (HITL stop, suspended tools, or structured output).
         *
         * <p>For tools that throw {@link io.agentscope.core.tool.ToolSuspendException}:
         * <ul>
         *   <li>The exception is caught by Toolkit and converted to a pending ToolResultBlock</li>
         *   <li>Successful results are stored in context, pending results are not</li>
         *   <li>Returns Msg with {@link GenerateReason#TOOL_SUSPENDED} containing suspended ToolUseBlocks</li>
         * </ul>
         *
         * @param iter Current iteration number
         * @return Mono containing the final result message
         */
        /**
         * 执行行动阶段 —— ReAct 循环的"行动"半边。
         *
         * <p>流程：extractPendingToolCalls 提取待执行工具（空则直接进下一轮推理）
         * → PreActing 钩子（可改写工具调用列表）→ onActing 中间件链包裹 actingStream
         * → 中间件发 RequestStopEvent 则带原因立即返回 → 结果分流：
         * 成功结果逐个过 PostActing 钩子（可 stopAgent），
         * 挂起（suspended）结果返回 TOOL_SUSPENDED 消息，
         * 全部完成后同步工具集状态并递归 executeIteration(iter+1)。
         *
         * <p>抛出 {@link io.agentscope.core.tool.ToolSuspendException} 的工具：
         * 异常被 Toolkit 转为 pending 状态的 ToolResultBlock，成功结果入库、
         * pending 结果不入库，最终返回携带挂起 ToolUseBlock 的
         * {@link GenerateReason#TOOL_SUSPENDED} 消息。
         *
         * @param iter 当前迭代轮次
         * @return 包含最终结果消息的 Mono
         */
        private Mono<Msg> acting(int iter) {
            List<ToolUseBlock> pendingToolCalls =
                    MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
            List<ToolUseBlock> roundToolCalls = extractRecentToolCalls();

            if (pendingToolCalls.isEmpty()) {
                List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();
                if (!recentToolCalls.isEmpty()
                        && MessageUtils.allToolCallsDenied(
                                state.contextMutable(), recentToolCalls)) {
                    return emitAllToolsDeniedThroughMiddleware(recentToolCalls, iter);
                }
                return executeIteration(iter + 1);
            }

            String replyId = UUID.randomUUID().toString().replace("-", "");
            // 工具执行结果的收集器（由 actingStream 填充）
            AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder =
                    new AtomicReference<>(List.of());

            // 跟踪 onActing 中间件发出的停止请求
            AtomicReference<RequestStopEvent> actingStopRequested = new AtomicReference<>();
            return hookDispatcher
                    .firePreActing(pendingToolCalls, toolkit)
                    .flatMap(
                            toolCalls -> {
                                // 行动核心：权限门控 + 工具执行事件流
                                Function<ActingInput, Flux<AgentEvent>> actingCore =
                                        ai -> actingStream(ai.toolCalls(), replyId, resultHolder);
                                // 用 onActing 中间件链包裹（计划模式强制、
                                // 异步工具超时等在此介入）
                                Flux<AgentEvent> stream =
                                        MiddlewareChain.build(
                                                        middlewaresAt(
                                                                MiddlewareBase.ExtensionPoint
                                                                        .ON_ACTING),
                                                        ReActAgent.this,
                                                        rc,
                                                        MiddlewareBase::onActing,
                                                        actingCore)
                                                .apply(new ActingInput(toolCalls));
                                return stream.doOnNext(
                                                ev -> {
                                                    if (ev instanceof RequestStopEvent rs) {
                                                        actingStopRequested.compareAndSet(null, rs);
                                                    }
                                                })
                                        .then(Mono.defer(() -> Mono.just(resultHolder.get())));
                            })
                    .flatMap(
                            results -> {
                                // Middleware requested stop during acting — return immediately with
                                // the requested GenerateReason, preserving any results already
                                // collected.
                                // 中间件在行动期间请求停止 —— 携带请求的 GenerateReason
                                // 立即返回，保留已收集的结果。
                                RequestStopEvent rs = actingStopRequested.get();
                                if (rs != null) {
                                    if (rs.getGenerateReason()
                                            == GenerateReason.PERMISSION_ASKING) {
                                        Msg lastAssistant =
                                                MessageUtils.lastAssistantMessage(
                                                        state.contextMutable());
                                        if (lastAssistant != null) {
                                            return Mono.just(
                                                    lastAssistant.withGenerateReason(
                                                            GenerateReason.PERMISSION_ASKING));
                                        }
                                    }
                                    Msg stopMsg = buildStopMsg(results, rs.getGenerateReason());
                                    return Mono.just(stopMsg);
                                }
                                // 结果分流：正常完成 vs 挂起（suspended）
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> successPairs =
                                        results.stream()
                                                .filter(e -> !e.getValue().isSuspended())
                                                .toList();
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs =
                                        results.stream()
                                                .filter(e -> e.getValue().isSuspended())
                                                .toList();

                                if (successPairs.isEmpty()) {
                                    if (!pendingPairs.isEmpty()) {
                                        // 全部挂起 → 返回 TOOL_SUSPENDED 消息
                                        return Mono.just(buildSuspendedMsg(pendingPairs));
                                    }
                                    return executeIteration(iter + 1);
                                }

                                boolean returnDirect =
                                        pendingToolCalls.size() == roundToolCalls.size()
                                                && pendingPairs.isEmpty()
                                                && !successPairs.isEmpty()
                                                && successPairs.stream()
                                                        .allMatch(this::isReturnDirectToolCall);

                                // Fire the hook and persist every executed tool's result, then
                                // let the earliest stopAgent() win: takeUntil would cancel the
                                // tail's hooks and tool_result writes, leaving dangling
                                // tool_use for tools that already ran (cf.
                                // synthesizeErrorResultsForPendingToolCalls on the interrupt
                                // path). Past a stop, results persist verbatim — the turn ends
                                // via ACTING_STOP_REQUESTED, not the returnDirect short-circuit.
                                AtomicBoolean stopSeen = new AtomicBoolean();
                                // 成功结果逐个过 PostActing 钩子
                                return Flux.fromIterable(successPairs)
                                        .concatMap(
                                                e ->
                                                        notifyPostActingHook(
                                                                e, returnDirect && !stopSeen.get()))
                                        .doOnNext(
                                                e -> {
                                                    if (e.isStopRequested()) {
                                                        stopSeen.set(true);
                                                    }
                                                })
                                        .collectList()
                                        .map(this::earliestStopOrLast)
                                        .flatMap(
                                                event -> {
                                                    // 钩子调用了 stopAgent() → HITL 停止
                                                    if (event.isStopRequested()) {
                                                        return Mono.just(
                                                                event.getToolResultMsg()
                                                                        .withGenerateReason(
                                                                                GenerateReason
                                                                                        .ACTING_STOP_REQUESTED));
                                                    }

                                                    if (returnDirect) {
                                                        return Mono.just(
                                                                finalizeReturnDirect(
                                                                        successPairs, replyId));
                                                    }

                                                    // 存在挂起工具 → 返回挂起消息等待外部恢复
                                                    if (!pendingPairs.isEmpty()) {
                                                        return Mono.just(
                                                                buildSuspendedMsg(pendingPairs));
                                                    }

                                                    return executeIteration(iter + 1);
                                                });
                            });
        }

        /**
         * Stream fine-grained {@link AgentEvent}s from tool execution during the acting phase.
         *
         * <p>Emits: {@link ToolResultStartEvent} → delta events → {@link ToolResultEndEvent}
         * for each tool call. The provided {@code resultHolder} is populated with the execution
         * results so the caller can process them afterward.
         *
         * @param toolCalls    the tool calls to execute
         * @param replyId      the reply identifier for event correlation
         * @param resultHolder populated with tool execution results on completion
         * @return event stream from tool execution
         */
        /**
         * 行动阶段工具执行的细粒度事件流。
         *
         * <p>先做权限门控（evaluatePermissions）：ALLOWED 的工具立即执行；
         * ASKING 的工具把智能体暂停（发射 RequireUserConfirmEvent +
         * RequestStopEvent(PERMISSION_ASKING)，等待第二次 call 携带确认结果恢复）；
         * DENIED 的工具写入 DENIED 结果。无挂起项时执行 runToolBatch。
         * 每个工具调用发射 ToolResultStart → delta → ToolResultEnd 事件，
         * 执行结果写入 {@code resultHolder} 供调用方后续处理。
         *
         * @param toolCalls    待执行的工具调用
         * @param replyId      事件关联用的回复标识
         * @param resultHolder 完成时填入工具执行结果
         * @return 工具执行事件流
         */
        Flux<AgentEvent> actingStream(
                List<ToolUseBlock> toolCalls,
                String replyId,
                AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder) {

            return evaluatePermissions(toolCalls)
                    .flatMapMany(
                            gate -> {
                                List<ToolUseBlock> pending = gate.pendingAsk();
                                Set<String> autoDenied = gate.autoDeniedIds();

                                // Mark ToolUseBlock.state in context for every gated tool. ALLOWED
                                // calls run immediately; ASKING calls cause the agent to pause and
                                // return; DENIED calls get DENIED ToolResultBlocks written below.
                                // 为每个受门控的工具更新上下文中 ToolUseBlock 的状态。
                                // ALLOWED 立即执行；ASKING 使智能体暂停并返回；
                                // DENIED 在下方写入 DENIED 结果块。
                                Map<String, ToolCallState> stateUpdates = new HashMap<>();
                                for (ToolUseBlock tc : toolCalls) {
                                    if (autoDenied.contains(tc.getId())) {
                                        // DENIED tools don't need a state change — they'll get a
                                        // DENIED ToolResultBlock and won't reappear in pending.
                                        // DENIED 工具无需改状态 —— 它们会拿到 DENIED 结果块，
                                        // 不会再次出现在 pending 中。
                                        continue;
                                    }
                                    stateUpdates.put(
                                            tc.getId(),
                                            pending.stream()
                                                            .anyMatch(
                                                                    p ->
                                                                            p.getId()
                                                                                    .equals(
                                                                                            tc
                                                                                                    .getId()))
                                                    ? ToolCallState.ASKING
                                                    : ToolCallState.ALLOWED);
                                }
                                updateToolCallStates(stateUpdates);

                                if (pending.isEmpty()) {
                                    // 无 ASKING 项 → 直接执行工具批次
                                    return runToolBatch(
                                            toolCalls, autoDenied, replyId, resultHolder);
                                }

                                // Permission HITL: surface the pending tool calls, persist any
                                // auto-denied results so the second call can identify which ones
                                // still need confirmation, then signal stop via RequestStopEvent.
                                // The agent's acting() will see the RequestStopEvent, set the
                                // GenerateReason to PERMISSION_ASKING, and return.
                                // 权限人在回路：暴露待确认的工具调用，持久化自动拒绝的结果
                                // （使第二次调用能识别哪些仍需确认），
                                // 然后用 RequestStopEvent 发停止信号。
                                // acting() 看到 RequestStopEvent 后会以
                                // PERMISSION_ASKING 原因返回。
                                if (!autoDenied.isEmpty()) {
                                    // Write DENIED results in-place so they aren't re-evaluated on
                                    // resume.
                                    // 就地写入 DENIED 结果，恢复时不会重复评估。
                                    writeAutoDeniedResults(toolCalls, autoDenied);
                                }
                                // resultHolder may be inspected by the caller after stream
                                // completion;
                                // initialise it to empty since no successful execution happened.
                                // 流结束后调用方可能检查 resultHolder；
                                // 此处没有成功执行，初始化为空列表。
                                resultHolder.set(List.of());
                                persistPendingRequestReplyId(
                                        Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, replyId);
                                Flux<AgentEvent> autoDeniedEvents =
                                        Flux.fromIterable(toolCalls)
                                                .filter(tc -> autoDenied.contains(tc.getId()))
                                                .concatMapIterable(
                                                        tc ->
                                                                deniedToolResultEvents(
                                                                        tc,
                                                                        replyId,
                                                                        PERMISSION_DENIED_BY_RULES));
                                return autoDeniedEvents.concatWith(
                                        // 先发用户确认请求事件，再发停止信号
                                        Flux.just(
                                                new RequireUserConfirmEvent(replyId, pending),
                                                new RequestStopEvent(
                                                        "permission asking",
                                                        GenerateReason.PERMISSION_ASKING)));
                            })
                    .doOnNext(this::publishEvent);
        }

        /**
         * Synthesise DENIED ToolResultBlocks for tools that were rejected by deny rules and append
         * them to context so the conversation reflects the rejection (and resume doesn't see them
         * as pending).
         */
        /**
         * 为被 deny 规则拒绝的工具合成 DENIED 结果块并追加进上下文，
         * 使会话体现拒绝事实（恢复时也不会把它们当作 pending）。
         */
        private void writeAutoDeniedResults(List<ToolUseBlock> toolCalls, Set<String> deniedIds) {
            for (ToolUseBlock tc : toolCalls) {
                if (!deniedIds.contains(tc.getId())) {
                    continue;
                }
                ToolResultBlock denied =
                        ToolResultBlock.text(PERMISSION_DENIED_BY_RULES)
                                .withIdAndName(tc.getId(), tc.getName())
                                .withState(ToolResultState.DENIED);
                Msg deniedMsg = ToolResultMessageBuilder.buildToolResultMsg(denied, tc, getName());
                state.contextMutable().add(deniedMsg);
            }
        }

        /**
         * Execute the given tool calls, synthesising DENIED results for any tool whose id is in
         * {@code deniedIds} (skipping toolkit invocation for those) and running the rest through
         * {@link #executeToolCalls(List)}. The combined results are written to {@code resultHolder}
         * and emitted as a stream of fine-grained {@link AgentEvent}s.
         */
        /**
         * 执行工具批次：id 在 {@code deniedIds} 中的工具直接合成 DENIED 结果
         * （跳过工具集调用），其余交给 {@link #executeToolCalls(List)} 执行。
         * 合并后的结果写入 {@code resultHolder}，并以细粒度事件流对外发射：
         * 每个工具先发 ToolResultStartEvent，执行期间经 chunk 回调发 delta 事件，
         * 完成后发 ToolResultEndEvent。内部用裸 subscribe 桥接工具执行，
         * 因此必须捕获并透传父级 Reactor Context（否则子智能体事件转发断链）。
         */
        private Flux<AgentEvent> runToolBatch(
                List<ToolUseBlock> toolCalls,
                Set<String> deniedIds,
                String replyId,
                AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder) {

            List<Map.Entry<ToolUseBlock, ToolResultBlock>> deniedEntries = new ArrayList<>();
            List<ToolUseBlock> approved = new ArrayList<>();
            for (ToolUseBlock tc : toolCalls) {
                if (deniedIds.contains(tc.getId())) {
                    ToolResultBlock denied =
                            ToolResultBlock.text(PERMISSION_DENIED_BY_RULES)
                                    .withIdAndName(tc.getId(), tc.getName())
                                    .withState(ToolResultState.DENIED);
                    deniedEntries.add(Map.entry(tc, denied));
                } else {
                    approved.add(tc);
                }
            }

            Flux<AgentEvent> deniedEvents =
                    Flux.fromIterable(deniedEntries)
                            .concatMapIterable(
                                    entry ->
                                            deniedToolResultEvents(
                                                    entry.getKey(),
                                                    replyId,
                                                    PERMISSION_DENIED_BY_RULES));

            if (approved.isEmpty()) {
                resultHolder.set(deniedEntries);
                return deniedEvents;
            }

            // Capture the parent Reactor Context (set by AgentBase.createEventStream, which puts
            // the
            // SubagentEventBus there) so we can forward it into the inner executeToolCalls
            // subscribe.
            // Without this, the bare .subscribe() below detaches from the upstream chain and tools
            // like AgentSpawnTool see an empty ContextView, breaking child-event forwarding.
            // 捕获父级 Reactor Context（由 AgentBase.createEventStream 设置，
            // 其中放着 SubagentEventBus），以便转发进内部 executeToolCalls 的订阅。
            // 否则下方的裸 .subscribe() 会与上游链脱离，AgentSpawnTool 等工具
            // 看到的是空 ContextView，子事件转发就此断链。
            Flux<AgentEvent> approvedEvents =
                    Flux.<AgentEvent>deferContextual(
                            parentCtx ->
                                    Flux.<AgentEvent>create(
                                            sink -> {
                                                for (ToolUseBlock tool : approved) {
                                                    sink.next(
                                                            new ToolResultStartEvent(
                                                                    replyId,
                                                                    tool.getId(),
                                                                    tool.getName()));
                                                }

                                                Set<String> chunkedToolIds =
                                                        ConcurrentHashMap.newKeySet();

                                                BiConsumer<ToolUseBlock, ToolResultBlock>
                                                        internalChunkCallback =
                                                                (toolUse, chunk) -> {
                                                                    if (chunk.getOutput() != null
                                                                            && !chunk.getOutput()
                                                                                    .isEmpty()) {
                                                                        chunkedToolIds.add(
                                                                                toolUse.getId());
                                                                        for (ContentBlock block :
                                                                                chunk.getOutput()) {
                                                                            if (block
                                                                                    instanceof
                                                                                    TextBlock tb) {
                                                                                sink.next(
                                                                                        new ToolResultTextDeltaEvent(
                                                                                                        replyId,
                                                                                                        toolUse
                                                                                                                .getId(),
                                                                                                        toolUse
                                                                                                                .getName(),
                                                                                                        tb
                                                                                                                .getText())
                                                                                                .withMetadata(
                                                                                                        chunk
                                                                                                                .getMetadata()));
                                                                            } else {
                                                                                sink.next(
                                                                                        new ToolResultDataDeltaEvent(
                                                                                                        replyId,
                                                                                                        toolUse
                                                                                                                .getId(),
                                                                                                        toolUse
                                                                                                                .getName(),
                                                                                                        block)
                                                                                                .withMetadata(
                                                                                                        chunk
                                                                                                                .getMetadata()));
                                                                            }
                                                                        }
                                                                    }
                                                                    hookDispatcher
                                                                            .fireActingChunk(
                                                                                    toolUse, chunk,
                                                                                    toolkit)
                                                                            .contextWrite(
                                                                                    ctx ->
                                                                                            ctx
                                                                                                    .putAll(
                                                                                                            parentCtx))
                                                                            .subscribe();
                                                                };

                                                Disposable toolCallsDisposable =
                                                        executeToolCalls(
                                                                        approved,
                                                                        internalChunkCallback)
                                                                .contextWrite(
                                                                        ctx -> {
                                                                            Context merged =
                                                                                    ctx.putAll(
                                                                                            parentCtx);
                                                                            if (!merged.hasKey(
                                                                                            SubagentEventBus
                                                                                                    .CONTEXT_KEY)
                                                                                    && !merged
                                                                                            .hasKey(
                                                                                                    AgentEventEmitter
                                                                                                            .CONTEXT_KEY)) {
                                                                                if (eventSink
                                                                                        != null) {
                                                                                    merged =
                                                                                            merged
                                                                                                    .put(
                                                                                                            AgentEventEmitter
                                                                                                                    .CONTEXT_KEY,
                                                                                                            (AgentEventEmitter)
                                                                                                                    eventSink
                                                                                                                            ::next);
                                                                                } else if (externalEventEmitter
                                                                                        != null) {
                                                                                    merged =
                                                                                            merged
                                                                                                    .put(
                                                                                                            AgentEventEmitter
                                                                                                                    .CONTEXT_KEY,
                                                                                                            externalEventEmitter);
                                                                                }
                                                                            }
                                                                            return merged;
                                                                        })
                                                                .subscribe(
                                                                        results -> {
                                                                            List<
                                                                                            Map
                                                                                                            .Entry<
                                                                                                    ToolUseBlock,
                                                                                                    ToolResultBlock>>
                                                                                    merged =
                                                                                            new ArrayList<>(
                                                                                                    deniedEntries);
                                                                            merged.addAll(results);
                                                                            resultHolder.set(
                                                                                    merged);
                                                                            for (Map.Entry<
                                                                                            ToolUseBlock,
                                                                                            ToolResultBlock>
                                                                                    entry :
                                                                                            results) {
                                                                                emitToolResultDelta(
                                                                                        sink,
                                                                                        replyId,
                                                                                        entry,
                                                                                        chunkedToolIds);
                                                                                ToolResultState
                                                                                        state =
                                                                                                determineToolResultState(
                                                                                                        entry
                                                                                                                .getValue());
                                                                                sink.next(
                                                                                        new ToolResultEndEvent(
                                                                                                        replyId,
                                                                                                        entry.getKey()
                                                                                                                .getId(),
                                                                                                        entry.getKey()
                                                                                                                .getName(),
                                                                                                        state)
                                                                                                .withMetadata(
                                                                                                        entry.getValue()
                                                                                                                .getMetadata()));
                                                                            }
                                                                            List<ToolUseBlock>
                                                                                    suspendedCalls =
                                                                                            getSuspendedToolCalls(
                                                                                                    results);
                                                                            if (!suspendedCalls
                                                                                    .isEmpty()) {
                                                                                persistPendingRequestReplyId(
                                                                                        Msg
                                                                                                .METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID,
                                                                                        replyId);
                                                                                sink.next(
                                                                                        new RequireExternalExecutionEvent(
                                                                                                replyId,
                                                                                                suspendedCalls));
                                                                            }
                                                                            sink.complete();
                                                                        },
                                                                        sink::error);
                                                sink.onCancel(toolCallsDisposable);
                                            }));

            return deniedEvents.concatWith(approvedEvents);
        }

        /**
         * Outcome of running every {@link ToolBase} call through the {@link PermissionEngine}.
         *
         * @param pendingAsk tool calls that require user confirmation before execution.
         * @param autoDeniedIds ids of tool calls whose decision was {@code DENY}; the agent loop
         *     synthesises denied results for them without invoking the tool.
         */
        /**
         * 权限门控结果：{@code pendingAsk} 是执行前需要用户确认的工具调用；
         * {@code autoDeniedIds} 是被 DENY 裁决的工具调用 ID，
         * 循环会为它们合成拒绝结果而不会真正调用工具。
         */
        private record PermissionGate(List<ToolUseBlock> pendingAsk, Set<String> autoDeniedIds) {}

        /**
         * Run every tool call through the permission gate.
         *
         * <p>When the agent's {@link io.agentscope.core.permission.PermissionContextState} is trivial
         * (default mode, no rules, no working directories — i.e. the user has not opted into the
         * permission system) we fall back to the lightweight pre-2.0 path: the tool's own
         * {@link ToolBase#checkPermissions} ASK gates a confirmation, anything else is approved.
         *
         * <p>Otherwise we engage the full {@link PermissionEngine} pipeline so deny/ask/allow rules
         * and EXPLORE/ACCEPT_EDITS/BYPASS/DONT_ASK modes are honoured before execution. Legacy
         * {@link AgentTool}s that do not extend {@link ToolBase} always pass through approved.
         */
        /**
         * 把每个工具调用送进权限门控。
         *
         * <p>当智能体的 {@link io.agentscope.core.permission.PermissionContextState}
         * 是平凡态（默认模式、无规则、无工作目录——即用户未启用权限系统）时，
         * 回退到 2.0 之前的轻量路径：工具自身 {@link ToolBase#checkPermissions}
         * 返回 ASK 才触发确认，其余一律放行。
         *
         * <p>否则走完整的 {@link PermissionEngine} 管线，使 deny/ask/allow 规则与
         * EXPLORE/ACCEPT_EDITS/BYPASS/DONT_ASK 模式在执行前都得到尊重。
         * 未继承 {@link ToolBase} 的遗留 {@link AgentTool} 总是直接放行。
         */
        private Mono<PermissionGate> evaluatePermissions(List<ToolUseBlock> toolCalls) {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Mono.just(new PermissionGate(List.of(), Set.of()));
            }
            // 权限上下文非平凡态 → 启用完整引擎
            boolean useEngine = !state.getPermissionContext().isTrivial();
            return Flux.fromIterable(toolCalls)
                    .concatMap(use -> evaluateOne(use, useEngine))
                    .collectList()
                    .map(
                            verdicts -> {
                                // 汇总裁决：DENY 收集 ID，ASK 收集待确认项，其余放行
                                List<ToolUseBlock> pending = new ArrayList<>();
                                Set<String> denied = new HashSet<>();
                                for (PermissionVerdict v : verdicts) {
                                    switch (v.behavior()) {
                                        case DENY -> denied.add(v.use().getId());
                                        case ASK -> pending.add(v.use());
                                        case ALLOW, PASSTHROUGH -> {
                                            // auto-approved; falls through to execution
                                            // 自动批准，继续执行
                                        }
                                    }
                                }
                                return new PermissionGate(pending, denied);
                            });
        }

        /** 对单个工具调用做权限裁决（完整引擎路径或轻量路径）。 */
        private Mono<PermissionVerdict> evaluateOne(ToolUseBlock use, boolean useEngine) {
            // Tools already promoted to ALLOWED by user confirmation skip the engine entirely.
            // 已被用户确认提升为 ALLOWED 的工具完全跳过引擎。
            if (use.getState() == ToolCallState.ALLOWED) {
                return Mono.just(new PermissionVerdict(use, PermissionBehavior.ALLOW));
            }
            AgentTool tool = activeToolkit.getTool(use.getName(), toolRequestConfig);
            if (!(tool instanceof ToolBase tb)) {
                // 遗留 AgentTool（非 ToolBase）总是放行
                return Mono.just(new PermissionVerdict(use, PermissionBehavior.ALLOW));
            }
            Map<String, Object> input = use.getInput() == null ? Map.of() : use.getInput();
            if (useEngine) {
                // 完整引擎路径：决策为 null 时视为 ASK
                return permissionEngine
                        .checkPermission(tb, input)
                        .map(
                                decision ->
                                        new PermissionVerdict(
                                                use,
                                                decision == null
                                                        ? PermissionBehavior.ASK
                                                        : decision.getBehavior()));
            }
            // 轻量路径：仅工具显式 ASK 才拦截，DENY 受尊重，其余放行
            return tb.checkPermissions(input, state.getPermissionContext())
                    .map(
                            decision -> {
                                if (decision == null) {
                                    return new PermissionVerdict(use, PermissionBehavior.ALLOW);
                                }
                                // In the legacy lightweight path only an explicit ASK from the tool
                                // gates execution; PASSTHROUGH and ALLOW both run, DENY is
                                // honoured.
                                // 遗留轻量路径下只有工具显式 ASK 才拦截执行；
                                // PASSTHROUGH 和 ALLOW 都执行，DENY 受尊重。
                                return switch (decision.getBehavior()) {
                                    case ASK -> new PermissionVerdict(use, PermissionBehavior.ASK);
                                    case DENY ->
                                            new PermissionVerdict(use, PermissionBehavior.DENY);
                                    default -> new PermissionVerdict(use, PermissionBehavior.ALLOW);
                                };
                            });
        }

        /** 单工具的权限裁决：工具调用块 + 行为（ALLOW/ASK/DENY/PASSTHROUGH）。 */
        private record PermissionVerdict(ToolUseBlock use, PermissionBehavior behavior) {}

        private List<ToolUseBlock> getSuspendedToolCalls(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> results) {
            return results.stream()
                    .filter(entry -> entry.getValue().isSuspended())
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /**
         * Emit delta events for tool results that were NOT already streamed via the chunk
         * callback. For non-streaming tools the chunk callback is never invoked, so the
         * event stream would otherwise contain only START and END with no content.
         */
        /**
         * 为尚未经 chunk 回调流式输出的工具结果补发 delta 事件。
         * 非流式工具不会触发 chunk 回调，否则事件流里就只有 START 和 END 而无内容。
         */
        private void emitToolResultDelta(
                FluxSink<AgentEvent> sink,
                String replyId,
                Map.Entry<ToolUseBlock, ToolResultBlock> entry,
                Set<String> chunkedToolIds) {
            String toolId = entry.getKey().getId();
            String toolName = entry.getKey().getName();
            ToolResultBlock toolResult = entry.getValue();
            if (chunkedToolIds.contains(toolId)) {
                return;
            }
            List<ContentBlock> output = toolResult.getOutput();
            if (output == null || output.isEmpty()) {
                return;
            }
            for (ContentBlock block : output) {
                if (block instanceof TextBlock tb) {
                    sink.next(
                            new ToolResultTextDeltaEvent(replyId, toolId, toolName, tb.getText())
                                    .withMetadata(toolResult.getMetadata()));
                } else {
                    sink.next(
                            new ToolResultDataDeltaEvent(replyId, toolId, toolName, block)
                                    .withMetadata(toolResult.getMetadata()));
                }
            }
        }

        /**
         * 推导工具结果的最终状态：挂起 → RUNNING；已有明确非 RUNNING 状态则沿用；
         * 文本以 "[ERROR]" 开头 → ERROR；否则 SUCCESS。
         */
        private ToolResultState determineToolResultState(ToolResultBlock result) {
            if (result.isSuspended()) {
                return ToolResultState.RUNNING;
            }
            if (result.getState() != null && result.getState() != ToolResultState.RUNNING) {
                return result.getState();
            }
            if (result.getOutput() != null
                    && result.getOutput().stream()
                            .anyMatch(
                                    b ->
                                            b instanceof TextBlock tb
                                                    && tb.getText() != null
                                                    && tb.getText().startsWith("[ERROR]"))) {
                return ToolResultState.ERROR;
            }
            return ToolResultState.SUCCESS;
        }

        /**
         * Build a message containing suspended tool calls for user execution.
         *
         * <p>The message contains both the ToolUseBlocks and corresponding pending ToolResultBlocks
         * for the suspended tools.
         *
         * @param pendingPairs List of (ToolUseBlock, pending ToolResultBlock) pairs
         * @return Msg with GenerateReason.TOOL_SUSPENDED
         */
        /**
         * 构建携带挂起工具调用的消息，交给用户侧执行。
         * 消息同时包含挂起工具的 ToolUseBlock 与对应的 pending ToolResultBlock，
         * 返回原因标记为 {@link GenerateReason#TOOL_SUSPENDED}。
         */
        private Msg buildSuspendedMsg(List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs) {
            List<ContentBlock> content = new ArrayList<>();
            for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : pendingPairs) {
                content.add(pair.getKey());
                content.add(pair.getValue());
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .generateReason(GenerateReason.TOOL_SUSPENDED)
                    .build();
        }

        /**
         * Build a stop-acknowledgement Msg for middleware-requested stops during acting. Preserves
         * any already-collected tool results so the caller sees partial progress.
         */
        /**
         * 为"中间件在行动期间请求停止"构建停止确认消息。
         * 保留已收集的工具结果，让调用方能看到部分进展。
         */
        private Msg buildStopMsg(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> results, GenerateReason reason) {
            List<ContentBlock> content = new ArrayList<>();
            if (results != null) {
                for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : results) {
                    content.add(pair.getKey());
                    content.add(pair.getValue());
                }
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .generateReason(reason)
                    .build();
        }

        /**
         * Execute tool calls and return paired results.
         *
         * <p>If tool execution fails (timeout, error, etc.), this method generates error tool results
         * for all pending tool calls instead of propagating the error. This ensures the agent can
         * continue processing and the model receives proper error feedback.
         *
         * @param toolCalls The list of tool calls (potentially modified by PreActingEvent hooks)
         * @return Mono containing list of (ToolUseBlock, ToolResultBlock) pairs
         */
        /**
         * 执行工具调用并返回配对结果。
         *
         * <p>工具执行失败（超时、错误等）时不向外传播错误，而是为所有待执行
         * 工具调用生成错误结果，确保智能体能继续处理、模型能收到明确的错误反馈。
         * 例外：{@link InterruptedException}（中断信号）会原样传播给停机策略。
         *
         * @param toolCalls 工具调用列表（可能已被 PreActingEvent 钩子改写）
         * @return 包含 (ToolUseBlock, ToolResultBlock) 配对列表的 Mono
         */
        private Mono<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> executeToolCalls(
                List<ToolUseBlock> toolCalls,
                BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
            return dispatchToolCalls(toolCalls, internalChunkCallback)
                    .map(
                            results ->
                                    // 按原始顺序把工具调用与结果配对
                                    IntStream.range(0, toolCalls.size())
                                            .mapToObj(
                                                    i ->
                                                            Map.entry(
                                                                    toolCalls.get(i),
                                                                    results.get(i)))
                                            .toList())
                    .onErrorResume(
                            Exception.class,
                            error -> {
                                // Preserve interruption signal for agent stop policy
                                // 保留中断信号，交给智能体停止策略处理
                                if (error instanceof InterruptedException) {
                                    return Mono.error(error);
                                }
                                // Generate error tool results for all pending tool calls.
                                // Only catch Exception subclasses; critical JVM errors
                                // (e.g. OutOfMemoryError) are left to propagate.
                                // 为所有待执行工具调用生成错误结果。
                                // 只捕获 Exception 子类；严重 JVM 错误
                                // （如 OutOfMemoryError）继续向外传播。
                                String errorMsg = ExceptionUtils.getErrorMessage(error);
                                log.error(
                                        "Tool execution failed, generating error results for {}"
                                                + " tool calls",
                                        toolCalls.size(),
                                        error);
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> errorResults =
                                        toolCalls.stream()
                                                .map(
                                                        toolCall -> {
                                                            ToolResultBlock errorResult =
                                                                    ToolResultBlock.error(
                                                                            toolCall.getId(),
                                                                            "Tool execution failed:"
                                                                                    + " "
                                                                                    + errorMsg);
                                                            return Map.entry(toolCall, errorResult);
                                                        })
                                                .toList();
                                return Mono.just(errorResults);
                            });
        }

        /**
         * Resolve tool results for {@code toolCalls}, in the same order. When this call is a
         * structured-output call, any {@code generate_response} invocations are executed against
         * the per-call {@link #soTool} (never registered on the shared toolkit); all other tools go
         * through {@link Toolkit#callTools}.
         */
        /**
         * 按原始顺序解析各工具调用的结果。本次调用是结构化输出调用时，
         * {@code generate_response} 调用路由到按调用的 {@link #soTool}
         * （它从未注册进共享工具集）；其余工具走 {@link Toolkit#callTools}。
         */
        private Mono<List<ToolResultBlock>> dispatchToolCalls(
                List<ToolUseBlock> toolCalls,
                BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
            boolean hasStructured =
                    soTool != null
                            && toolCalls.stream()
                                    .anyMatch(t -> STRUCTURED_OUTPUT_TOOL_NAME.equals(t.getName()));
            if (!hasStructured) {
                return activeToolkit.callTools(
                        toolCalls,
                        toolExecutionConfig,
                        ReActAgent.this,
                        buildMergedRuntimeContext(rc),
                        toolRequestConfig,
                        internalChunkCallback);
            }

            List<ToolUseBlock> regular =
                    toolCalls.stream()
                            .filter(t -> !STRUCTURED_OUTPUT_TOOL_NAME.equals(t.getName()))
                            .toList();
            Mono<Map<String, ToolResultBlock>> regularResults =
                    regular.isEmpty()
                            ? Mono.just(Map.of())
                            : activeToolkit
                                    .callTools(
                                            regular,
                                            toolExecutionConfig,
                                            ReActAgent.this,
                                            buildMergedRuntimeContext(rc),
                                            toolRequestConfig,
                                            internalChunkCallback)
                                    .map(
                                            list -> {
                                                Map<String, ToolResultBlock> byId = new HashMap<>();
                                                for (int i = 0; i < regular.size(); i++) {
                                                    byId.put(regular.get(i).getId(), list.get(i));
                                                }
                                                return byId;
                                            });
            return regularResults.flatMap(
                    byId ->
                            Flux.fromIterable(toolCalls)
                                    .concatMap(
                                            use -> {
                                                if (STRUCTURED_OUTPUT_TOOL_NAME.equals(
                                                        use.getName())) {
                                                    return executeStructuredTool(use);
                                                }
                                                ToolResultBlock result = byId.get(use.getId());
                                                if (result == null) {
                                                    return Mono.just(
                                                            ToolResultBlock.error(
                                                                    use.getId(),
                                                                    "Internal error: missing tool"
                                                                            + " result for '"
                                                                            + use.getName()
                                                                            + "'"));
                                                }
                                                return Mono.just(result);
                                            })
                                    .collectList());
        }

        /**
         * Execute the per-call {@code generate_response} tool for a single tool call, mirroring the
         * schema validation the executor performs for registered tools.
         */
        /**
         * 执行按调用的 {@code generate_response} 工具：先做与注册工具相同的
         * Schema 参数校验，校验失败返回错误结果；成功则调用合成工具并把
         * 结果块补上原工具调用的 id/name。
         */
        private Mono<ToolResultBlock> executeStructuredTool(ToolUseBlock use) {
            String validationError =
                    ToolValidator.validateInput(use.getContent(), soTool.getParameters());
            if (validationError != null) {
                return Mono.just(
                        ToolResultBlock.error(
                                use.getId(),
                                "Parameter validation failed for tool '"
                                        + STRUCTURED_OUTPUT_TOOL_NAME
                                        + "': "
                                        + validationError));
            }
            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(use.getInput() == null ? Map.of() : use.getInput())
                            .agent(ReActAgent.this)
                            .runtimeContext(buildMergedRuntimeContext(rc))
                            .build();
            return soTool.callAsync(param).map(rb -> rb.withIdAndName(use.getId(), use.getName()));
        }

        /**
         * Fire PostActingEvent for a single tool result, build message and add to context.
         *
         * <p>When {@code returnDirect} is {@code true} (the whole batch is being returned
         * directly) and the hook did not stop, the tool result written to context is replaced
         * with the placeholder sentence; the full result is kept on the hook event for
         * auditing and later lifted into the closing assistant message.
         */
        /**
         * 为单个工具结果触发 PostActingEvent：先推导并固化最终状态
         * （避免历史查询永远显示 RUNNING），构建结果消息，触发钩子
         * （钩子可 stopAgent / 改写消息）；若该结果是成功的 generate_response
         * 则标记 soCompleted 并主动 stopAgent（结构化输出自然终止）；
         * 最后把结果消息加入上下文。
         */
        private Mono<PostActingEvent> notifyPostActingHook(
                Map.Entry<ToolUseBlock, ToolResultBlock> entry, boolean returnDirect) {
            ToolUseBlock toolUse = entry.getKey();
            ToolResultBlock result = entry.getValue();

            // FIX: determine the final state and update ToolResultBlock before
            // adding to contextMutable(), so that history queries via
            // agent.getAgentState(ctx).contextMutable() reflect the correct
            // final state instead of always showing RUNNING.
            // 修复：在加入 contextMutable() 之前先确定最终状态并更新
            // ToolResultBlock，使通过 agent.getAgentState(ctx).contextMutable()
            // 的历史查询反映正确的最终状态，而不是永远显示 RUNNING。
            ToolResultState finalState = determineToolResultState(result);
            ToolResultBlock updatedResult = result.withState(finalState);

            Msg toolMsg =
                    ToolResultMessageBuilder.buildToolResultMsg(updatedResult, toolUse, getName());

            return hookDispatcher
                    .firePostActing(toolUse, updatedResult, toolkit, toolMsg)
                    .doOnNext(
                            e -> {
                                // generate_response 成功 → 结构化输出完成，主动停止智能体
                                if (soTool != null
                                        && STRUCTURED_OUTPUT_TOOL_NAME.equals(toolUse.getName())
                                        && result.getMetadata() != null
                                        && Boolean.TRUE.equals(
                                                result.getMetadata().get("success"))) {
                                    soCompleted = true;
                                    soResultMsg = e.getToolResultMsg();
                                    e.stopAgent();
                                }
                                Msg resultMsg = e.getToolResultMsg();
                                if (returnDirect && !e.isStopRequested()) {
                                    if (e.getToolResult() != updatedResult
                                            || e.getToolResultMsg() != toolMsg) {
                                        log.warn(
                                                "returnDirect: discarding PostActing hook rewrite"
                                                        + " for tool '{}' - apply transformations"
                                                        + " at the tool/converter level"
                                                        + " instead",
                                                toolUse.getName());
                                    }
                                    resultMsg =
                                            buildReturnDirectPlaceholderMsg(toolUse, updatedResult);
                                    log.debug(
                                            "returnDirect: replaced tool result with placeholder"
                                                    + " for tool '{}'",
                                            toolUse.getName());
                                }
                                state.contextMutable().add(resultMsg);
                            });
        }

        /** The earliest stop-requesting event of the batch, or the last event if none stopped. */
        private PostActingEvent earliestStopOrLast(List<PostActingEvent> events) {
            return events.stream()
                    .filter(PostActingEvent::isStopRequested)
                    .findFirst()
                    .orElseGet(() -> events.get(events.size() - 1));
        }

        /**
         * Whether a single tool result may participate in the returnDirect short-circuit: the tool
         * declared {@code returnDirect = true} and its result actually executed successfully.
         * DENIED / ERROR / INTERRUPTED results must never be presented as the final answer.
         */
        private boolean isReturnDirectToolCall(Map.Entry<ToolUseBlock, ToolResultBlock> entry) {
            AgentTool tool = toolkit.getTool(entry.getKey().getName());
            if (tool == null || !tool.isReturnDirect()) {
                return false;
            }
            return determineToolResultState(entry.getValue()) == ToolResultState.SUCCESS;
        }

        /** Builds the placeholder tool_result, replacing only the output while keeping id/name/state. */
        private Msg buildReturnDirectPlaceholderMsg(ToolUseBlock toolUse, ToolResultBlock result) {
            ToolResultBlock placeholder =
                    placeholderResultBlock(toolUse.getId(), toolUse.getName(), result.getState());
            return ToolResultMessageBuilder.buildToolResultMsg(placeholder, toolUse, getName());
        }

        /**
         * The placeholder-shaped {@link ToolResultBlock} persisted in place of a returnDirect
         * tool's real result: same id/name/state, output swapped for the placeholder sentence.
         */
        private ToolResultBlock placeholderResultBlock(
                String id, String name, ToolResultState state) {
            return ToolResultBlock.builder()
                    .id(id)
                    .name(name)
                    .output(TextBlock.builder().text(RETURN_DIRECT_PLACEHOLDER).build())
                    .state(state)
                    .build();
        }

        /**
         * Finalize a returnDirect turn: synthesize the closing assistant message, persist it,
         * emit the closing text events, and log.
         *
         * <p>Shared by the in-framework path ({@code acting}) and the external-resume path
         * ({@code doCallInner}), so both produce identical message, context, return-contract,
         * and event-stream shapes. {@code replyId} correlates the emitted events with the
         * originating reply (the acting replyId, or the suspended external-execution request).
         */
        private Msg finalizeReturnDirect(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs, String replyId) {
            Msg result = buildReturnDirectResultMsg(pairs);
            state.contextMutable().add(result);
            publishReturnDirectTextEvents(result, replyId);
            logReturnDirect(pairs);
            return result;
        }

        /**
         * Emit the standard text-block lifecycle ({@code TextBlockStart/Delta/End}) for the
         * returnDirect closing message, so event-stream consumers (AG-UI, A2A, ...) see the
         * same projection they would for a model-generated final answer.
         *
         * <p>The delta carries {@link AgentEvent#METADATA_GENERATE_REASON} set to {@code
         * TOOL_RETURN_DIRECT} for audit purposes. Non-text blocks (images, data) emit nothing
         * and keep travelling via the Msg-level {@code AgentResultEvent}. Events are published
         * synchronously before the closing message is returned, hence ahead of {@code
         * AgentResultEvent}/{@code AgentEndEvent}.
         */
        private void publishReturnDirectTextEvents(Msg closingMsg, String replyId) {
            String effectiveReplyId =
                    replyId == null || replyId.isEmpty()
                            ? UUID.randomUUID().toString().replace("-", "")
                            : replyId;
            for (TextBlock block : closingMsg.getContentBlocks(TextBlock.class)) {
                String blockId = UUID.randomUUID().toString().replace("-", "");
                publishEvent(new TextBlockStartEvent(effectiveReplyId, blockId));
                publishEvent(
                        new TextBlockDeltaEvent(effectiveReplyId, blockId, block.getText())
                                .withMetadata(
                                        Map.of(
                                                AgentEvent.METADATA_GENERATE_REASON,
                                                GenerateReason.TOOL_RETURN_DIRECT.name())));
                publishEvent(new TextBlockEndEvent(effectiveReplyId, blockId));
            }
        }

        /**
         * Logs the returnDirect short-circuit: {@code info} marks that the tool result(s) were
         * returned directly as the final answer, {@code debug} adds the tool count and names.
         */
        private void logReturnDirect(List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs) {
            List<String> names = pairs.stream().map(e -> e.getKey().getName()).toList();
            log.info(
                    "returnDirect: returning tool result(s) for {} directly as the final answer",
                    String.join(", ", names));
            log.debug("returnDirect: {} tool result(s) from tools {}", pairs.size(), names);
        }

        /**
         * Synthesises the closing assistant message carrying the full tool result, mimicking the
         * final answer the model would otherwise have produced.
         *
         * <p>For multiple tools the output blocks are concatenated in execution order (= {@code
         * pairs} order = the model's tool_calls order) without inlining tool names or adding
         * separator blocks. Just like a model-generated summary, the closing message carries no
         * per-block provenance: consumers correlate blocks to tools (when needed) via block
         * order plus the tool id/name carried by the event stream's tool-result events, never
         * by assuming the turn was short-circuited.
         *
         * <p>A tool whose result contains <em>zero</em> content blocks (possible only via a custom
         * converter returning an empty output list) contributes nothing to the closing message —
         * mirroring how a model summary skips a tool that produced no output — and matches the
         * event stream, which emits no text projection for such results either. An empty-text
         * block (e.g. {@code TextBlock("")} from an MCP tool) is a block and passes through
         * unchanged. Only when the whole batch yields zero blocks is a single {@code "(no
         * output)"} placeholder inserted, keeping the closing message non-empty for providers
         * that reject empty assistant content; callers can tell it apart from a real tool
         * output via {@link GenerateReason#TOOL_RETURN_DIRECT} and the {@code
         * _tool_return_direct} metadata. Tool authors declaring {@code returnDirect} should
         * ensure successful results always carry presentable blocks, so the placeholder stays a
         * defensive fallback rather than an expected outcome.
         *
         * <p>Boundary: the message is assembled from the pre-hook execution results. Rewrites made
         * by the (deprecated, for-removal) PostActing hook are intentionally ignored, as are
         * {@code onModelCall} middleware text transformations, since no model call occurs. Use
         * the tool's result converter (or an {@code AgentTool} decorator) for content
         * transformation/redaction: transformations at the source apply uniformly to both the
         * in-framework and the external-resume path.
         */
        private Msg buildReturnDirectResultMsg(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs) {
            List<ContentBlock> content = new ArrayList<>();
            for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : pairs) {
                content.addAll(pair.getValue().getOutput());
            }
            if (content.isEmpty()) {
                content.add(TextBlock.builder().text("(no output)").build());
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .metadata(Map.of(MessageMetadataKeys.TOOL_RETURN_DIRECT, true))
                    .generateReason(GenerateReason.TOOL_RETURN_DIRECT)
                    .build();
        }

        /**
         * Generate summary when max iterations reached.
         */
        /**
         * 达到最大迭代次数后的总结阶段：
         * <ol>
         *   <li>为仍未完成的 pending 工具调用补"因达到上限被取消"的错误结果；</li>
         *   <li>发射 ExceedMaxItersEvent；</li>
         *   <li>PreSummary 钩子 → 一次无工具调用的总结模型请求（summaryStream）
         *       → PostSummary 钩子，最终消息标记 {@link GenerateReason#MAX_ITERATIONS}。</li>
         * </ol>
         */
        protected Mono<Msg> summarizing() {
            log.debug("Maximum iterations reached. Generating summary...");

            // Handle pending tool calls that were not completed before max iterations
            // 处理达到最大迭代次数前未完成的待执行工具调用
            List<ToolUseBlock> pendingTools =
                    MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
            if (!pendingTools.isEmpty()) {
                log.warn(
                        "Max iterations reached with {} pending tool calls. Adding error results.",
                        pendingTools.size());

                for (ToolUseBlock toolUse : pendingTools) {
                    ToolResultBlock errorResult =
                            ToolResultBlock.error(
                                    toolUse.getId(),
                                    "Tool execution cancelled because maximum iterations limit ("
                                            + maxIters
                                            + ") was reached");

                    Msg errorResultMsg =
                            ToolResultMessageBuilder.buildToolResultMsg(
                                    errorResult, toolUse, getName());
                    state.contextMutable().add(errorResultMsg);
                }
            }

            List<Msg> messageList = prepareSummaryMessages();
            GenerateOptions generateOptions = buildGenerateOptions();
            ReasoningContext context = new ReasoningContext(getName());
            Model summaryModel = modelForCall();
            publishEvent(new ExceedMaxItersEvent("", maxIters, maxIters));

            return hookDispatcher
                    .firePreSummary(
                            messageList,
                            generateOptions,
                            summaryModel.getModelName(),
                            maxIters,
                            systemMsg)
                    .flatMap(
                            preSummaryEvent -> {
                                List<Msg> effectiveMessages =
                                        MessageUtils.prependSystemMessage(
                                                preSummaryEvent.getInputMessages(),
                                                preSummaryEvent.getSystemMessage());
                                GenerateOptions effectiveOptions =
                                        preSummaryEvent.getEffectiveGenerateOptions();

                                return summaryStream(
                                                context,
                                                effectiveMessages,
                                                effectiveOptions,
                                                summaryModel)
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                Mono.justOrEmpty(
                                                                        context
                                                                                .buildFinalMessage())))
                                        .flatMap(
                                                msg ->
                                                        hookDispatcher
                                                                .firePostSummary(
                                                                        msg,
                                                                        effectiveOptions,
                                                                        summaryModel.getModelName())
                                                                .map(
                                                                        postEvent -> {
                                                                            Msg finalMsg =
                                                                                    postEvent
                                                                                            .getSummaryMessage()
                                                                                            .withGenerateReason(
                                                                                                    GenerateReason
                                                                                                            .MAX_ITERATIONS);
                                                                            state.contextMutable()
                                                                                    .add(finalMsg);
                                                                            return finalMsg;
                                                                        }));
                            })
                    .onErrorResume(this::handleSummaryError);
        }

        /**
         * Stream fine-grained {@link AgentEvent}s from a model call during summarization.
         *
         * <p>Structurally identical to {@link #reasoningStream} but notifies summary-specific
         * hooks (SummaryChunkEvent) and does not pass tool schemas to the model.
         *
         * @param context   reasoning context for chunk accumulation
         * @param messages  the messages to send to the model
         * @param options   generation options
         * @param model     the model used for this summary call
         * @return event stream from the summary model call
         */
        /**
         * 总结阶段的模型流式调用事件管道。
         *
         * <p>结构与 {@code reasoningStream} 相同，但：不传工具 Schema 给模型
         * （{@code tools} 为 null），钩子通知走 SummaryChunkEvent。
         * 同样经 onModelCall 中间件链包裹，事件逐个 publishEvent 外发。
         */
        Flux<AgentEvent> summaryStream(
                ReasoningContext context,
                List<Msg> messages,
                GenerateOptions options,
                Model model) {

            Function<ModelCallInput, Flux<AgentEvent>> summaryModelCallCore =
                    mci -> summaryModelCallStream(context, mci, options);

            return MiddlewareChain.build(
                            middlewaresAt(MiddlewareBase.ExtensionPoint.ON_MODEL_CALL),
                            ReActAgent.this,
                            rc,
                            MiddlewareBase::onModelCall,
                            summaryModelCallCore)
                    .apply(new ModelCallInput(messages, List.of(), options, model))
                    .doOnNext(this::publishEvent);
        }

        /**
         * 总结阶段模型流的核心处理（未被中间件包裹的裸实现）：
         * 每个 chunk 先过中断检查 → 累积进 ReasoningContext 并触发
         * SummaryChunk 钩子（透传父 Reactor Context）→ 按 text/thinking 块
         * 生成 Delta 事件；流结束后 flushAll 补发块结束事件 + ModelCallEndEvent。
         * 注意：总结阶段不处理 ToolUseBlock（无工具调用）。
         */
        private Flux<AgentEvent> summaryModelCallStream(
                ReasoningContext context, ModelCallInput mci, GenerateOptions hookOptions) {

            String replyId = UUID.randomUUID().toString().replace("-", "");
            ModelCallBlockLifecycle blockLifecycle = new ModelCallBlockLifecycle(replyId);

            Flux<AgentEvent> modelEvents =
                    mci.model().stream(mci.messages(), mci.tools(), mci.options())
                            .concatMap(chunk -> checkInterrupted().thenReturn(chunk))
                            .concatMap(
                                    chunk ->
                                            Flux.deferContextual(
                                                    parentCtx -> {
                                                        List<Msg> chunkMsgs =
                                                                context.processChunk(chunk);
                                                        for (Msg msg : chunkMsgs) {
                                                            hookDispatcher
                                                                    .fireSummaryChunk(
                                                                            msg,
                                                                            context,
                                                                            hookOptions,
                                                                            mci.model()
                                                                                    .getModelName())
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        }

                                                        List<AgentEvent> events = new ArrayList<>();
                                                        for (ContentBlock block :
                                                                chunk.getContent()) {
                                                            if (block instanceof TextBlock tb) {
                                                                blockLifecycle.startText(events);
                                                                if (tb.getText() != null
                                                                        && !tb.getText()
                                                                                .isEmpty()) {
                                                                    events.add(
                                                                            new TextBlockDeltaEvent(
                                                                                    blockLifecycle
                                                                                            .replyId,
                                                                                    blockLifecycle
                                                                                            .currentTextBlockId(),
                                                                                    tb.getText()));
                                                                }
                                                            } else if (block
                                                                    instanceof ThinkingBlock tb) {
                                                                blockLifecycle.startThinking(
                                                                        events);
                                                                if (tb.getThinking() != null
                                                                        && !tb.getThinking()
                                                                                .isEmpty()) {
                                                                    events.add(
                                                                            new ThinkingBlockDeltaEvent(
                                                                                    blockLifecycle
                                                                                            .replyId,
                                                                                    blockLifecycle
                                                                                            .currentThinkingBlockId(),
                                                                                    tb
                                                                                            .getThinking()));
                                                                }
                                                            }
                                                        }
                                                        return Flux.fromIterable(events);
                                                    }));

            Flux<AgentEvent> endEvents =
                    Flux.defer(
                            () -> {
                                List<AgentEvent> events = new ArrayList<>();
                                blockLifecycle.flushAll(events);
                                events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                                return Flux.fromIterable(events);
                            });

            return Flux.concat(Flux.just(new ModelCallStartEvent(replyId)), modelEvents, endEvents);
        }

        /**
         * 组装总结请求的消息列表：当前上下文副本 + 追加一条"已达最大迭代次数，
         * 请直接总结当前状况"的用户消息（不写回上下文，只用于本次请求）。
         */
        private List<Msg> prepareSummaryMessages() {
            List<Msg> messageList = new ArrayList<>(state.contextMutable());
            messageList.add(
                    UserMessage.builder()
                            .name("user")
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "You have failed to generate response within"
                                                            + " the maximum iterations. Now respond"
                                                            + " directly by summarizing the current"
                                                            + " situation.")
                                            .build())
                            .build());
            return messageList;
        }

        /**
         * 总结阶段的错误兜底：中断异常继续上抛；其他错误记日志并生成一条
         * "达到最大迭代 + 总结失败原因"的助手消息写入上下文，保证调用有结果返回。
         */
        private Mono<Msg> handleSummaryError(Throwable error) {
            if (error instanceof InterruptedException) {
                return Mono.error(error);
            }
            log.error("Error generating summary", error);
            Msg errorMsg =
                    AssistantMessage.builder()
                            .name(getName())
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    String.format(
                                                            "Maximum iterations (%d) reached. Error"
                                                                    + " generating summary: %s",
                                                            maxIters, error.getMessage()))
                                            .build())
                            .metadata(Map.of(MessageMetadataKeys.SUMMARY_FAILED, true))
                            .generateReason(GenerateReason.MAX_ITERATIONS)
                            .build();
            state.contextMutable().add(errorMsg);
            return Mono.just(errorMsg);
        }

        // ==================== Helper Methods ====================

        /**
         * Check if the ReAct loop should terminate.
         *
         * <p>A response with unfinished or local tool calls continues to the acting phase.
         * Completed server tool calls do not require local execution. A tool-free response
         * finishes only when it carries visible content: empty or thinking-only responses (the
         * entire answer in the reasoning channel) loop back to reasoning, bounded by {@code
         * maxIters}, instead of silently ending the agent with an empty reply.
         *
         * @param msg The reasoning message
         * @return true if should finish, false if should continue to acting
         */
        /**
         * 判断 ReAct 循环是否应当终止：推理消息为空或不含任何工具调用即结束。
         * 即使工具名不存在也继续进入 acting，由 ToolExecutor 返回
         * "工具未找到"错误让模型自行纠错。
         */
        private boolean isFinished(Msg msg) {
            if (msg == null) {
                return true;
            }
            List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
            if (toolCalls.isEmpty()) {
                return msg.getContentBlocks(TextBlock.class).stream()
                        .anyMatch(
                                textBlock ->
                                        textBlock.getText() != null
                                                && !textBlock.getText().isBlank());
            }

            // Server tool calls are executed by the provider and their results arrive in the
            // same assistant message: if every tool call is a server tool with its result
            // present, there is nothing left to act on. A server tool call without a result
            // (e.g. pause_turn) keeps the loop running so the conversation goes back to the
            // provider to continue.
            Set<String> inlineResultIds = MessageUtils.inlineServerToolResultIds(msg);
            return toolCalls.stream()
                    .allMatch(
                            toolCall ->
                                    toolCall.isServerTool()
                                            && inlineResultIds.contains(toolCall.getId()));
        }

        /**
         * Build the synthetic {@code system} reminder injected before looping back to reasoning
         * after an empty final response.
         *
         * <p>Unlike {@code TaskReminderMiddleware}'s transient todo reminder, this reminder is
         * written into the agent context and persists in the session state (like {@code
         * SubagentsMiddleware}'s task-delivery reminder): the corrective signal stays visible to
         * later turns. Accumulation is bounded by {@code maxIters} per call.
         */
        private static Msg buildEmptyResponseReminder() {
            return Msg.builder()
                    .role(MsgRole.USER)
                    .name("system")
                    .content(TextBlock.builder().text(EMPTY_RESPONSE_REMINDER_TEXT).build())
                    .metadata(
                            Map.of(
                                    Msg.METADATA_SYNTHETIC,
                                    true,
                                    Msg.METADATA_REMINDER_KIND,
                                    "empty_response"))
                    .build();
        }

        /**
         * Build an onActing middleware chain that emits {@link AllToolsDeniedEvent}, giving
         * middlewares the opportunity to emit a {@link RequestStopEvent}. If a stop is requested,
         * the agent returns immediately; otherwise it continues to the next iteration.
         */
        private Mono<Msg> emitAllToolsDeniedThroughMiddleware(
                List<ToolUseBlock> deniedToolCalls, int iter) {
            AtomicReference<RequestStopEvent> stopRef = new AtomicReference<>();

            Function<ActingInput, Flux<AgentEvent>> core =
                    ai -> Flux.just(new AllToolsDeniedEvent(ai.toolCalls()));

            Flux<AgentEvent> stream =
                    MiddlewareChain.build(
                                    middlewaresAt(MiddlewareBase.ExtensionPoint.ON_ACTING),
                                    ReActAgent.this,
                                    rc,
                                    MiddlewareBase::onActing,
                                    core)
                            .apply(new ActingInput(deniedToolCalls));

            return stream.doOnNext(
                            ev -> {
                                if (ev instanceof RequestStopEvent rs) {
                                    stopRef.compareAndSet(null, rs);
                                }
                            })
                    .then(
                            Mono.defer(
                                    () -> {
                                        RequestStopEvent rs = stopRef.get();
                                        if (rs != null) {
                                            Msg lastMsg =
                                                    MessageUtils.lastAssistantMessage(
                                                            state.contextMutable());
                                            GenerateReason reason =
                                                    rs.getGenerateReason() != null
                                                            ? rs.getGenerateReason()
                                                            : GenerateReason.ALL_TOOLS_DENIED;
                                            if (lastMsg != null) {
                                                return Mono.just(
                                                        lastMsg.withGenerateReason(reason));
                                            }
                                            return Mono.just(
                                                    Msg.builder()
                                                            .role(MsgRole.ASSISTANT)
                                                            .textContent("")
                                                            .generateReason(reason)
                                                            .build());
                                        }
                                        return executeIteration(iter + 1);
                                    }));
        }

        /**
         * Extract tool calls from the most recent assistant message.
         */
        /** 从最近一条（本智能体的）助手消息中提取全部工具调用。 */
        private List<ToolUseBlock> extractRecentToolCalls() {
            return MessageUtils.extractRecentToolCalls(state.contextMutable(), getName());
        }

        // ==================== Tool call state helpers (Permission HITL) ====================

        /**
         * Locate the last assistant Msg in context and replace the {@code state} of every
         * {@link ToolUseBlock} whose id matches the given map's key. Mirrors Python's
         * {@code _update_tool_call_state} but operates in bulk to minimise list rebuilds.
         */
        /**
         * 批量更新工具调用状态：定位上下文中最后一条助手消息，
         * 把 id 命中的 {@link ToolUseBlock} 的 {@code state} 替换为新值。
         * 对齐 Python 版 {@code _update_tool_call_state}，批量操作以减少列表重建。
         */
        private void updateToolCallStates(Map<String, ToolCallState> updates) {
            if (updates == null || updates.isEmpty()) {
                return;
            }
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null) {
                return;
            }
            Map<String, ToolUseBlock> replacements = new HashMap<>();
            for (ToolUseBlock toolUse : lastAssistant.getContentBlocks(ToolUseBlock.class)) {
                ToolCallState newState = updates.get(toolUse.getId());
                if (newState != null) {
                    replacements.put(toolUse.getId(), toolUse.withState(newState));
                }
            }
            MessageUtils.replaceToolUseBlocks(state.contextMutable(), replacements);
        }

        /** The ToolUseBlocks in the last assistant Msg that are in ASKING state (HITL pending). */
        /** 最后一条助手消息中处于 ASKING 状态（HITL 待确认）的工具调用列表。 */
        private List<ToolUseBlock> askingToolCalls() {
            Msg last = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (last == null) {
                return List.of();
            }
            return last.getContent().stream()
                    .filter(
                            b ->
                                    b instanceof ToolUseBlock t
                                            && t.getState() == ToolCallState.ASKING)
                    .map(ToolUseBlock.class::cast)
                    .toList();
        }
    }

    /**
     * 组装每次推理的基础 GenerateOptions：构建期配置的 generateOptions
     * 与 modelExecutionConfig（重试/超时）合并；两者都未配置时返回空选项。
     */
    protected GenerateOptions buildGenerateOptions() {
        // Start with user-configured generateOptions if available
        // 以用户构建期配置的 generateOptions 为基础
        GenerateOptions baseOptions = generateOptions;

        // Layer the agent-level retry budget underneath explicit per-call settings.
        if (modelConfig != null) {
            GenerateOptions retryBudgetOptions =
                    GenerateOptions.builder()
                            .executionConfig(
                                    ExecutionConfig.builder()
                                            .maxAttempts(modelConfig.maxRetries())
                                            .build())
                            .build();
            baseOptions = GenerateOptions.mergeOptions(baseOptions, retryBudgetOptions);
        }

        // If modelExecutionConfig is set, merge it into the options
        // 若配置了 modelExecutionConfig，合并进选项
        if (modelExecutionConfig != null) {
            GenerateOptions execConfigOptions =
                    GenerateOptions.builder().executionConfig(modelExecutionConfig).build();
            baseOptions = GenerateOptions.mergeOptions(execConfigOptions, baseOptions);
        }

        return baseOptions != null ? baseOptions : GenerateOptions.builder().build();
    }

    private Model modelForCall() {
        Model fallbackModel = modelConfig.fallbackModel();
        if (fallbackModel == null) {
            return model;
        }
        FailoverListener failoverListener = modelConfig.failoverListener();

        AtomicReference<Model> activeModel = new AtomicReference<>(model);
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                // Route synchronous model setup failures through the same first-signal fallback.
                Flux<ChatResponse> primaryFlux =
                        Flux.defer(() -> model.stream(messages, tools, options));
                return primaryFlux.switchOnFirst(
                        (signal, flux) -> {
                            if (signal.isOnError()) {
                                Throwable error = signal.getThrowable();
                                activeModel.set(fallbackModel);
                                log.warn(
                                        "Primary model {} failed, switching to fallback {}",
                                        model.getModelName(),
                                        fallbackModel.getModelName(),
                                        error);
                                notifyFailover(failoverListener, model, error);
                                return fallbackModel.stream(messages, tools, options);
                            }
                            return flux;
                        });
            }

            @Override
            public String getModelName() {
                return activeModel.get().getModelName();
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return activeModel.get().supportsNativeStructuredOutput();
            }

            @Override
            public int getContextWindowSize() {
                return activeModel.get().getContextWindowSize();
            }
        };
    }

    /**
     * Notifies the failover listener at the switch site. An exception from the listener is
     * contained here: it is logged and does not affect the switch or the fallback call that
     * follows.
     */
    private static void notifyFailover(FailoverListener listener, Model primary, Throwable error) {
        if (listener == null) {
            return;
        }
        try {
            listener.onFailover(primary, error);
        } catch (Exception e) {
            log.warn("Failover listener threw an exception, ignoring", e);
        }
    }

    /**
     * 中断处理钩子（AgentBase 模板方法）：按中断来源分流——
     * <ul>
     *   <li>{@link InterruptSource#SYSTEM}（优雅停机）：先触发状态持久化，
     *       再抛 {@link AgentShuttingDownException} 使调用以错误终止；</li>
     *   <li>用户中断：向上下文写入一条"我注意到你中断了我"的恢复消息并正常返回，
     *       让会话保持连贯，下次调用可无缝继续。</li>
     * </ul>
     * 注意：中断来源从本调用会话级的 interruptControl 解析，
     * 而不是 AgentBase 传来的实例级信号（并发下实例级不按调用隔离）。
     */
    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // Resolve the source from this execution's own interrupt signal.
                    InterruptSource source = scope.interruption.getSource();
                    if (source == InterruptSource.SYSTEM) {
                        String requestId =
                                (String) cv.getOrDefault(AgentBase.SHUTDOWN_REQUEST_ID_KEY, null);
                        shutdownManager.saveOnInterruptObserved(requestId);
                        return Mono.error(new AgentShuttingDownException());
                    }
                    // Reconcile any pending tool calls before appending the recovery message.
                    // The tool_use written during reasoning would otherwise be persisted with no
                    // matching tool result, leaving the AgentState inconsistent for the next run.
                    scope.synthesizeErrorResultsForPendingToolCalls();
                    String recoveryText =
                            "I noticed that you have interrupted me. What can I do for you?";
                    Msg recoveryMsg =
                            AssistantMessage.builder()
                                    .name(getName())
                                    .content(TextBlock.builder().text(recoveryText).build())
                                    .generateReason(GenerateReason.INTERRUPTED)
                                    .build();
                    scope.state.contextMutable().add(recoveryMsg);
                    return saveStateToSession(scope)
                            .thenReturn(recoveryMsg)
                            .onErrorResume(
                                    e -> {
                                        log.warn(
                                                "Failed to save agent state after user interrupt",
                                                e);
                                        return Mono.just(recoveryMsg);
                                    });
                });
    }

    /**
     * observe() 的落地实现：把外部消息直接追加进当前（默认槽位的）上下文，
     * 供下次推理可见；不触发任何模型调用。
     */
    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null) {
            getAgentState().contextMutable().add(msg);
        }
        return Mono.empty();
    }

    // ==================== Getters ====================
    // ==================== 访问器 ====================

    /** Returns this agent's toolkit; request-specific views do not mutate its registry. */
    /** Returns this agent's toolkit (a per-instance deep copy made at build time). */
    /** 返回本智能体的工具集（build 时按实例深拷贝，实例间隔离）。 */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /** 返回构建期配置的原始系统提示词（下发前还会经中间件加工）。 */
    public String getSysPrompt() {
        return sysPrompt;
    }

    /** 返回主模型实例。 */
    public Model getModel() {
        return model;
    }

    /** 返回 ReAct 循环最大迭代次数。 */
    public int getMaxIters() {
        return maxIters;
    }

    /**
     * Gets the configured generation options for this agent.
     *
     * @return The generation options, or null if not configured
     */
    /**
     * 获取本智能体配置的生成选项。
     *
     * @return 生成选项，未配置时返回 null
     */
    public GenerateOptions getGenerateOptions() {
        return generateOptions;
    }

    /**
     * @deprecated Use {@link #getAgentState(RuntimeContext)} or
     *     {@link #getAgentState(String, String)} with explicit session identity.
     *     This method delegates to the default session slot.
     */
    /**
     * @deprecated 请改用带显式会话身份的 {@link #getAgentState(RuntimeContext)}
     *     或 {@link #getAgentState(String, String)}。
     *     本方法委托到默认会话槽位。
     */
    @Deprecated
    @Override
    public AgentState getAgentState() {
        return getAgentState(null, defaultSessionId);
    }

    /**
     * Returns the {@link AgentState} for the session identified by the given {@link RuntimeContext}.
     *
     * @param ctx the runtime context (uses {@code getUserId()} and {@code getSessionId()})
     * @return the agent state for the identified session
     */
    /**
     * 返回指定 {@link RuntimeContext} 所标识会话的 {@link AgentState}
     * （取其 userId / sessionId，缺失 sessionId 时用默认会话兜底）。
     */
    public AgentState getAgentState(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        return getAgentState(uid, sid);
    }

    /**
     * Returns the {@link AgentState} for the given {@code (userId, sessionId)} slot, loading it
     * from the configured {@link AgentStateStore} on first access and caching it for subsequent
     * calls within this JVM.
     *
     * <p>Note: in distributed deployments the authoritative reload happens at call start inside
     * {@code activateSlotForContext}. This method returns the locally cached instance (suitable
     * for the "get → mutate → save" pattern used by admin APIs and tests).
     */
    /**
     * 返回指定 {@code (userId, sessionId)} 槽位的 {@link AgentState}：
     * 首次访问时从 {@link AgentStateStore} 加载并在本 JVM 内缓存。
     *
     * <p>注意：分布式部署下权威的重载发生在调用开始时的
     * {@code activateSlotForContext} 内。本方法返回本地缓存实例
     * （适用于管理 API / 测试的"取 → 改 → 存"模式）。
     */
    public AgentState getAgentState(String userId, String sessionId) {
        String slot = slotKey(userId, sessionId);
        return stateCache.computeIfAbsent(
                slot,
                k -> {
                    VersionedState<AgentState> versioned =
                            loadOrCreateAgentStateForSlot(
                                    stateStore,
                                    userId,
                                    sessionId,
                                    initialPermissionContext,
                                    getAgentId(),
                                    initialActiveToolGroups);
                    slotVersions.put(slot, versioned.version());
                    return versioned.value();
                });
    }

    /**
     * Clears all locally cached per-session state and permission engines.
     *
     * <p>This only releases the in-memory cache held by this agent. It does not delete or modify
     * any state in the configured {@link AgentStateStore}. A later {@code getAgentState(...)} or
     * {@code call(...)} reloads the session from the store when one is configured.
     *
     * <p>Call this after the agent's in-flight calls have completed. Existing callers that still
     * hold an {@link AgentState} reference, or an in-flight call that captured one, may continue
     * to retain that object until those references are released.
     */
    public void clearStateCache() {
        stateCache.clear();
        permissionEngineCache.clear();
        slotVersions.clear();
    }

    /**
     * Clears the locally cached state and permission engine for the session identified by
     * {@code ctx}.
     *
     * @param ctx runtime context identifying the session; a missing session id uses the default
     *     session id
     */
    public void clearStateCache(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        clearStateCache(uid, sid);
    }

    /**
     * Clears the locally cached state and permission engine for one {@code (userId, sessionId)}
     * slot.
     *
     * <p>This only releases local references. It does not delete the corresponding state from
     * the configured {@link AgentStateStore}; the next access reloads the persisted state.
     *
     * @param userId user identity for the slot ({@code null} = anonymous / single-tenant)
     * @param sessionId session identity; {@code null} or blank uses the default session id
     */
    public void clearStateCache(String userId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        String slot = slotKey(userId, sid);
        stateCache.remove(slot);
        permissionEngineCache.remove(slot);
        slotVersions.remove(slot);
    }

    /**
     * Clears the model-visible conversation context for the session identified by {@code ctx}.
     *
     * <p>The session identity, permission configuration, tool state, tasks, and plan-mode state
     * are preserved. When an {@link AgentStateStore} is configured and the session has already
     * been persisted, the latest persisted state is reloaded before clearing so only the
     * conversation messages and any compaction summary are removed. The updated state is persisted
     * immediately.
     *
     * <p>If the target session has neither cached state nor persisted state, this method is a
     * no-op.
     *
     * <p>This method does not cancel an in-flight call. Invoke it after the session's current call
     * has completed so that the next call reliably starts with an empty conversation context.
     *
     * @param ctx runtime context identifying the session; a missing session id uses the default
     *     session id
     */
    public void clearContext(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        clearContext(uid, sid);
    }

    /**
     * Clears the model-visible conversation context for one {@code (userId, sessionId)} session.
     *
     * <p>The session keeps the same identity. Unlike creating a new session, this only removes
     * the conversation messages and compaction summary, so permission configuration, tool state,
     * tasks, and plan-mode state remain available. When an {@link AgentStateStore} is configured
     * and the session has already been persisted, the latest persisted state is reloaded before
     * clearing. The updated state is persisted immediately.
     *
     * <p>If the target session has neither cached state nor persisted state, this method is a
     * no-op.
     *
     * <p>This method does not cancel an in-flight call. Invoke it after the session's current call
     * has completed so that the next call reliably starts with an empty conversation context.
     *
     * @param userId user identity for the slot ({@code null} = anonymous / single-tenant)
     * @param sessionId session identity; {@code null} or blank uses the default session id
     */
    public void clearContext(String userId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        String slot = slotKey(userId, sid);
        AgentState state;
        if (stateStore != null) {
            if (stateStore.exists(userId, sid)) {
                VersionedState<AgentState> versioned =
                        loadOrCreateAgentStateForSlot(
                                stateStore,
                                userId,
                                sid,
                                initialPermissionContext,
                                getAgentId(),
                                initialActiveToolGroups);
                state = versioned.value();
                stateCache.put(slot, state);
                slotVersions.put(slot, versioned.version());
            } else {
                state = stateCache.get(slot);
                if (state == null) {
                    return;
                }
            }
        } else {
            state = stateCache.get(slot);
            if (state == null) {
                return;
            }
        }
        state.contextMutable().clear();
        state.setSummary("");
        saveAgentState(userId, sid);
    }

    /**
     * Switches the {@link PermissionMode} for the given {@code (userId, sessionId)} session at
     * runtime and rebuilds that session's cached {@link PermissionEngine} so the change takes
     * effect on the next tool evaluation. The configured rules and working directories are
     * preserved; only the mode changes. The change is persisted so the next {@code call} on that
     * session sees it.
     *
     * <p>Use this to implement a deliberate, user-initiated "bypass permissions" toggle (pass
     * {@link PermissionMode#BYPASS}) or to restore stricter enforcement afterwards (pass
     * {@link PermissionMode#DEFAULT}). {@code BYPASS} disables all rule evaluation, so it should be
     * an explicit, per-session action and is best paired with a sandboxed environment.
     *
     * <p>An in-flight call keeps the engine it started with; the new mode applies to subsequent
     * calls on the slot.
     *
     * @param userId user identity for the slot (may be {@code null})
     * @param sessionId session identity (falls back to the default session id when {@code null})
     * @param mode the permission mode to switch to
     */
    /**
     * 运行时切换指定 {@code (userId, sessionId)} 会话的 {@link PermissionMode}，
     * 并重建该会话缓存的 {@link PermissionEngine}，使变更在下次工具评估时生效。
     * 已配置的规则与工作目录保持不变，仅切换模式；变更会被持久化。
     *
     * <p>用于实现用户主动发起的"跳过权限确认"开关（传 {@link PermissionMode#BYPASS}）
     * 或事后恢复严格管控（传 {@link PermissionMode#DEFAULT}）。
     * BYPASS 会禁用全部规则评估，应当是按会话的显式动作，最好配合沙箱环境使用。
     *
     * <p>进行中的调用仍使用其启动时的引擎；新模式对该槽位的后续调用生效。
     */
    public void setPermissionMode(String userId, String sessionId, PermissionMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        AgentState state = getAgentState(userId, sid);
        installPermissionContext(userId, sid, state, state.getPermissionContext().withMode(mode));
    }

    /**
     * Replaces the permission context for one {@code (userId, sessionId)} slot, rebuilds that
     * slot's permission engine, and persists the updated state.
     *
     * <p>An in-flight call keeps the call-scoped engine it started with. The replacement applies
     * to subsequent calls on this slot and does not affect any other user or session.
     *
     * @param userId user identity for the slot (may be {@code null})
     * @param sessionId session identity (falls back to the default session id when {@code null})
     * @param permissionContext complete replacement context
     */
    public void replacePermissionContext(
            String userId, String sessionId, PermissionContextState permissionContext) {
        Objects.requireNonNull(permissionContext, "permissionContext must not be null");
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        AgentState state = getAgentState(userId, sid);
        installPermissionContext(userId, sid, state, permissionContext);
    }

    private void installPermissionContext(
            String userId,
            String sessionId,
            AgentState state,
            PermissionContextState permissionContext) {
        state.setPermissionContext(permissionContext);
        permissionEngineCache.put(
                slotKey(userId, sessionId), new PermissionEngine(permissionContext));
        saveAgentState(userId, sessionId);
    }

    /**
     * Switches the {@link PermissionMode} for the session identified by the given
     * {@link RuntimeContext}. See {@link #setPermissionMode(String, String, PermissionMode)}.
     *
     * @param ctx the runtime context identifying the session
     * @param mode the permission mode to switch to
     */
    /**
     * 按 {@link RuntimeContext} 标识的会话切换 {@link PermissionMode}。
     * 语义见 {@link #setPermissionMode(String, String, PermissionMode)}。
     */
    public void setPermissionMode(RuntimeContext ctx, PermissionMode mode) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        setPermissionMode(uid, sid, mode);
    }

    /**
     * Returns the current {@link PermissionMode} for the given {@code (userId, sessionId)} session.
     *
     * @param userId user identity for the slot (may be {@code null})
     * @param sessionId session identity (falls back to the default session id when {@code null})
     * @return the session's current permission mode
     */
    /** 返回指定 {@code (userId, sessionId)} 会话当前的 {@link PermissionMode}。 */
    public PermissionMode getPermissionMode(String userId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        return getAgentState(userId, sid).getPermissionContext().getMode();
    }

    /**
     * Persists the cached {@link AgentState} for the session identified by the given
     * {@link RuntimeContext}. No-op when no store is configured or the session has never
     * been loaded.
     *
     * @param ctx the runtime context identifying the session to save
     */
    /**
     * 持久化指定 {@link RuntimeContext} 会话缓存的 {@link AgentState}。
     * 未配置存储或该会话从未加载时为空操作。
     */
    public void saveAgentState(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        saveAgentState(uid, sid);
    }

    /**
     * Persists the cached {@link AgentState} for the given {@code (userId, sessionId)} slot via the
     * configured {@link AgentStateStore}. No-op when no store is configured or the slot has never
     * been loaded into the cache.
     */
    /**
     * 通过配置的 {@link AgentStateStore} 持久化指定 {@code (userId, sessionId)}
     * 槽位缓存的 {@link AgentState}。未配置存储或槽位从未加载进缓存时为空操作。
     */
    public void saveAgentState(String userId, String sessionId) {
        if (stateStore == null) {
            return;
        }
        String slot = slotKey(userId, sessionId);
        AgentState s = stateCache.get(slot);
        if (s != null) {
            long expected = slotVersions.getOrDefault(slot, AgentStateStore.UNVERSIONED);
            persistAgentStateCas(userId, sessionId, slot, s, expected, s.getContext().size());
        }
    }

    /** Returns how many optimistic-concurrency conflicts have been observed on this agent. */
    public long getStateConflictCount() {
        return stateConflictCount.get();
    }

    /** Returns the configured {@link ConflictPolicy} for agent_state saves. */
    public ConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    /** Returns the {@link AgentStateStore} configured for state persistence, or {@code null}. */
    /** 返回配置的状态持久化存储；未配置时返回 {@code null}。 */
    public AgentStateStore getStateStore() {
        return stateStore;
    }

    /**
     * Returns the builder-time fallback {@code sessionId}, used when a call's
     * {@link RuntimeContext} carries no {@code sessionId}.
     */
    /**
     * 返回构建期的兜底 {@code sessionId}：
     * 调用的 {@link RuntimeContext} 未携带 {@code sessionId} 时使用。
     */
    public String getDefaultSessionId() {
        return defaultSessionId;
    }

    /** Returns the model-call configuration (retries, timeouts). */
    /** 返回模型调用配置（重试、超时），供观测/导出使用。 */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /** Returns the reasoning-loop configuration (maxIters, stopOnReject). */
    /** 返回 ReAct 循环配置（maxIters、拒绝即停），供观测/导出使用。 */
    public ReactConfig getReactConfig() {
        return reactConfig;
    }

    /** @deprecated Use {@code getAgentState(userId, sessionId).getPermissionContext()} instead. */
    /** @deprecated 请改用 {@code getAgentState(userId, sessionId).getPermissionContext()}。 */
    @Deprecated
    public PermissionEngine getPermissionEngine() {
        String slot = slotKey(null, defaultSessionId);
        AgentState s = getAgentState(null, defaultSessionId);
        return permissionEngineCache.computeIfAbsent(
                slot, k -> new PermissionEngine(s.getPermissionContext()));
    }

    /** @deprecated Use {@code getAgentState(userId, sessionId).getPermissionContext()} instead. */
    /** @deprecated 请改用 {@code getAgentState(userId, sessionId).getPermissionContext()}。 */
    @Deprecated
    public PermissionContextState getPermissionContext() {
        return getAgentState().getPermissionContext();
    }

    /** Returns the immutable list of registered middlewares. */
    /** 返回已注册中间件的不可变列表（GracefulShutdownMiddleware 固定在最前）。 */
    public List<MiddlewareBase> getMiddlewares() {
        return middlewares;
    }

    /** Returns the per-model-call {@link ExecutionConfig}, or {@code null} if none was set. */
    /** 返回每次模型调用的 {@link ExecutionConfig}；未设置时为 {@code null}。 */
    public ExecutionConfig getModelExecutionConfig() {
        return modelExecutionConfig;
    }

    /** Returns the per-tool-call {@link ExecutionConfig}, or {@code null} if none was set. */
    /** 返回每次工具调用的 {@link ExecutionConfig}；未设置时为 {@code null}。 */
    public ExecutionConfig getToolExecutionConfig() {
        return toolExecutionConfig;
    }

    /** Returns the {@link ToolExecutionContext} bound at build time, or {@code null} if none. */
    /** 返回构建期绑定的 {@link ToolExecutionContext}；未绑定时为 {@code null}。 */
    public ToolExecutionContext getToolExecutionContext() {
        return toolExecutionContext;
    }

    /**
     * Returns whether pending-tool-call recovery is enabled (HITL: resume tool calls left in
     * a pending state across {@code call()} invocations).
     */
    /**
     * 是否启用"悬空工具调用恢复"（HITL：跨 {@code call()} 调用恢复
     * 处于 pending 状态的工具调用，自动补合成错误结果）。
     */
    public boolean isPendingToolRecoveryEnabled() {
        return enablePendingToolRecovery;
    }

    /** Returns the system prompt (alias for {@link #getSysPrompt()}). */
    /** 返回系统提示词（{@link #getSysPrompt()} 的别名）。 */
    public String getSystemPrompt() {
        return sysPrompt;
    }

    /** 创建 {@link Builder} 构建器入口。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 关闭钩子：核心 ReActAgent 为空操作；
     * 子类/包装器（如 HarnessAgent）可在此释放额外资源。
     */
    @Override
    public void close() {
        // Release the ShutdownStateSaver registered in the constructor so that ephemeral /
        // per-call agent instances are not retained by GracefulShutdownManager.stateSavers.
        shutdownManager.unbindStateSaver(this);
        clearStateCache();
        // No-op for the core ReActAgent. Subclasses / wrappers (HarnessAgent) may release
        // additional resources here.
        // 核心 ReActAgent 无操作。子类/包装器（HarnessAgent）可在此释放额外资源。
    }

    // ==================== Builder ====================
    // ==================== 构建器 ====================

    /**
     * {@link ReActAgent} 的构建器 —— 收集全部配置后由 {@link #build()} 一次性装配。
     *
     * <p><b>build() 关键编排：</b>
     * <ol>
     *   <li>{@code toolkit.copy()} 深拷贝，保证实例间工具集隔离；</li>
     *   <li>对拷贝后的工具集做 ToolkitAware rebind（把工具内部的 toolkit
     *       引用指向新拷贝）；</li>
     *   <li>从 Hook 注册工具（{@code hook.tools()}）；</li>
     *   <li>消费 1.x 遗留配置（长期记忆 / RAG / SkillBox，见 configureXxx）；</li>
     *   <li>按 2.0 方式挂载技能仓库（DynamicSkillMiddleware）；</li>
     *   <li>{@code new ReActAgent(this, agentToolkit)} 并通过 selfRef 破环。</li>
     * </ol>
     */
    @SuppressWarnings("deprecation")
    public static class Builder {
        /** 智能体名称。 */
        String name;

        /** 智能体描述（多智能体协作时供其他智能体了解本智能体职责）。 */
        String description;

        /** 系统提示词。 */
        String sysPrompt;

        /** 主模型。 */
        Model model;

        /** 工具集（build 时会深拷贝，传入的实例不会被智能体直接持有）。 */
        Toolkit toolkit = new Toolkit();

        /** ReAct 最大迭代次数，默认 10。 */
        int maxIters = 10;

        /** 模型调用执行配置（重试/超时）。 */
        ExecutionConfig modelExecutionConfig;

        /** 工具调用执行配置（重试/超时）。 */
        ExecutionConfig toolExecutionConfig;

        /** 构建期默认生成选项。 */
        GenerateOptions generateOptions;

        /** 钩子集合（LinkedHashSet 保序 + 去重，按优先级执行）。 */
        final Set<Hook> hooks = new LinkedHashSet<>();

        /** 中间件列表（build 时会在最前面插入 GracefulShutdownMiddleware）。 */
        private final List<MiddlewareBase> middlewares = new ArrayList<>();

        /** 是否启用 meta-tool（向模型自述可用工具）。 */
        private boolean enableMetaTool = false;

        /** 是否启用内置任务清单能力（todo_write 工具 + 每轮提醒）。 */
        private boolean taskListEnabled = false;

        /** 构建期工具执行上下文。 */
        private ToolExecutionContext toolExecutionContext;

        /** 是否启用悬空工具调用自动恢复。 */
        private boolean enablePendingToolRecovery = false;

        // 2.0 core fields
        // 2.0 核心字段
        /** 权限上下文模板（应用于每个新会话槽位）。 */
        private PermissionContextState permissionContext;

        // Flat setters backing ModelConfig / ReactConfig values
        // 扁平 setter 的暂存字段（最终汇入 ModelConfig / ReactConfig）
        private Integer flatMaxRetries;
        private Model flatFallbackModel;
        private FailoverListener flatFailoverListener;
        private Boolean flatStopOnReject;

        /** 状态持久化存储。 */
        private AgentStateStore stateStore;

        private ConflictPolicy conflictPolicy;

        /** 兜底会话 ID。 */
        private String defaultSessionId;

        // ==================== 1.x legacy compatibility fields ====================
        // Below fields back the deprecated `longTermMemory(...)`, `knowledge(...)`,
        // `skillBox(...)` setters. They are consumed by configureXxx() during build() so
        // legacy 1.x user code keeps producing equivalent runtime behavior.
        // ==================== 1.x 遗留兼容字段 ====================
        // 以下字段支撑已废弃的 longTermMemory(...)、knowledge(...)、skillBox(...)
        // setter，在 build() 期间由 configureXxx() 消费，
        // 使 1.x 遗留用户代码仍能产生等价的运行时行为。

        /** 1.x 遗留：长期记忆实例。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private LongTermMemory longTermMemory;

        /** 1.x 遗留：长期记忆模式（默认 BOTH：既检索也记录）。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;

        /** 1.x 遗留：是否在结束时异步记录长期记忆。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private boolean longTermMemoryAsyncRecord = false;

        /** 1.x 遗留：RAG 知识库集合。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private final Set<Knowledge> knowledgeBases = new LinkedHashSet<>();

        /** 1.x 遗留：RAG 模式（默认 GENERIC 钩子方式）。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private RAGMode ragMode = RAGMode.GENERIC;

        /** 1.x 遗留：RAG 检索配置（默认取 5 条、相关度阈值 0.5）。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        /** 1.x 遗留：技能箱。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        private SkillBox skillBox;

        // ==================== 2.0 skill repository entry ====================
        // The 2.0 way to mount skills: hand the agent one or more
        // {@link AgentSkillRepository} instances. {@link #build()} installs a
        // {@link DynamicSkillMiddleware} that rebuilds the skill prompt on every call,
        // letting per-user namespaced repositories swap content under the same skill name.
        // ==================== 2.0 技能仓库入口 ====================
        // 2.0 挂载技能的方式：把一个或多个 AgentSkillRepository 交给智能体。
        // build() 会安装 DynamicSkillMiddleware，在每次调用时重建技能提示词，
        // 允许按用户命名空间的仓库在同名技能下替换内容。

        /** 2.0 技能仓库列表。 */
        private final List<AgentSkillRepository> skillRepositories = new ArrayList<>();

        /** 技能过滤器（按运行时条件筛选技能）。 */
        private SkillFilter skillFilter;

        /** 是否启用动态技能（默认开启）。 */
        private boolean dynamicSkillsEnabled = true;

        /**
         * When true, {@link DynamicSkillMiddleware} toggles the prompt provider's code-execution
         * block (per-skill {@code <files-root>} listing + instructions). Off by default —
         * enabling it without also wiring a shell-like tool will produce a prompt that asks the
         * model to do things it cannot.
         */
        /**
         * 为 true 时，{@link DynamicSkillMiddleware} 会启用提示词的"代码执行"段
         * （每个技能的 {@code <files-root>} 目录清单 + 操作说明）。默认关闭——
         * 若开启却没有配套 shell 类工具，提示词会让模型做它做不到的事。
         */
        private boolean skillCodeExecutionEnabled = false;

        /**
         * Stable working directory passed to {@link DynamicSkillMiddleware}. {@code null} means
         * the middleware will mkdtemp one on first reload and reuse it for the agent's lifetime.
         */
        /**
         * 传给 {@link DynamicSkillMiddleware} 的稳定工作目录。{@code null} 表示
         * 中间件首次重载时自建临时目录并在智能体生命周期内复用。
         */
        private Path skillWorkDir;

        /** 包私有构造器：只能经 {@link ReActAgent#builder()} 创建。 */
        private Builder() {}

        /**
         * Sets the name for this agent.
         *
         * @param name The agent name, must not be null
         * @return This builder instance for method chaining
         */
        /** 设置智能体名称。 */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** 设置智能体描述（供多智能体协作时互相了解职责）。 */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** @deprecated No longer enforced; per-session serialization handles concurrency. */
        /** @deprecated 不再强制执行；并发已由按会话串行化机制处理。本 setter 为空操作。 */
        @Deprecated
        public Builder checkRunning(boolean checkRunning) {
            return this;
        }

        /**
         * Sets the system prompt for this agent.
         *
         * @param sysPrompt The system prompt, can be null or empty
         * @return This builder instance for method chaining
         */
        /** 设置系统提示词（可为 null 或空）。 */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * Sets the language model for this agent.
         *
         * @param model The language model to use for reasoning, must not be null
         * @return This builder instance for method chaining
         */
        /** 设置用于推理的语言模型。 */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Configures the model from a string id resolved via {@link ModelRegistry}: a named
         * registration ({@link ModelRegistry#register(String, Model)}) or an extension provider
         * pattern such as {@code openai:gpt-5.5}, {@code dashscope:qwen-max}, {@code
         * anthropic:claude-sonnet-4-5}, {@code gemini:gemini-2.0-flash}, or {@code ollama:llama3}.
         * Extension modules read API keys from their standard environment variables when auto-created.
         *
         * @param modelId registry id or {@code provider:model} string
         * @return this builder
         * @throws IllegalArgumentException if the id cannot be resolved
         */
        /**
         * 通过 {@link ModelRegistry} 解析字符串 id 配置模型：
         * 命名注册，或 {@code openai:gpt-5.5}、{@code dashscope:qwen-max}、
         * {@code anthropic:claude-sonnet-4-5} 等扩展提供方模式。
         * 扩展模块自动创建时从标准环境变量读取 API Key。
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * Sets the toolkit containing available tools for this agent.
         *
         * <p>The registry is copied at build time before hook/meta tools are registered.
         * Existing tool instances are shared by reference and must support concurrent use.
         *
         * @param toolkit The toolkit with available tools, must not be null
         * @return This builder instance for method chaining
         */
        /** 设置可用工具集（build 时深拷贝，传入实例不被直接持有）。 */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Sets the maximum number of reasoning-acting iterations.
         *
         * @param maxIters Maximum iterations, must be positive
         * @return This builder instance for method chaining
         */
        /** 设置"推理-行动"最大迭代次数（必须为正）。 */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * Adds a hook for monitoring and intercepting agent execution events.
         *
         * <p>Hooks can observe or modify events during reasoning, acting, and other phases.
         * Multiple hooks can be added and will be executed in priority order (lower priority
         * values execute first).
         *
         * @param hook The hook to add, must not be null
         * @return This builder instance for method chaining
         * @see Hook
         * @see Hook#tools()
         */
        /**
         * 添加单个钩子：观测/拦截推理、行动等阶段的执行事件。
         * 多个钩子按优先级执行（优先级数值小的先执行）。
         * 钩子还可通过 {@link Hook#tools()} 向智能体注入工具。
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * Adds multiple hooks for monitoring and intercepting agent execution events.
         *
         * <p>Hooks can observe or modify events during reasoning, acting, and other phases.
         * All hooks will be executed in priority order (lower priority values execute first).
         *
         * @param hooks The list of hooks to add, must not be null
         * @return This builder instance for method chaining
         * @see Hook
         * @see Hook#tools()
         */
        /** 批量添加钩子（按优先级顺序执行）。 */
        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * Adds a middleware for intercepting agent execution.
         *
         * @param middleware the middleware to add
         * @return this builder instance for method chaining
         */
        /** 添加单个中间件（拦截智能体执行的各阶段）。 */
        public Builder middleware(MiddlewareBase middleware) {
            this.middlewares.add(middleware);
            return this;
        }

        /**
         * Adds multiple middlewares for intercepting agent execution.
         *
         * @param middlewares the list of middlewares to add
         * @return this builder instance for method chaining
         */
        /** 批量添加中间件。 */
        public Builder middlewares(List<? extends MiddlewareBase> middlewares) {
            this.middlewares.addAll(middlewares);
            return this;
        }

        /**
         * Enables or disables the meta-tool functionality.
         *
         * <p>When enabled, the toolkit will automatically register a meta-tool that provides
         * information about available tools to the agent. This can help the agent understand
         * what tools are available without relying solely on the system prompt.
         *
         * @param enableMetaTool true to enable meta-tool, false to disable
         * @return This builder instance for method chaining
         */
        /**
         * 启用/禁用 meta-tool：启用后工具集会自动注册一个元工具，
         * 向智能体自述可用工具信息，减少对系统提示词的依赖。
         */
        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        /**
         * Enables the built-in task-list capability ({@code todo_write} tool + per-turn reminder).
         *
         * <p>Equivalent to {@link #enableTaskList(boolean) enableTaskList(true)}.
         *
         * @return this builder for chaining
         */
        /** 启用内置任务清单能力，等价于 {@code enableTaskList(true)}。 */
        public Builder enableTaskList() {
            return enableTaskList(true);
        }

        /**
         * Enables or disables the built-in task-list capability.
         *
         * <p>When enabled, {@link #build()} registers a {@code todo_write} tool (operating on
         * {@link io.agentscope.core.state.AgentState#getTasksContext()} with full-list-replace
         * semantics) and a {@link io.agentscope.core.middleware.TaskReminderMiddleware} that
         * re-surfaces the current list before every reasoning step. Default OFF.
         *
         * @param enabled true to enable the task list
         * @return this builder for chaining
         */
        /**
         * 启用/禁用内置任务清单能力：启用后 {@link #build()} 会注册
         * {@code todo_write} 工具（操作 AgentState 的 TasksContext，整表替换语义）
         * 与 TaskReminderMiddleware（每次推理前重新提示当前清单）。默认关闭。
         */
        public Builder enableTaskList(boolean enabled) {
            this.taskListEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables automatic recovery from orphaned pending tool calls.
         *
         * <p>When enabled, the agent automatically detects orphaned pending tool calls and
         * patches them with synthetic error results before processing new input. This prevents
         * {@link IllegalStateException} when tool execution fails, times out, or is interrupted.
         *
         * <p>Disable this if you prefer to handle pending tool calls manually, for example
         * through HITL (Human-in-the-loop) mechanisms or custom error handling strategies.
         *
         * @param enable true to enable auto-recovery, false to disable
         * @return This builder instance for method chaining
         */
        /**
         * 启用/禁用"悬空工具调用自动恢复"：启用后智能体会在处理新输入前
         * 检测孤立的 pending 工具调用并补合成错误结果，
         * 防止工具执行失败/超时/中断时抛 IllegalStateException。
         * 若希望用 HITL 或自定义策略手动处理，可禁用。
         */
        public Builder enablePendingToolRecovery(boolean enable) {
            this.enablePendingToolRecovery = enable;
            return this;
        }

        /**
         * Sets the execution configuration for model API calls.
         *
         * <p>This configuration controls timeout, retry behavior, and backoff strategy for
         * model requests during the reasoning phase. If not set, the agent will use the
         * model's default execution configuration.
         *
         * @param modelExecutionConfig The execution configuration for model calls, can be null
         * @return This builder instance for method chaining
         * @see ExecutionConfig
         */
        /**
         * 设置模型 API 调用的执行配置（超时、重试、退避策略），
         * 作用于推理阶段；未设置时使用模型自身的默认配置。
         */
        public Builder modelExecutionConfig(ExecutionConfig modelExecutionConfig) {
            this.modelExecutionConfig = modelExecutionConfig;
            return this;
        }

        /**
         * Sets the execution configuration for tool executions.
         *
         * <p>This configuration controls timeout, retry behavior, and backoff strategy for
         * tool calls during the acting phase. If not set, the toolkit will use its default
         * execution configuration.
         *
         * @param toolExecutionConfig The execution configuration for tool calls, can be null
         * @return This builder instance for method chaining
         * @see ExecutionConfig
         */
        /**
         * 设置工具执行的执行配置（超时、重试、退避策略），
         * 作用于行动阶段；未设置时使用工具集的默认配置。
         */
        public Builder toolExecutionConfig(ExecutionConfig toolExecutionConfig) {
            this.toolExecutionConfig = toolExecutionConfig;
            return this;
        }

        /**
         * Sets the generation options for model API calls.
         *
         * <p>This configuration controls LLM generation parameters such as temperature, topP,
         * maxTokens, frequencyPenalty, presencePenalty, etc. These options are passed to the
         * model during the reasoning phase.
         *
         * <p><b>Example usage:</b>
         * <pre>{@code
         * ReActAgent agent = ReActAgent.builder()
         *     .name("assistant")
         *     .model(model)
         *     .generateOptions(GenerateOptions.builder()
         *         .temperature(0.7)
         *         .topP(0.9)
         *         .maxTokens(1000)
         *         .build())
         *     .build();
         * }</pre>
         *
         * <p><b>Note:</b> If both generateOptions and modelExecutionConfig are set,
         * the modelExecutionConfig's executionConfig will be merged into the generateOptions,
         * with modelExecutionConfig taking precedence for execution settings.
         *
         * @param generateOptions The generation options for model calls, can be null
         * @return This builder instance for method chaining
         * @see GenerateOptions
         */
        /**
         * 设置模型生成的参数选项（temperature、topP、maxTokens 等），
         * 在推理阶段传给模型。
         *
         * <p>注意：同时设置 generateOptions 与 modelExecutionConfig 时，
         * 后者的 executionConfig 会合并进 generateOptions，
         * 且执行类设置以 modelExecutionConfig 为准。
         */
        public Builder generateOptions(GenerateOptions generateOptions) {
            this.generateOptions = generateOptions;
            return this;
        }

        /**
         * Sets the tool execution context for this agent.
         *
         * @param toolExecutionContext The tool execution context
         * @return This builder instance for method chaining
         * @deprecated Use {@link RuntimeContext} with {@code agent.call(msg, runtimeContext)}
         *     or register POJOs directly via {@code RuntimeContext.builder().put(Type, value)}.
         */
        /**
         * 设置工具执行上下文。
         *
         * @deprecated 请改用 {@code agent.call(msg, runtimeContext)} 传入
         *     {@link RuntimeContext}，或直接通过
         *     {@code RuntimeContext.builder().put(Type, value)} 注册 POJO。
         */
        @Deprecated
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        /**
         * Sets the {@link AgentStateStore} backing automatic AgentState load (at construction) and save
         * (after every successful {@code call()} and on graceful shutdown). When {@code null}, the
         * agent runs purely in-memory and persistence is a no-op.
         */
        /**
         * 设置 {@link AgentStateStore}，支撑 AgentState 的自动加载（构造时）
         * 与保存（每次 call() 成功后及优雅停机时）。
         * 为 {@code null} 时纯内存运行，持久化为空操作。
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * Sets the policy applied when an optimistic-concurrency save of {@code agent_state}
         * conflicts with another writer's update. Defaults to {@link ConflictPolicy#OVERWRITE}.
         * Prefer {@link ConflictPolicy#FAIL} when a distributed session turn gate is enabled.
         */
        public Builder conflictPolicy(ConflictPolicy conflictPolicy) {
            this.conflictPolicy = conflictPolicy;
            return this;
        }

        /**
         * Sets the builder-time fallback {@code sessionId} used to persist {@code agent_state}
         * when a call does not supply a {@code sessionId} on its {@link RuntimeContext}. Defaults
         * to the agent name when unset. Per-call routing is via {@code RuntimeContext.sessionId};
         * this is only the bootstrap / single-tenant default.
         */
        /**
         * 设置构建期兜底 {@code sessionId}：调用的 RuntimeContext 未携带
         * sessionId 时用于持久化 agent_state。未设置时默认为智能体名。
         * 按调用路由仍通过 RuntimeContext.sessionId；这里只是引导/单租户默认值。
         */
        public Builder defaultSessionId(String sessionId) {
            this.defaultSessionId = sessionId;
            return this;
        }

        /**
         * Sets the model-call retry budget (max attempts including the first try). Defaults to
         * {@link ModelConfig#DEFAULT_MAX_RETRIES} when unset.
         */
        /** 设置模型调用重试预算（含首次尝试的总次数，必须 > 0）。 */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries <= 0) {
                throw new IllegalArgumentException("maxRetries must be > 0: " + maxRetries);
            }
            this.flatMaxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the fallback model invoked after the primary model exhausts its retry budget.
         * Pass {@code null} to explicitly clear (no fallback).
         */
        /** 设置主模型耗尽重试预算后启用的回退模型；传 null 显式清空。 */
        public Builder fallbackModel(Model fallbackModel) {
            this.flatFallbackModel = fallbackModel;
            return this;
        }

        /**
         * Convenience overload that resolves {@code modelId} via
         * {@link io.agentscope.core.model.ModelRegistry#resolve(String)} (named registration or
         * {@code provider:model} pattern like {@code openai:gpt-5.5}, {@code dashscope:qwen-max}).
         *
         * @throws IllegalArgumentException if the id cannot be resolved
         */
        /** 回退模型的字符串 id 便捷重载（经 ModelRegistry 解析）。 */
        public Builder fallbackModel(String modelId) {
            this.flatFallbackModel = io.agentscope.core.model.ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * Sets the listener notified when the fallback model takes over from a failed primary
         * model. Pass {@code null} to explicitly clear (no notification).
         *
         * @see FailoverListener for the threading and failure contract
         */
        public Builder failoverListener(FailoverListener failoverListener) {
            this.flatFailoverListener = failoverListener;
            return this;
        }

        /**
         * Controls whether a permission rejection of any tool call terminates the reasoning loop
         * (instead of feeding the rejection back into the next reasoning round). Defaults to
         * {@link ReactConfig#DEFAULT_STOP_ON_REJECT}.
         */
        /**
         * 控制工具调用被权限拒绝时是否直接终止推理循环
         * （而不是把拒绝结果喂给下一轮推理）。
         */
        public Builder stopOnReject(boolean stopOnReject) {
            this.flatStopOnReject = stopOnReject;
            return this;
        }

        /**
         * Sets the {@link PermissionContextState} consulted by the {@link PermissionEngine} during tool
         * execution. When unset, an empty permission context is used (PASSTHROUGH for all tools).
         */
        /**
         * 设置权限引擎在工具执行时参考的 {@link PermissionContextState}。
         * 未设置时使用空权限上下文（所有工具 PASSTHROUGH 直通）。
         */
        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContext = permissionContext;
            return this;
        }

        // ==================== 1.x legacy compatibility setters ====================
        // The setters below are deprecated since 2.0 and will be removed in the next minor.
        // Each one captures a value used later by configureXxx() during build(), wiring the
        // corresponding legacy hook(s) and/or tool(s) into the agent so 1.x user code keeps
        // producing equivalent runtime behavior. Internal references use legacy.* packages.
        // ==================== 1.x 遗留兼容 setter ====================
        // 以下 setter 自 2.0 起废弃，将在下个 minor 版本移除。
        // 每个都只暂存值，供 build() 期间的 configureXxx() 消费，
        // 将对应的遗留钩子/工具装配进智能体，使 1.x 用户代码保持等价行为。

        /**
         * @deprecated since 2.0.0. Long-term memory is being redesigned around the upcoming reme
         *     base class. Hooks added through this path still work.
         */
        /**
         * @deprecated 2.0.0 起废弃。长期记忆正围绕即将推出的 reme 基类重新设计，
         *     经此路径添加的钩子仍然有效。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #longTermMemory}.
         */
        /** @deprecated 2.0.0 起废弃，见 {@link #longTermMemory}。设置长期记忆模式。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            this.longTermMemoryMode = mode;
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #longTermMemory}.
         */
        /** @deprecated 2.0.0 起废弃，见 {@link #longTermMemory}。设置是否异步记录长期记忆。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemoryAsyncRecord(boolean asyncRecord) {
            this.longTermMemoryAsyncRecord = asyncRecord;
            return this;
        }

        /**
         * @deprecated since 2.0.0. RAG is being redesigned; legacy adapters remain functional.
         */
        /** @deprecated 2.0.0 起废弃。RAG 正在重新设计；遗留适配器仍可用。添加单个知识库。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder knowledge(Knowledge knowledge) {
            if (knowledge != null) {
                this.knowledgeBases.add(knowledge);
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #knowledge}.
         */
        /** @deprecated 2.0.0 起废弃，见 {@link #knowledge}。批量添加知识库。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder knowledges(List<Knowledge> knowledges) {
            if (knowledges != null) {
                this.knowledgeBases.addAll(knowledges);
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #knowledge}.
         */
        /** @deprecated 2.0.0 起废弃，见 {@link #knowledge}。设置 RAG 模式。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder ragMode(RAGMode mode) {
            if (mode != null) {
                this.ragMode = mode;
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. See {@link #knowledge}.
         */
        /** @deprecated 2.0.0 起废弃，见 {@link #knowledge}。设置 RAG 检索配置。 */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder retrieveConfig(RetrieveConfig config) {
            if (config != null) {
                this.retrieveConfig = config;
            }
            return this;
        }

        /**
         * @deprecated since 2.0.0. Skills now flow through {@link #skillRepository} /
         *     {@link #skillRepositories}; legacy {@link io.agentscope.core.skill.SkillBox}
         *     instances are still accepted for source compatibility, but combining a
         *     {@code skillBox(...)} with {@code skillRepository(...)} is untested — new code
         *     should prefer {@link #skillRepository(AgentSkillRepository)}.
         */
        /**
         * @deprecated 2.0.0 起废弃。技能现在经由 {@link #skillRepository} /
         *     {@link #skillRepositories} 挂载；遗留 SkillBox 为源码兼容仍被接受，
         *     但 skillBox(...) 与 skillRepository(...) 混用未经测试——
         *     新代码请使用 {@link #skillRepository(AgentSkillRepository)}。
         */
        @Deprecated(since = "2.0.0")
        public Builder skillBox(SkillBox skillBox) {
            this.skillBox = skillBox;
            return this;
        }

        /**
         * Adds a single {@link AgentSkillRepository} to the layered skill stack. Multiple calls
         * append in order from low to high priority — when two repositories expose a skill with
         * the same {@link io.agentscope.core.skill.AgentSkill#getName()}, the later (higher
         * priority) entry wins.
         *
         * <p>If at least one repository is registered and dynamic skills remain enabled
         * (see {@link #dynamicSkillsEnabled(boolean)}), {@link #build()} attaches a
         * {@link DynamicSkillMiddleware} that rebuilds the skill prompt on every {@code call()}.
         */
        /**
         * 向分层技能栈添加单个 {@link AgentSkillRepository}。多次调用按
         * 低→高优先级顺序追加——两个仓库暴露同名技能时，后者（高优先级）胜出。
         *
         * <p>至少注册一个仓库且动态技能保持启用时，{@link #build()} 会挂上
         * {@link DynamicSkillMiddleware}，在每次 {@code call()} 时重建技能提示词。
         */
        public Builder skillRepository(AgentSkillRepository repo) {
            if (repo != null) {
                this.skillRepositories.add(repo);
            }
            return this;
        }

        /**
         * Replaces the current repository list with the supplied collection. {@code null}
         * entries are dropped silently; passing {@code null} clears the list.
         */
        /**
         * 用给定集合整体替换当前技能仓库列表。
         * null 元素静默丢弃；传 null 清空列表。
         */
        public Builder skillRepositories(List<AgentSkillRepository> repos) {
            this.skillRepositories.clear();
            if (repos != null) {
                for (AgentSkillRepository r : repos) {
                    if (r != null) {
                        this.skillRepositories.add(r);
                    }
                }
            }
            return this;
        }

        /**
         * Builder-time {@link SkillFilter} applied by the auto-installed
         * {@link DynamicSkillMiddleware}. Defaults to {@link SkillFilter#all()} when unset.
         */
        /**
         * 设置构建期的 {@link SkillFilter}，由自动安装的
         * {@link DynamicSkillMiddleware} 应用。未设置时默认 {@link SkillFilter#all()}。
         */
        public Builder skillFilter(SkillFilter filter) {
            this.skillFilter = filter;
            return this;
        }

        /**
         * Toggles automatic installation of {@link DynamicSkillMiddleware}. Set to {@code false}
         * when an external orchestrator (e.g. {@code HarnessAgent}) wants to attach its own
         * subclass of {@link DynamicSkillMiddleware} or fall back to a static
         * {@link io.agentscope.core.skill.SkillBox}. Defaults to {@code true}.
         */
        /**
         * 开关 {@link DynamicSkillMiddleware} 的自动安装。当外部编排者
         * （如 HarnessAgent）想挂自己的 DynamicSkillMiddleware 子类
         * 或退回静态 SkillBox 时设为 false。默认 true。
         */
        public Builder dynamicSkillsEnabled(boolean enabled) {
            this.dynamicSkillsEnabled = enabled;
            return this;
        }

        /**
         * Enables the code-execution prompt block emitted by
         * {@link io.agentscope.core.skill.AgentSkillPromptProvider}. When on:
         * <ul>
         *   <li>If every visible skill has an on-disk origin directory (e.g. produced by
         *       {@link io.agentscope.core.skill.repository.FileSystemSkillRepository}), each
         *       {@code <skill>} entry includes a {@code <files-root>} child with the absolute
         *       path so the LLM can shell-execute scripts directly;</li>
         *   <li>Otherwise the prompt falls back to a single {@code uploadDir} root.</li>
         * </ul>
         *
         * <p>Only flip this on when the agent's toolkit has a shell-like tool wired in —
         * otherwise the prompt will ask the model to do things it cannot. Defaults to {@code false}.
         */
        /**
         * 启用技能提示词的"代码执行"段：开启后，若所有可见技能都有磁盘来源目录，
         * 每个 {@code <skill>} 条目会附带 {@code <files-root>} 绝对路径，
         * 让 LLM 可直接 shell 执行脚本；否则回退为单一 uploadDir 根目录。
         *
         * <p>仅当工具集中已接入 shell 类工具时才应开启，默认 false。
         */
        public Builder skillCodeExecutionEnabled(boolean enabled) {
            this.skillCodeExecutionEnabled = enabled;
            return this;
        }

        /**
         * Sets a stable working directory used by {@link DynamicSkillMiddleware} for
         * {@code uploadSkillFiles}. Pass {@code null} (the default) to let the middleware mkdtemp
         * a fresh directory on first reload and reuse it for the agent's lifetime.
         */
        /**
         * 设置 {@link DynamicSkillMiddleware} 用于 uploadSkillFiles 的稳定工作目录。
         * 传 null（默认）则中间件首次重载时自建临时目录并在智能体生命周期内复用。
         */
        public Builder skillWorkDir(Path dir) {
            this.skillWorkDir = dir;
            return this;
        }

        /**
         * Returns a new {@link Builder} pre-populated with the given agent's observable
         * configuration: name, description, system prompt, model, maxIters, generateOptions, and
         * a defensive copy of the toolkit.
         *
         * <p>Use to derive a related agent without re-specifying every field.
         */
        /**
         * 基于现有智能体预填充一个新 {@link Builder}：复制 name、description、
         * 系统提示词、model、maxIters、generateOptions，以及工具集的防御性拷贝。
         *
         * <p>用于派生相关智能体而无需逐项重新指定配置。
         */
        public static Builder fromAgent(ReActAgent agent) {
            Builder b = new Builder();
            b.name = agent.getName();
            b.description = agent.getDescription();
            b.sysPrompt = agent.getSysPrompt();
            b.model = agent.getModel();
            b.maxIters = agent.getMaxIters();
            b.generateOptions = agent.getGenerateOptions();
            ModelConfig srcModelConfig = agent.getModelConfig();
            if (srcModelConfig != null) {
                b.flatMaxRetries = srcModelConfig.maxRetries();
                b.flatFallbackModel = srcModelConfig.fallbackModel();
                b.flatFailoverListener = srcModelConfig.failoverListener();
            }
            b.toolkit = agent.getToolkit().copy();
            return b;
        }

        // ==================== 1.x legacy configureXxx helpers ====================
        // Ported verbatim from 1.x ReActAgent.Builder (origin/1.x ReActAgent.java:1762-1925),
        // with two adjustments for 2.0:
        //   1) all legacy classes are referenced through the io.agentscope.core.legacy.* packages;
        //   2) configureLongTermMemory's static hook receives an AgentStateMemoryView that lazily
        //      reads AgentState.context via a `selfRef` shared with build().
        // ==================== 1.x 遗留 configureXxx 辅助方法 ====================
        // 从 1.x ReActAgent.Builder 原样移植，2.0 做了两处调整：
        //   1) 所有遗留类经 io.agentscope.core.legacy.* 包引用；
        //   2) configureLongTermMemory 的静态钩子接收 AgentStateMemoryView，
        //      通过与 build() 共享的 selfRef 延迟读取 AgentState.context。

        /**
         * Configures long-term memory based on the selected mode.
         *
         * <p>AGENT_CONTROL registers memory tools for the agent to call. STATIC_CONTROL adds
         * a {@link StaticLongTermMemoryHook} that retrieves /
         * records memory automatically. BOTH combines them. The hook reads context lazily from
         * {@code selfRef.get().getAgentState()} so it tolerates being constructed before the
         * agent itself exists.
         */
        /**
         * 按所选模式配置长期记忆：
         * AGENT_CONTROL 注册记忆工具供智能体主动调用；
         * STATIC_CONTROL 添加 StaticLongTermMemoryHook 自动检索/记录；
         * BOTH 两者兼有。钩子经 {@code selfRef.get().getAgentState()} 延迟读取
         * 上下文，因此可以在智能体实例尚不存在时就构造（破环设计）。
         */
        @SuppressWarnings("deprecation")
        private void configureLongTermMemory(
                Toolkit agentToolkit,
                java.util.concurrent.atomic.AtomicReference<ReActAgent> selfRef) {
            if (longTermMemoryMode == LongTermMemoryMode.AGENT_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                agentToolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }
            if (longTermMemoryMode == LongTermMemoryMode.STATIC_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                Memory contextView =
                        new AgentStateMemoryView(
                                () -> {
                                    ReActAgent a = selfRef.get();
                                    return a == null ? null : a.getAgentState();
                                });
                hooks.add(
                        new StaticLongTermMemoryHook(
                                longTermMemory, contextView, longTermMemoryAsyncRecord));
            }
        }

        /**
         * Configures RAG (Retrieval-Augmented Generation) based on the selected mode.
         */
        /**
         * 按所选模式配置 RAG：GENERIC 挂 GenericRAGHook（自动检索注入上下文）；
         * AGENTIC 注册 KnowledgeRetrievalTools（由智能体主动决定何时检索）；
         * NONE 不做任何事。多个知识库时先聚合为单一 Knowledge 视图。
         */
        @SuppressWarnings("deprecation")
        private void configureRAG(Toolkit agentToolkit) {
            Knowledge aggregatedKnowledge =
                    knowledgeBases.size() == 1
                            ? knowledgeBases.iterator().next()
                            : buildAggregatedKnowledge();

            switch (ragMode) {
                case GENERIC -> hooks.add(new GenericRAGHook(aggregatedKnowledge, retrieveConfig));
                case AGENTIC ->
                        agentToolkit.registerTool(
                                new KnowledgeRetrievalTools(aggregatedKnowledge, retrieveConfig));
                case NONE -> {
                    // intentionally no-op
                }
            }
        }

        /**
         * 把多个知识库聚合为单一 Knowledge 视图：写入时广播到所有库；
         * 检索时并行查询所有库，按文档 id 去重（保留得分更高者），
         * 再按得分降序排序并截取 limit 条。
         */
        @SuppressWarnings("deprecation")
        private Knowledge buildAggregatedKnowledge() {
            return new Knowledge() {
                @Override
                public Mono<Void> addDocuments(List<Document> documents) {
                    return reactor.core.publisher.Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.addDocuments(documents))
                            .then();
                }

                @Override
                public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                    return reactor.core.publisher.Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.retrieve(query, config))
                            .collectList()
                            .map(this::mergeAndSortResults);
                }

                private List<Document> mergeAndSortResults(List<List<Document>> allResults) {
                    return allResults.stream()
                            .flatMap(List::stream)
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            Document::getId,
                                            d -> d,
                                            (d1, d2) ->
                                                    d1.getScore() != null
                                                                    && d2.getScore() != null
                                                                    && d1.getScore() > d2.getScore()
                                                            ? d1
                                                            : d2))
                            .values()
                            .stream()
                            .sorted(
                                    java.util.Comparator.comparing(
                                            Document::getScore,
                                            java.util.Comparator.nullsLast(
                                                    java.util.Comparator.reverseOrder())))
                            .limit(retrieveConfig.getLimit())
                            .toList();
                }
            };
        }

        /**
         * Registers the built-in task-list tool ({@code todo_write}) and a per-turn reminder
         * middleware. Opt-in via {@link #enableTaskList()}.
         */
        /**
         * 注册内置任务清单工具（{@code todo_write}）与每轮提醒中间件。
         * 由 {@link #enableTaskList()} 选择性开启。
         */
        private void configureTodoTools(Toolkit agentToolkit) {
            agentToolkit.registerTool(new io.agentscope.core.tool.builtin.TodoTools());
            middlewares.add(new io.agentscope.core.middleware.TaskReminderMiddleware());
        }

        /**
         * Configures SkillBox by binding the toolkit, registering the skill-load tool, uploading
         * skill files when auto-upload is enabled, and adding the SkillHook to the chain.
         */
        /**
         * 配置遗留 SkillBox：绑定工具集 → 注册技能加载工具 →
         * （启用自动上传时）上传技能文件 → 把 SkillHook 加入钩子链。
         */
        @SuppressWarnings("deprecation")
        private Hook configureSkillBox(Toolkit agentToolkit) {
            SkillBox skillBox = this.skillBox.copyForToolkit(agentToolkit);
            skillBox.registerSkillLoadTool();
            if (skillBox.isAutoUploadSkill()) {
                skillBox.uploadSkillFiles();
            }
            return new io.agentscope.core.skill.SkillHook(skillBox);
        }

        /**
         * Builds and returns a new ReActAgent instance with the configured settings.
         *
         * @return A new ReActAgent instance
         * @throws IllegalArgumentException if required parameters are missing or invalid
         */
        /**
         * 构建并返回新的 ReActAgent 实例。编排顺序：
         * <ol>
         *   <li>toolkit.copy() 深拷贝，避免多智能体间状态串扰；</li>
         *   <li>对持有原工具集引用的外部中间件做 ToolkitAware rebind；</li>
         *   <li>注册钩子声明的工具（registerToolsFromHooks）；</li>
         *   <li>按需注册 meta-tool；</li>
         *   <li>消费 1.x 遗留配置（长期记忆/RAG/任务清单/SkillBox）；</li>
         *   <li>按需安装 DynamicSkillMiddleware（2.0 技能仓库）；</li>
         *   <li>构造智能体并把实例写回 selfRef（供遗留钩子延迟解析）。</li>
         * </ol>
         */
        public ReActAgent build() {
            // Isolate registrations added by this agent while sharing existing tool instances.
            // Request-specific visibility is composed at execution time, without copying.
            Toolkit agentToolkit = this.toolkit.copy();

            registerToolsFromHooks(agentToolkit);

            if (enableMetaTool) {
                agentToolkit.registerMetaTool();
            }

            // 1.x legacy compat: shared selfRef gives the long-term-memory hook (constructed
            // pre-agent) a way to resolve AgentState.context lazily once the agent exists.
            // 1.x 遗留兼容：共享的 selfRef 让在智能体之前构造的长期记忆钩子，
            // 能在智能体存在后延迟解析 AgentState.context（打破构造顺序循环依赖）。
            AtomicReference<ReActAgent> selfRef = new AtomicReference<>();

            if (longTermMemory != null) {
                configureLongTermMemory(agentToolkit, selfRef);
            }
            if (!knowledgeBases.isEmpty()) {
                configureRAG(agentToolkit);
            }
            if (taskListEnabled) {
                configureTodoTools(agentToolkit);
            }
            Hook skillHook = skillBox != null ? configureSkillBox(agentToolkit) : null;
            // 2.0 技能仓库：注册了仓库且动态技能启用时，安装 DynamicSkillMiddleware
            if (!skillRepositories.isEmpty() && dynamicSkillsEnabled) {
                middlewares.add(
                        new DynamicSkillMiddleware(
                                List.copyOf(skillRepositories),
                                agentToolkit,
                                skillFilter != null ? skillFilter : SkillFilter.all(),
                                skillCodeExecutionEnabled,
                                skillWorkDir));
            }

            // List.sort is stable: middlewares with equal order retain their registration order.
            middlewares.sort(Comparator.comparingInt(MiddlewareBase::order).reversed());

            if (skillHook != null) {
                hooks.add(skillHook);
            }
            try {
                ReActAgent agent = new ReActAgent(this, agentToolkit);
                // 把构造好的实例写回 selfRef，供遗留钩子延迟解析上下文
                selfRef.set(agent);
                return agent;
            } finally {
                // Generated hooks belong only to this agent, not subsequent builder uses.
                if (skillHook != null) {
                    hooks.remove(skillHook);
                }
            }
        }

        /**
         * Registers tool objects declared by hooks ({@link Hook#tools()}) on the agent toolkit.
         *
         * <p>The agent owns a build-time registry copy, so hook-supplied registrations cannot
         * overwrite tools in other agents built from the same source toolkit.
         */
        /**
         * 把钩子经 {@link Hook#tools()} 声明的工具对象注册到智能体工具集。
         *
         * <p>在 {@link Toolkit#copy()} 之后执行，使钩子提供的工具只作用于
         * 本智能体实例，不会改动构建器的原始工具集。
         */
        private void registerToolsFromHooks(Toolkit agentToolkit) {
            for (Hook hook : hooks) {
                List<Object> toolObjects = hook.tools();
                if (toolObjects == null || toolObjects.isEmpty()) {
                    continue;
                }
                for (Object toolObject : toolObjects) {
                    if (toolObject != null) {
                        agentToolkit.registerTool(toolObject);
                    }
                }
            }
        }
    }
}
