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
import java.util.List;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Appends workspace context (session info, AGENTS.md, MEMORY.md, knowledge) to the
 * system prompt via {@link #onSystemPrompt(Agent, RuntimeContext, String)}.
 *
 * <p>Runs once per {@code call()} (just like the previous {@code WorkspaceContextHook}
 * fired on {@code PreCallEvent}).
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
            ## 智能体状态存储上下文
            本段为%s，用于配置本次对话上下文。
            今日日期：%s
            当前操作系统：%s
            工作目录路径：%s
            项目临时目录路径：%s
            %s
            """;

    //    private static final String SESSION_CONTEXT_SECTION_TEMPLATE =
    //            """
    //            ## AgentStateStore Context
    //            This is the %s. We are setting up the context for our chat.
    //            Today's date is %s.
    //            My operating system is: %s
    //            The workspace directory is: %s
    //            The project's temporary directory is: %s
    //            %s
    //            """;

    /** 行为指引模板：告知模型如何使用 knowledge/ 知识库、何时检索记忆、如何用 memory_save 持久化记忆。 */
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

    //    private static final String GUIDANCE_TEMPLATE =
    //            """
    //            ## Domain Knowledge
    //            The workspace `knowledge/` tree holds many detailed reference documents (not only
    // a single summary file). When the task needs specs, procedures, schemas, or domain facts,
    // treat that directory as the source of truth.
    //            Below, `<domain_knowledge_context>` already includes what you need to navigate it:
    // injected `knowledge/KNOWLEDGE.md` (if present) plus a **full list of knowledge file paths**
    // under `knowledge/` — use that as the catalog of what exists and where.
    //            For content not inlined here, open only the paths you need with read_file, grep,
    // or glob (prefer targeted reads over loading entire trees into the reply).
    //
    //            ## Memory Recall
    //            Before answering questions about prior work, decisions, dates, people, or
    // preferences: \
    //            run memory_search on MEMORY.md + memory/*.md, then memory_get for needed lines. \
    //            Include Source: <path#line> citations when helpful.
    //
    //            ## Memory Persistence
    //            You have a persistent MEMORY.md. Update it proactively when:
    //            - User shares preferences, project context, or decisions
    //            - Important outcomes or action items are established
    //            Use the **memory_save** tool to persist memories — it atomically updates \
    //            both MEMORY.md and the daily ledger. Do NOT use write_file or edit_file on \
    //            MEMORY.md or any path under memory/ — always use memory_save instead. \
    //            Memory is also automatically extracted at conversation end.
    //            """;

    /** 注入说明：向模型解释 &lt;loaded_context&gt; 中的内容来自工作区文件（记忆、偏好、规范等）。 */
    private static final String WORKSPACE_FILES_NOTICE =
            """
            ## 工作区文件（已载入）
            下方<loaded_context>内容均从工作区文件加载。
            AGENTS.md、MEMORY.md、knowledge/KNOWLEDGE.md等文件记录着历史记忆、客观事实、用户偏好、行为规范以及过往交互积累的个性化信息。
            """;

    //    private static final String WORKSPACE_FILES_NOTICE =
    //            """
    //            ## Workspace Files (Injected)
    //            The following <loaded_context> was loaded in from files in your workspace.
    //            These files (for example, `AGENTS.md`, `MEMORY.md`, and `knowledge/KNOWLEDGE.md`)
    // contain memory, facts, preferences, guidelines, and user-specific details learned from prior
    // interactions with user.
    //            """;

    /** 记忆内容超预算被截断时附加的提示，引导模型用 memory_search 查找更早的条目。 */
    private static final String TRUNCATION_NOTICE = "\n\n...（内容已截断，查阅更早记录请使用memory_search检索）...\n";

    //    private static final String TRUNCATION_NOTICE =
    //            "\n\n... (memory truncated — use memory_search for older entries) ...\n";

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

    /** 额外需要注入的上下文文件相对路径列表（通过 setter 配置），默认为空。 */
    private List<String> additionalContextFiles = List.of();

    /** 简化构造器：使用默认智能体名与默认 token 预算（{@value #DEFAULT_MAX_CONTEXT_TOKENS}）。 */
    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager) {
        this(workspaceManager, "HarnessAgent", null, DEFAULT_MAX_CONTEXT_TOKENS);
    }

    /** 可自定义 token 预算的构造器，其余使用默认值。 */
    public WorkspaceContextMiddleware(WorkspaceManager workspaceManager, int maxContextTokens) {
        this(workspaceManager, "HarnessAgent", null, maxContextTokens);
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
        this.workspaceManager = workspaceManager;
        this.agentName = agentName != null && !agentName.isBlank() ? agentName : "HarnessAgent";
        this.environmentMemory = environmentMemory;
        this.maxContextTokens = maxContextTokens;
    }

    /** 配置额外需要注入系统提示词的上下文文件（工作区相对路径），传 null 时清空。 */
    public void setAdditionalContextFiles(List<String> files) {
        this.additionalContextFiles = files != null ? files : List.of();
    }

    /**
     * 系统提示词钩子：在现有提示词末尾追加工作区上下文区块。
     *
     * <p>区块构建失败或为空时原样返回当前提示词；否则按换行规则拼接后返回。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        String section = buildWorkspaceSection(rc);
        if (section.isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        // 原提示词非空且未以换行结尾时补一个换行，避免与新区块粘连
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return Mono.just(base + separator + section);
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
        String memoryContent = workspaceManager.readMemoryMd(rc).strip();
        String knowledgeContent = workspaceManager.readKnowledgeMd(rc).strip();
        Path workspace = workspaceManager.getWorkspace();
        String sessionContext = buildSessionContextSection(workspace, rc);

        String knowledgeBlock = buildKnowledgeBlock(rc, knowledgeContent, workspace);
        String additionalBlock = buildAdditionalContextBlock(rc);

        // 固定部分占用的 token 数（会话上下文 + AGENTS.md + 知识库 + 附加文件）
        int fixedTokens =
                estimateTokens(sessionContext)
                        + estimateTokens(agentsContent)
                        + estimateTokens(knowledgeBlock)
                        + estimateTokens(additionalBlock);
        int memoryTokens = estimateTokens(memoryContent);
        int available = maxContextTokens - fixedTokens;
        // 记忆超出剩余预算时按预算截断
        if (available > 0 && memoryTokens > available) {
            memoryContent = truncateToTokenBudget(memoryContent, available);
        }

        String workspaceParagraph =
                buildWorkspaceParagraph(workspace, workspaceManager.getFilesystem());
        String loadedContext =
                buildLoadedContextSection(
                        agentsContent, memoryContent, knowledgeBlock, additionalBlock, rc);
        return assembleSection(
                sessionContext, GUIDANCE_TEMPLATE, workspaceParagraph, loadedContext);
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
        sb.append("\n").append(WORKSPACE_FILES_NOTICE).append("\n").append(loadedContextSection);
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
     *       描述隔离容器视图以及宿主文件如何传入。</li>
     *   <li><b>远程</b>（{@link CompositeFilesystem}）：描述基于分布式存储的工作区，
     *       并说明没有宿主文件系统可以回退。</li>
     *   <li><b>其他</b>：对普通
     *       {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem}
     *       或无法识别的类型，输出单行的旧式 "working directory is X" 格式。</li>
     * </ul>
     */
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

    /*private static String buildWorkspaceParagraph(Path workspace, AbstractFilesystem fs) {
        StringBuilder sb = new StringBuilder("## Workspace\n");
        LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
        Path project = localUpper != null ? localUpper.getShellCwd() : null;
        if (project != null) {
            sb.append("Project (the user's source tree you're assisting with): ")
                    .append(project.toAbsolutePath())
                    .append("\n");
            sb.append("Workspace (your home base — memory, sessions, skills, runtime data): ")
                    .append(workspace.toAbsolutePath())
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
            sb.append(
                    "Files are isolated inside this container. The host filesystem is not"
                            + " directly accessible — use upload/download tools when you need to"
                            + " move bytes across the boundary.\n");
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
        return sb.toString();
    }*/

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
            String additionalBlock,
            RuntimeContext rc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<loaded_context>\n");
        sb.append(buildXmlContext("agents_context", agentsContent));
        sb.append(buildXmlContext("memory_context", memoryContent));
        sb.append(buildXmlContext("domain_knowledge_context", knowledgeBlock));
        if (!additionalBlock.isBlank()) {
            sb.append(additionalBlock);
        }
        sb.append("</loaded_context>\n");
        return sb.toString();
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

    /** 按 token 预算截断文本（换算为字符数），超出部分截掉并附加截断提示。 */
    private static String truncateToTokenBudget(String text, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + TRUNCATION_NOTICE;
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
