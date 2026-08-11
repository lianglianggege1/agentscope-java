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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private static helpers backing {@link HarnessAgent.Builder}'s orchestration path.
 *
 * <p>Each helper accepts a {@link HarnessAgent.Builder} so it can read the builder's harness
 * fields without going through public accessors.
 */
/**
 * 包私有静态工具类，为{@link HarnessAgent.Builder}的编排逻辑提供底层支撑。
 *
 * <p>每个工具方法均接收{@link HarnessAgent.Builder}入参，可直接读取构建器内部核心字段，无需调用公共访问方法。
 */
final class HarnessAgentBuilderSupport {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentBuilderSupport.class);

    private HarnessAgentBuilderSupport() {}

    // -----------------------------------------------------------------
    //  Subagent context section
    // -----------------------------------------------------------------
    // ----------------------------- 子智能体上下文区 -----------------------------

    /**
     * 注入到所有子智能体系统提示词中的子智能体上下文片段。
     * 用于定义叶子工作智能体的身份、运行规则、输出格式与禁止行为。
     * 任务本体将作为首条用户消息传入，不在此处重复携带。
     */
    /*static final String SUBAGENT_CONTEXT_SECTION =
            """
             # 子代理上下文

             你是主代理为特定任务而生成的一个**子代理**。

             ## 你的角色
             - 完成分配的任务。这是你的全部目的。
             - 你不是主要代理人。不要试图成为主要代理人。

             ## 规则
             1. **保持专注** — 专注于完成分配给你的任务，不要分心
             2. **完成任务** — 您的最终消息将自动报告给主代理
             3. **不要主动** — 不要主动示好，不要主动采取行动，不要主动做额外任务
             4. **短暂存在** — 任务完成后，你可能会被终止。这没关系。
             5. **从截断的工具输出中恢复** — 如果您看到“[截断：输出超出上下文限制]”，请使用较小的数据块（使用偏移量/限制读取，或使用有针对性的grep/head/tail）重新读取您需要的内容，而不是全部重新读取

             ## 输出格式
             完成后，你的最终回复应包括：
             - 你所取得的成就或发现
             - 主要代理人应了解的任何相关细节
             - 保持简洁但信息丰富

             ## 你不应该做的事
             - 无用户对话（这是主要代理人的职责）
             - 不再生成更多子代理 — 你是一名基层工作者
             - 不要假装自己是主要行动者
             - 返回纯文本结果；让主代理将它们传递给用户
            """;*/

    // @formatter:off
    /**
     * Subagent context section injected into every subagent's system prompt.
     * Establishes identity, rules, output format, and prohibited behaviours for a leaf worker.
     * The task itself is delivered as the first user message, not duplicated here.
     */
    static final String SUBAGENT_CONTEXT_SECTION =
            """
            # Subagent Context

            You are a **subagent** spawned by the main agent for a specific task.

            ## Your Role
            - Complete the assigned task. That's your entire purpose.
            - You are NOT the main agent. Don't try to be.

            ## Rules
            1. **Stay focused** — Do your assigned task, nothing else
            2. **Complete the task** — Your final message will be automatically reported to the main agent
            3. **Don't initiate** — No heartbeats, no proactive actions, no side quests
            4. **Be ephemeral** — You may be terminated after task completion. That's fine.
            5. **Recover from truncated tool output** — If you see `[truncated: output exceeded context limit]`, re-read only what you need using smaller chunks (read with offset/limit, or targeted grep/head/tail) instead of full re-reads

            ## Output Format
            When complete, your final response should include:
            - What you accomplished or found
            - Any relevant details the main agent should know
            - Keep it concise but informative

            ## What You DON'T Do
            - NO user conversations (that's the main agent's job)
            - NO spawning further subagents — you are a leaf worker
            - NO pretending to be the main agent
            - Return plain text results; let the main agent deliver them to the user
            """;

    // 你是能力完备的通用子智能体。
    // @formatter:on

    /** 通用型子智能体的基础提示词前缀，拼接在子智能体上下文片段之前。 */
    static final String GENERAL_PURPOSE_BASE_PROMPT =
            "You are a highly capable general-purpose subagent.";

    /**
     * Builds a system prompt for a subagent by appending {@link #SUBAGENT_CONTEXT_SECTION} to the
     * given base prompt. If the base is blank, only the context section is used.
     */
    /**
     * 为子智能体构建系统提示词：将 {@link #SUBAGENT_CONTEXT_SECTION} 追加至传入的基础提示词后。
     * 若基础提示词为空，则仅使用该上下文片段。
     */
    static String buildSubagentSysPrompt(String basePrompt) {
        String base =
                (basePrompt != null && !basePrompt.isBlank()) ? basePrompt.stripTrailing() : "";
        return base.isEmpty() ? SUBAGENT_CONTEXT_SECTION : base + "\n\n" + SUBAGENT_CONTEXT_SECTION;
    }

    /** Custom-supplied subagent factory entry: name + factory function from name to Agent. */
    /** 用户自定义子智能体工厂条目：包含名称以及根据名称生成智能体的工厂函数。 */
    record SubagentFactoryEntry(String name, Function<String, Agent> factory) {}

    // -----------------------------------------------------------------
    //  Filesystem
    // -----------------------------------------------------------------
    // ----------------------------- 文件系统解析区 -----------------------------

    /**
     * 文件系统解析的总入口，按优先级逐级选择：
     * 1) 逃生舱（用户直接注入的 {@link AbstractFilesystem}）；
     * 2) 远程/组合文件系统规格（模式1，附带工作空间索引注入）；
     * 3) 本地文件系统规格（模式3）；
     * 4) 全部未配置时，默认走本地叠加层（项目目录在下、智能体工作空间在上）。
     */
    static AbstractFilesystem resolveFilesystem(
            HarnessAgent.Builder b,
            Path workspace,
            String agentId,
            WorkspaceIndex workspaceIndex,
            NamespaceFactory nsFactory) {
        // 优先级 1：逃生舱直通
        if (b.abstractFilesystem != null) {
            return b.abstractFilesystem;
        }
        // 优先级 2：远程/组合文件系统，远程模式下把工作空间索引交给规格对象管理
        if (b.remoteFilesystemSpec != null) {
            if (workspaceIndex != null) {
                b.remoteFilesystemSpec.workspaceIndex(workspaceIndex);
            }
            return b.remoteFilesystemSpec.toFilesystem(workspace, agentId, nsFactory);
        }
        // 优先级 3：显式配置的本地文件系统
        if (b.localFilesystemSpec != null) {
            return b.localFilesystemSpec.toFilesystem(workspace, nsFactory);
        }
        // Default: route through LocalFilesystemSpec so the default project (= ${user.dir})
        // is overlaid below the agent workspace, matching the Claude-Code-style two-layer model.
        // 默认路径：走 LocalFilesystemSpec，把默认项目目录（即 ${user.dir}）
        // 叠加在智能体工作空间之下，形成类 Claude-Code 的双层文件系统模型。
        return new LocalFilesystemSpec().toFilesystem(workspace, nsFactory);
    }

    /**
     * Builds a {@link RuntimeContext} that bakes in the supplied {@code userId} and
     * {@code sessionId} for out-of-band IO performed via
     * {@link HarnessAgent#workspaceFor(String, String)}. Used together with
     * {@code BakedContextFilesystem} so the underlying namespace factories see this identity
     * regardless of what the caller passes downstream.
     */
    /**
     * 构建 {@link RuntimeContext}，内置传入的 {@code userId} 与 {@code sessionId}，
     * 供 {@link HarnessAgent#workspaceFor(String, String)} 执行带外IO操作。
     * 常与 {@code BakedContextFilesystem} 配合使用，确保底层命名空间工厂能够读取该身份信息，
     * 不受下游调用方传入参数影响。
     */
    static RuntimeContext buildBakedRuntimeContext(String userId, String sessionId) {
        if ((userId == null || userId.isBlank()) && (sessionId == null || sessionId.isBlank())) {
            return RuntimeContext.empty();
        }
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        return b.build();
    }

    // -----------------------------------------------------------------
    //  Subagents
    // -----------------------------------------------------------------

    /**
     * Builds the subagent entries from programmatic declarations,
     * {@code workspace/subagents/*.md}, and custom factories.
     */
    /**
     * 通过代码声明、{@code workspace/subagents/*.md} 文件以及自定义工厂构建子智能体条目。
     */
    static List<SubagentEntry> buildSubagentEntries(
            HarnessAgent.Builder b, Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
        // 声明来源一：编程式声明列表
        List<SubagentDeclaration> allDeclarations = new ArrayList<>(b.subagentDeclarations);

        // 声明来源二：workspace/subagents/ 目录下的声明文件（md 格式）
        Path subagentsDir = resolvedWorkspace.resolve("subagents");
        if (Files.isDirectory(subagentsDir)) {
            allDeclarations.addAll(
                    AgentSpecLoader.loadFromDirectory(subagentsDir, resolvedWorkspace));
        }

        List<SubagentEntry> entries = new ArrayList<>();

        // 内置的通用子智能体永远排第一：能力与主智能体一致，用于可完全委派的独立任务
        entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "General-purpose subagent with same capabilities as the main agent."
                                + " Use for any isolated task that can be fully delegated.",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));
        /*entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "通用子智能体，具备与主智能体相同的全部能力，可用于各类能够全权委派的独立任务。",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));*/

        // 编程式声明 + 目录声明统一物化为条目
        for (SubagentDeclaration decl : allDeclarations) {
            entries.add(
                    new SubagentEntry(
                            decl.getName(),
                            decl.getDescription(),
                            buildDeclaredFactory(b, decl, resolvedWorkspace, sandboxFs),
                            decl));
        }

        for (SubagentFactoryEntry custom : b.customSubagentFactories) {
            entries.add(
                    new SubagentEntry(
                            custom.name(),
                            custom.name(),
                            // custom factory uses Function<String, Agent> — pre-B-0 signature
                            // doesn't accept RuntimeContext. Bridge by ignoring rc here; users
                            // that need parent-aware isolation should register a programmatic
                            // SubagentEntry directly with a B-0 SubagentFactory lambda.
                            // 自定义工厂是 Function<String, Agent> 旧签名，不接收 RuntimeContext；
                            // 这里忽略 rc 做桥接。若需要感知父级身份的隔离能力，
                            // 应改用 SubagentFactory lambda 直接注册编程式条目。
                            (rc) -> custom.factory().apply(custom.name()),
                            null));
        }

        return entries;
    }

    /**
     * Like {@link #buildSubagentEntries(HarnessAgent.Builder, Path, SandboxBackedFilesystem)} but
     * omits the local-disk {@code subagents/} scan. The {@code DynamicSubagentsMiddleware} performs that
     * scan itself on every reasoning step (Layer 2), so feeding the same entries in here would
     * register them twice.
     */
    /**
     * 作用与 {@link #buildSubagentEntries(HarnessAgent.Builder, Path, SandboxBackedFilesystem)} 一致，
     * 但跳过本地磁盘 {@code subagents/} 目录扫描。
     * {@code DynamicSubagentsMiddleware} 会在每一轮推理步骤（第二层）自行执行目录扫描，
     * 若在此处重复加载会造成子智能体重复注册。
     */
    static List<SubagentEntry> buildStaticSubagentEntries(
            HarnessAgent.Builder b, Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> entries = new ArrayList<>();

        entries.add(
        new SubagentEntry(
                "general-purpose",
                "General-purpose subagent with same capabilities as the main agent."
                        + " Use for any isolated task that can be fully delegated.",
                buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                null));

        /*entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "通用子智能体，能力与主智能体完全一致，适用于各类可完整委派的独立任务。",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));*/

        for (SubagentDeclaration decl : b.subagentDeclarations) {
            entries.add(
                    new SubagentEntry(
                            decl.getName(),
                            decl.getDescription(),
                            buildDeclaredFactory(b, decl, resolvedWorkspace, sandboxFs),
                            decl));
        }

        for (SubagentFactoryEntry custom : b.customSubagentFactories) {
            entries.add(
                    new SubagentEntry(
                            custom.name(),
                            custom.name(),
                            // custom factory uses Function<String, Agent> — pre-B-0 signature
                            // doesn't accept RuntimeContext. Bridge by ignoring rc here; users
                            // that need parent-aware isolation should register a programmatic
                            // SubagentEntry directly with a B-0 SubagentFactory lambda.
                            // 与 buildSubagentEntries 相同的旧签名桥接说明。
                            (rc) -> custom.factory().apply(custom.name()),
                            null));
        }

        return entries;
    }

    /**
     * Builds a factory for the built-in general-purpose subagent.
     */
    /**
     * 构建内置通用子智能体对应的工厂实例。
     */
    static SubagentFactory buildGeneralPurposeFactory(
            HarnessAgent.Builder b, Path workspace, SandboxBackedFilesystem sandboxFs) {
        // 构建期快照：把父级 Builder 的配置冻结为 final 局部变量，
        // 供 spawn 时刻（lambda 执行时）使用，避免构建后父配置继续变化影响子智能体。
        final Model capturedModel = b.model;
        final Toolkit capturedParentToolkit = b.toolkit != null ? b.toolkit.copy() : new Toolkit();
        final AbstractFilesystem capturedBackend =
                sandboxFs != null ? sandboxFs : b.abstractFilesystem;
        final int capturedMaxIters = b.maxIters;
        final ExecutionConfig capturedModelExec = b.modelExecutionConfig;
        final ExecutionConfig capturedToolExec = b.toolExecutionConfig;
        final GenerateOptions capturedGenOpts = b.generateOptions;
        final String capturedEnvMemory = b.environmentMemory;
        final List<Hook> capturedHooks = List.copyOf(b.hooks);
        // L486: 构建时快照父 Builder 上的用户中间
        final List<MiddlewareBase> capturedMiddlewares = List.copyOf(b.middlewares);
        final List<AgentSkillRepository> capturedSkillRepos = List.copyOf(b.skillRepositories);
        final Path capturedProjectGlobalSkillsDir = b.projectGlobalSkillsDir;
        final boolean capturedUseLegacyXmlWorkspaceContext = b.useLegacyXmlWorkspaceContext;
        final boolean capturedDisableFilesystemTools = b.disableFilesystemTools;
        final boolean capturedDisableShellTool = b.disableShellTool;
        final boolean capturedDisableMemoryTools = b.disableMemoryTools;
        final boolean capturedDisableMemoryHooks = b.disableMemoryHooks;
        final boolean capturedDisableSessionPersistence = b.disableSessionPersistence;
        final boolean capturedDisableWorkspaceContext = b.disableWorkspaceContext;
        final CompactionConfig capturedCompactionConfig = b.compactionConfig;
        final boolean capturedDisableCompaction = b.disableCompaction;
        final ToolResultEvictionConfig capturedToolResultEvictionConfig =
                b.toolResultEvictionConfig;
        final boolean capturedDisableToolResultEviction = b.disableToolResultEviction;
        final boolean capturedAgentTracingLogEnabled = b.agentTracingLogEnabled;
        final List<String> capturedAdditionalContextFiles = List.copyOf(b.additionalContextFiles);
        final int capturedMaxContextTokens = b.maxContextTokens;
        // Propagate the parent's (distributed) state store so an exposed subagent can be
        // re-materialized on another node / after a restart and still load its conversation
        // history by sessionId. Null in purely local default deployments — children then keep
        // their own local store, preserving legacy behaviour.
        // 透传父级（分布式）状态存储：被对外暴露的子智能体可在其他节点/重启后
        // 重新物化，并按 sessionId 恢复会话历史。纯本地默认部署下为 null，
        // 此时子智能体沿用各自的本地存储，保持旧行为。
        final io.agentscope.core.state.AgentStateStore capturedStateStore = b.stateStoreOverride;

        return (RuntimeContext parentRc) -> {
            // general-purpose subagent shares the parent's workspace and is short-lived per spawn;
            // we don't need parent-aware bucketing here. parentRc is accepted for interface
            // compatibility and reserved for future use.
            // 通用子智能体与父级共享工作空间，且每次 spawn 即用即弃，
            // 无需按父级身份分桶；parentRc 仅为接口兼容而保留，暂不使用。
            HarnessAgent.Builder sub =
                    HarnessAgent.builder()
                            .name("general-purpose-subagent")
                            .description("General-purpose subagent for isolated task execution")
                            .sysPrompt(buildSubagentSysPrompt(null))
                            .model(capturedModel)
                            .toolkit(capturedParentToolkit.copy())
                            .workspace(workspace)
                            .asLeafSubagent()
                            .maxIters(capturedMaxIters)
                            .environmentMemory(capturedEnvMemory)
                            .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext)
                            .enableAgentTracingLog(capturedAgentTracingLogEnabled)
                            .maxContextTokens(capturedMaxContextTokens);

            capturedAdditionalContextFiles.forEach(sub::additionalContextFile);

            if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
            if (capturedDisableShellTool) sub.disableShellTool();
            if (capturedDisableMemoryTools) sub.disableMemoryTools();
            if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
            if (capturedDisableSessionPersistence) sub.disableSessionPersistence();
            if (capturedDisableWorkspaceContext) sub.disableWorkspaceContext();

            if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
            if (capturedProjectGlobalSkillsDir != null) {
                sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
            }
            if (capturedBackend != null) sub.abstractFilesystem(capturedBackend);
            if (capturedStateStore != null) sub.stateStore(capturedStateStore);
            if (capturedModelExec != null) sub.modelExecutionConfig(capturedModelExec);
            if (capturedToolExec != null) sub.toolExecutionConfig(capturedToolExec);
            if (capturedGenOpts != null) sub.generateOptions(capturedGenOpts);
            if (capturedDisableCompaction) {
                sub.disableCompaction();
            } else if (capturedCompactionConfig != null) {
                sub.compaction(capturedCompactionConfig);
            }
            if (capturedDisableToolResultEviction) {
                sub.disableToolResultEviction();
            } else if (capturedToolResultEvictionConfig != null) {
                sub.toolResultEviction(capturedToolResultEvictionConfig);
            }

            // L605: 物化子 agent 时注
            sub.middlewares(capturedMiddlewares);
            sub.hooks(capturedHooks);

            return sub.build();
        };
    }

    /**
     * Builds a factory for a user-declared subagent from a {@link SubagentDeclaration}.
     */
    /**
     * 根据 {@link SubagentDeclaration} 构建用户声明式子智能体的工厂实例。
     */
    static SubagentFactory buildDeclaredFactory(
            HarnessAgent.Builder b,
            SubagentDeclaration decl,
            Path mainWorkspace,
            SandboxBackedFilesystem sandboxFs) {
        final Model capturedModel = b.model;
        final Toolkit capturedParentToolkit = b.toolkit != null ? b.toolkit.copy() : new Toolkit();
        final Function<String, Model> capturedResolver = b.modelResolver;
        // L486: 构建时快照父 Builder 上的用户中间
        final List<MiddlewareBase> capturedMiddlewares = List.copyOf(b.middlewares);
        final AbstractFilesystem capturedSharedBackend =
                sandboxFs != null ? sandboxFs : b.abstractFilesystem;
        final boolean capturedUseLegacyXmlWorkspaceContext = b.useLegacyXmlWorkspaceContext;
        final boolean capturedDisableFilesystemTools = b.disableFilesystemTools;
        final boolean capturedDisableShellTool = b.disableShellTool;
        final boolean capturedDisableMemoryTools = b.disableMemoryTools;
        final boolean capturedDisableMemoryHooks = b.disableMemoryHooks;
        final boolean capturedDisableSessionPersistence = b.disableSessionPersistence;
        final GenerateOptions capturedGenOpts = b.generateOptions;
        // Snapshot of main agent's Local filesystem configuration. ISOLATED subagents get a
        // fresh spec carrying the same project / additionalRoots / mode so PathPolicy stays in
        // sync; without this, every isolated subagent would default to project=${user.dir} and
        // lose any --add-dir style allow-list configured at the main level.
        // 主智能体本地文件系统配置的快照：ISOLATED 子智能体会拿到携带相同
        // project / additionalRoots / mode 的新规格，保证路径策略与父级一致；
        // 否则隔离子智能体都会退化到 project=${user.dir}，丢失主级配置的目录白名单。
        final io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec
                capturedLocalFilesystemSpec = b.localFilesystemSpec;
        final List<AgentSkillRepository> capturedSkillRepos = List.copyOf(b.skillRepositories);
        final Path capturedProjectGlobalSkillsDir = b.projectGlobalSkillsDir;
        // See buildGeneralPurposeFactory: propagate the parent's (distributed) state store so the
        // subagent's conversation survives cross-node re-materialization. Null in local defaults.
        final io.agentscope.core.state.AgentStateStore capturedStateStore = b.stateStoreOverride;

        return (RuntimeContext parentRc) -> {
            // 远程声明不在本节点物化，返回一个轻量存根
            if (decl.isRemote()) {
                return new io.agentscope.harness.agent.subagent.RemoteSubagentStub(
                        decl.getName(), decl.getDescription());
            }
            // ---- Resolve workspace root ----
            // ---- 解析工作空间根目录（SHARED 复用主工作空间；ISOLATED 自动建目录）----
            Path runtimeWorkspace = resolveDeclaredWorkspace(decl, mainWorkspace);

            // ---- Resolve system prompt ----
            // ---- 解析系统提示词基础段（优先读声明目录的 AGENTS.md，否则用内联内容）----
            String sysPromptBase = resolveDeclaredSysPromptBase(decl);

            // ---- Resolve model ----
            // ---- 解析模型：声明可覆盖主模型，解析失败回退父模型 ----
            Model effectiveModel =
                    resolveModel(decl.getModel(), capturedModel, capturedResolver, decl.getName());

            // ---- Derive child session ID: bucket persisted AgentState by parent identity ----
            // ---- 派生子会话 ID：按父级身份分桶持久化子智能体的 AgentState ----
            // (Phase B-0) Without this every (user, parent-session) shares the same bucket and
            // can read each other's subagent conversations through AgentStateStore.get(...).
            // （B-0 阶段）缺少该分桶时，所有(用户, 父会话)共享同一个存储桶，
            // 彼此都能通过 AgentStateStore.get(...) 读到对方的子智能体会话。
            String childSessionId = deriveChildSessionId(decl, parentRc);

            // ---- Build child agent ----
            // ---- 组装子智能体：叶子标记 + 继承工具白名单 + 会话分桶 ----
            HarnessAgent.Builder sub =
                    HarnessAgent.builder()
                            .name(decl.getName())
                            .description(decl.getDescription())
                            .model(effectiveModel)
                            .toolkit(
                                    allowlistedInheritedToolkit(
                                            capturedParentToolkit, decl.getTools()))
                            .workspace(runtimeWorkspace)
                            .defaultSessionId(childSessionId)
                            .maxIters(decl.getSteps())
                            .asLeafSubagent()
                            .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext)
                            .sysPrompt(buildSubagentSysPrompt(sysPromptBase));

            // Overlay declaration-specified temperature/topP on top of the parent's
            // GenerateOptions.
            // Null fields in the overlay fall back to parent via GenerateOptions.mergeOptions.
            // Note: SubagentDeclaration.variant is parsed and retained on the declaration but is
            // NOT plumbed into GenerateOptions today because the core Model layer has no variant
            // concept; treat it as schema-forward storage until variant support lands.
            // 声明中指定的 temperature/topP 叠加在父级 GenerateOptions 之上，
            // 叠加层中为 null 的字段经 mergeOptions 回退到父级值。
            // 注意：声明中的 variant 字段已解析并保留，但核心 Model 层尚无 variant 概念，
            // 暂未传入 GenerateOptions——作为面向未来的 schema 预留，待支持后再接线。
            if (decl.getTemperature() != null || decl.getTopP() != null) {
                GenerateOptions overlay =
                        GenerateOptions.builder()
                                .temperature(decl.getTemperature())
                                .topP(decl.getTopP())
                                .build();
                sub.generateOptions(GenerateOptions.mergeOptions(overlay, capturedGenOpts));
            } else if (capturedGenOpts != null) {
                sub.generateOptions(capturedGenOpts);
            }

            // 文件系统选择：SHARED 模式直接复用父级共享后端；
            // 其他模式克隆一份本地规格（保留白名单但工作空间独立）。
            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED && capturedSharedBackend != null) {
                sub.abstractFilesystem(capturedSharedBackend);
            } else if (decl.getWorkspaceMode() != WorkspaceMode.SHARED
                    && capturedLocalFilesystemSpec != null) {
                sub.filesystem(cloneLocalSpecForSubagent(capturedLocalFilesystemSpec));
            }

            if (capturedStateStore != null) {
                sub.stateStore(capturedStateStore);
            }

            if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
            if (capturedDisableShellTool) sub.disableShellTool();
            if (capturedDisableMemoryTools) sub.disableMemoryTools();
            if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
            if (capturedDisableSessionPersistence) sub.disableSessionPersistence();

            if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
            if (capturedProjectGlobalSkillsDir != null) {
                sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
            }

            List<String> skillAllowlist = decl.getSkills();
            if (!skillAllowlist.isEmpty()) {
                sub.skillFilter(SkillFilter.only(skillAllowlist.toArray(new String[0])));
            }

            // L605: 物化子 agent 时注
            sub.middlewares(capturedMiddlewares);
            return sub.build();
        };
    }

    /**
     * Builds a fresh {@link io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec} for
     * an ISOLATED subagent, copying the main agent's project, mode, and additionalRoots so the
     * subagent's {@code PathPolicy} stays consistent with the parent. The subagent gets its own
     * workspace (passed separately via {@code sub.workspace(...)}) so its MEMORY/sessions stay
     * isolated; only the allow-list inputs are shared.
     */
    /**
     * 为 ISOLATED 子智能体创建全新的本地文件系统规格，拷贝主智能体的
     * project、mode 与 additionalRoots，使子智能体的路径策略与父级保持一致。
     * 子智能体的工作空间另行指定（经 {@code sub.workspace(...)}），
     * 从而隔离其记忆与会话数据——只共享目录白名单输入。
     */
    private static io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec
            cloneLocalSpecForSubagent(
                    io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec parent) {
        io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec spec =
                new io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec();
        if (parent.getProject() != null) {
            spec.project(parent.getProject());
        }
        if (parent.getMode() != null) {
            spec.mode(parent.getMode());
        }
        spec.additionalRoots(parent.getAdditionalRoots());
        return spec;
    }

    /** Returns a defensive copy of inherited parent tools filtered by the optional allowlist. */
    /** 返回父级工具集的保护性拷贝；若声明了工具白名单，则移除白名单之外的工具。 */
    static Toolkit allowlistedInheritedToolkit(Toolkit parentToolkit, List<String> allowlist) {
        Toolkit toolkit = parentToolkit != null ? parentToolkit.copy() : new Toolkit();
        if (allowlist == null || allowlist.isEmpty()) {
            return toolkit;
        }
        List<String> toRemove =
                toolkit.getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .filter(name -> !allowlist.contains(name))
                        .toList();
        toRemove.forEach(toolkit::removeTool);
        return toolkit;
    }

    /**
     * Composes the child agent's persisted session ID, bucketing by declaration name and the
     * spawn-time parent identity:
     *
     * <pre>
     * {declarationName}[@{parentSessionId}][#{userId}]
     * </pre>
     *
     * <p>Empty / blank uid or sid segments are dropped — keeps single-tenant demos working with
     * the legacy single-bucket form. {@link WorkspaceMode#SHARED} subagents intentionally fall
     * back to the legacy single-bucket form: they're sharing the parent's full state tree by
     * design.
     *
     * <p>This works uniformly across {@link io.agentscope.core.state.AgentStateStore} stores —
     * Workspace, Redis, InMemory, or custom — because all of them bucket {@code save}/{@code get}
     * by session ID. (Phase B-0)
     */
    /**
     * 组合子智能体的持久化会话 ID，按声明名与 spawn-time 的父级身份分桶：
     *
     * <pre>
     * {声明名}[@{父会话ID}][#{用户ID}]
     * </pre>
     *
     * <p>为空的用户/会话段会被丢弃，单租户演示场景保持旧版单桶形式。
     * SHARED 模式的子智能体有意回退到旧版单桶形式——它们本来就与父级共享完整状态树。
     *
     * <p>该方案对所有 AgentStateStore 实现一致生效（Workspace、Redis、内存或自定义），
     * 因为它们都按会话 ID 对 save/get 分桶。（B-0 阶段）
     */
    static String deriveChildSessionId(SubagentDeclaration decl, RuntimeContext parentRc) {
        String declName = decl.getName();
        if (decl.getWorkspaceMode() == WorkspaceMode.SHARED || parentRc == null) {
            return declName;
        }
        String sid = sanitizeIdentifier(parentRc.getSessionId());
        String uid = sanitizeIdentifier(parentRc.getUserId());
        if (sid == null && uid == null) {
            return declName;
        }
        StringBuilder sb = new StringBuilder(declName);
        if (sid != null) sb.append('@').append(sid);
        if (uid != null) sb.append('#').append(uid);
        return sb.toString();
    }

    /**
     * Returns {@code null} when the input is null or blank; otherwise replaces characters that
     * confuse path-based AgentStateStore stores (slashes, backslashes, whitespace, controls) with
     * underscores. Keeps Redis/InMemory/SQL keys unaffected since their stored form is opaque.
     */
    /**
     * 输入为 null 或空白时返回 null；否则把会干扰基于路径的 AgentStateStore 的字符
     *（斜杠、反斜杠、空白、控制符）替换为下划线。
     * Redis/内存/SQL 存储的键为不透明字符串，不受该替换影响。
     */
    static String sanitizeIdentifier(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.replaceAll("[/\\\\\\s\\p{Cntrl}]", "_");
    }

    /**
     * Resolves the runtime workspace root for a declared subagent. Creates the auto-generated
     * isolated directory when needed.
     */
    /**
     * 解析声明式子智能体的运行时工作空间根目录；必要时自动创建隔离目录。
     * 规则：SHARED 模式一律用主工作空间（即便声明了路径）；
     * ISOLATED 模式优先用声明路径，未声明时自动创建 agents/&lt;名称&gt;/workspace/。
     */
    static Path resolveDeclaredWorkspace(SubagentDeclaration decl, Path mainWorkspace) {
        if (decl.getWorkspacePath() != null) {
            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
                return mainWorkspace;
            }
            return decl.getWorkspacePath();
        }
        if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
            return mainWorkspace;
        }
        // ISOLATED + no path: auto-create agents/<name>/workspace/
        // ISOLATED 且未声明路径：自动创建 agents/<名称>/workspace/
        Path isolated =
                mainWorkspace.resolve("agents").resolve(decl.getName()).resolve("workspace");
        try {
            Files.createDirectories(isolated);
        } catch (Exception e) {
            log.warn(
                    "Failed to create isolated workspace for subagent '{}' at {}: {}",
                    decl.getName(),
                    isolated,
                    e.getMessage());
        }
        return isolated;
    }

    /** Resolves the system-prompt base for a declared subagent. */
    /**
     * 解析声明式子智能体的系统提示词基础段：声明指定了工作空间路径时读取其中的
     * AGENTS.md；否则使用声明的内联内容；两者皆无返回空串。
     */
    static String resolveDeclaredSysPromptBase(SubagentDeclaration decl) {
        if (decl.getWorkspacePath() != null) {
            Path agentsMd = decl.getWorkspacePath().resolve("AGENTS.md");
            if (Files.isRegularFile(agentsMd)) {
                try {
                    return Files.readString(agentsMd, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn(
                            "Failed to read AGENTS.md for subagent '{}' from {}: {}",
                            decl.getName(),
                            agentsMd,
                            e.getMessage());
                }
            }
            return "";
        }
        String inline = decl.getInlineAgentsBody();
        return (inline != null) ? inline : "";
    }

    /** Resolves the effective {@link Model} for a subagent, applying the optional override. */
    /**
     * 解析子智能体的生效模型：无覆盖声明时直接用父模型；
     * 有覆盖时经注册表或自定义解析器解析，失败则记录告警并回退父模型。
     */
    static Model resolveModel(
            String modelOverride,
            Model parentModel,
            Function<String, Model> resolver,
            String subagentName) {
        // ① 没有覆盖声明 → 直接用父模型
        if (modelOverride == null || modelOverride.isBlank()) {
            return parentModel;
        }
        // ② 选择解析器：自定义 > ModelRegistry
        Function<String, Model> effectiveResolver =
                resolver != null ? resolver : ModelRegistry::resolve;
        // ③  尝试解析
        if (ModelRegistry.canResolve(modelOverride) || resolver != null) {
            try {
                Model resolved = effectiveResolver.apply(modelOverride);
                if (resolved != null) {
                    log.debug(
                            "Subagent '{}' using overridden model: {}",
                            subagentName,
                            modelOverride);
                    return resolved;
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to resolve model '{}' for subagent '{}', falling back to parent"
                                + " model: {}",
                        modelOverride,
                        subagentName,
                        e.getMessage());
            }
        }
        return parentModel;
    }

    // -----------------------------------------------------------------
    //  Subagents middlewares
    // -----------------------------------------------------------------
    // ----------------------------- 子智能体中间件装配区 -----------------------------

    /**
     * 装配静态子智能体中间件（无文件系统或禁用动态加载时使用）。
     * 构建期一次性装载全部条目（通用 + 编程声明 + 目录声明 + 自定义工厂）；
     * 若注入了外部子智能体工具，则以它替代默认的 task 工具族。
     */
    static SubagentsMiddleware buildSubagentsMiddleware(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            Path workspace,
            SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> entries = buildSubagentEntries(b, workspace, sandboxFs);
        TaskRepository repo = resolveTaskRepository(b, wsManager);

        if (b.externalSubagentTool != null) {
            return new SubagentsMiddleware(entries, b.externalSubagentTool, repo);
        }

        // 动态声明工厂：会话模式按轮重载声明文件时，用它物化新出现的声明
        AbstractFilesystem fs = wsManager.getFilesystem();
        Function<SubagentDeclaration, SubagentFactory> factoryFn =
                decl -> buildDeclaredFactory(b, decl, workspace, sandboxFs);
        return new SubagentsMiddleware(entries, repo, wsManager, fs, workspace, factoryFn);
    }

    /**
     * 装配动态子智能体中间件（有文件系统且未禁用动态加载时使用）。
     * 注意这里只装载静态条目（跳过目录扫描）——目录声明由中间件每轮推理自行扫描，
     * 避免重复注册；AgentManager 持有静态条目用于网关物化。
     */
    static DynamicSubagentsMiddleware buildDynamicSubagentsMiddleware(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            Path workspace,
            SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> staticEntries = buildStaticSubagentEntries(b, workspace, sandboxFs);
        TaskRepository repo = resolveTaskRepository(b, wsManager);

        AbstractFilesystem fs = wsManager.getFilesystem();
        Function<SubagentDeclaration, SubagentFactory> factoryFn =
                decl -> buildDeclaredFactory(b, decl, workspace, sandboxFs);
        DefaultAgentManager manager = new DefaultAgentManager(staticEntries, wsManager);
        return new DynamicSubagentsMiddleware(
                staticEntries, fs, workspace, factoryFn, manager, b.externalSubagentTool, repo);
    }

    /**
     * 解析后台任务仓库：用户显式指定则直接使用；
     * 否则默认创建绑定工作空间的 WorkspaceTaskRepository
     *（任务记录持久化在工作空间内，按 agentId 分目录）。
     */
    private static TaskRepository resolveTaskRepository(
            HarnessAgent.Builder b, WorkspaceManager wsManager) {
        if (b.taskRepository != null) {
            return b.taskRepository;
        }
        Objects.requireNonNull(
                wsManager,
                "WorkspaceManager must be non-null when resolving the default TaskRepository;"
                        + " HarnessAgent.build() always constructs one. Pass an explicit"
                        + " .taskRepository(...) if you need a non-workspace-backed implementation.");

//        Objects.requireNonNull(
//                wsManager,
//                "解析默认任务仓库时，工作空间管理器不可为空；HarnessAgent.build()方法会自动创建该实例。"
//                        + "若需脱离工作空间的自定义实现，请手动调用.taskRepository(...)指定实例。");

        String taskAgentId =
                b.agentId != null && !b.agentId.isBlank()
                        ? b.agentId
                        : (b.name != null && !b.name.isBlank() ? b.name : "ReActAgent");
        return new WorkspaceTaskRepository(wsManager, taskAgentId);
    }

    // -----------------------------------------------------------------
    //  Skills
    // -----------------------------------------------------------------

    /**
     * Assembles the ordered list of skill repositories used by this build (low-to-high priority).
     */
    /**
     * 组装本次构建使用的技能仓库有序列表（低优先级 → 高优先级，后者覆盖前者同名技能）。
     */
    static List<AgentSkillRepository> composeSkillRepositories(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            AbstractFilesystem filesystem,
            Supplier<RuntimeContext> currentRcSupplier) {
        List<AgentSkillRepository> ordered = new ArrayList<>();

        // Layer 1 (lowest priority): project-global skills directory.
        // 第一层（优先级最低）：项目全局技能目录。
        if (b.projectGlobalSkillsDir != null && Files.isDirectory(b.projectGlobalSkillsDir)) {
            try {
                ordered.add(new FileSystemSkillRepository(b.projectGlobalSkillsDir));
            } catch (Exception e) {
                log.warn(
                        "Failed to register project-global skills dir {}: {}",
                        b.projectGlobalSkillsDir,
                        e.getMessage());
            }
        }

        // Layer 2: marketplace repositories (user-supplied).
        // 第二层：市场/外部技能仓库（用户提供）。
        ordered.addAll(b.skillRepositories);

        // Layer 3: workspace agent-shared directory.
        // 第三层：工作空间内智能体共享的技能目录。
        Path workspaceSkillsDir = wsManager.getSkillsDir();
        if (workspaceSkillsDir != null && Files.isDirectory(workspaceSkillsDir)) {
            try {
                ordered.add(new FileSystemSkillRepository(workspaceSkillsDir));
            } catch (Exception e) {
                log.warn(
                        "Failed to load workspace skills from {}: {}",
                        workspaceSkillsDir,
                        e.getMessage());
            }
        }

        // Layer 4 (highest priority): per-user namespaced filesystem view, via the new
        // WorkspaceSkillRepository (replaces legacy FilesystemBackedSkillRepository).
        // Skipped when the user opts out with disableDefaultWorkspaceSkills().
        // 第四层（优先级最高）：按用户命名空间隔离的文件系统技能视图，
        // 由新版 WorkspaceSkillRepository 承载（取代旧版 FilesystemBackedSkillRepository）。
        // 用户可通过 disableDefaultWorkspaceSkills() 退出该层。
        if (filesystem != null && !b.disableDefaultWorkspaceSkills) {
            ordered.add(
                    new io.agentscope.harness.agent.skill.WorkspaceSkillRepository(
                            filesystem,
                            "skills",
                            currentRcSupplier,
                            "workspace-namespaced",
                            false));
        }

        return ordered;
    }

    /**
     * Eagerly assembles a static {@link SkillBox} from {@code repos} (low-to-high priority) so
     * callers using {@code disableDynamicSkills()} keep the legacy {@code SkillHook} path while
     * still benefiting from the additive composition.
     */
    /**
     * 从仓库列表（低优先级 → 高优先级）预先装配静态技能箱：
     * 同名技能按顺序覆盖合并。供 {@code disableDynamicSkills()} 场景走旧版
     * SkillHook 路径时仍享受多层叠加的组合能力。
     */
    static SkillBox staticSkillBoxFromRepos(
            List<AgentSkillRepository> repos, Toolkit agentToolkit) {
        LinkedHashMap<String, AgentSkill> merged = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repos) {
            try {
                List<AgentSkill> skills = repo.getAllSkills();
                if (skills == null) {
                    continue;
                }
                for (AgentSkill skill : skills) {
                    if (skill != null && skill.getName() != null) {
                        merged.put(skill.getName(), skill);
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to load skills from {}: {}",
                        repo.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        SkillBox box = new SkillBox(agentToolkit);
        for (AgentSkill skill : merged.values()) {
            box.registerSkill(skill);
        }
        log.info("Loaded {} skills from {} repositories (static)", merged.size(), repos.size());
        return box;
    }
}
