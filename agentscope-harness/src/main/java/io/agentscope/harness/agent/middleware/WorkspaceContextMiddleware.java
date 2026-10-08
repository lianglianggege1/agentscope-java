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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Appends workspace context (session info, AGENTS.md, MEMORY.md, knowledge) to the
 * system prompt via {@link #onSystemPrompt(Agent, RuntimeContext, String)}.
 *
 * <p>Runs once per {@code call()} (just like the previous {@code WorkspaceContextHook}
 * fired on {@code PreCallEvent}).
 *
 * <p>Memory-related guidance and {@code <memory_context>} injection are gated by the same
 * builder flags as Harness memory tools/hooks ({@code disableMemoryTools} /
 * {@code disableMemoryHooks}) so the model is not instructed to use capabilities that are
 * turned off.
 */
/**
 * 通过 {@link #onSystemPrompt(Agent, RuntimeContext, String)} 向系统提示词追加工作区上下文
 * （会话信息、AGENTS.md、MEMORY.md、知识库内容等）。
 *
 * <p>每次 {@code call()} 只执行一次（与旧版 {@code WorkspaceContextHook} 在
 * {@code PreCallEvent} 上触发的行为一致）。
 */
public class WorkspaceContextMiddleware implements HarnessRuntimeMiddleware {

    /** 会话上下文区块模板：注入智能体名称、今日日期、操作系统、工作区目录、临时目录等基础环境信息。 */
    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
            """
            ## AgentStateStore Context
            This is the %s. We are setting up the context for our chat.
            Today's date is %s.
            My operating system is: %s
            The workspace directory is: %s
            The project's temporary directory is: %s
            %s
            """;

    /* 中文译文（当前未启用，保留备查；测试断言依赖英文段落标题）
    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
            """
            ## 智能体状态存储上下文
            本段为%s，用于配置本次对话上下文。
            今日日期：%s
            当前操作系统：%s
            工作目录路径：%s
            项目临时目录路径：%s
            %s
            """;
    */

    /** 领域知识库指引：告知模型 knowledge/ 目录是权威信息来源及查阅方式。 */
    private static final String DOMAIN_KNOWLEDGE_GUIDANCE =
            """
            ## Domain Knowledge
            The workspace `knowledge/` tree holds many detailed reference documents (not only a single summary file). When the task needs specs, procedures, schemas, or domain facts, treat that directory as the source of truth.
            Below, `<domain_knowledge_context>` already includes what you need to navigate it: injected `knowledge/KNOWLEDGE.md` (if present) plus a **full list of knowledge file paths** under `knowledge/` — use that as the catalog of what exists and where.
            For content not inlined here, open only the paths you need with read_file, grep, or glob (prefer targeted reads over loading entire trees into the reply).
            """;

    /** 记忆调取指引（记忆工具启用时）：回答过往工作、决策、日期、人物、偏好相关问题前先检索记忆文件。 */
    private static final String MEMORY_RECALL_GUIDANCE =
            """
            ## Memory Recall
            Before answering questions about prior work, decisions, dates, people, or preferences: \
            run memory_search on MEMORY.md + memory/*.md, then memory_get for needed lines. \
            Include Source: <path#line> citations when helpful.
            """;

    /** 记忆持久化指引（记忆工具启用时）：列出需要主动更新 MEMORY.md 的场景。 */
    private static final String MEMORY_PERSISTENCE_HEADER =
            """
            ## Memory Persistence
            You have a persistent MEMORY.md. Update it proactively when:
            - User shares preferences, project context, or decisions
            - Important outcomes or action items are established
            """;

    /** 记忆持久化指引（仅记忆钩子启用时）：说明 MEMORY.md 由框架自动维护。 */
    private static final String MEMORY_PERSISTENCE_HEADER_HOOKS_ONLY =
            """
            ## Memory Persistence
            You have a persistent MEMORY.md that the harness maintains automatically.
            """;

    /** 记忆写入指引（记忆工具启用时）：持久化统一走 memory_save 工具。 */
    private static final String MEMORY_SAVE_TOOL_GUIDANCE =
            """
            Use the **memory_save** tool to persist memories — it atomically updates \
            both MEMORY.md and the daily ledger. Do NOT use write_file or edit_file on \
            MEMORY.md or any path under memory/ — always use memory_save instead.
            """;

    /** 记忆文件保护提示（仅钩子启用时）：记忆文件由框架托管，禁止直接写。 */
    private static final String MEMORY_WRITE_FILE_GUARD =
            """
            Do NOT use write_file or edit_file on MEMORY.md or any path under memory/ — \
            the harness owns those files.
            """;

    /** 记忆自动提取提示：会话结束时系统会自动提取记忆。 */
    private static final String MEMORY_AUTO_EXTRACT_GUIDANCE =
            "Memory is also automatically extracted at conversation end.\n";

    /** 工作区文件注入说明（含 MEMORY.md）：向模型解释 &lt;loaded_context&gt; 中的内容均来自工作区文件。 */
    private static final String WORKSPACE_FILES_NOTICE_WITH_MEMORY =
            """
            ## Workspace Files (Injected)
            The following <loaded_context> was loaded in from files in your workspace.
            These files (for example, `AGENTS.md`, `MEMORY.md`, and `knowledge/KNOWLEDGE.md`) contain memory, facts, preferences, guidelines, and user-specific details learned from prior interactions with user.
            """;

    /** 工作区文件注入说明（无 MEMORY.md）：向模型解释 &lt;loaded_context&gt; 中的内容均来自工作区文件。 */
    private static final String WORKSPACE_FILES_NOTICE_WITHOUT_MEMORY =
            """
            ## Workspace Files (Injected)
            The following <loaded_context> was loaded in from files in your workspace.
            These files (for example, `AGENTS.md` and `knowledge/KNOWLEDGE.md`) contain guidelines and domain context for this agent.
            """;

    /** 记忆截断提示（记忆工具启用时）：引导模型用 memory_search 查找更早的条目。 */
    private static final String TRUNCATION_NOTICE_WITH_SEARCH =
            "\n\n... (memory truncated — use memory_search for older entries) ...\n";

    /** 记忆截断提示（记忆工具关闭时）：仅提示内容已截断。 */
    private static final String TRUNCATION_NOTICE_PLAIN = "\n\n... (memory truncated) ...\n";

    /* ===== 中文译文参考（当前未启用，保留备查）=====
       相关测试断言依赖英文段落标题（如 "## Domain Knowledge"），故中文版仅作译文留存。
    private static final String GUIDANCE_TEMPLATE =
            """
            ## 领域知识库
            工作区knowledge/目录内存放大量详细参考文档，并非仅有一份汇总文件。任务需要规范、流程、结构定义、领域事实时，以此目录作为权威信息来源。
            下方<domain_knowledge_context>已提供查阅指引：注入knowledge/KNOWLEDGE.md文件（若存在），附带knowledge/下**全部文件路径清单**，可将该清单当作文件目录查阅资料。
            未内嵌展示的内容，仅按需通过read_file、grep、glob读取对应文件，优先精准读取，切勿一次性加载整个目录内容。

            ## 记忆调取规则
            回答过往工作、决策、日期、人物、偏好相关问题前：
            对MEMORY.md与memory目录下所有md文件执行memory_search检索，再通过memory_get读取所需文本片段。
            必要时标注引用来源：文件路径#行号。

            ## 记忆持久化规则
            MEMORY.md为持久化记忆文件，出现以下场景需主动更新：
            - 用户告知个人偏好、项目背景、已定决策
            - 确定重要结果与待执行事项
            必须使用memory_save工具持久化记忆，该工具可原子更新MEMORY.md与日常记忆台账。
            禁止使用write_file、edit_file修改MEMORY.md以及memory目录下任意文件，统一使用memory_save。
            对话结束时系统也会自动提取留存记忆。
            """;

    private static final String WORKSPACE_FILES_NOTICE =
            """
            ## 工作区文件（已载入）
            下方<loaded_context>内容均从工作区文件加载。
            AGENTS.md、MEMORY.md、knowledge/KNOWLEDGE.md等文件记录着历史记忆、客观事实、用户偏好、行为规范以及过往交互积累的个性化信息。
            """;

    private static final String TRUNCATION_NOTICE = "\n\n...（内容已截断，查阅更早记录请使用memory_search检索）...\n";
    */
    /** 注入上下文的默认 token 预算上限，超出部分（优先裁剪记忆）会被截断。 */
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    /** 工作区管理器，负责读取 AGENTS.md、MEMORY.md、知识库等工作区文件。 */
    private final WorkspaceManager workspaceManager;

    /** 智能体名称，用于会话上下文区块中的自我介绍，默认 "HarnessAgent"。 */
    private final String agentName;

    /** 额外的环境记忆文本，注入到会话上下文的动态部分，可为 null。 */
    private final String environmentMemory;

    /** 注入上下文的 token 预算上限，记忆内容在预算不足时会被截断。 */
    private final int maxContextTokens;

    /** 是否禁用记忆工具（memory_search/memory_get/memory_save），影响提示词中的记忆指引。 */
    private final boolean disableMemoryTools;

    /** 是否禁用记忆钩子（自动记忆提取等），影响提示词中的记忆指引。 */
    private final boolean disableMemoryHooks;

    /** 额外需要注入的上下文文件相对路径列表（通过 setter 配置），默认为空。 */
    private List<String> additionalContextFiles = List.of();

    private boolean artifactDeliveryEnabled = false;

    /** 简化构造器：使用默认智能体名与默认 token 预算（{@value #DEFAULT_MAX_CONTEXT_TOKENS}）。 */
    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager) {
        this(workspaceManager, "HarnessAgent", null, DEFAULT_MAX_CONTEXT_TOKENS, false, false);
    }

    /** 可自定义 token 预算的构造器，其余使用默认值。 */
    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager, int maxContextTokens) {
        this(workspaceManager, "HarnessAgent", null, maxContextTokens, false, false);
    }

    /**
     * 完整构造器。
     *
     * @param agentName 智能体名称，为 null 或空白时使用 "HarnessAgent"
     * @param environmentMemory 额外环境记忆文本，可为 null
     * @param maxContextTokens 注入上下文的 token 预算上限
     */
    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens) {
        this(workspaceManager, agentName, environmentMemory, maxContextTokens, false, false);
    }

    public WorkspaceContextMiddleware(
            WorkspaceManager workspaceManager,
            String agentName,
            String environmentMemory,
            int maxContextTokens,
            boolean disableMemoryTools,
            boolean disableMemoryHooks) {
        this.workspaceManager = workspaceManager;
        this.agentName = agentName != null && !agentName.isBlank() ? agentName : "HarnessAgent";
        this.environmentMemory = environmentMemory;
        this.maxContextTokens = maxContextTokens;
        this.disableMemoryTools = disableMemoryTools;
        this.disableMemoryHooks = disableMemoryHooks;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    /** 配置额外需要注入系统提示词的上下文文件（工作区相对路径），传 null 时清空。 */
    public void setAdditionalContextFiles(List<String> files) {
        this.additionalContextFiles = files != null ? files : List.of();
    }

    /** 返回本中间件是否禁用了记忆工具（影响提示词中的记忆使用指引）。 */
    public boolean isDisableMemoryTools() {
        return disableMemoryTools;
    }

    /** 返回本中间件是否禁用了记忆钩子（影响提示词中的记忆使用指引）。 */
    public boolean isDisableMemoryHooks() {
        return disableMemoryHooks;
    }

    /**
     * 配置是否已注册 {@link io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget}
     * 并暴露 {@code deliver_artifact} 工具：为 {@code true} 时工作区描述段落的沙箱分支
     * 会指示模型改用该工具交付文件；为 {@code false} 时提示文件无法离开沙箱。
     */
    public void setArtifactDeliveryEnabled(boolean artifactDeliveryEnabled) {
        this.artifactDeliveryEnabled = artifactDeliveryEnabled;
    }

    /**
     * 系统提示词钩子：在现有提示词末尾追加工作区上下文区块。
     *
     * <p>区块构建（含工作区文件读取）在 {@code boundedElastic} 调度器上执行，避免阻塞
     * 提示词组装线程；原提示词非空且未以换行结尾时补一个换行，避免与新区块粘连。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
                            String base = currentPrompt != null ? currentPrompt : "";
                            String section = buildWorkspaceSection(rc);
                            String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
                            return base + separator + section;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建完整的工作区上下文区块。
     *
     * <p>组装顺序：会话上下文 → 行为指引 → 工作区描述段落 → 已加载文件内容。
     * token 预算控制策略：会话上下文、AGENTS.md、知识库、附加文件视为固定开销，
     * 剩余预算不足时仅截断 MEMORY.md 记忆内容。
     */
    private String buildWorkspaceSection(RuntimeContext rc) {
        String agentsContent = workspaceManager.readAgentsMd(rc).strip();
        boolean includeMemoryContext = includeMemoryContext();
        String memoryContent =
                includeMemoryContext ? workspaceManager.readMemoryMd(rc).strip() : "";
        String knowledgeContent = workspaceManager.readKnowledgeMd(rc).strip();
        Path workspace = workspaceManager.getWorkspace();
        AbstractFilesystem filesystem = workspaceManager.getFilesystem();
        Path effectiveWorkspace =
                detectLocalUpper(filesystem) != null
                        ? workspaceManager.resolveRuntimeDataPath(rc, "")
                        : workspace;
        String sessionContext = buildSessionContextSection(effectiveWorkspace, rc);

        String knowledgeBlock = buildKnowledgeBlock(rc, knowledgeContent, workspace);
        String additionalBlock = buildAdditionalContextBlock(rc);

        // 固定部分占用的 token 数（会话上下文 + AGENTS.md + 知识库 + 附加文件）
        int fixedTokens =
                estimateTokens(sessionContext)
                        + estimateTokens(agentsContent)
                        + estimateTokens(knowledgeBlock)
                        + estimateTokens(additionalBlock);
        if (includeMemoryContext) {
            int memoryTokens = estimateTokens(memoryContent);
            int available = maxContextTokens - fixedTokens;
            // 记忆超出剩余预算时按预算截断
            if (available > 0 && memoryTokens > available) {
                memoryContent = truncateToTokenBudget(memoryContent, available);
            }
        }

        String workspaceParagraph =
                buildWorkspaceParagraph(
                        workspace, effectiveWorkspace, filesystem, artifactDeliveryEnabled);
        String loadedContext =
                buildLoadedContextSection(
                        agentsContent, memoryContent, knowledgeBlock, additionalBlock);
        return assembleSection(sessionContext, buildGuidance(), workspaceParagraph, loadedContext);
    }

    /**
     * Inject {@code MEMORY.md} unless both memory tools and hooks are disabled — at that point
     * the harness memory surface is fully off and the file should not appear as model context.
     */
    private boolean includeMemoryContext() {
        return !(disableMemoryTools && disableMemoryHooks);
    }

    private String buildGuidance() {
        StringBuilder sb = new StringBuilder();
        sb.append(DOMAIN_KNOWLEDGE_GUIDANCE.strip()).append("\n\n");
        if (!disableMemoryTools) {
            sb.append(MEMORY_RECALL_GUIDANCE.strip()).append("\n\n");
        }
        String persistence = buildMemoryPersistenceGuidance();
        if (!persistence.isBlank()) {
            sb.append(persistence.strip()).append("\n\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private String buildMemoryPersistenceGuidance() {
        if (disableMemoryTools && disableMemoryHooks) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!disableMemoryTools) {
            sb.append(MEMORY_PERSISTENCE_HEADER.strip()).append("\n");
            sb.append(MEMORY_SAVE_TOOL_GUIDANCE.strip()).append("\n");
        } else {
            sb.append(MEMORY_PERSISTENCE_HEADER_HOOKS_ONLY.strip()).append("\n");
            sb.append(MEMORY_WRITE_FILE_GUARD.strip()).append("\n");
        }
        if (!disableMemoryHooks) {
            sb.append(MEMORY_AUTO_EXTRACT_GUIDANCE.strip()).append("\n");
        }
        return sb.toString();
    }

    /** 按固定顺序拼接四个部分：会话上下文、行为指引、工作区描述、已加载文件内容。 */
    private static String assembleSection(
            String sessionContext,
            String guidance,
            String workspaceParagraph,
            String loadedContextSection) {
        StringBuilder sb = new StringBuilder();
        if (!sessionContext.isBlank()) {
            sb.append(sessionContext).append("\n\n");
        }
        sb.append(guidance);
        if (!workspaceParagraph.isEmpty()) {
            sb.append("\n").append(workspaceParagraph);
        }
        sb.append("\n").append(loadedContextSection);
        return sb.toString();
    }

    /**
     * Builds the {@code ## Workspace} paragraph, branching by the active filesystem type so the
     * LLM sees a description that matches its real deployment surface.
     *
     * <ul>
     *   <li><b>Local overlay</b> ({@link OverlayFilesystem} wrapping
     *       {@link LocalFilesystemWithShell}) — renders Project + Workspace as two lines plus
     *       overlay/shell semantics.
     *   <li><b>Sandbox</b> ({@link AbstractSandboxFilesystem} not wrapped in an overlay) —
     *       describes the isolated container view and how host files reach it.
     *   <li><b>Remote</b> ({@link CompositeFilesystem}) — describes the distributed store-backed
     *       workspace and the fact that there is no host filesystem to fall back to.
     *   <li><b>Other</b> — single-line legacy "working directory is X" form for plain
     *       {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} or anything we
     *       don't recognize.
     * </ul>
     */
    /**
     * 构建 {@code ## Workspace} 段落，按当前生效的文件系统类型分支生成描述，
     * 让模型看到的说明与其真实部署环境一致。
     *
     * <ul>
     *   <li><b>本地叠加层</b>（包装 {@link LocalFilesystemWithShell} 的 {@link OverlayFilesystem}）：
     *       以两行分别展示项目目录与工作区目录，并说明叠加层/Shell 语义。</li>
     *   <li><b>沙箱</b>（未被叠加层包装的 {@link AbstractSandboxFilesystem}）：
     *       描述隔离容器的文件视图；{@code artifactDeliveryEnabled} 为 true 时追加
     *       File Isolation Notice，指示模型任务完成后调用 {@code deliver_artifact} 交付成品，
     *       否则说明文件无法离开沙箱。</li>
     *   <li><b>远程</b>（{@link CompositeFilesystem}）：描述基于分布式存储的工作区，
     *       并说明没有宿主文件系统可以回退。</li>
     *   <li><b>其他</b>：对普通
     *       {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem}
     *       或无法识别的类型，输出单行的旧式 "working directory is X" 格式。</li>
     * </ul>
     *
     * <p>{@code effectiveWorkspace} 为实际运行数据目录（本地叠加场景下可能与
     * {@code workspace} 模板根目录不同）。
     */
    private static String buildWorkspaceParagraph(
            Path workspace,
            Path effectiveWorkspace,
            AbstractFilesystem fs,
            boolean artifactDeliveryEnabled) {
        StringBuilder sb = new StringBuilder("## Workspace\n");
        LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
        Path project = localUpper != null ? localUpper.getShellCwd() : null;
        if (project != null) {
            sb.append("Project (the user's source tree you're assisting with): ")
                    .append(project.toAbsolutePath())
                    .append("\n");
            sb.append("Workspace (your home base — memory, sessions, skills, runtime data): ")
                    .append(effectiveWorkspace.toAbsolutePath())
                    .append("\n");
            List<Path> extraRoots = extraRootsOf(localUpper, project, workspace);
            if (!extraRoots.isEmpty()) {
                sb.append("Additional roots: ");
                for (int i = 0; i < extraRoots.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(extraRoots.get(i).toAbsolutePath());
                }
                sb.append("\n");
            }
            LocalFsMode mode = localUpper.getMode();
            sb.append("Path access policy: ")
                    .append(describeMode(mode))
                    .append(". File tools reject absolute paths outside the roots above")
                    .append(mode == LocalFsMode.ROOTED ? " with a security error" : "")
                    .append(".\n");
            if (fs instanceof ProjectAwareOverlay) {
                sb.append(
                        "File tools write project files (code, configs, etc.) to the project"
                                + " directory. Workspace metadata paths (memory, sessions, skills,"
                                + " agents, knowledge) are written to the workspace.\n");
            } else {
                sb.append(
                        "Relative paths resolve under the workspace and read-fall-back to"
                                + " the project (overlay copy-on-write).\n");
            }
            sb.append("Shell commands run with `pwd` set to the project directory.\n");
        } else if (fs instanceof AbstractSandboxFilesystem sandbox
                && !(fs instanceof OverlayFilesystem)) {
            sb.append("Sandbox root: /workspace (container id: ")
                    .append(sandbox.id())
                    .append(")\n");
            if (artifactDeliveryEnabled) {
                sb.append(
                        "Files are isolated inside this container. The host filesystem is not"
                                + " directly accessible — see the File Isolation Notice below for"
                                + " how to deliver files out of the sandbox.\n");
            } else {
                sb.append(
                        "Files are isolated inside this container. The host filesystem is not"
                                + " accessible and there is no mechanism for moving files across"
                                + " the boundary.\n");
            }
        } else if (fs instanceof CompositeFilesystem) {
            sb.append("Distributed workspace template root: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append(
                    "Runtime data (MEMORY.md, sessions, tasks, skills) lives in a shared remote"
                            + " store, not on the local host. Reads of project-authored template"
                            + " files fall back to the workspace template root above.\n");
        } else {
            sb.append("Your working directory is: ")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            sb.append(
                    "Treat this directory as the single global workspace for file operations"
                            + " unless explicitly instructed otherwise.\n");
        }
        sb.append(
                "AGENTS.md defines persona and local conventions — honor them when consistent"
                        + " with safety and policy.\n");
        if (artifactDeliveryEnabled
                && fs instanceof AbstractSandboxFilesystem
                && !(fs instanceof OverlayFilesystem)) {
            sb.append(
                    "**File Isolation Notice**\n"
                            + "Files inside this container are isolated from the host filesystem"
                            + " and are not directly accessible from outside. If your work"
                            + " produces any final deliverables—such as documents, reports,"
                            + " images, spreadsheets, archives, audio/video files, code"
                            + " artifacts, or similar—you **must** call deliver_artifact"
                            + " automatically when you finish the task to export them to their"
                            + " configured external destination. Deliver it silently: do not ask"
                            + " the user whether they want it delivered — the tool call itself lets"
                            + " the user see and retrieve the artifact directly, so do not mention"
                            + " the delivery or the deliver_artifact tool in your reply.\n"
                            + "\n"
                            + "**Important Notes**:\n"
                            + "- Only deliver the final output of your task. **Do not** deliver"
                            + " temporary files, working copies, internal intermediate files, or"
                            + " any sensitive information (e.g., credentials, keys, personal"
                            + " data).\n"
                            + "- Do not simply print the file path as a reference; the user"
                            + " cannot access your container's filesystem directly.\n");
        }
        return sb.toString();
    }

    /* 中文译文（当前未启用，保留备查；测试断言依赖英文段落文本，如 "Project (the user's source tree"、"Sandbox root: /workspace"）
    private static String buildWorkspaceParagraph(Path workspace, AbstractFilesystem fs) {
        StringBuilder sb = new StringBuilder("## 工作空间\n");
        LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
        Path project = localUpper != null ? localUpper.getShellCwd() : null;
        if (project != null) {
            sb.append("项目目录（你协助处理的用户源码根目录）：").append(project.toAbsolutePath()).append("\n");
            sb.append("工作空间目录（存放记忆、会话、工具、运行数据的根目录）：")
                    .append(workspace.toAbsolutePath())
                    .append("\n");
            List<Path> extraRoots = extraRootsOf(localUpper, project, workspace);
            if (!extraRoots.isEmpty()) {
                sb.append("额外挂载根目录：");
                for (int i = 0; i < extraRoots.size(); i++) {
                    if (i > 0) {
                        sb.append("，");
                    }
                    sb.append(extraRoots.get(i).toAbsolutePath());
                }
                sb.append("\n");
            }
            LocalFsMode mode = localUpper.getMode();
            sb.append("路径访问权限策略：").append(describeMode(mode)).append("。文件操作工具会拒绝访问上述根目录之外的绝对路径");
            if (mode == LocalFsMode.ROOTED) {
                sb.append("，并抛出安全异常");
            }
            sb.append("。\n");
            if (fs instanceof ProjectAwareOverlay) {
                sb.append("文件写入规则：代码、配置等项目文件写入项目目录；记忆、会话、工具、智能体、知识库等元数据写入工作空间目录。\n");
            } else {
                sb.append("相对路径默认解析至工作空间，读取缺失文件时回退至项目目录，采用覆盖写时复制机制。\n");
            }
            sb.append("Shell命令执行时，当前工作目录默认锁定为项目目录。\n");
        } else if (fs instanceof AbstractSandboxFilesystem sandbox
                && !(fs instanceof OverlayFilesystem)) {
            sb.append("沙箱根目录：/workspace（容器ID：").append(sandbox.id()).append("）\n");
            sb.append("所有文件隔离在容器内部，无法直接访问宿主机文件；跨容器传输文件必须使用上传、下载工具。\n");
        } else if (fs instanceof CompositeFilesystem) {
            sb.append("分布式工作空间模板根目录：").append(workspace.toAbsolutePath()).append("\n");
            sb.append("MEMORY.md、会话、任务、工具等运行数据存储在远程共享存储，不在本地主机；读取项目模板文件时会回退至上方模板根目录。\n");
        } else {
            sb.append("当前工作目录：").append(workspace.toAbsolutePath()).append("\n");
            sb.append("若无特殊指令，所有文件操作均以此目录作为全局唯一工作空间。\n");
        }
        sb.append("AGENTS.md 规定智能体人设与本地使用规范，在合规安全的前提下需要严格遵守该文件要求。\n");
        return sb.toString();
    }
    */

    /**
     * Best-effort: returns the upper {@link LocalFilesystemWithShell} when {@code fs} is an
     * overlay constructed by {@code LocalFilesystemSpec}, otherwise {@code null}. Used to pull
     * project / mode / policy metadata for the prompt without leaking those into other
     * filesystem types.
     */
    /**
     * 尽力探测：若 {@code fs} 是由 {@code LocalFilesystemSpec} 构建的叠加层，
     * 返回其上层 {@link LocalFilesystemWithShell}，否则返回 {@code null}。
     * 用于在不影响其他文件系统类型的前提下，从提示词中获取项目目录/模式/路径策略元数据。
     */
    private static LocalFilesystemWithShell detectLocalUpper(AbstractFilesystem fs) {
        if (fs instanceof OverlayFilesystem ov
                && ov.getUpper() instanceof LocalFilesystemWithShell lfs) {
            return lfs;
        }
        return null;
    }

    /**
     * Extra allow-list roots beyond the project and workspace (which the LLM already sees).
     * Filters out exact matches and ancestors to keep the prompt focused on truly additional
     * locations.
     */
    /**
     * 列出项目目录与工作区之外的额外白名单根目录（前两者模型已经可见）。
     * 过滤掉与项目/工作区完全相同的条目，保持提示词只聚焦真正额外的位置。
     */
    private static List<Path> extraRootsOf(
            LocalFilesystemWithShell upper, Path project, Path workspace) {
        PathPolicy policy = upper.getPathPolicy();
        if (policy == null || policy.isEmpty()) {
            return List.of();
        }
        Path projectAbs = project.toAbsolutePath().normalize();
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        List<Path> extras = new java.util.ArrayList<>();
        for (Path root : policy.roots()) {
            if (root.equals(projectAbs) || root.equals(workspaceAbs)) {
                continue;
            }
            extras.add(root);
        }
        return extras;
    }

    /** 将本地文件系统模式翻译为面向模型的策略描述文本，null 视为默认 ROOTED。 */
    private static String describeMode(LocalFsMode mode) {
        if (mode == null) {
            return "ROOTED (default)";
        }
        return switch (mode) {
            case SANDBOXED -> "SANDBOXED (all paths anchored to the workspace; `..` blocked)";
            case ROOTED -> "ROOTED (absolute paths accepted only inside the roots above)";
            case UNRESTRICTED -> "UNRESTRICTED (absolute paths pass through unchanged)";
        };
    }

    /** 构建会话上下文区块：填入今日日期、操作系统、工作区目录、临时目录及动态部分。 */
    private String buildSessionContextSection(Path workspace, RuntimeContext rc) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE MMM d, yyyy"));
        String platform = System.getProperty("os.name") + " " + System.getProperty("os.version");
        String tempDir = System.getProperty("java.io.tmpdir");
        String dynamicPart = buildSessionDynamicPart(rc);

        return String.format(
                        SESSION_CONTEXT_SECTION_TEMPLATE,
                        agentName,
                        today,
                        platform,
                        workspace.toAbsolutePath(),
                        tempDir,
                        dynamicPart)
                .strip();
    }

    /** 会话上下文的动态部分：会话 ID（如有）与环境记忆文本（如有），多行拼接。 */
    private String buildSessionDynamicPart(RuntimeContext rc) {
        List<String> parts = new ArrayList<>();
        if (rc != null && rc.getSessionId() != null) {
            parts.add("AgentStateStore ID: " + rc.getSessionId());
        }
        if (environmentMemory != null && !environmentMemory.isBlank()) {
            parts.add(environmentMemory);
        }
        return parts.isEmpty() ? "" : String.join("\n", parts);
    }

    /**
     * 构建 &lt;loaded_context&gt; 区块：把 AGENTS.md、MEMORY.md、知识库、附加文件
     * 分别包进各自的 XML 标签，便于模型区分内容来源。
     */
    private String buildLoadedContextSection(
            String agentsContent,
            String memoryContent,
            String knowledgeBlock,
            String additionalBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append(workspaceFilesNotice());
        sb.append("\n");
        sb.append("<loaded_context>\n");
        sb.append(buildXmlContext("agents_context", agentsContent));
        if (includeMemoryContext()) {
            sb.append(buildXmlContext("memory_context", memoryContent));
        }
        sb.append(buildXmlContext("domain_knowledge_context", knowledgeBlock));
        if (!additionalBlock.isBlank()) {
            sb.append(additionalBlock);
        }
        sb.append("</loaded_context>\n");
        return sb.toString();
    }

    /** 按是否注入记忆上下文选择工作区文件说明文案（含记忆版 / 不含记忆版）。 */
    private String workspaceFilesNotice() {
        return includeMemoryContext()
                ? WORKSPACE_FILES_NOTICE_WITH_MEMORY
                : WORKSPACE_FILES_NOTICE_WITHOUT_MEMORY;
    }

    /** 将内容包进指定 XML 标签；内容为空时输出自闭合式的空标签。 */
    private static String buildXmlContext(String tagName, String content) {
        if (content == null || content.isBlank()) {
            return "  <" + tagName + "></" + tagName + ">\n";
        }
        return "  <" + tagName + ">\n" + indentByTwo(content.strip()) + "\n  </" + tagName + ">\n";
    }

    /** 每行增加两个空格缩进，用于 XML 标签内的内容排版。 */
    private static String indentByTwo(String text) {
        return text.lines().map(line -> "  " + line).collect(Collectors.joining("\n"));
    }

    /**
     * 读取 {@link #additionalContextFiles} 配置的额外文件并包成 XML 标签。
     * 标签名由文件相对路径转换而来（路径分隔符与点替换为下划线并转小写），
     * 读取失败或内容为空的文件直接跳过。
     */
    private String buildAdditionalContextBlock(RuntimeContext rc) {
        if (additionalContextFiles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String relPath : additionalContextFiles) {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relPath);
            if (content != null && !content.isBlank()) {
                String tag = relPath.replace("/", "_").replace(".", "_").toLowerCase();
                sb.append("  <").append(tag).append(">\n");
                sb.append(indentByTwo(content.strip())).append("\n");
                sb.append("  </").append(tag).append(">\n");
            }
        }
        return sb.toString();
    }

    /** 粗略估算 token 数：按 4 个字符约等于 1 个 token 的经验比例换算。 */
    private static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : text.length() / 4;
    }

    /**
     * 按 token 预算截断文本（换算为字符数），超出部分截掉并附加截断提示。
     * 提示文案取决于记忆工具是否禁用：可用时引导使用 memory_search 检索更早记录。
     */
    private String truncateToTokenBudget(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        String notice =
                disableMemoryTools ? TRUNCATION_NOTICE_PLAIN : TRUNCATION_NOTICE_WITH_SEARCH;
        return text.substring(0, maxChars) + notice;
    }

    /**
     * 构建知识库区块：先放 knowledge/KNOWLEDGE.md 的内容（如有），
     * 再列出 knowledge/ 下全部文件的相对路径清单，作为模型按需检索的目录索引。
     */
    private String buildKnowledgeBlock(RuntimeContext rc, String knowledgeContent, Path workspace) {
        List<Path> knowledgeFiles = workspaceManager.listKnowledgeFiles(rc);
        StringBuilder sb = new StringBuilder();

        if (!knowledgeContent.isBlank()) {
            sb.append(knowledgeContent.strip()).append("\n");
        }

        if (!knowledgeFiles.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("Knowledge files:\n");
            sb.append(
                    knowledgeFiles.stream()
                            .map(f -> "- " + workspace.relativize(f))
                            .collect(Collectors.joining("\n")));
            sb.append("\n");
        }

        return sb.toString();
    }
}
