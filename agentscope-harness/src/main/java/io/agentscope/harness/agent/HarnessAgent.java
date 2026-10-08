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
package io.agentscope.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.agentscope.harness.agent.middleware.AsyncToolMiddleware;
import io.agentscope.harness.agent.middleware.AtPathExpansionMiddleware;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.middleware.InboxMiddleware;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.middleware.TranscriptMiddleware;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.RejectAllGate;
import io.agentscope.harness.agent.skill.curator.SkillAuditLog;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromoter;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.ArtifactDeliveryTool;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySaveTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.tool.ProposeSkillTool;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tool.SkillManageTool;
import io.agentscope.harness.agent.tool.WebTools;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.transcript.FilesystemTranscriptStore;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HarnessAgent is the user-facing harness API that wraps a {@link ReActAgent} with workspace /
 * filesystem / sandbox / subagent / skill / plan-mode / MCP orchestration.
 *
 * <p>Use {@link #builder()} to configure. For plain ReAct usage without any of the above, use
 * {@link ReActAgent#builder()} directly.
 *
 * <p>Capabilities added on top of the inner {@link ReActAgent}:
 *
 * <ul>
 *   <li>Workspace-based context loading (AGENTS.md, MEMORY.md, KNOWLEDGE.md)</li>
 *   <li>Pluggable file-system backend (local, sandbox, remote/composite)</li>
 *   <li>Subagent orchestration via {@code task} / {@code task_output} tools (sync + background)</li>
 *   <li>Skill loading via {@link AgentSkillRepository}, including the self-learning loop</li>
 *   <li>Memory flush + message offload before context compression</li>
 *   <li>Workspace-managed {@code tools.json} (MCP servers + allow/deny filter)</li>
 *   <li>Plan mode (read-only design phase) with {@code plan_enter}/{@code plan_write}/{@code plan_exit} tools</li>
 *   <li>Context-overflow emergency compaction via {@link CompactionMiddleware}</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> {@code HarnessAgent} is stateless between calls and safe to use as a
 * singleton serving multiple users/sessions concurrently. Each {@code call()} uses the
 * {@link io.agentscope.core.agent.RuntimeContext}'s {@code (userId, sessionId)} to isolate state.
 * Calls targeting the same session are serialized automatically; different sessions run in parallel.
 */
/**
 * HarnessAgent 是面向使用者的上层调度API，内部封装 {@link ReActAgent}，并集成工作空间、文件系统、沙箱、子智能体、技能、规划模式与MCP编排能力。
 *
 * <p>请通过 {@link #builder()} 完成配置。若仅需使用原生ReAct能力、无需上述扩展功能，可直接使用 {@link ReActAgent#builder()}。
 *
 * <p>在内层 {@link ReActAgent} 基础上新增的能力如下：
 *
 * <ul>
 *   <li>基于工作空间加载上下文（AGENTS.md、MEMORY.md、KNOWLEDGE.md）</li>
 *   <li>可插拔文件系统后端（本地、沙箱、远程/组合文件系统）</li>
 *   <li>依托 {@code task}、{@code task_output} 工具实现子智能体编排（同步执行与后台异步执行）</li>
 *   <li>通过 {@link AgentSkillRepository} 加载技能，支持自学习循环机制</li>
 *   <li>上下文压缩前执行内存落盘与消息卸载</li>
 *   <li>由工作空间统一管理 {@code tools.json}（包含MCP服务配置、黑白名单权限过滤）</li>
 *   <li>规划模式（只读方案设计阶段），配套 {@code plan_enter}/{@code plan_write}/{@code plan_exit} 工具</li>
 *   <li>借助 {@link CompactionMiddleware} 对溢出上下文执行紧急压缩</li>
 * </ul>
 *
 * <p><b>线程安全说明：</b>{@code HarnessAgent} 在多次调用间无状态，可作为单例并发服务多用户、多会话。
 * 每次 {@code call()} 调用依靠 {@link io.agentscope.core.agent.RuntimeContext} 中的(userId, sessionId)二元组隔离运行状态。
 * 同一会话的多次调用会自动串行执行，不同会话之间可并行运行。
 */
public class HarnessAgent implements Agent, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgent.class);

    /** 被封装的内层 ReActAgent，所有 call/stream 最终都委托给它执行推理循环。 */
    private final ReActAgent delegate;

    /** 主工作空间管理器，持有工作空间目录、文件系统与索引；未配置工作空间时为 null。 */
    private final WorkspaceManager workspaceManager;

    /**
     * 带外工作空间工厂：按 (userId, sessionId) 创建绑定了该身份的 WorkspaceManager，
     * 供不经过 call() 主流程的 IO 场景（如网关、后台任务）访问对应用户的命名空间数据。
     */
    private final BiFunction<String, String, WorkspaceManager> workspaceFactory;

    /** 由本智能体自行创建（因而也由其负责关闭）的工作空间索引；为 null 表示索引归外部管理。 */
    private final WorkspaceIndex ownedWorkspaceIndex;

    /** 默认沙箱上下文（沙箱模式下由 SandboxFilesystemSpec 构建），注入每次调用的 RuntimeContext。 */
    private final SandboxContext defaultSandboxContext;

    /** 上下文压缩中间件实例；同时充当"溢出紧急压缩"恢复路径的开关（null 则无法自动恢复）。 */
    private final CompactionMiddleware compactionHook;

    /** 沙箱生命周期中间件；非沙箱模式下为 null。wrappedCall/stream 用它做调用级沙箱申请与释放。 */
    private final SandboxLifecycleMiddleware sandboxLifecycleMw;

    /** 技能仓库有序列表（低优先级 → 高优先级叠加），用于动态技能加载。 */
    private final List<AgentSkillRepository> skillRepositories;

    /** 规划模式管理器；未启用 enablePlanMode 时为 null。 */
    private final PlanModeManager planModeManager;

    // Skill self-learning — null unless enableSkillManageTool / enableSkillCurator.
    // 技能自学习组件：仅在开启enableSkillManageTool、enableSkillCurator配置项时实例化，否则为空。
    private final SkillPromoter skillPromoter;

    /** 技能调用统计的边车存储（skill_manage 链路启用时才有值）。 */
    private final SkillUsageStore skillUsageStore;

    private final SkillCurator skillCurator;
    private final SkillAuditLog skillAuditLog;

    /** 长期记忆配置（落盘/整合/维护参数），构造时缺省回退为 {@link MemoryConfig#defaults()}。 */
    private final MemoryConfig memoryConfig;

    private final Toolkit ownedMcpToolkit;

    /** The subagent middleware (either SubagentsMiddleware or DynamicSubagentsMiddleware). */
    /** 子智能体中间件（可为SubagentsMiddleware或DynamicSubagentsMiddleware）。 */
    private final Object subagentMiddleware;

    /**
     * Distributed storage store, retained so the lazily-created gateway can build a durable
     * {@link io.agentscope.harness.agent.gateway.SubagentRegistry} for cross-node exposed-subagent
     * recovery. {@code null} for purely local deployments.
     */
    /**
     * 分布式存储实例，予以保留以支持延迟创建网关，网关可构建持久化的
     * {@link io.agentscope.harness.agent.gateway.SubagentRegistry}，用于跨节点暴露子智能体的故障恢复。
     * 纯本地部署场景下该值为 {@code null}。
     */
    private final DistributedStore distributedStore;

    /** 工作空间路径归一化器，将绝对工作空间路径折叠为相对形式供工具与 shell 使用。 */
    private final WorkspacePathNormalizer pathNormalizer;

    /** Lazily created internal gateway for {@link #channel}. */
    /** 为 {@link #channel} 延迟创建的内部网关。 */
    private volatile HarnessGateway internalGateway;

    /**
     * 私有全参构造器，仅由 {@link Builder#build()} 调用。
     * 所有编排工作（中间件注册、工具装配、子智能体布线）都在 Builder 中完成，
     * 本构造器只做字段固化；skillRepositories 做保护性拷贝、memoryConfig 缺省兜底。
     */
    private HarnessAgent(
            ReActAgent delegate,
            WorkspaceManager workspaceManager,
            BiFunction<String, String, WorkspaceManager> workspaceFactory,
            WorkspaceIndex ownedWorkspaceIndex,
            SandboxContext defaultSandboxContext,
            CompactionMiddleware compactionHook,
            SandboxLifecycleMiddleware sandboxLifecycleMw,
            List<AgentSkillRepository> skillRepositories,
            PlanModeManager planModeManager,
            SkillPromoter skillPromoter,
            SkillUsageStore skillUsageStore,
            SkillCurator skillCurator,
            SkillAuditLog skillAuditLog,
            MemoryConfig memoryConfig,
            Object subagentMiddleware,
            DistributedStore distributedStore,
            WorkspacePathNormalizer pathNormalizer,
            Toolkit ownedMcpToolkit) {
        this.delegate = delegate;
        this.ownedMcpToolkit = ownedMcpToolkit;
        this.workspaceManager = workspaceManager;
        this.workspaceFactory = workspaceFactory;
        this.ownedWorkspaceIndex = ownedWorkspaceIndex;
        this.defaultSandboxContext = defaultSandboxContext;
        this.compactionHook = compactionHook;
        this.sandboxLifecycleMw = sandboxLifecycleMw;
        this.skillRepositories =
                skillRepositories != null ? List.copyOf(skillRepositories) : List.of();
        this.planModeManager = planModeManager;
        this.skillPromoter = skillPromoter;
        this.skillUsageStore = skillUsageStore;
        this.skillCurator = skillCurator;
        this.skillAuditLog = skillAuditLog;
        this.memoryConfig = memoryConfig != null ? memoryConfig : MemoryConfig.defaults();
        this.subagentMiddleware = subagentMiddleware;
        this.distributedStore = distributedStore;
        this.pathNormalizer = pathNormalizer;
    }

    /** Returns the workspace manager bound to this agent, or {@code null} if not configured. */
    /** 返回绑定至当前智能体的工作空间管理器，未配置时返回 {@code null}。 */
    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    /**
     * Returns a {@link WorkspaceManager} view whose filesystem and namespace are bound to the
     * given {@code (userId, sessionId)} for the duration of the returned view's IO. Unlike
     * {@link #getWorkspaceManager()}, this does not mutate any shared state on this agent — so it
     * is safe to call concurrently from per-request controllers without racing with active chats.
     */
    /**
     * 返回一个 {@link WorkspaceManager} 视图，该视图在IO操作期间，文件系统与命名空间均绑定指定的
     * {@code (userId, sessionId)}。与 {@link #getWorkspaceManager()} 不同，该方法不会修改当前智能体的共享状态，
     * 因此可在各请求控制器中并发调用，不会与正在运行的会话产生竞态问题。
     */
    public WorkspaceManager workspaceFor(String userId, String sessionId) {
        if (workspaceFactory == null) {
            return workspaceManager;
        }
        return workspaceFactory.apply(userId, sessionId);
    }

    /** Returns the {@link CompactionMiddleware} instance if compaction was configured, or {@code null}. */
    /** 若已配置上下文压缩，则返回对应的 {@link CompactionMiddleware} 实例，否则返回 {@code null}。 */
    public CompactionMiddleware getCompactionHook() {
        return compactionHook;
    }

    /**
     * Returns the ordered list of {@link AgentSkillRepository} instances bound to this agent (low
     * to high priority).
     */
    /**
     * 返回绑定至当前智能体、按优先级从低到高排序的 {@link AgentSkillRepository} 实例列表。
     */
    public List<AgentSkillRepository> getSkillRepositories() {
        return skillRepositories;
    }

    /** Access to the sidecar telemetry store. Null when {@code enableSkillManageTool}
     * was not configured. */
    /** 边车遥测存储访问对象，未配置enableSkillManageTool时为null。 */
    public SkillUsageStore getSkillUsageStore() {
        return skillUsageStore;
    }

    /**
     * Query the audit log for a given UTC day. Pass {@code null} for "today". Returns an empty
     * list when the audit log is not configured (no {@code enableSkillManageTool} call).
     */
    /**
     * 查询指定UTC日期的审计日志，传入null则查询当日日志。未开启enableSkillManageTool、未配置审计日志时返回空列表。
     */
    public List<SkillAuditLog.Entry> queryAudit(
            String dayUtc, java.util.function.Predicate<SkillAuditLog.Entry> filter) {
        if (skillAuditLog == null) {
            return List.of();
        }
        return skillAuditLog.query(dayUtc, filter);
    }

    /**
     * Force-run the skill curator immediately, bypassing the idle-and-interval gate. Returns
     * a {@code Mono} that emits {@code null} when the curator is not configured.
     */
    /**
     * 绕过空闲与时间间隔限制，立即强制执行技能整理器。未配置整理器时，返回的Mono将推送null。
     */
    public Mono<SkillCurator.CuratorRunReport> runCuratorOnce() {
        if (skillCurator == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> skillCurator.runOnce(null));
    }

    /**
     * Promote a draft skill for the explicitly supplied request context.
     *
     * <p>Callers promoting skills outside an active {@code call(...)} must use this overload when
     * the workspace is user- or session-scoped so the draft is resolved and moved within the
     * correct namespace.
     */
    /**
     * 通过已配置的 {@link SkillPromotionGate}，将草稿目录 {@code skills/_drafts/} 中的草稿技能正式迁移至正式技能根目录。
     */
    public Mono<SkillPromoter.PromotionResult> promoteSkill(
            String name, String reviewerId, RuntimeContext ctx) {
        if (skillPromoter == null) {
            return Mono.just(
                    SkillPromoter.PromotionResult.invalid(
                            "skill promoter not configured; call"
                                    + " enableSkillManageTool(...) on the builder"));
        }
        return skillPromoter.promote(name, reviewerId, ctx);
    }

    /**
     * Enters plan mode for the session identified by the given {@link RuntimeContext}.
     * The change is persisted so the next {@code call} on that session sees it.
     */
    /**
     * 为指定 {@link RuntimeContext} 对应的会话开启规划模式。该状态变更会持久化，该会话的下一次call调用将生效。
     */
    public void enterPlanMode(RuntimeContext ctx) {
        enterPlanMode(ctx.getUserId(), ctx.getSessionId());
    }

    /** Exits plan mode for the session identified by the given {@link RuntimeContext}. */
    /** 退出指定 {@link RuntimeContext} 对应会话的规划模式。 */
    public void exitPlanMode(RuntimeContext ctx) {
        exitPlanMode(ctx.getUserId(), ctx.getSessionId());
    }

    /** @return whether plan mode is active for the session identified by the given {@link RuntimeContext}. */
    /** @return 判断指定 {@link RuntimeContext} 对应的会话是否处于规划模式。 */
    public boolean isPlanModeActive(RuntimeContext ctx) {
        return isPlanModeActive(ctx.getUserId(), ctx.getSessionId());
    }

    /**
     * Clears the model-visible conversation context for the session identified by {@code ctx}.
     *
     * <p>The session identity and non-conversation state are preserved. The next call starts with
     * an empty conversation context. This method does not cancel an in-flight call.
     *
     * @param ctx runtime context identifying the session
     */
    public void clearContext(RuntimeContext ctx) {
        delegate.clearContext(ctx);
    }

    /**
     * Clears the model-visible conversation context for one {@code (userId, sessionId)} session.
     *
     * <p>The session identity and non-conversation state are preserved. The next call starts with
     * an empty conversation context. This method does not cancel an in-flight call.
     *
     * @param userId user identity for the slot ({@code null} = anonymous / single-tenant)
     * @param sessionId session identity; {@code null} or blank uses the default session id
     */
    public void clearContext(String userId, String sessionId) {
        delegate.clearContext(userId, sessionId);
    }

    /**
     * Clears all locally cached per-session state and permission engines held by the wrapped
     * {@link ReActAgent}. Persisted state in the configured {@link AgentStateStore} is preserved.
     */
    public void clearStateCache() {
        delegate.clearStateCache();
    }

    /**
     * Clears the locally cached state and permission engine for the session identified by
     * {@code ctx}. Persisted state in the configured {@link AgentStateStore} is preserved.
     *
     * @param ctx runtime context identifying the session
     */
    public void clearStateCache(RuntimeContext ctx) {
        delegate.clearStateCache(ctx);
    }

    /**
     * Clears the locally cached state and permission engine for one {@code (userId, sessionId)}
     * slot. Persisted state in the configured {@link AgentStateStore} is preserved.
     *
     * @param userId user identity for the slot ({@code null} = anonymous / single-tenant)
     * @param sessionId session identity; {@code null} or blank uses the default session id
     */
    public void clearStateCache(String userId, String sessionId) {
        delegate.clearStateCache(userId, sessionId);
    }

    /**
     * Enters plan mode for the given {@code (userId, sessionId)} session, independent of which slot
     * is currently active. The change is persisted so the next {@code call} on that session sees
     * it.
     */
    /**
     * 为指定(userId, sessionId)会话开启规划模式，不受当前活跃槽位影响。该状态变更将持久保存，该会话的下一次call调用即可生效。
     */
    public void enterPlanMode(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        if (planModeManager != null) {
            planModeManager.enter(s);
        } else {
            s.getPlanModeContext().setPlanActive(true);
        }
        delegate.saveAgentState(userId, sessionId);
    }

    /** Exits plan mode for the given {@code (userId, sessionId)} session and persists the change. */
    /** 退出指定(userId, sessionId)会话的规划模式，并持久化该状态变更。 */
    public void exitPlanMode(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        if (planModeManager != null) {
            planModeManager.exit(s);
        } else {
            s.getPlanModeContext().setPlanActive(false);
        }
        delegate.saveAgentState(userId, sessionId);
    }

    /** @return whether plan mode is active for the given {@code (userId, sessionId)} session. */
    /** @return 指定(userId, sessionId)会话是否处于规划模式。 */
    public boolean isPlanModeActive(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        return s.getPlanModeContext().isPlanActive();
    }

    /**
     * Switches the {@link io.agentscope.core.permission.PermissionMode} for the session identified
     * by the given {@link RuntimeContext} at runtime. See
     * {@link ReActAgent#setPermissionMode(String, String, io.agentscope.core.permission.PermissionMode)}.
     */
    /**
     * 运行时切换指定{@link RuntimeContext}对应会话的{@link io.agentscope.core.permission.PermissionMode}权限模式，
     * 详情参考{@link ReActAgent#setPermissionMode(String, String, io.agentscope.core.permission.PermissionMode)}。
     */
    public void setPermissionMode(
            RuntimeContext ctx, io.agentscope.core.permission.PermissionMode mode) {
        delegate.setPermissionMode(ctx, mode);
    }

    /**
     * Switches the {@link io.agentscope.core.permission.PermissionMode} for the given
     * {@code (userId, sessionId)} session at runtime, preserving configured rules and rebuilding
     * the cached permission engine.
     */
    /**
     * 运行时切换指定(userId, sessionId)会话的{@link io.agentscope.core.permission.PermissionMode}权限模式，保留原有配置规则并重建权限引擎缓存。
     */
    public void setPermissionMode(
            String userId, String sessionId, io.agentscope.core.permission.PermissionMode mode) {
        delegate.setPermissionMode(userId, sessionId, mode);
    }

    /** @return the current permission mode for the given {@code (userId, sessionId)} session. */
    /** @return 返回指定(userId, sessionId)会话当前的权限模式。 */
    public io.agentscope.core.permission.PermissionMode getPermissionMode(
            String userId, String sessionId) {
        return delegate.getPermissionMode(userId, sessionId);
    }

    /**
     * 关闭智能体并释放全部资源。顺序：先停后台任务仓库（防止任务回调继续触发），
     * 再关闭本智能体拥有的工作空间索引，最后关闭内层 ReActAgent。
     * 每一步用 finally 串联，确保前置步骤失败也不阻塞后续释放。
     */
    @Override
    public void close() {
        try {
            // Drain fire-and-forget session/transcript mirrors so async workspace writes do not
            // race with resource cleanup (e.g., temp workspace deletion in tests).
            io.agentscope.harness.agent.memory.session.SessionTree.awaitMirrorQuiescence(
                    5, java.util.concurrent.TimeUnit.SECONDS);
            // Drain fire-and-forget memory flush/maintenance so async memory/*.md writes do not
            // race with resource cleanup (e.g., temp workspace deletion in tests).
            io.agentscope.harness.agent.memory.MemoryBackgroundTasks.awaitQuiescence(
                    5, java.util.concurrent.TimeUnit.SECONDS);
            shutdownTaskRepository();
        } finally {
            try {
                if (ownedWorkspaceIndex != null) {
                    ownedWorkspaceIndex.close();
                }
            } finally {
                ownedMcpToolkit.closeMcpClients();
                delegate.close();
            }
        }
    }

    /** 若子智能体中间件持有工作空间型任务仓库，则停止其后台任务线程。 */
    private void shutdownTaskRepository() {
        TaskRepository taskRepo = null;
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            taskRepo = sm.getTaskRepository();
        } else if (subagentMiddleware instanceof DynamicSubagentsMiddleware dsm) {
            taskRepo = dsm.getTaskRepository();
        }
        if (taskRepo != null) {
            taskRepo.shutdown();
        }
    }

    // ==================== Agent interface delegation ====================
    // ==================== Agent 接口委托区 ====================
    // 以下方法均直接转发给内层 delegate，HarnessAgent 本身不叠加任何行为。

    /** Returns the wrapped {@link ReActAgent}. */
    /** 返回被封装的 {@link ReActAgent} 实例。 */
    public ReActAgent getDelegate() {
        return delegate;
    }

    /** 返回当前使用的模型实例。 */
    public Model getModel() {
        return delegate.getModel();
    }

    /** 返回 ReAct 推理循环的最大迭代轮数。 */
    public int getMaxIters() {
        return delegate.getMaxIters();
    }

    /** 返回会话状态存储（AgentState 持久化后端）。 */
    public AgentStateStore getStateStore() {
        return delegate.getStateStore();
    }

    public ConflictPolicy getConflictPolicy() {
        return delegate.getConflictPolicy();
    }

    /**
     * The distributed store configured on this agent, or {@code null} for local
     * deployments. Exposed so {@link io.agentscope.harness.agent.gateway.GatewayBootstrap} can build
     * a durable {@link io.agentscope.harness.agent.gateway.SubagentRegistry} for exposed-subagent
     * recovery.
     */
    /**
     * 当前智能体配置的分布式存储，本地部署时为null。
     * 对外暴露该对象，
     * 供{@link io.agentscope.harness.agent.gateway.GatewayBootstrap}构建持久化的子注册中心，
     * 用于对外子智能体故障恢复。
     */
    public DistributedStore getDistributedStore() {
        return distributedStore;
    }

    /**
     * The internal {@link io.agentscope.harness.agent.subagent.DefaultAgentManager} able to
     * re-materialize this agent's subagents, or {@code null} when none is owned (e.g. session-mode
     * external subagent tool). Used to wire a gateway materializer for cross-node recovery.
     */
    /**
     * 内部的{@link io.agentscope.harness.agent.subagent.DefaultAgentManager}，可重新实例化当前智能体的子智能体；
     * 无归属管理器（如会话模式外部子智能体工具）时值为null。用于对接网关实例化组件，实现跨节点故障恢复。
     */
    public io.agentscope.harness.agent.subagent.DefaultAgentManager getSubagentAgentManager() {
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            return sm.getAgentManager();
        }
        if (subagentMiddleware instanceof DynamicSubagentsMiddleware dm) {
            return dm.getAgentManager();
        }
        return null;
    }

    /**
     * Background subagent {@link TaskRepository} owned by the subagent middleware, or {@code null}
     * when subagents are not configured.
     */
    public TaskRepository getTaskRepository() {
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            return sm.getTaskRepository();
        }
        if (subagentMiddleware instanceof DynamicSubagentsMiddleware dsm) {
            return dsm.getTaskRepository();
        }
        return null;
    }

    /** @see ReActAgent#getDefaultSessionId() */
    /** 参考 ReActAgent#getDefaultSessionId() */
    public String getDefaultSessionId() {
        return delegate.getDefaultSessionId();
    }

    /**
     * @deprecated Use {@link #getDelegate()}{@code .getAgentState(RuntimeContext)} or
     *     {@code .getAgentState(String, String)} with explicit session identity.
     */
    /**
     * @deprecated 请使用 {@link #getDelegate()}{@code .getAgentState(RuntimeContext)}
     * 或携带明确会话标识的 {@code .getAgentState(String, String)} 方法。
     */
    @Deprecated
    @Override
    public AgentState getAgentState() {
        return delegate.getAgentState();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getAgentId() {
        return delegate.getAgentId();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    /** 中断当前正在执行的调用（不附加消息）。 */
    @Override
    public void interrupt() {
        delegate.interrupt();
    }

    /** 中断当前调用，并以给定消息作为本轮的最终回复收尾。 */
    @Override
    public void interrupt(Msg msg) {
        delegate.interrupt(msg);
    }

    /**
     * Interrupts the in-flight call for the session identified by {@code ctx}.
     *
     * @param ctx runtime context identifying the session to interrupt
     */
    public void interrupt(RuntimeContext ctx) {
        delegate.interrupt(ctx);
    }

    /**
     * Interrupts the in-flight call for the session identified by {@code ctx} with an associated
     * user message.
     *
     * @param ctx runtime context identifying the session to interrupt
     * @param msg optional user message to attach to the interrupt signal
     */
    public void interrupt(RuntimeContext ctx, Msg msg) {
        delegate.interrupt(ctx, msg);
    }

    /**
     * Interrupts the in-flight call for a specific {@code (userId, sessionId)} session.
     *
     * @param userId user identity for the slot ({@code null} = anonymous / single-tenant)
     * @param sessionId session identity; {@code null} or blank uses the default session id
     */
    public void interrupt(String userId, String sessionId) {
        delegate.interrupt(userId, sessionId);
    }

    /**
     * Interrupts the in-flight call for a specific {@code (userId, sessionId)} session with an
     * associated user message.
     */
    public void interrupt(String userId, String sessionId, Msg msg) {
        delegate.interrupt(userId, sessionId, msg);
    }

    // -----------------------------------------------------------------
    //  Channel / Gateway
    // -----------------------------------------------------------------

    /**
     * Returns a channel bound to this agent's internal gateway. The gateway is created lazily on
     * the first call and this agent is registered as the sole (main) agent.
     *
     * <p>Typical usage in a Spring controller:
     * <pre>{@code
     * HarnessAgent agent = HarnessAgent.builder()...build();
     * ChatUiChannel chat = agent.channel(ChatUiChannel.perPeer());
     *
     * // in controller
     * chat.send("user-123", "hello");
     * }</pre>
     *
     * @param channel the channel instance to bind (e.g. {@code ChatUiChannel.perPeer()})
     * @param <T> the channel type
     * @return the same channel instance, now wired to this agent's gateway
     */
    /**
     * 获取绑定至当前智能体内置网关的通道。网关在首次调用时延迟创建，当前智能体将注册为唯一主智能体。
     *
     * <p>Spring控制器常规用法示例：
     * <pre>{@code
     * HarnessAgent agent = HarnessAgent.builder()...build();
     * ChatUiChannel chat = agent.channel(ChatUiChannel.perPeer());
     *
     * // 控制器内部
     * chat.send("user-123", "hello");
     * }</pre>
     *
     * @param channel 待绑定的通道实例（例如 {@code ChatUiChannel.perPeer()}）
     * @param <T> 通道泛型类型
     * @return 已对接当前智能体网关的原通道实例
     */
    public <T extends Channel> T channel(T channel) {
        ensureGateway();
        channel.init(internalGateway);
        return channel;
    }

    /**
     * Returns the internal gateway backing {@link #channel}. Creates it lazily if not yet
     * initialized. Exposed for advanced usage (e.g. direct {@code runSubagent} calls).
     */
    /**
     * 返回支撑{@link #channel}方法的内部网关，未初始化时将延迟创建。对外开放用于高级场景（例如直接调用runSubagent）。
     */
    public HarnessGateway gateway() {
        ensureGateway();
        return internalGateway;
    }

    /**
     * 延迟初始化内部网关（加锁保证只创建一次）。步骤：
     * 1) 创建网关并把本智能体注册为主智能体；
     * 2) 向子智能体中间件注入网关桥——子智能体被对外暴露时，经由网关登记并返回全局 subagentId；
     * 3) 若持有 AgentManager / 分布式存储，则进一步接入跨节点重建与持久化注册能力。
     */
    private synchronized void ensureGateway() {
        if (internalGateway != null) {
            return;
        }
        HarnessGateway gw = HarnessGateway.create();
        gw.bindMainAgent(this);

        // 网关桥实现：把"对外暴露子智能体"的请求转发给网关，返回网关分配的 subagentId。
        SubagentGatewayBridge bridge =
                (agentId, sessionId, agent, replyTo) -> {
                    String subagentId = gw.exposeSubagent(agentId, sessionId, agent, replyTo);
                    return new SubagentGatewayBridge.ExposeResult(subagentId);
                };
        io.agentscope.harness.agent.subagent.DefaultAgentManager agentManager = null;
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            sm.setGatewayBridge(bridge);
            agentManager = sm.getAgentManager();
        } else if (subagentMiddleware instanceof DynamicSubagentsMiddleware dm) {
            dm.setGatewayBridge(bridge);
            agentManager = dm.getAgentManager();
        }

        // Wire exposed-subagent recovery: a materializer rebuilds the agent on any node, and a
        // durable registry (when a distributed store is present) makes the subagentId resolvable
        // beyond this process. Without these, exposure stays in-process (legacy behaviour).
        // 接入对外子Agent故障恢复能力：实例化器可在任意节点重建智能体，
        // 同时若配置分布式存储，持久化注册中心可让子Agent标识脱离当前进程实现全局解析。
        // 缺少以上二者时，对外暴露能力仅局限于进程内部（兼容旧版逻辑）。
        if (agentManager != null) {
            final io.agentscope.harness.agent.subagent.DefaultAgentManager am = agentManager;
            gw.setSubagentMaterializer(am::createAgentIfPresent);
        }
        if (distributedStore != null) {
            gw.setSubagentRegistry(
                    new io.agentscope.harness.agent.gateway.StoreBackedSubagentRegistry(
                            distributedStore.baseStore()));
            gw.setBaseStore(distributedStore.baseStore());
            io.agentscope.harness.agent.gateway.SessionTurnGate turnGate =
                    distributedStore.sessionTurnGate();
            if (turnGate != null) {
                gw.setSessionTurnGate(turnGate);
            }
        }

        this.internalGateway = gw;
    }

    /**
     * @deprecated Use {@link #call(List, RuntimeContext)} with explicit runtime context.
     */
    /**
     * @deprecated 请使用携带显式运行上下文的 {@link #call(List, RuntimeContext)} 方法。
     */
    @Deprecated(since = "2.2.0")
    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return wrappedCall(msgs, RuntimeContext.empty(), () -> delegate.call(msgs));
    }

    /**
     * @deprecated Use {@link #call(List, Class, RuntimeContext)} with explicit runtime context.
     */
    /**
     * @deprecated 请使用携带显式运行上下文的{@link #call(List, Class, RuntimeContext)}方法。
     */
    @Deprecated(since = "2.2.0")
    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return wrappedCall(
                msgs, RuntimeContext.empty(), () -> delegate.call(msgs, structuredModel));
    }

    /**
     * @deprecated Use {@link #call(List, JsonNode, RuntimeContext)} with explicit runtime context.
     */
    /**
     * @deprecated 请使用携带显式运行上下文的{@link #call(List, JsonNode, RuntimeContext)}方法。
     */
    @Deprecated(since = "2.2.0")
    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return wrappedCall(msgs, RuntimeContext.empty(), () -> delegate.call(msgs, schema));
    }

    /** 单条消息 + 运行上下文的便捷入口，等价于 {@code call(List.of(msg), ctx)}。 */
    public Mono<Msg> call(Msg msg, RuntimeContext ctx) {
        return call(List.of(msg), ctx);
    }

    /**
     * Calls the agent with a plain text input and per-call {@link RuntimeContext}.
     *
     * @param text input text (wrapped into a {@link UserMessage})
     * @param ctx  per-call runtime context
     * @return response message
     */
    /**
     * 以纯文本输入结合单次调用独立的{@link RuntimeContext}执行智能体调用。
     *
     * @param text 输入文本，将被封装为{@link UserMessage}
     * @param ctx 单次调用运行上下文
     * @return 响应消息
     */
    public Mono<Msg> call(String text, RuntimeContext ctx) {
        return call(new UserMessage(text), ctx);
    }

    /**
     * 主调用入口：先经 {@link #ensureSessionDefaults} 补全会话缺省值，
     * 再交给 {@link #wrappedCall} 叠加沙箱生命周期与溢出恢复包装，最终委托内层执行。
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, effective));
    }

    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, structuredModel, effective));
    }

    public Mono<Msg> call(List<Msg> msgs, JsonNode schema, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, schema, effective));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the fine-grained
     *     {@code AgentEvent} stream that aligns with Python 2.0's {@code agent.reply_stream()}.
     */
    /**
     * @deprecated 自2.0.0版本起标记废弃，后续将移除。
     * 如需获取精细化的AgentEvent事件流（与Python 2.0的agent.reply_stream()对齐），请使用{@link #streamEvents(List)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return wrappedStream(RuntimeContext.empty(), () -> delegate.stream(msgs, options));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the fine-grained
     *     {@code AgentEvent} stream.
     */
    /**
     * @deprecated 自2.0.0版本起废弃，后续将移除。如需精细化AgentEvent事件流，请使用{@link #streamEvents(List)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return wrappedStream(
                RuntimeContext.empty(), () -> delegate.stream(msgs, options, structuredModel));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the fine-grained
     *     {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return wrappedStream(RuntimeContext.empty(), () -> delegate.stream(msgs, options, schema));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(Msg, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(Msg msg, RuntimeContext ctx) {
        return stream(List.of(msg), StreamOptions.defaults(), ctx);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, RuntimeContext ctx) {
        return stream(msgs, StreamOptions.defaults(), ctx);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(effective, () -> delegate.stream(msgs, options, effective));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    /**
     * @deprecated 自2.0.0版本起废弃，后续将移除。请使用{@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, Class<?> structuredModel, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(
                effective, () -> delegate.stream(msgs, options, structuredModel, effective));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    /**
     * @deprecated 自2.0.0版本起废弃，后续将移除。请使用{@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(effective, () -> delegate.stream(msgs, options, schema, effective));
    }

    /**
     * Prepare a cancellable execution covering the complete harness/sandbox lifecycle. Adopts the
     * context's runId ({@code run.runId() == ctx.getRunId()}); {@code ensureSessionDefaults}
     * still runs at subscribe time and never alters it. A null context uses a fresh {@link
     * RuntimeContext#empty()} so derived defaults inherit this runId.
     */
    public AgentRun<AgentEvent> prepareRun(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext source = ctx != null ? ctx : RuntimeContext.empty();
        return AgentRun.create(getAgentId(), source.getRunId(), () -> streamEvents(msgs, source));
    }

    /**
     * Prepare a cancellable reply execution covering the complete harness/sandbox lifecycle.
     * Adopts the context's runId ({@code run.runId() == ctx.getRunId()});
     * {@code ensureSessionDefaults} still runs at subscribe time and never alters it. A null
     * context uses a fresh {@link RuntimeContext#empty()} so derived defaults inherit this
     * runId.
     */
    public AgentRun<Msg> prepareCall(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext source = ctx != null ? ctx : RuntimeContext.empty();
        return AgentRun.create(getAgentId(), source.getRunId(), () -> call(msgs, source));
    }

    // ==================== streamEvents (AgentEvent — v2 aligned) ====================

    /**
     * @deprecated Use {@link #streamEvents(Msg, RuntimeContext)} with explicit runtime context.
     */
    /**
     * @deprecated 请使用携带显式运行上下文的 {@link #streamEvents(Msg, RuntimeContext)} 方法。
     */
    @Deprecated(since = "2.2.0")
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg), RuntimeContext.empty());
    }

    /**
     * @deprecated Use {@link #streamEvents(List, RuntimeContext)} with explicit runtime context.
     */
    /**
     * @deprecated 请使用携带显式运行上下文的 {@link #streamEvents(List, RuntimeContext)} 方法。
     */
    @Deprecated(since = "2.2.0")
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, RuntimeContext.empty());
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single message with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param msg input message
     * @param ctx runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    /**
     * 基于调用方提供的{@link RuntimeContext}，为单条消息流式输出精细化{@link AgentEvent}事件。
     *
     * @param msg 输入消息
     * @param ctx 本次调用传递使用的运行上下文
     * @return 覆盖智能体完整调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext ctx) {
        return streamEvents(List.of(msg), ctx);
    }

    /**
     * @deprecated Use {@link #streamEvents(String, RuntimeContext)} with explicit runtime context.
     */
    /**
     * @deprecated 请使用携带显式运行上下文的 {@link #streamEvents(String, RuntimeContext)} 方法。
     */
    @Deprecated(since = "2.2.0")
    public Flux<AgentEvent> streamEvents(String text) {
        return streamEvents(new UserMessage(text), RuntimeContext.empty());
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a plain text input with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param text input text (wrapped into a {@link UserMessage})
     * @param ctx  runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    /**
     * 基于调用方提供的{@link RuntimeContext}，将纯文本输入封装后流式输出精细化{@link AgentEvent}事件。
     *
     * @param text 输入文本，会被封装为{@link UserMessage}
     * @param ctx 本次调用所使用的运行上下文
     * @return 贯穿智能体完整调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(String text, RuntimeContext ctx) {
        return streamEvents(new UserMessage(text), ctx);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a list of messages with a caller-supplied
     * {@link RuntimeContext}. The harness wraps the delegate's
     * {@code ReActAgent#streamEvents(List, RuntimeContext)} with the same sandbox-lifecycle
     * acquire/release semantics that the {@code call(...)} family uses, so streaming and
     * blocking callers behave consistently with respect to sandbox warm-up.
     *
     * <p>Synchronous subagent events spawned via {@code agent_spawn} / {@code agent_send} are
     * forwarded into this stream in real time with a {@link AgentEvent#getSource() source} tag
     * identifying the originating child agent.
     *
     * @param msgs input messages
     * @param ctx runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    /**
     * 依托调用方传入的{@link RuntimeContext}，对一组消息流式输出精细化{@link AgentEvent}事件。
     * 该容器封装了委托对象的ReActAgent#streamEvents(List, RuntimeContext)方法，沿用与call系列方法一致的沙箱生命周期申请与释放逻辑，保证流式调用与阻塞调用在沙箱预热行为上保持统一。
     *
     * <p>通过agent_spawn、agent_send生成的同步子智能体事件会实时推送至当前事件流，同时携带{@link AgentEvent#getSource()}来源标记，用于区分事件所属子智能体。
     *
     * @param msgs 输入消息列表
     * @param ctx 本次调用全程沿用的运行上下文
     * @return 覆盖智能体完整调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStreamEvents(effective, () -> delegate.streamEvents(msgs, effective));
    }

    /** 旁路注入一条消息到会话记忆，不触发推理。 */
    @Override
    public Mono<Void> observe(Msg msg) {
        return delegate.observe(msg);
    }

    /** 旁路注入多条消息到会话记忆，不触发推理。 */
    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return delegate.observe(msgs);
    }

    /** 返回内层智能体的工具集。 */
    public Toolkit getToolkit() {
        return delegate.getToolkit();
    }

    // ==================== Call/stream wrappers ====================
    // ==================== 调用/流式包装区 ====================
    // 以下三个 wrapped* 方法是 call/stream/streamEvents 的统一包装层：
    // 负责沙箱调用级申请/释放，wrappedCall 额外负责上下文溢出恢复。

    /**
     * 阻塞式调用的统一包装，是主流程的关键一环。两层包装：
     *
     * <p>1. <b>沙箱生命周期</b>：{@link Mono#using} 在订阅时调用
     * {@code acquireForCall} 申请会话级沙箱（含预热/恢复），无论调用成功或失败，
     * 终止/取消时都会经 {@code releaseForCall} 归还引用计数；
     *
     * <p>2. <b>上下文溢出恢复</b>：仅当配置了压缩中间件时生效——捕获
     * {@link #isContextOverflowError} 判定为上下文超限的错误后，转交
     * {@link #recoverFromOverflow} 执行紧急压缩并重试；其他错误原样抛出。
     */
    private Mono<Msg> wrappedCall(
            List<Msg> msgs, RuntimeContext effective, Supplier<Mono<Msg>> inner) {
        Mono<Msg> base =
                Mono.using(
                        () -> {
                            // 资源工厂：申请本次调用所需的沙箱
                            if (sandboxLifecycleMw != null) {
                                sandboxLifecycleMw.acquireForCall(effective);
                            }
                            return effective;
                        },
                        eff -> inner.get(),
                        eff -> {
                            // 清理回调：释放沙箱引用（成功、失败、取消均会执行）
                            if (sandboxLifecycleMw != null) {
                                sandboxLifecycleMw.releaseForCall(eff);
                            }
                        });
        if (compactionHook != null) {
            // 错误拦截：识别上下文溢出 → 紧急压缩后重试
            return base.onErrorResume(
                    e -> {
                        if (isContextOverflowError(e)) {
                            return recoverFromOverflow(msgs, effective);
                        }
                        return Mono.error(e);
                    });
        }
        return base;
    }

    /**
     * @deprecated since 2.0.0, for removal alongside the {@link #stream(List, StreamOptions)}
     *     family. Replaced by {@link #wrappedStreamEvents(RuntimeContext, Supplier)}.
     */
    /**
     * @deprecated 自2.0.0版本起废弃，随 {@link #stream(List, StreamOptions)} 系列一同移除。
     * 已由 {@link #wrappedStreamEvents(RuntimeContext, Supplier)} 取代。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    private Flux<Event> wrappedStream(RuntimeContext effective, Supplier<Flux<Event>> inner) {
        return Flux.using(
                () -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.acquireForCall(effective);
                    }
                    return effective;
                },
                eff -> inner.get(),
                eff -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.releaseForCall(eff);
                    }
                });
    }

    /**
     * AgentEvent 事件流的统一包装：与 {@link #wrappedCall} 相同的沙箱申请/释放语义
     * （{@link Flux#using}），保证流式与阻塞调用在沙箱预热行为上一致。
     * 注意：流式路径不做溢出恢复——事件流中途溢出时直接向上抛出错误。
     */
    private Flux<AgentEvent> wrappedStreamEvents(
            RuntimeContext effective, Supplier<Flux<AgentEvent>> inner) {
        return Flux.using(
                () -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.acquireForCall(effective);
                    }
                    return effective;
                },
                eff -> inner.get(),
                eff -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.releaseForCall(eff);
                    }
                });
    }

    /**
     * Fills in a default {@code sessionId} when the caller didn't provide one, and injects the
     * default sandbox context. The agent's persistence backend is bound at builder time via
     * {@code .stateStore(...)}; the per-call routing is via {@code (userId, sessionId)} on the
     * RuntimeContext (consumed by {@code ReActAgent.activateSlotForContext}).
     */
    /**
     * 当调用方未传入会话编号时自动填充默认sessionId，并注入默认沙箱上下文。
     * 智能体持久化存储在构造器构建阶段通过.stateStore(...)绑定；单次调用的路由依据RuntimeContext内的用户ID与会话ID完成，由ReActAgent.activateSlotForContext进行解析使用。
     */
    private RuntimeContext ensureSessionDefaults(RuntimeContext ctx) {
        RuntimeContext source = ctx != null ? ctx : RuntimeContext.empty();
        // 1) sessionId 缺省：未提供时回退为智能体名称，保证状态桶始终存在
        String ctxSessionId = source.getSessionId();
        if (ctxSessionId == null || ctxSessionId.isBlank()) {
            ctxSessionId = getName();
        }
        AbstractFilesystem sourceFs = source.get(AbstractFilesystem.class);
        // 2) 沙箱上下文缺省：调用方未显式指定时，注入构建期创建的默认沙箱上下文
        SandboxContext sandboxCtx =
                source.get(SandboxContext.class) != null
                        ? source.get(SandboxContext.class)
                        : defaultSandboxContext;
        AbstractFilesystem fs = workspaceManager != null ? workspaceManager.getFilesystem() : null;

        // 快速路径：三项都无需调整时直接复用原对象，避免多余拷贝
        if (ctxSessionId.equals(source.getSessionId())
                && sandboxCtx == source.get(SandboxContext.class)
                && (fs == null || sourceFs != null)) {
            return source;
        }
        // 3) 重建上下文：补 sessionId、沙箱上下文、文件系统，并附带工作空间管理器与路径归一化器
        RuntimeContext.Builder b = RuntimeContext.builder(source).sessionId(ctxSessionId);
        b.put(SandboxContext.class, sandboxCtx);
        if (sourceFs == null && fs != null) {
            b.put(AbstractFilesystem.class, fs);
        }
        if (workspaceManager != null) {
            b.put(WorkspaceManager.class, workspaceManager);
        }
        if (pathNormalizer != null) {
            b.put(WorkspacePathNormalizer.class, pathNormalizer);
        }
        return b.build();
    }

    /**
     * 上下文溢出后的恢复入口：配置了压缩中间件则走
     * {@link #forceCompactAndRetry} 紧急压缩并重试；否则无法自愈，直接报错。
     */
    private Mono<Msg> recoverFromOverflow(List<Msg> msgs, RuntimeContext effective) {
        if (compactionHook != null) {
            log.warn(
                    "Context overflow detected, triggering emergency compaction via"
                            + " CompactionMiddleware");
            return forceCompactAndRetry(msgs, effective);
        }
        return Mono.error(
                new RuntimeException(
                        "Context overflow: no compaction configured, unable to recover"));
    }

    /**
     * 紧急压缩并重试，是上下文溢出的最后兜底。流程：
     *
     * <ol>
     *   <li>从 RuntimeContext 解析当前会话的 AgentState，取出全部上下文消息；上下文为空时无从压缩，直接失败；</li>
     *   <li>构造 {@code triggerMessages=1} 的强制压缩配置（无论消息多少都触发），
     *       并按记忆配置装配落盘器 {@link MemoryFlushManager}——压缩前先把关键信息写回工作空间记忆，避免信息丢失；</li>
     *   <li>调用 {@link ConversationCompactor#compactIfNeeded} 生成摘要化后的消息列表；</li>
     *   <li>压缩成功则原地替换会话上下文（clear + addAll），重新发起 {@code delegate.call} 重试；
     *       压缩未产出结果则报错终止。</li>
     * </ol>
     */
    private Mono<Msg> forceCompactAndRetry(List<Msg> msgs, RuntimeContext effective) {
        // 第一步：解析会话状态，读取当前完整上下文
        AgentState state = RuntimeContext.resolveAgentState(effective, delegate);
        List<Msg> allMsgs = state.contextMutable();
        if (allMsgs.isEmpty()) {
            return Mono.error(
                    new RuntimeException("Context overflow: context is empty, cannot compact"));
        }
        String agentId = getName();
        String sessionId =
                effective != null && effective.getSessionId() != null
                        ? effective.getSessionId()
                        : "default";

        // 第二步：triggerMessages=1 表示无视阈值强制执行压缩
        CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
        String effectiveFlushPrompt =
                memoryConfig.flushPrompt() != null
                        ? memoryConfig.flushPrompt()
                        : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
        MemoryFlushManager fm =
                new MemoryFlushManager(workspaceManager, getModel(), effectiveFlushPrompt);
        ConversationCompactor compactor = new ConversationCompactor(getModel(), fm);

        // 第三/四步：执行压缩 → 成功则替换上下文并重试本次调用
        return compactor
                .compactIfNeeded(
                        effective != null ? effective : RuntimeContext.empty(),
                        allMsgs,
                        forceConfig,
                        agentId,
                        sessionId)
                .flatMap(
                        opt -> {
                            if (opt.isPresent()) {
                                state.contextMutable().clear();
                                state.contextMutable().addAll(opt.get());
                                return delegate.call(
                                        msgs,
                                        effective != null ? effective : RuntimeContext.empty());
                            }
                            return Mono.error(
                                    new RuntimeException(
                                            "Context overflow: emergency compaction yielded no"
                                                    + " result"));
                        });
    }

    /**
     * 基于错误消息文本启发式判断是否为"上下文超限"错误。
     * 各家模型服务的报错措辞不统一，故用关键词匹配；仅当命中时才会触发紧急压缩恢复。
     */
    private static boolean isContextOverflowError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens")
                || lower.contains("exceeds the model's maximum")
                || lower.contains("reduce the length");
    }

    /** 创建 {@link Builder} 构建器，所有 harness 能力都通过它配置。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default state directory for the built-in {@link JsonFileAgentStateStore} backend:
     * {@code ~/.agentscope/state/<agentId>/}. Lives outside any workspace so agent state
     * (a prerequisite for restoring the workspace via {@link
     * io.agentscope.harness.agent.sandbox.SandboxState#getWorkspaceSpec()}) is not entangled
     * with workspace data.
     *
     * <p>The base directory ({@code ~/.agentscope/state}) can be overridden via the
     * {@code agentscope.state.home} system property. This is primarily for tests / CI to
     * redirect state into a build-scoped temporary directory rather than polluting the
     * developer's real home directory; production deployments generally don't need it.
     */
    /**
     * 内置{@link JsonFileAgentStateStore}持久化后端的默认状态目录：
     * {@code ~/.agentscope/state/<agentId>/}。该目录独立于工作空间，可确保智能体状态（通过{@link
     * io.agentscope.harness.agent.sandbox.SandboxState#getWorkspaceSpec()}恢复工作空间的前置依赖）不会与工作空间数据混杂在一起。
     *
     * <p>可通过系统属性{@code agentscope.state.home}重新指定根目录{@code ~/.agentscope/state}。
     * 该配置主要用于测试与持续集成场景，将状态文件重定向至构建临时目录，避免污染开发者本地用户目录；生产环境一般无需修改。
     */
    static Path defaultStateDir(String agentId) {
        String override = System.getProperty("agentscope.state.home");
        Path root =
                override != null && !override.isBlank()
                        ? Paths.get(override)
                        : Paths.get(System.getProperty("user.home"), ".agentscope", "state");
        return root.resolve(agentId);
    }

    /** System property that overrides the default workspace directory. */
    static final String WORKSPACE_PROPERTY = "agentscope.workspace";

    /** Environment variable that overrides the default workspace directory. */
    static final String WORKSPACE_ENV = "AGENTSCOPE_WORKSPACE";

    /**
     * Resolves the workspace directory to use when {@link Builder#workspace(Path)} /
     * {@link Builder#workspace(String)} was not set explicitly.
     *
     * <p>Resolution order (highest priority first):
     * <ol>
     *   <li>{@code agentscope.workspace} system property</li>
     *   <li>{@code AGENTSCOPE_WORKSPACE} environment variable</li>
     *   <li>{@code ${user.dir}/.agentscope/workspace} (built-in default)</li>
     * </ol>
     *
     * <p>The system property / environment variable are primarily useful for container image
     * deployments, where the workspace location is injected at run time rather than hard-coded
     * in application code.
     *
     * @return the resolved default workspace directory; never {@code null}
     */
    static Path resolveDefaultWorkspace() {
        String property = System.getProperty(WORKSPACE_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Paths.get(property.strip());
        }
        String env = System.getenv(WORKSPACE_ENV);
        if (env != null && !env.isBlank()) {
            return Paths.get(env.strip());
        }
        return Paths.get(System.getProperty("user.dir")).resolve(".agentscope/workspace");
    }

    /**
     * Returns true when the given session is a local in-process implementation that cannot share
     * state across nodes. Used by sandbox / remote-filesystem fail-fast checks to reject
     * configurations that would silently leak per-node state in distributed deployments.
     */
    /**
     * 若指定会话为仅当前进程生效的本地实现、无法跨节点共享状态，则返回true。
     * 用于沙箱与远程文件系统的快速校验，拦截分布式部署中会造成节点状态静默泄露的不合理配置。
     */
    static boolean isLocalSession(AgentStateStore stateStore) {
        return stateStore instanceof JsonFileAgentStateStore
                || stateStore instanceof InMemoryAgentStateStore;
    }

    // ==================== Builder ====================

    /**
     * Builder for {@link HarnessAgent}. Owns the harness orchestration: workspace + filesystem +
     * sandbox + hooks/middlewares + tools + skills + subagents + tools.json + plan-mode.
     */
    /**
     * {@link HarnessAgent} 的构造器。负责管控容器编排体系：包含工作空间、文件系统、沙箱、钩子与中间件、工具、技能、子智能体、tools.json 配置以及规划模式。
     */
    public static class Builder {

        /** 内层 ReActAgent 构建器：所有镜像字段与 harness 装配的中间件/工具最终都落到它身上。 */
        private final ReActAgent.Builder inner = ReActAgent.builder();

        // ---- Mirrored fields readable by HarnessAgentBuilderSupport ----
        // 与 HarnessAgentBuilderSupport 共享的镜像字段区
        // These mirror the values forwarded to `inner` so the helpers + subagent factories
        // can read them without crossing package-private boundaries on ReActAgent.Builder.
        // 镜像字段：与转发给 inner 的值保持同步，使包内辅助方法与子智能体工厂
        // 无需越过 ReActAgent.Builder 的包私有边界即可读取配置。

        /** 智能体名称；未显式指定 sessionId / agentId 时会作为回退值。 */
        String name;

        String description;
        String sysPrompt;
        boolean checkRunning = true;
        boolean enablePendingToolRecovery = false;

        /** 主模型；同时作为记忆/压缩等辅助链路的默认模型，未配置时子智能体编排将被跳过。 */
        Model model;

        /** 工具集模板：build() 时会深拷贝一份，避免多次构建间相互污染。 */
        Toolkit toolkit = newDefaultToolkit();

        int maxIters = 10;

        ExecutionConfig modelExecutionConfig;
        ExecutionConfig toolExecutionConfig;
        GenerateOptions generateOptions;

        /** 旧式钩子集合（v1 兼容），会同步转发给 inner。 */
        final Set<Hook> hooks = new LinkedHashSet<>();

        /** 用户自定义中间件列表（v2 扩展面），与 harness 内置中间件叠加。 */
        final List<MiddlewareBase> middlewares = new ArrayList<>();

        // ---- Harness orchestration fields ----
        // ---- Harness 编排字段 ----

        String agentId;

        /** 外部/市场技能仓库列表，与工作空间技能按层叠加。 */
        final List<AgentSkillRepository> skillRepositories = new ArrayList<>();

        Path projectGlobalSkillsDir;

        /** 工作空间目录；缺省为 ${cwd}/.agentscope/workspace。 */
        Path workspace;

        String environmentMemory;

        /** 逃生舱：直接注入自定义文件系统，与三种 filesystem(...) 规格互斥。 */
        AbstractFilesystem abstractFilesystem;

        /** 叶子子智能体标记：为 true 时不再挂载嵌套子智能体编排。 */
        boolean leafSubagent = false;

        boolean agentTracingLogEnabled = true;

        /** 上下文压缩配置（默认启用）。 */
        CompactionConfig compactionConfig = CompactionConfig.builder().build();

        /** 长期记忆配置（落盘/整合/维护）。 */
        MemoryConfig memoryConfig = MemoryConfig.defaults();

        /** 工具结果淘汰配置（默认 80k 字符触发）。 */
        ToolResultEvictionConfig toolResultEvictionConfig = ToolResultEvictionConfig.defaults();

        boolean disableCompaction = false;
        boolean disableToolResultEviction = false;

        /** 编程式子智能体声明列表。 */
        final List<SubagentDeclaration> subagentDeclarations = new ArrayList<>();

        /** 完全自定义的子智能体工厂（名称 → Agent）。 */
        final List<HarnessAgentBuilderSupport.SubagentFactoryEntry> customSubagentFactories =
                new ArrayList<>();

        TaskRepository taskRepository;

        /** 外部子智能体工具（如 SessionsTool），设置后替代默认 task 工具。 */
        Object externalSubagentTool;

        Function<String, Model> modelResolver;

        /** 附加上下文文件（相对工作空间路径），随 AGENTS.md 等注入系统提示词。 */
        final List<String> additionalContextFiles = new ArrayList<>();

        /** 工作区上下文最大令牌预算，默认 8000。 */
        int maxContextTokens = 8000;

        boolean useLegacyXmlWorkspaceContext = false;

        ArtifactDeliveryTarget artifactDeliveryTarget;
        // ---- 各能力的禁用开关（默认全部启用）----
        boolean disableFilesystemTools = false;
        boolean disableShellTool = false;
        boolean disableWebTools = false;

        /** Optional caller-supplied client used by the built-in web tools; {@code null} = default. */
        HttpClient webHttpClient;

        boolean disableMemoryTools = false;
        boolean disableMemoryHooks = false;
        boolean disableTranscript = false;
        TranscriptStore transcriptStore;
        String transcriptTenant;
        boolean disableSessionPersistence = false;
        boolean disableWorkspaceContext = false;
        boolean disableAtPathExpansion = false;
        boolean disableSubagents = false;
        boolean disableDynamicSkills = false;
        boolean disableDefaultWorkspaceSkills = false;
        boolean disableDynamicSubagents = false;
        boolean disableToolsConfig = false;

        // ---- 技能自学习相关 ----
        boolean skillManageToolEnabled = false;
        SkillManageConfig skillManageConfig;
        SkillPromotionGate promotionGate;
        SkillVisibilityFilter visibilityFilter;

        /** 部署环境标签，默认 prod，供技能环境过滤使用。 */
        String environment = "prod";

        boolean skillCuratorEnabled = false;
        SkillCuratorConfig skillCuratorConfig;
        io.agentscope.core.skill.SkillFilter skillFilter;
        PermissionContextState permissionContextOverride;

        // ---- 规划模式 ----
        boolean planModeEnabled = false;
        boolean planModeAllowShell = false;
        String planFileDir = PlanModeManager.DEFAULT_PLAN_DIR;

        /** 以代码方式覆盖 workspace/tools.json 的工具配置。 */
        ToolsConfig toolsConfigOverride;

        McpServerRegistrationListener mcpServerRegistrationListener;

        // ---- 三种互斥的文件系统规格（模式2沙箱 / 模式1远程组合 / 模式3本地）----
        SandboxFilesystemSpec sandboxFilesystemSpec;
        RemoteFilesystemSpec remoteFilesystemSpec;
        LocalFilesystemSpec localFilesystemSpec;
        final Map<String, AbstractFilesystem> filesystemRoutes = new LinkedHashMap<>();

        // AgentStateStore — mirrored only to pass through to inner; the user-set AgentStateStore
        // can also be replaced inside orchestration when none is provided (defaults to a
        // JsonFileAgentStateStore rooted at ~/.agentscope/state/<agentId>/, outside any workspace).
        // AgentStateStore 镜像字段——仅用于透传给 inner；未显式设置时，
        // build() 会替换为默认的 JsonFileAgentStateStore
        // （根目录 ~/.agentscope/state/<agentId>/，刻意放在工作空间之外）。
        AgentStateStore stateStoreOverride;

        /** 分布式存储：一次性提供状态/文件/沙箱快照/消息总线等分布式组件。 */
        DistributedStore distributedStore;

        io.agentscope.harness.agent.bus.MessageBus messageBus;

        /** 异步工具超时；与消息总线同时存在时才启用 AsyncToolMiddleware。 */
        java.time.Duration asyncToolTimeout;

        io.agentscope.harness.agent.bus.AsyncToolRegistry asyncToolRegistry;

        io.agentscope.harness.agent.team.TeamClient teamsModeClient;
        io.agentscope.harness.agent.team.TeamContext teamsModeContext;
        String teamsModeSessionId;

        /** 私有构造器，统一经 {@link HarnessAgent#builder()} 或 {@link #fromAgent} 创建。 */
        private Builder() {}

        /**
         * Enables AgentTeams mode: attaches {@link
         * io.agentscope.harness.agent.middleware.TeamsMiddleware} and registers the role-clipped
         * {@code team} tool. Used for both Managed resolve({@code teamContext}) and Entry-B lead
         * sessions that declare a roster template.
         */
        public Builder teamsMode(
                io.agentscope.harness.agent.team.TeamClient teamClient,
                io.agentscope.harness.agent.team.TeamContext teamContext) {
            return teamsMode(teamClient, teamContext, null);
        }

        /**
         * Same as {@link #teamsMode(io.agentscope.harness.agent.team.TeamClient,
         * io.agentscope.harness.agent.team.TeamContext)} but also binds the middleware to {@code
         * sessionId} so control-plane TeamEvents addressed at that session reach this agent.
         */
        public Builder teamsMode(
                io.agentscope.harness.agent.team.TeamClient teamClient,
                io.agentscope.harness.agent.team.TeamContext teamContext,
                String sessionId) {
            this.teamsModeClient = teamClient;
            this.teamsModeContext = teamContext;
            this.teamsModeSessionId = sessionId;
            return this;
        }

        /**
         * Returns a new {@link Builder} pre-populated with as much of the given {@link ReActAgent}'s
         * observable configuration as can be read back from public getters.
         *
         * <p>This is a <b>partial</b> migration helper. The caller still needs to set every
         * harness-specific concern explicitly (workspace, filesystem, sandbox, subagents, skills,
         * plan mode, etc.) — those have no analog on a vanilla {@link ReActAgent}, so they cannot
         * be derived from {@code agent}.
         *
         * <h4>What this method copies</h4>
         *
         * <table border="1">
         *   <caption>Fields copied from the source ReActAgent</caption>
         *   <tr><th>Group</th><th>Field</th><th>Source</th></tr>
         *   <tr><td rowspan="7">Observable configuration</td>
         *       <td>{@code name}</td><td>{@code agent.getName()}</td></tr>
         *   <tr><td>{@code description}</td><td>{@code agent.getDescription()}</td></tr>
         *   <tr><td>{@code sysPrompt}</td><td>{@code agent.getSysPrompt()}</td></tr>
         *   <tr><td>{@code model}</td><td>{@code agent.getModel()}</td></tr>
         *   <tr><td>{@code maxIters}</td><td>{@code agent.getMaxIters()}</td></tr>
         *   <tr><td>{@code generateOptions}</td><td>{@code agent.getGenerateOptions()}</td></tr>
         *   <tr><td>{@code toolkit}</td><td>defensive copy via {@code agent.getToolkit().copy()}</td></tr>
         *   <tr><td rowspan="3">Persistence</td>
         *       <td>{@code session}</td><td>{@code agent.getStateStore()} if non-null</td></tr>
         *   <tr><td>{@code conflictPolicy}</td><td>{@code agent.getConflictPolicy()}</td></tr>
         *   <tr><td>{@code defaultSessionId}</td><td>{@code agent.getDefaultSessionId()} if non-null</td></tr>
         *   <tr><td rowspan="3">Model resilience (from {@code agent.getModelConfig()})</td>
         *       <td>{@code maxRetries}</td><td>{@link ModelConfig#maxRetries()}</td></tr>
         *   <tr><td>{@code fallbackModel}</td><td>{@link ModelConfig#fallbackModel()} if non-null</td></tr>
         *   <tr><td>{@code failoverListener}</td><td>{@link ModelConfig#failoverListener()} if non-null</td></tr>
         *   <tr><td>Reasoning loop (from {@code agent.getReactConfig()})</td>
         *       <td>{@code stopOnReject}</td><td>{@link ReactConfig#stopOnReject()}</td></tr>
         *   <tr><td rowspan="2">Execution</td>
         *       <td>{@code modelExecutionConfig}</td><td>{@code agent.getModelExecutionConfig()} if non-null</td></tr>
         *   <tr><td>{@code toolExecutionConfig}</td><td>{@code agent.getToolExecutionConfig()} if non-null</td></tr>
         *   <tr><td rowspan="3">Behavior</td>
         *       <td>{@code toolExecutionContext}</td><td>{@code agent.getToolExecutionContext()} if non-null</td></tr>
         *   <tr><td>{@code enablePendingToolRecovery}</td><td>{@code agent.isPendingToolRecoveryEnabled()}</td></tr>
         *   <tr><td>{@code checkRunning}</td><td>{@code agent.isCheckRunning()}</td></tr>
         *   <tr><td>Permissions</td>
         *       <td>{@code permissionContext}</td><td>{@code agent.getPermissionContext()} if non-null
         *           (the same {@link PermissionContextState} is reused; it carries the rules registered
         *           on the source engine)</td></tr>
         *   <tr><td>Extension surface</td>
         *       <td>{@code middlewares}</td><td>{@code agent.getMiddlewares()} copied, excluding
         *           harness runtime middlewares</td></tr>
         *   <tr><td>Legacy extension</td>
         *       <td>{@code hooks}</td><td>{@code agent.getHooks()} appended as-is ({@link Hook}
         *           itself is {@code @Deprecated(forRemoval=true)}; prefer middlewares for new
         *           code)</td></tr>
         * </table>
         *
         * <p>Note: {@code enableMetaTool} and {@code enableTaskList} are builder-time flags that
         * mutate the toolkit at build. They do not round-trip as flags, but the toolkit copy
         * <i>already</i> carries the tools they registered, so the resulting agent has the same
         * tool surface.
         *
         * <h4>What this method does <b>not</b> copy</h4>
         *
         * <p><b>Skipped — harness-only, has no source on a {@code ReActAgent}.</b> These
         * <i>must</i> be configured on the returned builder if you want HarnessAgent semantics:
         * <ul>
         *   <li>Workspace &amp; filesystem: {@link #workspace(Path)}, {@link #filesystem(SandboxFilesystemSpec)},
         *       {@link #filesystem(LocalFilesystemSpec)}, {@link #filesystem(RemoteFilesystemSpec)},
         *       {@link #abstractFilesystem(AbstractFilesystem)},
         *       {@link #environmentMemory(String)}</li>
         *   <li>Subagents: {@link #subagent(SubagentDeclaration)}, {@link #subagents(List)},
         *       {@link #subagentFactory(String, Function)}, {@link #externalSubagentTool(Object)},
         *       {@link #taskRepository(TaskRepository)}, {@link #modelResolver(Function)}</li>
         *   <li>Skill governance: {@link #skillRepository(AgentSkillRepository)},
         *       {@link #projectGlobalSkillsDir(Path)},
         *       {@link #enableSkillManageTool(SkillManageConfig)},
         *       {@link #enableSkillCurator(SkillCuratorConfig)},
         *       {@link #enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)},
         *       {@link #skillFilter(io.agentscope.core.skill.SkillFilter)},
         *       {@link #environment(String)}</li>
         *   <li>Plan mode: {@link #enablePlanMode()}, {@link #planFileDirectory(String)}</li>
         *   <li>Context engineering: {@link #additionalContextFile(String)},
         *       {@link #maxContextTokens(int)}, {@link #compaction(CompactionConfig)},
         *       {@link #toolResultEviction(ToolResultEvictionConfig)},
         *       {@link #toolsConfig(ToolsConfig)},
         *       {@link #mcpServerRegistrationListener(McpServerRegistrationListener)}</li>
         *   <li>All {@code disableXxx()} toggles and {@link #enableAgentTracingLog(boolean)}</li>
         * </ul>
         *
         * <h4>Behavior caveats</h4>
         *
         * <p>Even after this method, the built {@code HarnessAgent} is <b>not</b> behaviorally
         * equivalent to the source {@code ReActAgent}: HarnessAgent installs additional
         * orchestration (workspace projection, agent-tracing middleware, default skill /
         * subagent middlewares) that the source did not have. If left unset, {@code session}
         * also defaults to a {@code JsonFileAgentStateStore} rooted at {@code ~/.agentscope/state/<agentId>/}
         * rather than the in-memory default, changing the on-disk persistence layout.
         *
         * @param agent source {@link ReActAgent} to inherit observable configuration from
         * @return a new {@link Builder} pre-populated with the inheritable subset
         */
        /**
         * 根据传入的{@link ReActAgent}，读取其公开Getter可获取的可见配置，预填充并返回全新构造器{@link Builder}。
         *
         * <p>该方法仅为**局部迁移辅助工具**。调用方仍必须手动配置所有容器专属模块（工作空间、文件系统、沙箱、子智能体、技能、规划模式等），
         * 原生ReActAgent不存在对应配置项，无法从源智能体自动读取生成。
         *
         * <h4>本方法可拷贝的配置项</h4>
         *
         * <table border="1">
         *   <caption>从源ReActAgent拷贝的字段清单</caption>
         *   <tr><th>分类</th><th>字段</th><th>取值来源</th></tr>
         *   <tr><td rowspan="7">可见基础配置</td>
         *       <td>{@code name}</td><td>{@code agent.getName()}</td></tr>
         *   <tr><td>{@code description}</td><td>{@code agent.getDescription()}</td></tr>
         *   <tr><td>{@code sysPrompt}</td><td>{@code agent.getSysPrompt()}</td></tr>
         *   <tr><td>{@code model}</td><td>{@code agent.getModel()}</td></tr>
         *   <tr><td>{@code maxIters}</td><td>{@code agent.getMaxIters()}</td></tr>
         *   <tr><td>{@code generateOptions}</td><td>{@code agent.getGenerateOptions()}</td></tr>
         *   <tr><td>{@code toolkit}</td><td>调用{@code agent.getToolkit().copy()}进行保护性拷贝</td></tr>
         *   <tr><td rowspan="2">持久化配置</td>
         *       <td>{@code session}</td><td>非空时取自{@code agent.getStateStore()}</td></tr>
         *   <tr><td>{@code defaultSessionId}</td><td>非空时取自{@code agent.getDefaultSessionId()}</td></tr>
         *   <tr><td rowspan="2">模型容错配置（源自{@code agent.getModelConfig()}）</td>
         *       <td>{@code maxRetries}</td><td>{@link ModelConfig#maxRetries()}</td></tr>
         *   <tr><td>{@code fallbackModel}</td><td>非空时取自{@link ModelConfig#fallbackModel()}</td></tr>
         *   <tr><td>推理循环配置（源自{@code agent.getReactConfig()}）</td>
         *       <td>{@code stopOnReject}</td><td>{@link ReactConfig#stopOnReject()}</td></tr>
         *   <tr><td rowspan="2">执行配置</td>
         *       <td>{@code modelExecutionConfig}</td><td>非空时取自{@code agent.getModelExecutionConfig()}</td></tr>
         *   <tr><td>{@code toolExecutionConfig}</td><td>非空时取自{@code agent.getToolExecutionConfig()}</td></tr>
         *   <tr><td rowspan="3">运行行为配置</td>
         *       <td>{@code toolExecutionContext}</td><td>非空时取自{@code agent.getToolExecutionContext()}</td></tr>
         *   <tr><td>{@code enablePendingToolRecovery}</td><td>{@code agent.isPendingToolRecoveryEnabled()}</td></tr>
         *   <tr><td>{@code checkRunning}</td><td>{@code agent.isCheckRunning()}</td></tr>
         *   <tr><td>权限配置</td>
         *       <td>{@code permissionContext}</td><td>非空时复用源对象{@code agent.getPermissionContext()}
         *           对应的{@link PermissionContextState}实例，继承源引擎注册的全部权限规则</td></tr>
         *   <tr><td>扩展接口</td>
         *       <td>{@code middlewares}</td><td>拷贝中间件列表，自动剔除容器运行时专属中间件</td></tr>
         *   <tr><td>旧式扩展</td>
         *       <td>{@code hooks}</td><td>直接原样追加钩子（{@link Hook}已标记废弃待移除；
         *           新代码优先使用中间件）</td></tr>
         * </table>
         *
         * <p>备注：{@code enableMetaTool}、{@code enableTaskList}属于构造阶段标记，仅用于构建时修改工具集，
         * 无法反向读取还原标记；但工具集拷贝已包含二者注册的工具，最终生成智能体工具集合完全一致。
         *
         * <h4>本方法不会拷贝的配置项</h4>
         *
         * <p><b>仅容器独有、原生ReActAgent无对应配置，如需启用HarnessAgent特性，必须在返回的构造器手动配置：</b>
         * <ul>
         *   <li>工作空间与文件系统：{@link #workspace(Path)}、{@link #filesystem(SandboxFilesystemSpec)}、
         *       {@link #filesystem(LocalFilesystemSpec)}、{@link #filesystem(RemoteFilesystemSpec)}、
         *       {@link #abstractFilesystem(AbstractFilesystem)}、{@link #environmentMemory(String)}</li>
         *   <li>子智能体：{@link #subagent(SubagentDeclaration)}、{@link #subagents(List)}、
         *       {@link #subagentFactory(String, Function)}、{@link #externalSubagentTool(Object)}、
         *       {@link #taskRepository(TaskRepository)}、{@link #modelResolver(Function)}</li>
         *   <li>技能管控：{@link #skillRepository(AgentSkillRepository)}、
         *       {@link #projectGlobalSkillsDir(Path)}、
         *       {@link #enableSkillManageTool(SkillManageConfig)}、
         *       {@link #enableSkillCurator(SkillCuratorConfig)}、
         *       {@link #enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)}、
         *       {@link #skillFilter(io.agentscope.core.skill.SkillFilter)}、
         *       {@link #environment(String)}</li>
         *   <li>规划模式：{@link #enablePlanMode()}、{@link #planFileDirectory(String)}</li>
         *   <li>上下文优化：{@link #additionalContextFile(String)}、
         *       {@link #maxContextTokens(int)}、{@link #compaction(CompactionConfig)}、
         *       {@link #toolResultEviction(ToolResultEvictionConfig)}、
         *       {@link #toolsConfig(ToolsConfig)}</li>
         *   <li>所有disableXxx开关、{@link #enableAgentTracingLog(boolean)}</li>
         * </ul>
         *
         * <h4>使用注意事项</h4>
         *
         * <p>即便使用本方法拷贝配置，最终构建的HarnessAgent与源ReActAgent行为并不完全等价：
         * HarnessAgent额外搭载编排能力（工作空间映射、追踪中间件、默认技能/子智能体中间件）；
         * 若未手动指定会话存储，默认使用{@code ~/.agentscope/state/<agentId>/}路径的JsonFileAgentStateStore持久化，
         * 而非内存存储，磁盘持久化结构会发生变更。
         *
         * @param agent 待读取配置的源{@link ReActAgent}实例
         * @return 预填充可继承配置的全新构造器{@link Builder}
         */
        public static Builder fromAgent(ReActAgent agent) {
            Builder b = new Builder();

            // Observable configuration.
            b.name(agent.getName());
            b.description(agent.getDescription());
            b.sysPrompt(agent.getSysPrompt());
            b.model(agent.getModel());
            b.maxIters(agent.getMaxIters());
            b.generateOptions(agent.getGenerateOptions());
            b.toolkit(agent.getToolkit().copy());

            // Persistence.
            AgentStateStore srcSession = agent.getStateStore();
            if (srcSession != null) {
                b.stateStore(srcSession);
            }
            b.conflictPolicy(agent.getConflictPolicy());
            String srcDefaultSessionId = agent.getDefaultSessionId();
            if (srcDefaultSessionId != null) {
                b.defaultSessionId(srcDefaultSessionId);
            }

            // Model resilience.
            ModelConfig mc = agent.getModelConfig();
            if (mc != null) {
                b.maxRetries(mc.maxRetries());
                if (mc.fallbackModel() != null) {
                    b.fallbackModel(mc.fallbackModel());
                }
                if (mc.failoverListener() != null) {
                    b.failoverListener(mc.failoverListener());
                }
            }

            // Reasoning loop. maxIters already covered above; only stopOnReject left.
            ReactConfig rc = agent.getReactConfig();
            if (rc != null) {
                b.stopOnReject(rc.stopOnReject());
            }

            // Execution configs.
            ExecutionConfig srcModelExec = agent.getModelExecutionConfig();
            if (srcModelExec != null) {
                b.modelExecutionConfig(srcModelExec);
            }
            ExecutionConfig srcToolExec = agent.getToolExecutionConfig();
            if (srcToolExec != null) {
                b.toolExecutionConfig(srcToolExec);
            }

            // Behavior flags + tool execution context.
            ToolExecutionContext srcToolCtx = agent.getToolExecutionContext();
            if (srcToolCtx != null) {
                b.toolExecutionContext(srcToolCtx);
            }
            b.enablePendingToolRecovery(agent.isPendingToolRecoveryEnabled());
            b.checkRunning(agent.isCheckRunning());

            // Permission context (same instance — carries rules registered on the source).
            PermissionContextState srcPerm = agent.getPermissionContext();
            if (srcPerm != null) {
                b.permissionContext(srcPerm);
            }

            // Extension chains. Middlewares are the v2 surface; hooks remain for v1 carry-over.
            List<MiddlewareBase> srcMiddlewares = agent.getMiddlewares();
            if (srcMiddlewares != null && !srcMiddlewares.isEmpty()) {
                b.middlewares(filterCopyableMiddlewares(srcMiddlewares));
            }
            List<Hook> srcHooks = agent.getHooks();
            if (srcHooks != null && !srcHooks.isEmpty()) {
                b.hooks(srcHooks);
            }

            return b;
        }

        // ---- Forwarder setters (proxy to inner + mirror) ----

        public Builder name(String name) {
            this.name = name;
            inner.name(name);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            inner.description(description);
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            inner.sysPrompt(sysPrompt);
            return this;
        }

        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            inner.checkRunning(checkRunning);
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            inner.model(model);
            return this;
        }

        public Builder model(String modelId) {
            Model resolved = io.agentscope.core.model.ModelRegistry.resolve(modelId);
            this.model = resolved;
            inner.model(resolved);
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit != null ? toolkit : newDefaultToolkit();
            // Don't push to inner yet — orchestration will register harness tools on this toolkit
            // and then push the final result via inner.toolkit(...) at build() time.
            // 先不转发给 inner——编排阶段还会往该工具集上注册 harness 工具，
            // 等 build() 完成装配后再经 inner.toolkit(...) 一次性推送最终结果。
            return this;
        }

        /**
         * Default toolkit for Harness agents. Uses {@link Toolkit}'s default config (parallel
         * tool execution enabled). Pass a custom {@link Toolkit} with
         * {@code ToolkitConfig.parallel(false)} to opt out.
         */
        static Toolkit newDefaultToolkit() {
            return new Toolkit();
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            inner.maxIters(maxIters);
            return this;
        }

        public Builder modelExecutionConfig(ExecutionConfig config) {
            this.modelExecutionConfig = config;
            inner.modelExecutionConfig(config);
            return this;
        }

        public Builder toolExecutionConfig(ExecutionConfig config) {
            this.toolExecutionConfig = config;
            inner.toolExecutionConfig(config);
            return this;
        }

        public Builder generateOptions(GenerateOptions options) {
            this.generateOptions = options;
            inner.generateOptions(options);
            return this;
        }

        public Builder hook(Hook hook) {
            if (hook != null) {
                hooks.add(hook);
                inner.hook(hook);
            }
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            if (hooks != null) {
                for (Hook h : hooks) {
                    hook(h);
                }
            }
            return this;
        }

        public Builder middleware(MiddlewareBase middleware) {
            if (middleware != null) {
                middlewares.add(middleware);
                inner.middleware(middleware);
            }
            return this;
        }

        public Builder middlewares(List<? extends MiddlewareBase> middlewareList) {
            if (middlewareList != null) {
                for (MiddlewareBase middleware : middlewareList) {
                    middleware(middleware);
                }
            }
            return this;
        }

        /**
         * fromAgent 拷贝中间件时的过滤器：剔除 harness 运行时中间件与优雅停机中间件——
         * 这两类会在新的 build() 中按自身逻辑重新装配，直接拷贝会造成重复注册。
         */
        private static List<MiddlewareBase> filterCopyableMiddlewares(
                List<MiddlewareBase> middlewares) {
            // Keep only observable registrations from the source agent.
            // 仅保留源智能体上"可观察的用户注册"中间件。
            List<MiddlewareBase> copyable = new ArrayList<>(middlewares.size());
            for (MiddlewareBase middleware : middlewares) {
                if (middleware != null
                        && !(middleware instanceof HarnessRuntimeMiddleware)
                        && !(middleware instanceof GracefulShutdownMiddleware)) {
                    copyable.add(middleware);
                }
            }
            return copyable;
        }

        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStoreOverride = stateStore;
            inner.stateStore(stateStore);
            return this;
        }

        /**
         * Policy applied when an {@code agent_state} save conflicts with another writer's update.
         * Defaults to {@link ConflictPolicy#OVERWRITE}, i.e. last-writer-wins with no error.
         *
         * @param conflictPolicy the policy to apply on conflict
         * @return this builder
         */
        public Builder conflictPolicy(ConflictPolicy conflictPolicy) {
            inner.conflictPolicy(conflictPolicy);
            return this;
        }

        /**
         * Configures a distributed store that provides all storage components at once:
         * {@link AgentStateStore}, {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore},
         * {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}, and
         * {@link io.agentscope.harness.agent.sandbox.SandboxExecutionGuard}.
         *
         * <p>Explicit builder methods ({@code stateStore()}, {@code filesystem()}) take
         * precedence over the distributed store for the components they configure.
         *
         * @param store the distributed store to use
         * @return this builder
         */
        /**
         * 配置分布式存储组件，可一次性提供四类存储组件：
         * {@link AgentStateStore}、{@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore}、
         * {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}
         * 以及 {@link io.agentscope.harness.agent.sandbox.SandboxExecutionGuard}。
         *
         * <p>stateStore()、filesystem() 这类单独配置方法的优先级高于本分布式存储统一配置。
         *
         * @param store 待启用的分布式存储实例
         * @return 当前构造器对象
         */
        public Builder distributedStore(DistributedStore store) {
            this.distributedStore = store;
            return this;
        }

        public Builder defaultSessionId(String defaultSessionId) {
            inner.defaultSessionId(defaultSessionId);
            return this;
        }

        public Builder toolExecutionContext(ToolExecutionContext ctx) {
            inner.toolExecutionContext(ctx);
            return this;
        }

        public Builder enableMetaTool(boolean enableMetaTool) {
            inner.enableMetaTool(enableMetaTool);
            return this;
        }

        /**
         * Enables recovery of orphaned tool calls when a new message arrives. Automatically
         * constructed local subagents inherit this setting unless their declaration overrides it.
         * Pending permission confirmations still require an explicit confirmation result.
         *
         * @param enable whether to synthesize error results for orphaned tool calls
         * @return this builder
         */
        public Builder enablePendingToolRecovery(boolean enable) {
            this.enablePendingToolRecovery = enable;
            inner.enablePendingToolRecovery(enable);
            return this;
        }

        public Builder enableTaskList() {
            inner.enableTaskList();
            return this;
        }

        public Builder enableTaskList(boolean enabled) {
            inner.enableTaskList(enabled);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            inner.maxRetries(maxRetries);
            return this;
        }

        public Builder fallbackModel(Model fallbackModel) {
            inner.fallbackModel(fallbackModel);
            return this;
        }

        public Builder fallbackModel(String modelId) {
            inner.fallbackModel(modelId);
            return this;
        }

        /**
         * Sets the listener notified when the fallback model takes over from a failed primary
         * model. Delegates to the inner {@link io.agentscope.core.ReActAgent.Builder}.
         *
         * @see FailoverListener for the threading and failure contract
         */
        public Builder failoverListener(FailoverListener failoverListener) {
            inner.failoverListener(failoverListener);
            return this;
        }

        public Builder stopOnReject(boolean stopOnReject) {
            inner.stopOnReject(stopOnReject);
            return this;
        }

        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContextOverride = permissionContext;
            inner.permissionContext(permissionContext);
            return this;
        }

        // ---- Harness-only setters ----

        /**
         * Sets the stable identifier used as the agent's namespace key in the composite filesystem
         * (e.g. {@code [agents, <agentId>, users, <userId>, ...]}). When unset, {@link #build()}
         * falls back to {@link #name(String)} for the namespace key.
         */
        /**
         * 设置稳定标识，该标识将作为复合文件系统中智能体的命名空间主键（示例格式：{@code [agents, <agentId>, users, <userId>, ...]}）。
         * 若未指定该参数，执行{@link #build()}构建时，会自动取用{@link #name(String)}的值作为命名空间主键。
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /**
         * Adds a marketplace / external skill repository (e.g. {@code GitSkillRepository}).
         * Repositories compose additively with workspace skills.
         */
        /**
         * 接入商城类或外部技能仓库（例如GitSkillRepository）。各类仓库与工作空间内的技能采用叠加组合的方式生效。
         */
        public Builder skillRepository(AgentSkillRepository skillRepository) {
            if (skillRepository != null) {
                this.skillRepositories.add(skillRepository);
            }
            return this;
        }

        /**
         * Replaces the current marketplace repository list with the given collection.
         */
        /**
         * 使用传入的集合替换当前全部外部技能仓库列表。
         */
        public Builder skillRepositories(List<AgentSkillRepository> repositories) {
            this.skillRepositories.clear();
            if (repositories != null) {
                for (AgentSkillRepository repo : repositories) {
                    if (repo != null) {
                        this.skillRepositories.add(repo);
                    }
                }
            }
            return this;
        }

        /**
         * Configures a project-global skills directory layered below marketplace and workspace
         * skills (lowest precedence).
         */
        /**
         * 配置项目全局技能目录，该目录优先级低于商城技能与工作空间技能，优先级最低。
         */
        public Builder projectGlobalSkillsDir(Path projectGlobalSkillsDir) {
            this.projectGlobalSkillsDir = projectGlobalSkillsDir;
            return this;
        }

        /**
         * Sets the workspace directory. Pass {@code null} to use the default
         * {@code ${cwd}/.agentscope/workspace}.
         */
        /**
         * Sets the workspace directory.
         *
         * <p>When left unset, the workspace is resolved at {@link #build()} time via
         * {@link HarnessAgent#resolveDefaultWorkspace()}: the {@code agentscope.workspace} system
         * property, then the {@code AGENTSCOPE_WORKSPACE} environment variable, then
         * {@code ${user.dir}/.agentscope/workspace}. Setting a value here takes precedence over
         * both the system property and the environment variable.
         *
         * @param workspace the workspace directory, or {@code null} to fall back to the resolved
         *     default
         * @return this builder
         * 设置工作空间目录。传入null则使用默认路径${cwd}/.agentscope/workspace。
         */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        /**
         * Sets the workspace directory from a filesystem path string.
         *
         * <p>See {@link #workspace(Path)} for the fallback behaviour when unset.
         *
         * @param path the workspace directory path, or {@code null} to fall back to the resolved
         *     default
         * @return this builder
         */
        /**
         * 通过文件系统路径字符串设置工作空间目录。
         */
        public Builder workspace(String path) {
            if (path == null) {
                this.workspace = null;
            } else {
                String trimmed = path.strip();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("workspace path must not be blank");
                }
                this.workspace = Path.of(trimmed);
            }
            return this;
        }

        public Builder environmentMemory(String environmentMemory) {
            this.environmentMemory = environmentMemory;
            return this;
        }

        /** Escape hatch: sets a custom {@link AbstractFilesystem} implementation directly. */
        /** 应急扩展接口：直接设置自定义的 {@link AbstractFilesystem} 实现类。 */
        public Builder abstractFilesystem(AbstractFilesystem store) {
            this.abstractFilesystem = store;
            return this;
        }

        /** Configures Mode 2 — sandbox filesystem. */
        /** 配置模式2：沙箱文件系统。 */
        public Builder filesystem(SandboxFilesystemSpec spec) {
            this.sandboxFilesystemSpec = spec;
            return this;
        }

        /** Configures Mode 1 — composite (non-sandbox) filesystem. */
        /** 配置模式1：组合式（非沙箱）文件系统。 */
        public Builder filesystem(RemoteFilesystemSpec spec) {
            this.remoteFilesystemSpec = spec;
            return this;
        }

        /** Configures Mode 3 — local filesystem with shell. */
        /** 配置模式3：搭载Shell能力的本地文件系统。 */
        public Builder filesystem(LocalFilesystemSpec spec) {
            this.localFilesystemSpec = spec;
            return this;
        }

        /**
         * Mounts an additional {@link AbstractFilesystem} under a path prefix, alongside the
         * primary filesystem configured via {@link #filesystem(SandboxFilesystemSpec)}, {@link
         * #filesystem(RemoteFilesystemSpec)}, or {@link #filesystem(LocalFilesystemSpec)}.
         *
         * <p>When the primary filesystem is sandbox-backed, the route is applied via {@link
         * RoutedSandboxFilesystem} so {@code shell_execute} still targets the sandbox. Otherwise
         * the route is applied via {@link CompositeFilesystem}.
         *
         * @param prefix path prefix for the route (e.g. {@code "memory-stores/notes/"})
         * @param filesystem backend serving paths under {@code prefix}
         */
        public Builder filesystemRoute(String prefix, AbstractFilesystem filesystem) {
            this.filesystemRoutes.put(prefix, filesystem);
            return this;
        }

        /**
         * Overrides the default {@link CompactionMiddleware} configuration.
         * Compaction is enabled by default with {@link CompactionConfig#builder()}.build()
         * defaults (dynamic trigger based on model context window, dynamic tail preservation).
         * Use {@link #disableCompaction()} to turn it off entirely.
         */
        /**
         * 覆写默认的{@link CompactionMiddleware}配置。
         * 压缩机制默认启用，采用{@link CompactionConfig#builder()}.build()默认参数（基于模型上下文窗口动态触发、动态保留尾部内容）。
         * 可调用{@link #disableCompaction()}彻底关闭该功能。
         */
        public Builder compaction(CompactionConfig config) {
            this.compactionConfig = config;
            this.disableCompaction = (config == null);
            return this;
        }

        /** Disables the {@link CompactionMiddleware} entirely. */
        /** 彻底禁用{@link CompactionMiddleware}上下文压缩中间件。 */
        public Builder disableCompaction() {
            this.disableCompaction = true;
            return this;
        }

        /**
         * Overrides the long-term memory pipeline configuration (flush + consolidation +
         * maintenance). When not called, {@link MemoryConfig#defaults()} is used and behaviour
         * matches the harness's historical defaults.
         *
         * <p>For the compaction (in-context summarization) pipeline, see
         * {@link #compaction(CompactionConfig)}.
         */
        /**
         * 重写长期记忆链路配置（包含刷新、整合、维护流程）。
         * 未调用该方法时，将采用 {@link MemoryConfig#defaults()} 默认配置，行为与容器历史默认规则保持一致。
         *
         * <p>上下文摘要压缩相关链路配置，请参考 {@link #compaction(CompactionConfig)}。
         */
        public Builder memory(MemoryConfig config) {
            this.memoryConfig = config != null ? config : MemoryConfig.defaults();
            return this;
        }

        /**
         * Overrides the default {@link ToolResultEvictionMiddleware} configuration.
         * Tool result eviction is enabled by default with
         * {@link ToolResultEvictionConfig#defaults()} (trigger at 80k chars).
         * Use {@link #disableToolResultEviction()} to turn it off entirely.
         */
        /**
         * 重写{@link ToolResultEvictionMiddleware}的默认配置。
         * 工具结果淘汰功能默认开启，采用{@link ToolResultEvictionConfig#defaults()}配置（字符达到80000时触发清理）。
         * 可调用{@link #disableToolResultEviction()}彻底关闭该功能。
         */
        public Builder toolResultEviction(ToolResultEvictionConfig config) {
            this.toolResultEvictionConfig = config;
            this.disableToolResultEviction = (config == null);
            return this;
        }

        /** Disables the {@link ToolResultEvictionMiddleware} entirely. */
        /** 彻底禁用{@link ToolResultEvictionMiddleware}工具结果淘汰中间件。 */
        public Builder disableToolResultEviction() {
            this.disableToolResultEviction = true;
            return this;
        }

        /** Programmatic override for {@code workspace/tools.json}. */
        /** 以代码方式覆盖 workspace/tools.json 配置。 */
        public Builder toolsConfig(ToolsConfig toolsConfig) {
            this.toolsConfigOverride = toolsConfig;
            return this;
        }

        /**
         * Sets the listener for terminal MCP server registration results produced while building
         * this agent. The listener is not propagated to dynamically created subagents. Passing
         * {@code null} disables result delivery.
         */
        public Builder mcpServerRegistrationListener(
                McpServerRegistrationListener mcpServerRegistrationListener) {
            this.mcpServerRegistrationListener = mcpServerRegistrationListener;
            return this;
        }

        /** Adds a subagent declaration. */
        /** 添加一条子智能体声明。 */
        public Builder subagent(SubagentDeclaration declaration) {
            this.subagentDeclarations.add(declaration);
            return this;
        }

        public Builder subagents(List<SubagentDeclaration> declarations) {
            this.subagentDeclarations.addAll(declarations);
            return this;
        }

        /** Adds a fully custom subagent factory for a given agent id. */
        /** 为指定智能体ID添加完全自定义的子智能体工厂。 */
        public Builder subagentFactory(String name, Function<String, Agent> factory) {
            return subagentFactory(name, null, factory);
        }

        /**
         * Adds a fully custom subagent factory for a given agent id, with a description shown to
         * the orchestrator. When {@code description} is null or blank, the name is used.
         */
        public Builder subagentFactory(
                String name, String description, Function<String, Agent> factory) {
            this.customSubagentFactories.add(
                    new HarnessAgentBuilderSupport.SubagentFactoryEntry(
                            name, description, factory));
            return this;
        }

        /** Sets a custom {@link TaskRepository} for background subagent execution. */
        /** 为后台子智能体执行设置自定义{@link TaskRepository}任务仓库。 */
        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        /**
         * Sets the {@link io.agentscope.harness.agent.bus.MessageBus} for inbox-based message delivery.
         * When set, an {@link InboxMiddleware} is automatically registered to drain the session's
         * inbox before each reasoning step.
         */
        /**
         * 配置基于收件箱消息投递的消息总线{@link io.agentscope.harness.agent.bus.MessageBus}。
         * 配置完成后，系统会自动注册收件箱中间件，在每次推理步骤执行前清空当前会话收件箱。
         */
        public Builder messageBus(io.agentscope.harness.agent.bus.MessageBus messageBus) {
            this.messageBus = messageBus;
            return this;
        }

        /**
         * Enables {@link AsyncToolMiddleware} with the given timeout. Requires
         * {@link #messageBus(io.agentscope.harness.agent.bus.MessageBus)} to be set. Tool executions that
         * exceed the timeout are offloaded to the background; results are delivered via the inbox.
         */
        /**
         * 根据指定超时时长启用异步工具中间件{@link AsyncToolMiddleware}。
         * 该功能依赖预先配置消息总线{@link #messageBus(io.agentscope.harness.agent.bus.MessageBus)}。
         * 执行超时的工具任务将转入后台运行，执行结果通过收件箱推送。
         */
        public Builder asyncToolTimeout(java.time.Duration timeout) {
            this.asyncToolTimeout = timeout;
            return this;
        }

        /**
         * Sets the {@link io.agentscope.harness.agent.bus.AsyncToolRegistry} for tracking async tool
         * executions. Enables stale async tool detection and cleanup in
         * {@link InboxMiddleware}. When not set, async tool lifecycle tracking is skipped.
         */
        /**
         * 设置用于追踪异步工具执行的注册器{@link io.agentscope.harness.agent.bus.AsyncToolRegistry}。
         * 开启后收件箱中间件可检测并清理过期异步任务；未配置则不进行异步工具生命周期管理。
         */
        public Builder asyncToolRegistry(
                io.agentscope.harness.agent.bus.AsyncToolRegistry registry) {
            this.asyncToolRegistry = registry;
            return this;
        }

        /** Injects an external subagent tool (typically {@code SessionsTool}). */
        /** 注入外部子智能体工具（通常为SessionsTool）。 */
        public Builder externalSubagentTool(Object tool) {
            this.externalSubagentTool = tool;
            return this;
        }

        /** Sets a resolver for model name strings to {@link Model} instances for subagents. */
        /** 配置解析器，用于将子智能体的模型名字符串解析为{@link Model}实例。 */
        public Builder modelResolver(Function<String, Model> resolver) {
            this.modelResolver = resolver;
            return this;
        }

        /**
         * Adds a custom context file (relative to workspace) loaded into the system prompt
         * alongside AGENTS.md, MEMORY.md, and KNOWLEDGE.md.
         */
        /**
         * 添加自定义上下文文件（路径相对于工作目录），该文件将与AGENTS.md、MEMORY.md、KNOWLEDGE.md一同加载至系统提示词。
         */
        public Builder additionalContextFile(String relativePath) {
            if (relativePath != null && !relativePath.isBlank()) {
                this.additionalContextFiles.add(relativePath);
            }
            return this;
        }

        /** Sets the maximum token budget for workspace context. */
        /** 设置工作区上下文的最大令牌配额。 */
        public Builder maxContextTokens(int maxTokens) {
            this.maxContextTokens = maxTokens;
            return this;
        }

        /** Switches workspace context rendering between markdown (default) and legacy XML style. */
        /** 切换工作区上下文渲染格式，可选默认Markdown格式与旧式XML格式。 */
        public Builder useLegacyXmlWorkspaceContext(boolean enabled) {
            this.useLegacyXmlWorkspaceContext = enabled;
            return this;
        }

        /**
         * Enables or disables agent execution trace logging via {@link AgentTraceMiddleware}.
         * Default is {@code true}.
         */
        /**
         * 通过{@link AgentTraceMiddleware}开启或关闭智能体执行轨迹日志，默认开启。
         */
        public Builder enableAgentTracingLog(boolean enabled) {
            this.agentTracingLogEnabled = enabled;
            return this;
        }

        /**
         * Configures the {@link ArtifactDeliveryTarget} used by the {@code deliver_artifact} tool.
         *
         * <p>When set, the generic {@link ArtifactDeliveryTool} is registered on the main agent and
         * the sandbox workspace prompt tells the model to use it to hand artifacts it produced to a
         * destination outside the sandbox. When unset (default), no delivery tool is exposed.
         *
         * <p>The tool is exposed only to the main agent — it is not propagated to automatically
         * constructed subagents, which return plain text results for the main agent to deliver.
         *
         * <p>Note: the tool reads files from the agent filesystem, so it is also suppressed when
         * {@link #disableFilesystemTools()} is used. Combining both leaves the tool
         * unregistered.
         */
        public Builder artifactDeliveryTarget(ArtifactDeliveryTarget target) {
            this.artifactDeliveryTarget = target;
            return this;
        }

        /** Skips registration of {@link FilesystemTool}. */
        /** 跳过文件系统工具{@link FilesystemTool}的注册。 */
        public Builder disableFilesystemTools() {
            this.disableFilesystemTools = true;
            return this;
        }

        /** Skips registration of {@link ShellExecuteTool}. */
        /** 跳过Shell执行工具{@link ShellExecuteTool}的注册。 */
        public Builder disableShellTool() {
            this.disableShellTool = true;
            return this;
        }

        /** Skips registration of the optional Tavily-backed {@code web_search} and {@code web_fetch} tools. */
        public Builder disableWebTools() {
            this.disableWebTools = true;
            return this;
        }

        /**
         * Supplies a custom {@link java.net.http.HttpClient} used by the built-in {@code web_fetch}
         * and {@code web_search} tools (e.g. custom proxy, TLS or HTTP version settings). When
         * unset, the tools use a default client with JDK version negotiation (HTTP/2 preferred,
         * automatic HTTP/1.1 fallback); inject an HTTP/1.1-only client here if a target server
         * fails under HTTP/2 negotiation (see issue #3101).
         */
        public Builder webHttpClient(HttpClient client) {
            this.webHttpClient = client;
            return this;
        }

        /**
         * Registers schema-only external tools on the builder toolkit (merged into the final
         * agent toolkit at {@link #build()}). Used by {@code self_hosted} environments to expose
         * hands tools that suspend for worker execution.
         */
        public Builder registerExternalSchemas(
                java.util.List<io.agentscope.core.model.ToolSchema> schemas) {
            if (schemas != null) {
                this.toolkit.registerSchemas(schemas);
            }
            return this;
        }

        /** Disables dynamic per-call skill loading from the workspace filesystem. */
        /** 禁用从工作区文件系统动态加载单次调用技能。 */
        public Builder disableDynamicSkills() {
            this.disableDynamicSkills = true;
            return this;
        }

        /**
         * Skips registration of the default Layer-4 {@link
         * io.agentscope.harness.agent.skill.WorkspaceSkillRepository}. User-supplied repositories
         * and the workspace skills directory (Layer 3) are still composed; only the namespaced
         * AbstractFilesystem-backed source is omitted.
         */
        /**
         * 跳过默认四层仓库{@link io.agentscope.harness.agent.skill.WorkspaceSkillRepository}的注册。
         * 用户自定义仓库与三层工作目录技能目录仍正常组合，仅移除基于文件系统的命名空间资源源。
         */
        public Builder disableDefaultWorkspaceSkills() {
            this.disableDefaultWorkspaceSkills = true;
            return this;
        }

        /**
         * Enables the agent-callable {@code skill_manage} tool so the agent can create / edit /
         * patch / archive its own skills in the workspace, and upgrades the workspace skill
         * repository to a writable variant.
         */
        /**
         * 启用智能体可调用的skill_manage工具，使其能够在工作区内创建、编辑、修补、归档自有技能，
         * 并将工作区技能仓库升级为可写入版本。
         */
        public Builder enableSkillManageTool(SkillManageConfig config) {
            this.skillManageToolEnabled = true;
            this.skillManageConfig = config != null ? config : SkillManageConfig.defaults();
            return this;
        }

        /** Shorthand for {@link #enableSkillManageTool(SkillManageConfig)} with default config. */
        /** 使用默认配置快捷调用{@link #enableSkillManageTool(SkillManageConfig)}方法。 */
        public Builder enableSkillManageTool(boolean autoPromote) {
            return enableSkillManageTool(
                    SkillManageConfig.builder().autoPromote(autoPromote).build());
        }

        /**
         * Configures the runtime promotion gate + visibility filter chain.
         */
        /**
         * 配置运行时晋升校验门与可见性过滤器链。
         */
        public Builder enableSkillPromotionGate(
                SkillPromotionGate gate, SkillVisibilityFilter visibilityFilter) {
            this.promotionGate = gate;
            this.visibilityFilter = visibilityFilter;
            return this;
        }

        /** Sets the deployment environment label used by {@code EnvironmentFilter}. */
        /** 设置环境过滤器所使用的部署环境标签。 */
        public Builder environment(String env) {
            this.environment = env != null ? env : "prod";
            return this;
        }

        /** Enables the background skill curator. Requires {@link #enableSkillManageTool}. */
        /** 启用后台技能整理器，该功能依赖开启技能管理工具。 */
        public Builder enableSkillCurator(SkillCuratorConfig config) {
            this.skillCuratorEnabled = true;
            this.skillCuratorConfig = config != null ? config : SkillCuratorConfig.defaults();
            return this;
        }

        /**
         * Enables plan mode (read-only design phase) with {@code plan_enter}/{@code plan_write}/
         * {@code plan_exit} tools and a {@code PlanModeMiddleware} that enforces read-only tools
         * while plan mode is active.
         */
        /**
         * 启用规划模式（只读设计阶段），提供plan_enter、plan_write、plan_exit工具，
         * 同时搭载规划模式中间件，在模式生效期间强制限定仅可使用只读工具。
         */
        public Builder enablePlanMode() {
            return enablePlanMode(true);
        }

        public Builder enablePlanMode(boolean enabled) {
            this.planModeEnabled = enabled;
            return this;
        }

        public Builder planFileDirectory(String dir) {
            if (dir != null && !dir.isBlank()) {
                this.planFileDir = dir;
            }
            return this;
        }

        /**
         * Allows the shell tool ({@code execute}) to run while plan mode is active. By default plan
         * mode is strictly read-only and the shell is denied (it is dual-use and cannot be
         * classified as read-only by name). Opt in when shell-based investigation (e.g.
         * {@code cat}/{@code grep}/{@code git log}) is needed to produce a realistic plan; the plan
         * banner instructs the model to keep shell usage read-only. Writes still flow through the
         * (denied) file-editing tools. Prefer pairing this with a sandboxed filesystem.
         */
        /**
         * 允许规划模式启用期间运行shell工具execute。规划模式默认严格只读，禁用shell工具，因其用途广泛，无法仅通过名称划定为只读工具。
         * 当需要借助shell查询命令（cat、grep、git log等）制定合理方案时可开启该配置，模式提示横幅会引导模型仅以只读方式使用shell。
         * 文件写入操作依旧会被编辑工具拦截，建议搭配沙箱文件系统一同使用。
         */
        public Builder allowShellInPlanMode() {
            return allowShellInPlanMode(true);
        }

        public Builder allowShellInPlanMode(boolean allowed) {
            this.planModeAllowShell = allowed;
            return this;
        }

        public Builder skillFilter(io.agentscope.core.skill.SkillFilter filter) {
            this.skillFilter = filter;
            return this;
        }

        public Builder skillsEnabled(boolean enabled) {
            this.skillFilter =
                    enabled
                            ? io.agentscope.core.skill.SkillFilter.all()
                            : io.agentscope.core.skill.SkillFilter.none();
            return this;
        }

        public Builder enableSkills(String... skillNames) {
            this.skillFilter = io.agentscope.core.skill.SkillFilter.only(skillNames);
            return this;
        }

        public Builder disableSkills(String... skillNames) {
            this.skillFilter = io.agentscope.core.skill.SkillFilter.except(skillNames);
            return this;
        }

        public Builder disableDynamicSubagents() {
            this.disableDynamicSubagents = true;
            return this;
        }

        /**
         * Skips registration of {@code memory_search} / {@code memory_get} / {@code memory_save} /
         * {@code session_search}, and omits matching Memory Recall / tool-based Persistence
         * guidance from the workspace system prompt.
         */
        public Builder disableMemoryTools() {
            this.disableMemoryTools = true;
            return this;
        }

        /**
         * Disables memory flush + background consolidation, and removes the "automatically
         * extracted" Persistence line from the workspace system prompt. Combined with {@link
         * #disableMemoryTools()}, also skips {@code MEMORY.md} injection into
         * {@code <memory_context>}.
         */
        public Builder disableMemoryHooks() {
            this.disableMemoryHooks = true;
            return this;
        }

        /**
         * Disables the independent session-transcript middleware. Prefer leaving transcript on;
         * memory hooks can be disabled separately via {@link #disableMemoryHooks()}.
         */
        public Builder disableTranscript() {
            this.disableTranscript = true;
            return this;
        }

        /** Optional override for the session {@link TranscriptStore} (segmented append store). */
        public Builder transcriptStore(TranscriptStore transcriptStore) {
            this.transcriptStore = transcriptStore;
            return this;
        }

        /** Tenant segment used in transcript object keys (default {@code "default"}). */
        public Builder transcriptTenant(String transcriptTenant) {
            this.transcriptTenant = transcriptTenant;
            return this;
        }

        /** No-op since 2.0; session persistence is owned by ReActAgent itself. */
        /** 自2.0版本起为空操作；会话持久化由ReActAgent自身管理。 */
        public Builder disableSessionPersistence() {
            this.disableSessionPersistence = true;
            return this;
        }

        public Builder disableWorkspaceContext() {
            this.disableWorkspaceContext = true;
            return this;
        }

        public Builder disableAtPathExpansion() {
            this.disableAtPathExpansion = true;
            return this;
        }

        public Builder disableSubagents() {
            this.disableSubagents = true;
            return this;
        }

        public Builder disableToolsConfig() {
            this.disableToolsConfig = true;
            return this;
        }

        /**
         * Marks this build as a leaf subagent (no nested subagent orchestration). Package-private
         * because only {@link HarnessAgentBuilderSupport} subagent factories should mark agents
         * as leaves.
         */
        /**
         * 将当前构建标记为叶子子智能体（不支持嵌套子智能体编排）。
         * 包私有访问权限，仅HarnessAgentBuilderSupport的子智能体工厂可进行该标记。
         */
        Builder asLeafSubagent() {
            this.leafSubagent = true;
            return this;
        }

        /**
         * Builds the subagent entries (general-purpose + declared + custom factories) without
         * constructing the full agent. Useful for callers that need to extract subagent factories
         * up front (for example to mount them on a session router).
         */
        /**
         * 构建子智能体条目（通用子智能体 + 声明式子智能体 + 自定义工厂），
         * 但不实例化完整智能体。适用于需要提前提取子智能体工厂的调用方
         *（例如将会子智能体挂载至会话路由）。
         */
        public List<SubagentEntry> buildSubagentEntries(Path resolvedWorkspace) {
            return HarnessAgentBuilderSupport.buildSubagentEntries(this, resolvedWorkspace, null);
        }

        /** 携带沙箱文件系统的重载：子智能体工厂将复用该沙箱后端文件系统。 */
        public List<SubagentEntry> buildSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            return HarnessAgentBuilderSupport.buildSubagentEntries(
                    this, resolvedWorkspace, sandboxFs);
        }

        /**
         * 为工作空间型任务仓库接线消息总线：后台子智能体任务完成时，
         * 1) 向会话收件箱推送一条 system-notification 提示（携带任务结果）；
         * 2) 入队一次唤醒事件，使主智能体下一轮能主动感知任务完成。
         */
        private static void wireTaskRepositoryMessageBus(
                io.agentscope.harness.agent.subagent.task.TaskRepository repo,
                io.agentscope.harness.agent.bus.MessageBus bus,
                String agentId) {
            repo.setCompletionCallback(
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
                        bus.inboxPush(
                                        sessionId,
                                        java.util.Map.of(
                                                "type",
                                                "hint",
                                                "id",
                                                hintId,
                                                "hint",
                                                hintContent,
                                                "source",
                                                "subagent_task"))
                                .subscribe();
                        bus.enqueueWakeup(
                                        userId != null ? userId : "",
                                        sessionId,
                                        agentId != null ? agentId : "")
                                .subscribe();
                    });
        }

        /**
         * 核心编排入口：把 Builder 中收集的全部配置装配成可用的 {@link HarnessAgent}。
         *
         * <p>执行顺序（每一步都对应下方一个注释分段）：
         * <ol>
         *   <li>工具集深拷贝与配置互斥校验，解析工作空间/agentId 缺省值；</li>
         *   <li>分布式存储自动接线（状态存储、远程文件、沙箱快照、消息总线）；</li>
         *   <li>确定会话存储与文件系统隔离范围，解析文件系统实例；</li>
         *   <li>沙箱集成（沙箱模式下替换文件系统并创建生命周期中间件）；</li>
         *   <li>创建工作空间管理器与带外工作空间工厂、消息总线缺省兜底；</li>
         *   <li>按固定顺序注册 harness 中间件链；</li>
         *   <li>子智能体编排（动态优先，退化为静态）；</li>
         *   <li>注册记忆/文件/Shell/规划等工具与 MCP 配置；</li>
         *   <li>技能仓库组合与技能自学习装配；</li>
         *   <li>应用 tools.json 黑白名单过滤，构建内层 ReActAgent 并固化所有引用。</li>
         * </ol>
         */
        public HarnessAgent build() {
            // ==== 阶段 0：工具集深拷贝 ====
            // Toolkit deep-copy: each agent gets its own toolkit so harness-registered tools and
            // user-registered tools never bleed across builds.
            // 每个智能体持有独立的工具集副本，harness 注册的工具与用户注册的工具不会跨构建互相渗透。
            Toolkit agentToolkit = this.toolkit.copy();

            // ---- Validation ----
            // ---- 校验：三种文件系统规格至多选一，且与逃生舱 abstractFilesystem 互斥 ----
            int specCount = 0;
            if (sandboxFilesystemSpec != null) specCount++;
            if (remoteFilesystemSpec != null) specCount++;
            if (localFilesystemSpec != null) specCount++;
            if (specCount > 1) {
                throw new IllegalStateException(
                        "At most one of sandboxFilesystemSpec, remoteFilesystemSpec,"
                                + " localFilesystemSpec may be configured");
            }
            if (abstractFilesystem != null && specCount > 0) {
                throw new IllegalStateException(
                        "abstractFilesystem() is an escape hatch and is mutually exclusive with"
                                + " filesystem(...) specs");
            }
            // 缺省值解析：工作空间默认 ${cwd}/.agentscope/workspace；agentId 缺省取 name
            Path resolvedWorkspace = workspace != null ? workspace : resolveDefaultWorkspace();
            String resolvedAgentId =
                    agentId != null && !agentId.isBlank()
                            ? agentId
                            : (name != null && !name.isBlank() ? name : "ReActAgent");
            // ---- DistributedStore auto-wiring ----
            // distributedStore provides storage components; filesystem mode is user's choice.
            // Priority: explicit builder methods > distributedStore > workspace defaults
            // ---- 分布式存储自动接线 ----
            // 分布式存储只提供组件，文件系统模式仍由用户选择；
            // 优先级：显式 builder 方法 > 分布式存储 > 工作空间默认实现。
            if (distributedStore != null) {
                // 未显式设置状态存储时，改用分布式状态存储
                if (stateStoreOverride == null) {
                    stateStoreOverride = distributedStore.agentStateStore();
                    inner.stateStore(stateStoreOverride);
                }
                // 向远程文件系统注入底层 BaseStore
                if (remoteFilesystemSpec != null) {
                    remoteFilesystemSpec.injectStoreIfAbsent(distributedStore.baseStore());
                }
                // 为沙箱补充快照与执行守卫组件
                if (sandboxFilesystemSpec != null) {
                    if (sandboxFilesystemSpec.getSnapshotSpecOverride() == null) {
                        sandboxFilesystemSpec.snapshotSpec(distributedStore.sandboxSnapshotSpec());
                    }
                    if (sandboxFilesystemSpec.getExecutionGuard() == null) {
                        sandboxFilesystemSpec.executionGuard(
                                distributedStore.sandboxExecutionGuard());
                    }
                }
                if (messageBus == null) {
                    messageBus = distributedStore.messageBus();
                }
                if (asyncToolRegistry == null) {
                    asyncToolRegistry = distributedStore.asyncToolRegistry();
                }
            }

            PeriodicGate periodicGate =
                    distributedStore != null
                            ? new StoreBackedPeriodicGate(distributedStore.baseStore())
                            : new LocalPeriodicGate();

            // ==== 阶段 2：会话存储与文件系统隔离范围 ====
            AgentStateStore effectiveSession = stateStoreOverride;
            // 隔离范围默认 USER；若文件系统规格显式指定了 IsolationScope 则以规格为准，
            // 它决定命名空间分桶方式与记忆维护的限流键。
            IsolationScope fsIsolationScope = IsolationScope.USER;
            if (remoteFilesystemSpec != null && remoteFilesystemSpec.getIsolationScope() != null) {
                fsIsolationScope = remoteFilesystemSpec.getIsolationScope();
            } else if (sandboxFilesystemSpec != null
                    && sandboxFilesystemSpec.getIsolationScope() != null) {
                fsIsolationScope = sandboxFilesystemSpec.getIsolationScope();
            } else if (localFilesystemSpec != null
                    && localFilesystemSpec.getIsolationScope() != null) {
                fsIsolationScope = localFilesystemSpec.getIsolationScope();
            }
            NamespaceFactory nsFactory = fsIsolationScope.toNamespaceFactory();
            // 未显式配置状态存储时，兜底为本地 JSON 文件存储（路径见 defaultStateDir）
            if (effectiveSession == null) {
                effectiveSession = new JsonFileAgentStateStore(defaultStateDir(resolvedAgentId));
                inner.stateStore(effectiveSession);
            }

            // 快速失败：远程（分布式）文件系统不允许搭配本地进程内状态存储，
            // 否则多副本部署下每个节点各存一份会话状态，数据会静默分裂。
            if (remoteFilesystemSpec != null && isLocalSession(effectiveSession)) {
                throw new IllegalStateException(
                        "filesystem(RemoteFilesystemSpec) is designed for distributed /"
                            + " multi-replica deployments, but the effective AgentStateStore is a"
                            + " local in-process implementation (JsonFileAgentStateStore /"
                            + " InMemoryAgentStateStore). Configure a distributed AgentStateStore"
                            + " (for example RedisAgentStateStore) via .stateStore(...) or use"
                            + " .distributedStore(...).");
            }
            // 仅远程模式需要工作空间索引（本地目录与远端存储的映射缓存）
            WorkspaceIndex workspaceIndex =
                    remoteFilesystemSpec != null ? WorkspaceIndex.open(resolvedWorkspace) : null;
            // 按优先级解析文件系统：显式逃生舱 > 远程规格 > 本地规格 > 默认本地叠加层
            AbstractFilesystem filesystem =
                    HarnessAgentBuilderSupport.resolveFilesystem(
                            this, resolvedWorkspace, resolvedAgentId, workspaceIndex, nsFactory);

            // ---- Sandbox integration ----
            // ---- 阶段 3：沙箱集成 ----
            SandboxLifecycleMiddleware sandboxLifecycleMw = null;
            SandboxContext defaultSandboxContext = null;
            SandboxBackedFilesystem capturedSandboxFs = null;
            if (sandboxFilesystemSpec != null) {
                // 沙箱模式：文件系统替换为"沙箱后端"占位实现，
                // 实际读写由调用时申请到的沙箱实例承载。
                capturedSandboxFs = new SandboxBackedFilesystem();
                filesystem =
                        filesystemRoutes.isEmpty()
                                ? capturedSandboxFs
                                : new RoutedSandboxFilesystem(capturedSandboxFs, filesystemRoutes);

                defaultSandboxContext = sandboxFilesystemSpec.toSandboxContext(resolvedWorkspace);

                // 沙箱状态随 AgentState 持久化：本地存储下 JVM 重启即丢失，仅告警不阻断
                if (isLocalSession(effectiveSession)) {
                    log.warn(
                            "[harness] Sandbox mode is using a local AgentStateStore ({})."
                                    + " Sandbox state will not survive JVM restarts and cannot be"
                                    + " shared across instances. For production, configure a"
                                    + " distributed AgentStateStore via .stateStore(...).",
                            effectiveSession.getClass().getSimpleName());
                }

                SessionSandboxStateStore stateStore =
                        new SessionSandboxStateStore(effectiveSession, resolvedAgentId);
                SandboxExecutionGuard executionGuard =
                        sandboxFilesystemSpec.getExecutionGuard() != null
                                ? sandboxFilesystemSpec.getExecutionGuard()
                                : SandboxExecutionGuard.noop();
                SandboxManager sandboxManager =
                        new SandboxManager(
                                defaultSandboxContext.getClient(),
                                stateStore,
                                resolvedAgentId,
                                executionGuard);
                sandboxLifecycleMw =
                        new SandboxLifecycleMiddleware(sandboxManager, capturedSandboxFs);
            } else if (!filesystemRoutes.isEmpty()) {
                filesystem = new CompositeFilesystem(filesystem, filesystemRoutes);
            }
            // ==== 阶段 4：工作空间管理器 + 带外工厂 + 消息总线兜底 ====
            WorkspaceManager wsManager =
                    new WorkspaceManager(resolvedWorkspace, filesystem, workspaceIndex, nsFactory);
            wsManager.validate();

            final AbstractFilesystem sharedFilesystemRef = filesystem;
            final Path capturedWorkspace = resolvedWorkspace;
            final WorkspaceIndex capturedIndex = workspaceIndex;
            // 带外工作空间工厂：把 (userId, sessionId) 烘焙进 RuntimeContext 后包装文件系统，
            // 让网关/后台任务等绕过 call() 的调用方也能命中正确的用户命名空间。
            BiFunction<String, String, WorkspaceManager> workspaceFactoryFn =
                    (uid, sid) -> {
                        RuntimeContext bakedRc =
                                HarnessAgentBuilderSupport.buildBakedRuntimeContext(uid, sid);
                        NamespaceFactory ctxNs =
                                rc -> (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                        AbstractFilesystem ctxFs =
                                new io.agentscope.harness.agent.filesystem.BakedContextFilesystem(
                                        sharedFilesystemRef, bakedRc);
                        return new WorkspaceManager(capturedWorkspace, ctxFs, capturedIndex, ctxNs);
                    };

            // ---- MessageBus / AsyncToolRegistry: workspace defaults ----
            // If not set explicitly or via DistributedStore, fall back to workspace-backed
            // implementations that use the same AbstractFilesystem as the rest of the agent.
            // 消息总线与异步工具注册表缺省兜底：未显式配置且无分布式存储时，
            // 退化为基于同一文件系统的工作空间实现（持久化在 .agentscope/bus 下）。
            if (messageBus == null && filesystem != null) {
                messageBus =
                        new io.agentscope.harness.agent.bus.WorkspaceMessageBus(
                                filesystem, ".agentscope/bus");
            }
            if (asyncToolRegistry == null && filesystem != null) {
                asyncToolRegistry =
                        new io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry(
                                filesystem, ".agentscope/bus/async-tools");
            }

            // ---- Middlewares ----
            // ---- 阶段 5：按固定顺序注册 harness 中间件链 ----
            // 顺序即执行嵌套顺序，各中间件职责：
            //   沙箱生命周期 → 执行追踪日志 → 工作空间上下文注入 → @路径展开
            //   → 记忆落盘 → 记忆维护 → 上下文压缩 → 工具结果淘汰 → 收件箱投递
            if (sandboxLifecycleMw != null) {
                inner.middleware(sandboxLifecycleMw);
            }
            if (agentTracingLogEnabled) {
                inner.middleware(new AgentTraceMiddleware());
            }
            boolean artifactDeliveryEnabled =
                    artifactDeliveryTarget != null && !disableFilesystemTools;
            // 工作空间上下文：把 AGENTS.md / MEMORY.md / KNOWLEDGE.md 等注入系统提示词
            if (!disableWorkspaceContext) {
                WorkspaceContextMiddleware markdownMw =
                        new WorkspaceContextMiddleware(
                                wsManager,
                                name != null ? name : "ReActAgent",
                                environmentMemory,
                                maxContextTokens,
                                disableMemoryTools,
                                disableMemoryHooks);
                markdownMw.setAdditionalContextFiles(additionalContextFiles);
                markdownMw.setArtifactDeliveryEnabled(artifactDeliveryEnabled);
                inner.middleware(markdownMw);
            }
            if (!disableAtPathExpansion) {
                inner.middleware(new AtPathExpansionMiddleware(wsManager));
            }
            // Transcript is independent of memory hooks — always persist session history.
            if (!disableTranscript) {
                TranscriptStore effectiveTranscriptStore = transcriptStore;
                if (effectiveTranscriptStore == null) {
                    if (wsManager.getFilesystem() != null) {
                        effectiveTranscriptStore =
                                new ObjectStoreTranscriptStore(wsManager.getFilesystem());
                    } else {
                        effectiveTranscriptStore =
                                new FilesystemTranscriptStore(
                                        wsManager
                                                .getWorkspace()
                                                .resolve(".agentscope/transcripts"));
                    }
                }
                inner.middleware(
                        new TranscriptMiddleware(
                                wsManager, effectiveTranscriptStore, transcriptTenant));
            }
            // 记忆链路：模型缺省复用主模型；落盘中间件 + 维护中间件成对装配
            Model memoryModel = memoryConfig.model() != null ? memoryConfig.model() : model;
            if (memoryModel != null && !disableMemoryHooks) {
                IsolationScope effectiveIsolationScope = fsIsolationScope;

                String effectiveFlushPrompt =
                        memoryConfig.flushPrompt() != null
                                ? memoryConfig.flushPrompt()
                                : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
                inner.middleware(
                        new MemoryFlushMiddleware(
                                wsManager,
                                memoryModel,
                                effectiveFlushPrompt,
                                memoryConfig.flushTrigger(),
                                effectiveIsolationScope,
                                periodicGate));

                String effectiveConsolidationPrompt =
                        memoryConfig.consolidationPrompt() != null
                                ? memoryConfig.consolidationPrompt()
                                : MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT;
                MemoryConsolidator consolidator =
                        new MemoryConsolidator(
                                wsManager,
                                memoryModel,
                                effectiveConsolidationPrompt,
                                memoryConfig.consolidationMaxTokens(),
                                distributedStore != null ? distributedStore.baseStore() : null);
                inner.middleware(
                        new MemoryMaintenanceMiddleware(
                                wsManager,
                                consolidator,
                                memoryConfig.dailyFileRetentionDays(),
                                memoryConfig.sessionRetentionDays(),
                                memoryConfig.consolidationMinGap(),
                                effectiveIsolationScope,
                                periodicGate));
            }
            // 上下文压缩：模型缺省复用主模型；实例保留给溢出恢复路径作开关判断
            CompactionMiddleware compactionHook = null;
            if (!disableCompaction && compactionConfig != null) {
                Model compactionModel =
                        compactionConfig.getModel() != null ? compactionConfig.getModel() : model;
                if (compactionModel != null) {
                    compactionHook =
                            new CompactionMiddleware(wsManager, compactionModel, compactionConfig);
                    inner.middleware(compactionHook);
                }
            }
            if (!disableToolResultEviction && toolResultEvictionConfig != null) {
                inner.middleware(
                        new ToolResultEvictionMiddleware(filesystem, toolResultEvictionConfig));
            }
            // 收件箱：每轮推理前排空会话收件箱（后台任务通知、异步工具结果等）
            if (messageBus != null) {
                inner.middleware(new InboxMiddleware(messageBus, 100, asyncToolRegistry, null));
            }

            TeamsMiddleware capturedTeamsMw = null;
            if (teamsModeClient != null && teamsModeContext != null) {
                TeamsMiddleware teamsMw = new TeamsMiddleware(teamsModeClient, teamsModeContext);
                if (messageBus != null) {
                    teamsMw.wireMessageBus(messageBus, agentId != null ? agentId : name);
                }
                teamsMw.bindSession(teamsModeSessionId);
                inner.middleware(teamsMw);
                for (Object t : teamsMw.getTools()) {
                    agentToolkit.registerTool(t);
                }
                capturedTeamsMw = teamsMw;
            }

            // ---- 阶段 6：子智能体编排 ----
            // 前提：非叶子智能体、未禁用子智能体且已配置模型。
            // 选型：有文件系统且未禁用动态加载 → 动态中间件（每轮扫描 workspace/subagents/）；
            //       否则退化为静态中间件（仅构建期装载）。
            Object capturedSubagentMw = null;
            if (!leafSubagent && !disableSubagents && model != null) {
                if (filesystem != null && !disableDynamicSubagents) {
                    DynamicSubagentsMiddleware dynMw =
                            HarnessAgentBuilderSupport.buildDynamicSubagentsMiddleware(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (dynMw != null) {
                        // 接线消息总线：后台任务完成时推送系统通知并唤醒主智能体
                        if (messageBus != null) {
                            wireTaskRepositoryMessageBus(
                                    dynMw.getTaskRepository(), messageBus, agentId);
                        }
                        inner.middleware(dynMw);
                        // 注册 task / task_output / agent_spawn 等工具
                        for (Object t : dynMw.getTools()) {
                            agentToolkit.registerTool(t);
                        }
                        capturedSubagentMw = dynMw;
                    }
                } else {
                    SubagentsMiddleware subagentsMw =
                            HarnessAgentBuilderSupport.buildSubagentsMiddleware(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (subagentsMw != null) {
                        if (messageBus != null) {
                            subagentsMw.wireMessageBus(messageBus, agentId);
                        }
                        inner.middleware(subagentsMw);
                        for (Object t : subagentsMw.getTools()) {
                            agentToolkit.registerTool(t);
                        }
                        capturedSubagentMw = subagentsMw;
                    }
                }
            }

            // 异步工具中间件：超时工具转入后台执行，结果经收件箱回传
            if (messageBus != null && asyncToolTimeout != null) {
                inner.middleware(
                        new AsyncToolMiddleware(messageBus, asyncToolTimeout, asyncToolRegistry));
            }
            // 有消息总线即提供"等待异步结果"工具
            if (messageBus != null) {
                TaskRepository waitTaskRepo = null;
                if (capturedSubagentMw instanceof SubagentsMiddleware sm) {
                    waitTaskRepo = sm.getTaskRepository();
                } else if (capturedSubagentMw instanceof DynamicSubagentsMiddleware dsm) {
                    waitTaskRepo = dsm.getTaskRepository();
                }
                io.agentscope.harness.agent.tool.WaitAsyncResultsTool waitTool =
                        new io.agentscope.harness.agent.tool.WaitAsyncResultsTool(
                                messageBus, waitTaskRepo);
                if (capturedTeamsMw != null) {
                    TeamsMiddleware teamsForWait = capturedTeamsMw;
                    waitTool.setExternalWorkProbe(teamsForWait::hasOutstandingTeamWork);
                }
                agentToolkit.registerTool(waitTool);
            }

            // ---- Toolkit (memory / filesystem / shell tools) ----
            // ---- 阶段 7：注册记忆工具与文件系统/Shell 工具 ----
            if (!disableMemoryTools) {
                agentToolkit.registerTool(new MemorySearchTool(wsManager));
                agentToolkit.registerTool(new MemoryGetTool(wsManager));
                agentToolkit.registerTool(new MemorySaveTool(wsManager));
                agentToolkit.registerTool(new SessionSearchTool(wsManager));
            }
            // 路径归一化策略按文件系统类型区分：
            WorkspacePathNormalizer pathNormalizer;
            if (filesystem instanceof OverlayFilesystem ov
                    && ov.getUpper() instanceof LocalFilesystemWithShell) {
                // Local overlay mode. ShellAwareOverlay is instanceof AbstractSandboxFilesystem,
                // so this branch must come before the sandbox check below to avoid using the
                // sandbox "/workspace" prefix for real host paths.
                // Only strip the workspace prefix — NOT the project prefix. Project absolute
                // paths are handled correctly by the ROOTED pathPolicy, and stripping them
                // would produce relative paths whose lower-layer virtual entries (/src/...)
                // then fail in the upper layer's ROOTED check.
                // 本地叠加模式：ShellAwareOverlay 也属于 AbstractSandboxFilesystem，
                // 因此本分支必须先于下方的沙箱判断，否则真实宿主机路径会被误加沙箱 /workspace 前缀。
                // 这里只剥离工作空间前缀、不剥离项目前缀——项目绝对路径由 ROOTED 路径策略正确处理，
                // 若强行剥离会生成相对路径，其下层虚拟条目（/src/...）在上层的 ROOTED 校验中会失败。
                pathNormalizer =
                        WorkspacePathNormalizer.of(
                                resolvedWorkspace.toAbsolutePath().toString(), nsFactory);
            } else if (filesystem instanceof AbstractSandboxFilesystem) {
                // 沙箱模式：统一使用 /workspace 前缀
                pathNormalizer =
                        WorkspacePathNormalizer.of(ShellPathPolicy.SANDBOX_WORKSPACE_PREFIX);
            } else {
                pathNormalizer =
                        WorkspacePathNormalizer.of(resolvedWorkspace.toAbsolutePath().toString());
            }
            if (!disableFilesystemTools) {
                agentToolkit.registerTool(new FilesystemTool(filesystem, pathNormalizer));
            }
            if (artifactDeliveryEnabled) {
                agentToolkit.registerTool(
                        new ArtifactDeliveryTool(
                                filesystem, pathNormalizer, artifactDeliveryTarget));
            }
            // Shell 工具仅在沙箱文件系统下注册（命令在沙箱内执行，天然隔离）
            if (!disableShellTool && filesystem instanceof AbstractSandboxFilesystem sandbox) {
                agentToolkit.registerTool(new ShellExecuteTool(sandbox));
            }
            if (!disableWebTools) {
                if (webHttpClient != null) {
                    agentToolkit.registerTool(new WebTools.WebFetchTool(webHttpClient));
                    agentToolkit.registerTool(new WebTools.WebSearchTool(webHttpClient));
                } else {
                    agentToolkit.registerTool(new WebTools.WebFetchTool());
                    agentToolkit.registerTool(new WebTools.WebSearchTool());
                }
            }

            // ---- Plan mode (read-only design phase) ----
            // ---- 规划模式（只读设计阶段）：注册三个 plan 工具并挂载只读强制中间件 ----
            PlanModeManager planModeManager = null;
            if (planModeEnabled) {
                planModeManager = new PlanModeManager(wsManager, planFileDir);
                agentToolkit.registerTool(new PlanModeTools.PlanEnterTool(planModeManager));
                agentToolkit.registerTool(new PlanModeTools.PlanWriteTool(planModeManager));
                agentToolkit.registerTool(new PlanModeTools.PlanExitTool(planModeManager));
                final Toolkit roToolkit = agentToolkit;
                // allowShellInPlanMode 开启时把 Shell 加入白名单（提示词仍约束只读用法）
                java.util.Set<String> planExtraAllowed =
                        planModeAllowShell
                                ? java.util.Set.of(ShellExecuteTool.NAME)
                                : java.util.Set.of();
                // 工具放行判定：查工具集中该工具是否声明为只读
                inner.middleware(
                        new io.agentscope.harness.agent.middleware.PlanModeMiddleware(
                                planModeManager,
                                toolName -> {
                                    AgentTool t = roToolkit.getTool(toolName);
                                    return t != null && t.isReadOnly();
                                },
                                planExtraAllowed));
            }

            // ---- workspace/tools.json: MCP servers + allow/deny filter ----
            // ---- MCP 服务装配：优先代码覆盖，其次读取 workspace/tools.json ----
            ToolsConfig resolvedToolsConfig = null;
            if (!disableToolsConfig) {
                if (toolsConfigOverride != null) {
                    resolvedToolsConfig = toolsConfigOverride;
                } else if (wsManager != null) {
                    resolvedToolsConfig = ToolsConfigLoader.load(wsManager).orElse(null);
                }
            }
            if (resolvedToolsConfig != null) {
                McpServerRegistrar.register(
                        agentToolkit,
                        resolvedToolsConfig.getMcpServers(),
                        mcpServerRegistrationListener);
            }

            // ---- Skills ----
            // ---- 阶段 8：技能仓库组合（四层叠加：全局 → 市场 → 工作空间 → 用户命名空间）----
            List<AgentSkillRepository> orderedSkillRepos =
                    HarnessAgentBuilderSupport.composeSkillRepositories(
                            this, wsManager, filesystem);

            // ---- Skill self-learning: writable workspace skills + skill_manage tool ----
            // ---- 技能自学习：可写工作空间技能仓库 + skill_manage 工具 ----
            SkillPromoter pendingSkillPromoter = null;
            SkillUsageStore pendingSkillUsageStore = null;
            SkillCurator pendingSkillCurator = null;
            SkillAuditLog pendingSkillAuditLog = null;
            if (skillManageToolEnabled && filesystem != null) {
                SkillManageConfig smConfig =
                        skillManageConfig != null
                                ? skillManageConfig
                                : SkillManageConfig.defaults();
                WorkspaceSkillRepository mainWritableRepo = null;
                for (int i = orderedSkillRepos.size() - 1; i >= 0; i--) {
                    AgentSkillRepository r = orderedSkillRepos.get(i);
                    // The default Layer-4 repo (composeSkillRepositories) is a read-only
                    // WorkspaceSkillRepository pointed at "skills". Replace it with a
                    // writable one pointed at the configured main dir so skill_manage can
                    // persist.
                    // 默认第四层仓库是只读的 WorkspaceSkillRepository（指向 skills 目录）。
                    // 这里把它替换为指向主技能目录的可写仓库，使 skill_manage 的写入能落盘。
                    if (r instanceof WorkspaceSkillRepository wsr && !wsr.isWriteable()) {
                        mainWritableRepo =
                                new WorkspaceSkillRepository(
                                        filesystem, smConfig.mainDir(), "workspace-writable");
                        orderedSkillRepos.set(i, mainWritableRepo);
                        break;
                    }
                }
                if (mainWritableRepo == null) {
                    mainWritableRepo =
                            new WorkspaceSkillRepository(
                                    filesystem, smConfig.mainDir(), "workspace-writable");
                    orderedSkillRepos.add(mainWritableRepo);
                }
                // 草稿区仓库：新技能先进草稿区，通过晋升门后才能进入主目录
                WorkspaceSkillRepository draftsWritableRepo =
                        new WorkspaceSkillRepository(
                                filesystem, smConfig.draftsDir(), "workspace-drafts");
                SkillUsageStore usageStore =
                        distributedStore != null
                                ? SkillUsageStore.baseStore(distributedStore.baseStore())
                                : new SkillUsageStore(filesystem);
                SkillAuditLog auditLog = new SkillAuditLog(filesystem, wsManager);
                SkillManageTool skillManageTool =
                        new SkillManageTool(
                                mainWritableRepo,
                                draftsWritableRepo,
                                smConfig,
                                usageStore,
                                auditLog);
                pendingSkillAuditLog = auditLog;
                agentToolkit.registerAgentTool(skillManageTool);
                agentToolkit.registerAgentTool(new ProposeSkillTool(skillManageTool));
                // 调用统计中间件：记录各技能的 view/use 次数，供晋升与整理决策使用
                inner.middleware(
                        new io.agentscope.harness.agent.middleware.SkillUsageMiddleware(
                                usageStore));

                // 晋升器：把满足条件的草稿技能提升为主技能；未配置晋升门时默认全部拒绝
                pendingSkillPromoter =
                        new SkillPromoter(
                                draftsWritableRepo,
                                mainWritableRepo,
                                wsManager,
                                usageStore,
                                promotionGate != null ? promotionGate : new RejectAllGate(),
                                smConfig.draftsDir(),
                                smConfig.mainDir(),
                                auditLog);
                pendingSkillUsageStore = usageStore;

                // 后台技能整理器：定期清理/归档低价值技能，依赖 skill_manage 已启用
                if (skillCuratorEnabled) {
                    SkillCurator curator =
                            new SkillCurator(
                                    filesystem,
                                    usageStore,
                                    mainWritableRepo,
                                    skillCuratorConfig != null
                                            ? skillCuratorConfig
                                            : SkillCuratorConfig.defaults(),
                                    periodicGate);
                    pendingSkillCurator = curator;
                    inner.middleware(
                            new io.agentscope.harness.agent.middleware.SkillCuratorMiddleware(
                                    curator));
                }
            }

            if (!orderedSkillRepos.isEmpty()) {
                // 市场技能暂存器：把市场技能物化到工作空间本地目录
                io.agentscope.harness.agent.skill.runtime.MarketplaceStager stager =
                        resolvedWorkspace != null
                                ? new io.agentscope.harness.agent.skill.runtime.MarketplaceStager(
                                        resolvedWorkspace)
                                : null;

                // 按文件系统类型推导 Shell 路径策略，决定技能脚本的执行环境与前缀
                io.agentscope.harness.agent.skill.runtime.ShellPathPolicy shellPolicy;
                boolean shellToolAvailable =
                        !disableShellTool
                                && ToolFilter.isAllowed(ShellExecuteTool.NAME, resolvedToolsConfig);
                if (!shellToolAvailable) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.noShell();
                } else if (filesystem instanceof LocalFilesystemWithShell) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                    .localWithShell(resolvedWorkspace);
                } else if (filesystem instanceof OverlayFilesystem ov
                        && ov.getUpper() instanceof LocalFilesystemWithShell) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                    .localWithShell(resolvedWorkspace);
                } else if (filesystem instanceof SandboxBackedFilesystem
                        || (filesystem instanceof RoutedSandboxFilesystem routed
                                && routed.primary() instanceof SandboxBackedFilesystem)) {
                    String wsPrefix =
                            defaultSandboxContext != null
                                            && defaultSandboxContext.getClientOptions() != null
                                    ? defaultSandboxContext.getClientOptions().getWorkspaceRoot()
                                    : io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                            .SANDBOX_WORKSPACE_PREFIX;
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.sandbox(
                                    wsPrefix);
                } else {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.noShell();
                }

                HarnessSkillMiddleware skillMiddleware =
                        disableDynamicSkills
                                ? HarnessSkillMiddleware.frozen(
                                        orderedSkillRepos,
                                        agentToolkit,
                                        skillFilter,
                                        visibilityFilter,
                                        stager,
                                        shellPolicy)
                                : new HarnessSkillMiddleware(
                                        orderedSkillRepos,
                                        agentToolkit,
                                        skillFilter,
                                        visibilityFilter,
                                        stager,
                                        shellPolicy);
                skillMiddleware.isolationScope(fsIsolationScope);
                inner.middleware(skillMiddleware);

                // Harness owns both the live and frozen repository paths.
                // 始终关闭核心层的技能自动装配——技能中间件由 harness 自行接管。
                inner.dynamicSkillsEnabled(false);

                // Wire pre-start staging so sandbox projection picks up .skills-cache content
                // that MarketplaceStager materialises from database-backed repositories.
                if (sandboxLifecycleMw != null && stager != null) {
                    sandboxLifecycleMw.setBeforeStartCallback(
                            skillMiddleware::prestageMarketplaceSkills);
                }
            } else if (disableDynamicSkills) {
                // No composed repositories exist, but preserve the explicit core opt-out.
                inner.dynamicSkillsEnabled(false);
            }

            // ---- Apply tools.json allow/deny filter ----
            // Platform tools (subagents/teams/tasks/…) survive allow; see ToolFilter.
            // ---- 阶段 9：最后统一应用 tools.json 的黑白名单过滤（此前注册的所有工具都受约束）----
            if (resolvedToolsConfig != null) {
                ToolFilter.apply(agentToolkit, resolvedToolsConfig);
            }

            log.info(
                    "HarnessAgent '{}' built [workspace={}, filesystem={}, subagents={}]",
                    name,
                    resolvedWorkspace,
                    filesystem.getClass().getSimpleName(),
                    !leafSubagent && !disableSubagents && model != null);

            // ---- Build inner ReActAgent ----
            // ---- 阶段 10：构建内层 ReActAgent 并固化所有引用 ----
            inner.toolkit(agentToolkit);
            ReActAgent delegate = inner.build();

            return new HarnessAgent(
                    delegate,
                    wsManager,
                    workspaceFactoryFn,
                    workspaceIndex,
                    defaultSandboxContext,
                    compactionHook,
                    sandboxLifecycleMw,
                    orderedSkillRepos,
                    planModeManager,
                    pendingSkillPromoter,
                    pendingSkillUsageStore,
                    pendingSkillCurator,
                    pendingSkillAuditLog,
                    memoryConfig,
                    capturedSubagentMw,
                    distributedStore,
                    pathNormalizer,
                    agentToolkit);
        }
    }
}
