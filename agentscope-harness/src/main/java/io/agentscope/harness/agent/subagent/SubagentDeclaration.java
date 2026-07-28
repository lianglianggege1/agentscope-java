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
package io.agentscope.harness.agent.subagent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Declares a subagent: its identity, workspace resolution strategy, and optional capability
 * allowlist.
 *
 * <p>A declaration binds to exactly one of two <em>source modes</em>:
 *
 * <ol>
 *   <li><b>Definition workspace</b> — {@link #getWorkspacePath()} points to a workspace directory
 *       containing at least {@code AGENTS.md}. That file is used as the subagent's system-prompt
 *       body. Skills, knowledge, and MEMORY in the definition directory are available when the
 *       {@link WorkspaceMode} is {@link WorkspaceMode#ISOLATED}.
 *   <li><b>Remote HTTP</b> — {@link #getUrl()} points to an AgentScope task HTTP server. No local
 *       definition workspace or inline body; the subagent runs out-of-process. Mutually exclusive
 *       with definition workspace and inline body.
 * </ol>
 *
 * <p>The three source modes are mutually exclusive: at most one of {@link Builder#workspace(Path)},
 * a non-blank {@link Builder#inlineAgentsBody(String)}, or a non-blank {@link Builder#url(String)}
 * may be set.
 *
 * <p>Workspace resolution follows the five-row decision table in {@link WorkspaceMode}.
 *
 * <p>The {@code tools} list, when non-empty, acts as an <em>allowlist filter</em> for inherited
 * parent tools: only inherited tools whose names appear in the list are kept. Child-local tool
 * registrations may still be added by the child builder.
 *
 * <p>Obtain instances via {@link #builder()}.
 *
 * <p>Example (programmatic):
 *
 * <pre>{@code
 * SubagentDeclaration decl = SubagentDeclaration.builder()
 *     .name("code-reviewer")
 *     .description("Reviews code for security, performance, and readability issues.")
 *     .workspace(Path.of("./defs/code-reviewer"))
 *     .workspaceMode(WorkspaceMode.ISOLATED)
 *     .model("qwen3-max")
 *     .tools(List.of("read_file", "grep_files", "edit_file"))
 *     .build();
 * }</pre>
 */
/**
 * 子智能体定义类：描述子智能体标识、工作区解析策略、可选工具白名单。
 *
 * <p>一份定义仅支持以下两种资源来源模式之一，二者互斥：
 *
 * <ol>
 *   <li><b>本地定义工作区模式</b> — {@link #getWorkspacePath()} 指向工作目录，目录内至少包含 AGENTS.md。
 *       该文件作为子智能体系统提示词；当 {@link WorkspaceMode} 为 {@link WorkspaceMode#ISOLATED} 时，
 *       目录内的技能、知识库、MEMORY记忆文件均可生效。
 *   <li><b>远程HTTP模式</b> — {@link #getUrl()} 指向 AgentScope 任务HTTP服务端。无本地定义目录、无内联提示文本，
 *       子智能体独立进程运行，与本地工作区、内联提示模式互斥。
 * </ol>
 *
 * <p>三种资源配置互斥：{@link Builder#workspace(Path)}、非空内联提示 {@link Builder#inlineAgentsBody(String)}、
 * 非空远程地址 {@link Builder#url(String)} 最多只能配置其中一项。
 *
 * <p>工作区路径解析规则遵循 {@link WorkspaceMode} 中五分支决策对照表。
 *
 * <p>tools 列表非空时，作为父智能体继承工具的白名单过滤：仅列表内命名的工具会被子智能体继承。
 * 子智能体自身构建器仍可额外注册本地专属工具。
 *
 * <p>通过 {@link #builder()} 获取构建器创建实例。
 *
 * <p>代码示例：
 *
 * <pre>{@code
 * SubagentDeclaration decl = SubagentDeclaration.builder()
 *     .name("code-reviewer")
 *     .description("Reviews code for security, performance, and readability issues.")
 *     .workspace(Path.of("./defs/code-reviewer"))
 *     .workspaceMode(WorkspaceMode.ISOLATED)
 *     .model("qwen3-max")
 *     .tools(List.of("read_file", "grep_files", "edit_file"))
 *     .build();
 * }</pre>
 */
public final class SubagentDeclaration {

    /**
     * Whether a declaration can be used as a top-level primary agent, only as a delegated
     * subagent, or both.
     *
     * <p>The {@link io.agentscope.harness.agent.subagent.DefaultAgentManager#createAgentIfPresent}
     * path rejects spawn requests for {@link #PRIMARY}-only declarations so they can never be
     * invoked as workers; conversely, top-level launchers may want to reject {@link #SUBAGENT}
     * declarations as entry points (current core does not own that check — top-level launch goes
     * through {@code HarnessAgent.builder()} directly, not through a declaration).
     */
    /**
     * 标识当前智能体配置的可用类型：仅可作为顶层主智能体、仅可作为委派子智能体，或是两者皆可。
     *
     * <p>{@link io.agentscope.harness.agent.subagent.DefaultAgentManager#createAgentIfPresent}
     * 逻辑会拒绝仅标记为 {@link #PRIMARY} 的配置生成子工作实例，这类配置不能作为被调度的工作子智能体；
     * 反之，顶层启动器通常会拦截 {@link #SUBAGENT} 类型配置作为程序入口（当前核心框架未内置该校验逻辑，顶层启动流程直接通过 {@code HarnessAgent.builder()} 构建，不走配置声明加载链路）。
     */
    public enum Mode {
        PRIMARY,
        SUBAGENT,
        ALL
    }

    private final String name;
    private final String description;
    private final WorkspaceMode workspaceMode;
    private final Path workspacePath;
    private final String inlineAgentsBody;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final String variant;
    private final int steps;
    private final Mode mode;
    private final boolean hidden;
    private final boolean persistSession;
    private final boolean inheritParentPermissions;
    private final Boolean exposeToUser;
    private final List<String> tools;
    private final List<String> skills;

    /** Base URL of the remote task server (e.g. {@code http://host:8080}). */
    private final String url;

    private final Map<String, String> headers;

    private SubagentDeclaration(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.workspaceMode = b.workspaceMode;
        this.workspacePath = b.workspacePath;
        this.inlineAgentsBody = b.inlineAgentsBody;
        this.model = b.model;
        this.temperature = b.temperature;
        this.topP = b.topP;
        this.variant = b.variant;
        this.steps = b.steps;
        this.mode = b.mode != null ? b.mode : Mode.ALL;
        this.hidden = b.hidden;
        this.persistSession = b.persistSession;
        this.inheritParentPermissions = b.inheritParentPermissions;
        this.exposeToUser = b.exposeToUser;
        this.tools = b.tools != null ? List.copyOf(b.tools) : List.of();
        this.skills = b.skills != null ? List.copyOf(b.skills) : List.of();
        this.url = b.url;
        this.headers = b.headers != null && !b.headers.isEmpty() ? Map.copyOf(b.headers) : null;
    }

    /** Factory method for a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Unique name / agent-id used to reference this subagent. */
    public String getName() {
        return name;
    }

    /** Human-readable description; the main agent uses this to decide when to delegate. */
    public String getDescription() {
        return description;
    }

    /**
     * Workspace resolution strategy. Defaults to {@link WorkspaceMode#ISOLATED} when not
     * specified.
     */
    public WorkspaceMode getWorkspaceMode() {
        return workspaceMode;
    }

    /**
     * Path to the definition workspace directory (contains at least {@code AGENTS.md}). When
     * {@code null} this declaration is in inline mode and {@link #getInlineAgentsBody()} provides
     * the system prompt.
     */
    public Path getWorkspacePath() {
        return workspacePath;
    }

    /**
     * Inline system-prompt body used when {@link #getWorkspacePath()} is {@code null}. May be
     * {@code null} or blank if neither a definition workspace nor an inline body is provided.
     */
    public String getInlineAgentsBody() {
        return inlineAgentsBody;
    }

    /**
     * Optional model override (e.g. {@code "qwen3-max"} or {@code "openai:gpt-4o-mini"}). When
     * {@code null} or blank, the parent model is used.
     */
    public String getModel() {
        return model;
    }

    /**
     * Maximum reasoning iterations. Defaults to 10.
     *
     * @deprecated since Phase A — use {@link #getSteps()}. Returns the same value; kept for source
     *     compatibility with callers built before the {@code steps} field existed.
     */
    @Deprecated
    public int getMaxIters() {
        return steps;
    }

    /** Maximum reasoning iterations (default 10). Replaces the historical {@code maxIters} field. */
    public int getSteps() {
        return steps;
    }

    /**
     * Optional sampling temperature override (e.g. {@code 0.0} for deterministic compaction-like
     * tasks, {@code 0.7} for creative generation). When {@code null}, the parent's
     * {@link io.agentscope.core.model.GenerateOptions#getTemperature()} applies unchanged.
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * Optional nucleus-sampling override. When {@code null}, the parent's
     * {@link io.agentscope.core.model.GenerateOptions#getTopP()} applies unchanged.
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * Optional model variant identifier (e.g. {@code "thinking"} for DashScope thinking-mode
     * variants). When {@code null} or blank, no variant transform is applied; the parent's
     * variant — if any — is inherited via builder copy.
     */
    public String getVariant() {
        return variant;
    }

    /**
     * The {@link Mode} of this declaration. Defaults to {@link Mode#ALL} when not specified —
     * both spawnable and primary-capable.
     */
    /**
     * 当前声明对应的 {@link Mode} 模式。未指定时默认为 {@link Mode#ALL}，
     * 同时支持作为可派生子智能体与主智能体运行。
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Whether this declaration should be hidden from the LLM's view of available subagents.
     * Used for internal subagents (e.g. compaction, summary, title) that the orchestrator should
     * not directly delegate to.
     */
    /**
     * 该子智能体声明是否对大模型隐藏，不在可用子智能体列表中展示。
     * 用于内部子智能体（如信息压缩、摘要生成、标题生成等），编排器不应直接向其下发任务。
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Whether the subagent's session state should persist across parent calls. When {@code true},
     * the spawn key is derived deterministically from (parentSessionId, agentId, label), enabling
     * state recovery after process restarts. When {@code false} (default), a random UUID is used.
     */
    /**
     * 子智能体会话状态是否在父会话多次调用间持久保留。
     * 设为 {@code true} 时，派生键由(parentSessionId、agentId、label)确定性生成，
     * 进程重启后可恢复状态。若为 {@code false}（默认），则使用随机UUID。
     */
    public boolean isPersistSession() {
        return persistSession;
    }

    /**
     * Whether the subagent inherits parent DENY permission rules. When {@code true} (default),
     * all DENY rules from the parent's permission context are propagated to the child's permission
     * engine at spawn time, preventing the child from circumventing parent-level restrictions.
     */
    /**
     * 子智能体是否继承父级黑名单权限规则。
     * 设为 {@code true}（默认值）时，父权限上下文中所有黑名单规则会在创建子智能体时同步至子权限引擎，
     * 避免子智能体绕过父级权限限制。
     */
    public boolean isInheritParentPermissions() {
        return inheritParentPermissions;
    }

    /**
     * Per-type policy for exposing spawned instances of this subagent as user-addressable threads.
     *
     * <p>Tri-state:
     *
     * <ul>
     *   <li>{@code TRUE} — always expose, regardless of what the LLM requests on {@code agent_spawn}
     *   <li>{@code FALSE} — never expose (hard opt-out), overriding an LLM {@code expose_to_user=true}
     *   <li>{@code null} (default) — no opinion; defer to the per-call {@code RuntimeContext} override
     *       and then the LLM's {@code expose_to_user} argument
     * </ul>
     *
     * <p>This is overridden at runtime by a {@code RuntimeContext} value keyed
     * {@code AgentSpawnTool#CTX_EXPOSE_TO_USER}. See {@code AgentSpawnTool} for the full
     * resolution precedence.
     */
    /**
     * 按智能体类型定义策略：控制生成的子智能体实例是否对外暴露为用户可访问线程。
     *
     * <p>三态取值规则：
     *
     * <ul>
     *   <li>{@code TRUE} — 强制对外暴露，不受大模型调用 agent_spawn 参数影响
     *   <li>{@code FALSE} — 完全禁止暴露（硬性禁用），优先级高于大模型传入的 expose_to_user=true
     *   <li>{@code null}（默认值）— 无强制策略；优先取单次调用 {@code RuntimeContext} 的覆盖配置，再沿用大模型传入的 expose_to_user 参数
     * </ul>
     *
     * <p>运行时会被 {@code AgentSpawnTool#CTX_EXPOSE_TO_USER} 键对应的运行时上下文值覆盖。完整的优先级判定逻辑参见 {@code AgentSpawnTool}。
     */
    public Boolean getExposeToUser() {
        return exposeToUser;
    }

    /**
     * Optional tool allowlist. When non-empty, only inherited parent tools whose names are listed
     * remain on the subagent's inherited toolkit. Empty means inherit all parent tools.
     */
    /**
     * 可选工具白名单。非空时，子智能体仅继承名单内命名的父级工具。
     * 为空则继承父智能体全部工具。
     */
    public List<String> getTools() {
        return tools;
    }

    public List<String> getSkills() {
        return skills;
    }

    /** Returns {@code true} when this declaration targets a remote task HTTP server. */
    public boolean isRemote() {
        return url != null && !url.isBlank();
    }

    /**
     * Base URL of the remote task server. Non-blank only in {@linkplain #isRemote() remote} mode.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Optional HTTP headers (e.g. auth) sent to the remote task server. Never empty when
     * non-null.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** Returns {@code true} when this declaration points at an external definition workspace. */
    public boolean hasDefinitionWorkspace() {
        return workspacePath != null;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        private String name;
        private String description;
        private WorkspaceMode workspaceMode = WorkspaceMode.ISOLATED;
        private Path workspacePath;
        private String inlineAgentsBody;
        private String model;
        private Double temperature;
        private Double topP;
        private String variant;
        private int steps = 10;
        private Mode mode = Mode.ALL;
        private boolean hidden = false;
        private boolean persistSession = false;
        private boolean inheritParentPermissions = true;
        private Boolean exposeToUser;
        private List<String> tools;
        private List<String> skills;
        private String url;
        private Map<String, String> headers;

        private Builder() {}

        /** Sets the unique name / agent-id for this subagent (required). */
        /** 设置该子智能体唯一名称/智能体ID（必填项）。 */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the human-readable description the orchestrator uses to decide when to delegate
         * (required).
         */
        /**
         * 设置便于人类阅读的描述信息，编排器依靠该描述判断何时将任务委派给此子智能体（必填项）。
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the workspace resolution mode. Defaults to {@link WorkspaceMode#ISOLATED}.
         *
         * @param mode workspace mode; {@code null} is treated as {@link WorkspaceMode#ISOLATED}
         */
        /**
         * 设置工作空间解析模式。默认值为 {@link WorkspaceMode#ISOLATED}。
         *
         * @param mode 工作空间模式；传入 {@code null} 时等效于 {@link WorkspaceMode#ISOLATED}
         */
        public Builder workspaceMode(WorkspaceMode mode) {
            this.workspaceMode = mode != null ? mode : WorkspaceMode.ISOLATED;
            return this;
        }

        /**
         * Points this declaration at an external definition workspace.
         *
         * <p>Mutually exclusive with {@link #inlineAgentsBody(String)}: passing both a non-null
         * path <em>and</em> a non-blank inline body will cause {@link #build()} to throw.
         *
         * @param workspacePath absolute path, or path relative to {@code mainWorkspace} when set
         *     via a Markdown front matter file
         */
        /**
         * 将当前声明指向外部定义工作空间。
         *
         * <p>该方法与 {@link #inlineAgentsBody(String)} 互斥：若同时传入非空路径与非空白内联正文，
         * 调用 {@link #build()} 时将抛出异常。
         *
         * @param workspacePath 绝对路径；若通过 Markdown 前置元文件配置，则为相对于 {@code mainWorkspace} 的路径
         */
        public Builder workspace(Path workspacePath) {
            this.workspacePath = workspacePath;
            return this;
        }

        /**
         * Sets the inline system-prompt body for lightweight subagents that do not need a
         * dedicated definition workspace.
         *
         * <p>Mutually exclusive with {@link #workspace(Path)}.
         *
         * @param body the system-prompt body text (Markdown); may be {@code null} or blank
         */
        /**
         * 为轻量子智能体设置内联系统提示词正文，这类子智能体无需独立定义工作目录。
         *
         * <p>该方法与 {@link #workspace(Path)} 互斥，不可同时使用。
         *
         * @param body Markdown格式系统提示词正文；允许为 {@code null} 或空文本
         */
        public Builder inlineAgentsBody(String body) {
            this.inlineAgentsBody = body;
            return this;
        }

        /**
         * Optional model override resolved via {@link io.agentscope.core.model.ModelRegistry}.
         * Falls back to the parent model when blank or unresolvable.
         */
        /**
         * 可选模型覆盖配置，通过 {@link io.agentscope.core.model.ModelRegistry} 解析。
         * 若为空或无法解析，则沿用父智能体使用的模型。
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Maximum reasoning iterations (default 10).
         *
         * @deprecated since Phase A — use {@link #steps(int)}. Equivalent in behaviour.
         */
        /**
         * 最大推理迭代次数（默认值：10）。
         *
         * @deprecated A阶段起废弃，请使用 {@link #steps(int)}。两者行为完全等价。
         */
        @Deprecated
        public Builder maxIters(int maxIters) {
            this.steps = maxIters;
            return this;
        }

        /** Maximum reasoning iterations (default 10). */
        /** 最大推理迭代次数（默认值：10）。 */
        public Builder steps(int steps) {
            this.steps = steps;
            return this;
        }

        /**
         * Optional sampling temperature override. {@code null} (default) means inherit the parent
         * agent's value. Typical range {@code 0.0 – 2.0}.
         */
        /**
         * 可选采样温度覆盖参数。默认值 {@code null}，代表继承父智能体配置。常规取值范围 {@code 0.0 – 2.0}。
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Optional nucleus-sampling (top-p) override. {@code null} (default) means inherit the
         * parent. Typical range {@code 0.0 – 1.0}.
         */
        /**
         * 可选核采样参数覆盖。默认值 {@code null}，代表继承父智能体配置。典型取值范围 {@code 0.0 – 1.0}。
         */
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Optional model-variant identifier (e.g. {@code "thinking"} for DashScope thinking-mode
         * variants). Blank / {@code null} means no variant transform; parent variant — if any —
         * is inherited via builder copy.
         */
        /**
         * 可选模型变体标识（例如 DashScope 思考模式变体可填写 {@code "thinking"}）。
         * 为空或 {@code null} 时不启用变体转换；构建器复制场景下会继承父智能体已配置的变体。
         */
        public Builder variant(String variant) {
            this.variant = variant;
            return this;
        }

        /**
         * Sets the {@link Mode}. {@code null} is treated as {@link Mode#ALL}.
         */
        /** 设置子智能体模式。默认值为 {@link Mode#ALL}。 */
        public Builder mode(Mode mode) {
            this.mode = mode != null ? mode : Mode.ALL;
            return this;
        }

        /**
         * Hide this declaration from the LLM's available-subagent list. Defaults to {@code false}.
         */
        /**
         * 将当前声明对子智能体可用列表隐藏，使大模型无法感知。默认值为 {@code false}。
         */
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        /**
         * When {@code true}, the subagent's spawn key is derived deterministically from
         * (parentSessionId, agentId, label), enabling state recovery across parent calls and
         * process restarts. Defaults to {@code false}.
         */
        /**
         * 设为 {@code true} 时，子智能体的生成密钥由 (parentSessionId, agentId, label) 确定性生成，
         * 支持在父会话多次调用、进程重启后恢复状态。默认值 {@code false}。
         */
        public Builder persistSession(boolean persistSession) {
            this.persistSession = persistSession;
            return this;
        }

        /**
         * When {@code true} (default), parent DENY permission rules are propagated to the child
         * at spawn time. Set to {@code false} only when the child requires permissions that the
         * parent explicitly denies (rare).
         */
        /**
         * 设为 {@code true}（默认）时，父智能体的拒绝权限规则会在创建子智能体时向下传递。
         * 仅当子智能体需要父智能体明确禁止的权限时才设置为 {@code false}（该场景较少见）。
         */
        public Builder inheritParentPermissions(boolean inheritParentPermissions) {
            this.inheritParentPermissions = inheritParentPermissions;
            return this;
        }

        /**
         * Per-type policy for exposing spawned instances as user-addressable threads.
         *
         * <p>{@code TRUE} forces exposure, {@code FALSE} forbids it (overriding an LLM request),
         * and {@code null} (default) defers to the {@code RuntimeContext} override and then the
         * LLM's {@code expose_to_user} argument.
         */
        /**
         * 针对创建实例是否对外暴露为用户可访问会话线程的按类型策略。
         *
         * <p>{@code TRUE} 强制对外暴露；{@code FALSE} 禁止暴露（优先级高于大模型请求指令）；
         * 默认值 {@code null}，先交由 {@code RuntimeContext} 配置接管，再遵从大模型传入的 {@code expose_to_user} 参数。
         */
        public Builder exposeToUser(Boolean exposeToUser) {
            this.exposeToUser = exposeToUser;
            return this;
        }

        /**
         * Tool allowlist: when non-empty, only inherited parent tools with listed names are kept.
         * Child-local tool registrations are unaffected.
         */
        /**
         * 工具白名单：非空时，仅保留父智能体中名称匹配清单的继承工具。
         * 子智能体本地注册的工具不受此规则影响。
         */
        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder skills(List<String> skills) {
            this.skills = skills;
            return this;
        }

        /**
         * Remote task server base URL. Mutually exclusive with {@link #workspace(Path)} and a
         * non-blank {@link #inlineAgentsBody(String)}.
         */
        /**
         * 远程任务服务基础地址。与 {@link #workspace(Path)}、非空的 {@link #inlineAgentsBody(String)} 互斥。
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Optional HTTP headers for the remote task server (e.g. {@code Authorization}). Only used
         * when {@link #url(String)} is set.
         */
        /**
         * 远程任务服务可选HTTP请求头（例如 {@code Authorization}）。
         * 仅当配置了 {@link #url(String)} 时生效。
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * Builds the {@link SubagentDeclaration}.
         *
         * @throws IllegalArgumentException if {@code name} or {@code description} is blank, or
         *     mutually exclusive fields are combined (workspace vs inline vs remote URL)
         */
        /**
         * 构建 {@link SubagentDeclaration} 对象。
         *
         * @throws IllegalArgumentException 当 {@code name} 或 {@code description} 为空，
         *     或是同时配置了互斥字段（工作空间 / 内联定义 / 远程地址）时抛出该异常
         */
        public SubagentDeclaration build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SubagentDeclaration requires a non-blank name");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                        "SubagentDeclaration requires a non-blank description");
            }
            boolean remote = url != null && !url.isBlank();
            if (remote) {
                if (workspacePath != null) {
                    throw new IllegalArgumentException(
                            "url() and workspace(Path) are mutually exclusive for subagent '"
                                    + name
                                    + "'");
                }
                if (inlineAgentsBody != null && !inlineAgentsBody.isBlank()) {
                    throw new IllegalArgumentException(
                            "url() and inlineAgentsBody() are mutually exclusive for subagent '"
                                    + name
                                    + "'");
                }
            } else if (workspacePath != null
                    && inlineAgentsBody != null
                    && !inlineAgentsBody.isBlank()) {
                throw new IllegalArgumentException(
                        "workspace(Path) and inlineAgentsBody() are mutually exclusive;"
                                + " set at most one for subagent '"
                                + name
                                + "'");
            }
            return new SubagentDeclaration(this);
        }
    }
}
