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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Dynamic counterpart to {@link SubagentsMiddleware} that re-resolves the registered subagent
 * set on every reasoning step, supporting per-user isolation through the workspace
 * {@link AbstractFilesystem} (e.g. {@code CompositeFilesystem} routing user-scoped writes into
 * a remote Store).
 *
 * <p><strong>Two-layer load</strong> (mirrors the previous {@code DynamicSubagentsHook}
 * override semantics):
 *
 * <ol>
 *   <li><em>Layer 1 (override)</em> — {@code filesystem.glob("*.md", "subagents")} + per-file
 *       {@code filesystem.read}. The backend's {@code NamespaceFactory} is applied transparently
 *       so each user sees their own slice of the store.
 *   <li><em>Layer 2 (base)</em> — {@code AgentSpecLoader.loadFromDirectory} reads the local
 *       workspace {@code subagents/} directory directly.
 *   <li><em>Merge</em> — same-name entries from Layer 1 override Layer 2; programmatic
 *       (builder-registered) entries are preserved as the static prefix and same-name dynamic
 *       declarations override them too.
 * </ol>
 */
/**
 * {@link SubagentsMiddleware} 的动态实现版本，每次推理步骤都会重新解析已注册子智能体集合，
 * 依托工作空间 {@link AbstractFilesystem} 实现按用户隔离（例如 {@code CompositeFilesystem}
 * 将用户域写入路由至远端存储）。
 *
 * <p><strong>双层加载机制</strong>（沿用旧 {@code DynamicSubagentsHook} 的覆盖语义）：
 *
 * <ol>
 *   <li><em>第一层（覆盖层）</em> — 通过 {@code filesystem.glob("*.md", "subagents")} 遍历文件并逐个调用
 *       {@code filesystem.read}。底层自动应用 {@code NamespaceFactory}，各用户仅能访问自身对应的存储分片。
 *   <li><em>第二层（基础层）</em> — {@code AgentSpecLoader.loadFromDirectory} 直接读取本地工作空间
 *       {@code subagents/} 目录。
 *   <li><em>合并规则</em> — 第一层中同名条目覆盖第二层；代码构造器注册的编程式条目作为静态基准保留，
 *       同名动态声明同样会覆盖这类静态条目。
 * </ol>
 */
public class DynamicSubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(DynamicSubagentsMiddleware.class);

    /** 存放子智能体声明文件的目录名（工作区相对路径）。 */
    private static final String SUBAGENTS_DIR = "subagents";

    /** 扫描声明文件时使用的通配符：仅识别 Markdown 声明文件。 */
    private static final String SUBAGENT_GLOB = "*.md";

    /** 通过代码构造器注册的静态子智能体条目（合并时作为基准，同名动态条目会覆盖它们）。 */
    private final List<SubagentEntry> staticEntries;

    /** 工作区文件系统（可能带用户命名空间隔离），用于第一层覆盖加载，可为 null。 */
    private final AbstractFilesystem filesystem;

    /** 主工作区根目录，第二层基础加载从该目录下的 subagents/ 读取，可为 null。 */
    private final Path mainWorkspace;

    /** 声明 → 工厂的构建函数，把加载到的声明实例化为可创建子智能体的工厂，可为 null。 */
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;

    /** 智能体管理器，负责按最新条目集合物化/替换子智能体，可为 null。 */
    private final DefaultAgentManager agentManager;

    /** 子智能体创建工具（默认 AgentSpawnTool），volatile 允许运行期通过网关桥重建。 */
    private volatile Object subagentTool;

    /** 任务查询工具，与任务仓库配套注入工具集。 */
    private final TaskTool taskTool;

    /** 子智能体任务仓库，记录派生任务的执行状态。 */
    private final TaskRepository taskRepository;

    /**
     * 完整构造器。
     *
     * @param staticEntries 编程式注册的静态条目，null 视为空列表（构造时做不可变拷贝）
     * @param filesystem 带命名空间隔离的文件系统（第一层加载），可为 null
     * @param mainWorkspace 主工作区目录（第二层加载），可为 null
     * @param factoryBuilder 声明转工厂的构建函数，可为 null（为 null 时不物化任何动态条目）
     * @param agentManager 智能体管理器，可为 null
     * @param subagentTool 自定义的子智能体创建工具，null 时默认构建 AgentSpawnTool
     * @param taskRepository 任务仓库，不允许为 null
     */
    public DynamicSubagentsMiddleware(
            List<SubagentEntry> staticEntries,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder,
            DefaultAgentManager agentManager,
            Object subagentTool,
            TaskRepository taskRepository) {
        this.staticEntries = List.copyOf(staticEntries != null ? staticEntries : List.of());
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
        this.agentManager = agentManager;
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.subagentTool =
                subagentTool != null
                        ? subagentTool
                        : new AgentSpawnTool(agentManager, taskRepository, 0);
        this.taskTool = new TaskTool(taskRepository);
    }

    /**
     * Wires a gateway bridge into the internal {@link AgentSpawnTool}, enabling spawned subagents
     * to be exposed as user-addressable threads. Only effective when the middleware owns a
     * {@link DefaultAgentManager}.
     */
    /**
     * 将网关桥装配至内置 {@link AgentSpawnTool}，使创建出的子智能体对外表现为可由用户寻址的会话线程。
     * 仅当中间件持有 {@link DefaultAgentManager} 实例时生效。
     */
    public DynamicSubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (agentManager == null) {
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
     * {@code null} when none is owned. Used to wire a gateway materializer for cross-node
     * exposed-subagent recovery.
     */
    /**
     * 返回可重新实例化子智能体的内部 {@link DefaultAgentManager}，当无持有实例时返回 {@code null}。用于装配网关实例化器，实现跨节点暴露的子智能体恢复。
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * Returns the tool instances this middleware contributes to the agent toolkit. The caller
     * is responsible for registering them on the toolkit at orchestration time.
     */
    /**
     * 返回当前中间件向智能体工具集提供的工具实例。调用方负责在编排阶段将这些工具注册至工具集。
     */
    public List<Object> getTools() {
        return List.of(subagentTool, taskTool);
    }

    /** 返回子智能体任务仓库，供外部查询或装配任务相关工具。 */
    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    /**
     * 推理钩子：每个推理步骤执行前重新解析子智能体集合（与静态版
     * {@link SubagentsMiddleware} 的核心差异），并同步到智能体管理器。
     *
     * <p>随后把"子智能体说明区块 + 任务摘要"前置追加到系统消息中，
     * 让模型在每一步都看到最新可用的子智能体与任务状态；
     * 两者均为空时原样透传输入。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        // 重新加载并合并静态/动态条目
        List<SubagentEntry> merged = reloadEntries(rc);
        if (agentManager != null) {
            // 用最新条目集合替换管理器中的子智能体
            agentManager.replaceAgents(merged);
        }
        StringBuilder addition = new StringBuilder();
        if (!merged.isEmpty()) {
            addition.append(SubagentsMiddleware.renderSubagentSection(merged, false));
        }
        String taskSummary = SubagentsMiddleware.buildTaskSummary(taskRepository, rc);
        if (taskSummary != null) {
            addition.append(taskSummary);
        }
        if (addition.length() == 0) {
            return next.apply(input);
        }
        // 把追加内容前置到系统消息，其余消息与工具配置保持不变
        List<Msg> rebuilt =
                SubagentsMiddleware.prependToSystemMessage(input.messages(), addition.toString());
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    /**
     * 重新加载并合并子智能体条目，共四步：
     *
     * <ol>
     *   <li>第二层基础加载：扫描本地工作区 subagents/ 目录；</li>
     *   <li>第一层覆盖加载：经文件系统（带用户命名空间）读取，同名条目覆盖基础层；</li>
     *   <li>物化工厂：把合并后的声明逐个构建为 {@link SubagentEntry}；</li>
     *   <li>与静态条目合并：动态条目同名优先。</li>
     * </ol>
     */
    private List<SubagentEntry> reloadEntries(RuntimeContext rc) {
        // ---- Layer 2 (base): local workspace scan ----
        // ---- 第二层（基础层）：扫描本地工作区目录 ----
        Map<String, SubagentDeclaration> declsByName = new LinkedHashMap<>();
        Path subagentsDir = mainWorkspace != null ? mainWorkspace.resolve(SUBAGENTS_DIR) : null;
        if (subagentsDir != null && Files.isDirectory(subagentsDir)) {
            for (SubagentDeclaration d :
                    AgentSpecLoader.loadFromDirectory(subagentsDir, mainWorkspace)) {
                declsByName.put(d.getName(), d);
            }
        }

        // ---- Layer 1 (override): filesystem with namespace ----
        // ---- 第一层（覆盖层）：经带命名空间的文件系统读取，同名覆盖基础层 ----
        if (filesystem != null) {
            for (SubagentDeclaration d : loadDeclarationsViaFilesystem(rc)) {
                declsByName.put(d.getName(), d);
            }
        }

        // ---- Materialise factories ----
        // ---- 物化工厂：声明 → 工厂 → 条目 ----
        List<SubagentEntry> dynamicEntries = new ArrayList<>(declsByName.size());
        for (SubagentDeclaration decl : declsByName.values()) {
            if (factoryBuilder == null) {
                continue;
            }
            try {
                SubagentFactory factory = factoryBuilder.apply(decl);
                if (factory == null) {
                    continue;
                }
                dynamicEntries.add(
                        new SubagentEntry(decl.getName(), decl.getDescription(), factory, decl));
            } catch (Exception e) {
                // 单个声明构建失败不影响其他子智能体，仅记录警告
                log.warn(
                        "Failed to build factory for declared subagent '{}': {}",
                        decl.getName(),
                        e.getMessage());
            }
        }

        // ---- Combine: static + dynamic, dynamic wins on name conflict ----
        // ---- 合并：静态 + 动态，同名冲突时动态条目胜出 ----
        Map<String, SubagentEntry> combined = new LinkedHashMap<>();
        for (SubagentEntry e : staticEntries) {
            combined.put(e.name(), e);
        }
        for (SubagentEntry e : dynamicEntries) {
            combined.put(e.name(), e);
        }
        return List.copyOf(combined.values());
    }

    /**
     * 第一层加载：经文件系统枚举 subagents/ 下的 *.md 声明文件并逐个解析。
     *
     * <p>文件系统后端会透明应用命名空间隔离，因此不同用户读到的是各自的存储分片。
     * 文件名（去掉 .md 后缀）作为子智能体名称；glob 或单文件读取失败时跳过该项，
     * 不影响其余声明的加载。
     */
    private List<SubagentDeclaration> loadDeclarationsViaFilesystem(RuntimeContext rc) {
        GlobResult glob;
        try {
            glob = filesystem.glob(rc, SUBAGENT_GLOB, SUBAGENTS_DIR);
        } catch (Exception e) {
            log.debug("Filesystem glob for subagents failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<SubagentDeclaration> decls = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            String fileName = extractFileName(path);
            if (!fileName.endsWith(".md")) {
                continue;
            }
            // 去掉 .md 后缀得到子智能体名称
            String name = fileName.substring(0, fileName.length() - 3);
            if (name.isEmpty()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(rc, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                SubagentDeclaration decl =
                        AgentSpecLoader.parse(rr.fileData().content(), name, mainWorkspace);
                if (decl != null) {
                    decls.add(decl);
                }
            } catch (Exception e) {
                // 单个文件解析失败只记警告，继续处理其余声明
                log.warn("Failed to load subagent declaration from '{}': {}", path, e.getMessage());
            }
        }
        return decls;
    }

    /** 从完整路径中提取文件名，兼容 Unix（/）与 Windows（\）两种分隔符。 */
    private static String extractFileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
