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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specification for the non-sandbox "composite" filesystem mode.
 *
 * <p>This spec produces a {@link CompositeFilesystem} that blends:
 *
 * <ul>
 *   <li>a plain {@link LocalFilesystem} (no shell) for workspace-local, unmanaged files;
 *   <li>per-route {@link RemoteFilesystem} instances for cross-node paths (memory, skills,
 *       subagents, knowledge, sessions, tasks). Each route gets its own store namespace
 *       segment to prevent key collisions across routes.
 * </ul>
 *
 * <p>Because the default backend is {@link LocalFilesystem} (not {@link LocalFilesystemWithShell}),
 * shell execution is intentionally not available in this mode — use a sandbox filesystem spec or
 * {@link LocalFilesystemWithShell} if shell is required.
 *
 * <p>Default shared routes (each gets an isolated store namespace segment):
 *
 * <ul>
 *   <li>{@code AGENTS.md}, {@code MEMORY.md} → segment {@code root}
 *   <li>{@code memory/} → segment {@code memory}
 *   <li>{@code skills/} → segment {@code skills}
 *   <li>{@code subagents/} → segment {@code subagents}
 *   <li>{@code knowledge/} → segment {@code knowledge}
 *   <li>{@code plans/} → segment {@code plans}
 *   <li>{@code agents/<agentId>/sessions/} → segment {@code sessions}
 *   <li>{@code agents/<agentId>/tasks/} → segment {@code tasks}
 * </ul>
 *
 * <p>The store namespace for shared files is controlled by {@link #isolationScope(IsolationScope)},
 * which mirrors the sandbox isolation semantics:
 *
 * <ul>
 *   <li>{@link IsolationScope#SESSION} — namespace per session</li>
 *   <li>{@link IsolationScope#USER} (default) — namespace per user, shared across sessions</li>
 *   <li>{@link IsolationScope#AGENT} — namespace per agent, shared across all users</li>
 *   <li>{@link IsolationScope#GLOBAL} — single global namespace</li>
 * </ul>
 */
/**
 * 无沙箱复合文件系统模式配置类。
 *
 * <p>该配置将生成 {@link CompositeFilesystem}，融合两类文件系统：
 *
 * <ul>
 *   <li>原生 {@link LocalFilesystem}（不支持Shell），用于存放工作区本地非托管文件；
 *   <li>多路由独立 {@link RemoteFilesystem} 实例，用于跨节点路径（持久记忆、技能、子智能体、知识库、会话、任务）。
 *       每条路由分配专属存储命名空间分片，避免不同路由间键名冲突。
 * </ul>
 *
 * <p>由于底层默认使用 {@link LocalFilesystem}（而非 {@link LocalFilesystemWithShell}），该模式默认不提供Shell执行能力；
 * 若需要执行Shell，请选用沙箱文件系统配置或 {@link LocalFilesystemWithShell}。
 *
 * <p>默认共享路由（每条路由拥有独立隔离的存储命名空间分片）：
 *
 * <ul>
 *   <li>{@code AGENTS.md}、{@code MEMORY.md} → 分片标识 {@code root}
 *   <li>{@code memory/} → 分片标识 {@code memory}
 *   <li>{@code skills/} → 分片标识 {@code skills}
 *   <li>{@code subagents/} → 分片标识 {@code subagents}
 *   <li>{@code knowledge/} → 分片标识 {@code knowledge}
 *   <li>{@code plans/} → 分片标识 {@code plans}
 *   <li>{@code agents/<agentId>/sessions/} → 分片标识 {@code sessions}
 *   <li>{@code agents/<agentId>/tasks/} → 分片标识 {@code tasks}
 * </ul>
 *
 * <p>共享文件的存储命名空间由 {@link #isolationScope(IsolationScope)} 控制，隔离逻辑与沙箱体系保持一致：
 *
 * <ul>
 *   <li>{@link IsolationScope#SESSION} — 按会话隔离命名空间</li>
 *   <li>{@link IsolationScope#USER}（默认）— 按用户隔离命名空间，同一用户多会话共享数据</li>
 *   <li>{@link IsolationScope#AGENT} — 按智能体隔离命名空间，所有用户共用</li>
 *   <li>{@link IsolationScope#GLOBAL} — 全局唯一命名空间</li>
 * </ul>
 */
public class RemoteFilesystemSpec {

    private BaseStore store;
    private final Set<String> extraSharedPrefixes = new LinkedHashSet<>();
    private String anonymousUserId = "_default";
    private IsolationScope isolationScope = IsolationScope.USER;
    private WorkspaceIndex workspaceIndex = null;
    private boolean sharedLocalWorkspace = false;

    /**
     * Creates a remote filesystem spec that defers store resolution to
     * {@link io.agentscope.harness.agent.DistributedStore#baseStore()}.
     *
     * <p>When used with {@code HarnessAgent.builder().distributedStore(store)},
     * the store is injected automatically during build. Without a distributed store,
     * an {@link IllegalStateException} is thrown at build time.
     */
    /**
     * 创建远程文件系统描述符，将存储实例解析委托至
     * {@link io.agentscope.harness.agent.DistributedStore#baseStore()}。
     *
     * <p>配合 {@code HarnessAgent.builder().distributedStore(store)} 使用时，
     * 构建阶段会自动注入存储实例。若未提供分布式存储，构建时将抛出 {@link IllegalStateException}。
     */
    public RemoteFilesystemSpec() {
        this.store = null;
    }

    public RemoteFilesystemSpec(BaseStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    /**
     * Returns whether the store has been set (either via constructor or injection).
     */
    /** 返回存储实例是否已完成设置（通过构造器传入或依赖注入）。 */
    public boolean hasStore() {
        return store != null;
    }

    /**
     * Returns the configured {@link BaseStore}, or {@code null} if not yet set.
     *
     * <p>Used by coordination wiring (e.g. {@code PeriodicGate}) when a remote filesystem is
     * present without a full {@code DistributedStore}.
     */
    public BaseStore store() {
        return store;
    }

    /**
     * Injects the store if not already set. Called by the builder during auto-wiring.
     */
    /** 若存储实例尚未设置，则执行注入。由构建器在自动装配阶段调用。 */
    public void injectStoreIfAbsent(BaseStore store) {
        if (this.store == null) {
            this.store = store;
        }
    }

    /**
     * Adds an extra workspace-relative prefix routed to the shared store.
     *
     * <p>Examples: {@code knowledge/}, {@code prompts/}.
     */
    /** 新增一条工作空间相对路径前缀，路由至共享存储。
     *
     * <p>示例：{@code knowledge/}、{@code prompts/}。
     */
    public RemoteFilesystemSpec addSharedPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            extraSharedPrefixes.add(normalizePrefix(prefix));
        }
        return this;
    }

    /**
     * Sets the fallback user identifier when runtime {@code userId} is absent/blank.
     */
    /** 设置运行时 {@code userId} 缺失或为空时所使用的兜底用户标识。 */
    public RemoteFilesystemSpec anonymousUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("anonymous user id must not be blank");
        }
        this.anonymousUserId = userId;
        return this;
    }

    /**
     * Sets the isolation scope that controls the store namespace for shared files.
     *
     * <p>Mirrors the sandbox {@link io.agentscope.harness.agent.sandbox.SandboxContext} isolation
     * semantics. Defaults to {@link IsolationScope#USER}.
     *
     * @param scope isolation scope
     * @return this spec
     */
    /**
     * 设置隔离范围，用于控制共享文件对应的存储命名空间。
     *
     * <p>与沙箱 {@link io.agentscope.harness.agent.sandbox.SandboxContext} 的隔离语义保持一致。
     * 默认值为 {@link IsolationScope#USER}。
     *
     * @param scope 隔离范围
     * @return 当前描述符实例
     */
    public RemoteFilesystemSpec isolationScope(IsolationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("isolation scope must not be null");
        }
        this.isolationScope = scope;
        return this;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * Sets the workspace index for accelerating remote filesystem reads (ls/glob/exists/grep).
     * If not set, the remote filesystem falls back to full store scans.
     */
    /**
     * 设置用于加速远程文件系统读取操作（ls/glob/exists/grep）的工作空间索引。
     * 若未配置，远程文件系统将降级为全量存储扫描。
     */
    public RemoteFilesystemSpec workspaceIndex(WorkspaceIndex index) {
        this.workspaceIndex = index;
        return this;
    }

    /**
     * When {@code true}, the default backend (everything not routed to a shared prefix) serves
     * the workspace root directly: no per-user namespace prefix and sandboxed path resolution.
     * Files the host application writes to the workspace root with plain {@code java.nio}
     * (e.g. {@code uploads/<sessionId>/result.md}) become visible to {@code read_file} /
     * {@code list_files} / {@code grep_files}, and absolute paths outside the workspace are
     * rejected instead of traversing up to the filesystem root — with the default namespaced
     * backend such reads resolve to {@code {workspace}/{userId}/...}, which the host never
     * writes, and UNRESTRICTED resolution lets {@code list_files} walk up to the drive root.
     *
     * <p>Shared prefix routes ({@code memory/}, {@code skills/}, ...) keep their per-user store
     * namespaces; only the default backend changes.
     *
     * <p><b>Not safe for multi-tenant workspaces:</b> shared mode disables per-user namespace
     * isolation at the workspace root — users/sessions configured on the same workspace
     * directory will read and overwrite each other's files there. Intended for single-tenant
     * host-app integrations where the host writes uploads directly to the workspace.
     *
     * <p>Defaults to {@code false}, preserving the namespaced default backend (#3245).
     *
     * @param shared whether the default backend serves the shared workspace root
     * @return this spec
     */
    public RemoteFilesystemSpec sharedLocalWorkspace(boolean shared) {
        this.sharedLocalWorkspace = shared;
        return this;
    }

    /**
     * Builds the composite filesystem described by this spec.
     *
     * <ul>
     *   <li>default backend: {@link LocalFilesystem} (no shell), per-user namespaced
     *   <li>shared <b>prefix</b> routes ({@code memory/}, {@code skills/}, {@code subagents/},
     *       {@code knowledge/}, {@code plans/}, {@code agents/<id>/sessions/},
     *       {@code agents/<id>/tasks/}, plus
     *       any {@code addSharedPrefix} extras): wrapped in an {@link OverlayFilesystem} where
     *       the <em>upper</em> layer is the {@link RemoteFilesystem} (per-user, persisted in the
     *       {@link BaseStore}) and the <em>lower</em> layer is a read-only {@link LocalFilesystem}
     *       rooted at {@code workspace.resolve(<routeDir>)}. So scaffolded template content under
     *       {@code <workspace>/skills/}, {@code <workspace>/subagents/}, etc. is visible as a
     *       baseline; per-user edits land in the remote store via copy-on-write and override the
     *       template on subsequent reads.
     *   <li>{@code AGENTS.md}, {@code MEMORY.md}, {@code tools.json} exact-file routes: wrapped
     *       in an {@link OverlayFilesystem} where the <em>upper</em> is the {@code root}-segment
     *       {@link RemoteFilesystem} and the <em>lower</em> is a read-only {@link LocalFilesystem}
     *       rooted at the workspace, so the scaffolded template files at the workspace root are
     *       visible as the baseline. {@link CompositeFilesystem} does not recurse into exact-file
     *       routes when listing/globbing the tree; it performs a single {@code exists} check
     *       against the overlay, which is satisfied by either layer.
     * </ul>
     */
    /**
     *
     * <ul>
     *   <li>默认后端：{@link LocalFilesystem}（不支持Shell），按用户划分命名空间
     *   <li>共享路径前缀路由（{@code memory/}、{@code skills/}、{@code subagents/}、
     *       {@code knowledge/}、{@code plans/}、{@code agents/<id>/sessions/}、
     *       {@code agents/<id>/tasks/}，以及通过 {@code addSharedPrefix} 添加的额外路径）：
     *       使用 {@link OverlayFilesystem} 封装；**上层**为 {@link RemoteFilesystem}（按用户隔离，持久化至 {@link BaseStore}），
     *       **底层**是只读 {@link LocalFilesystem}，根目录指向 {@code workspace.resolve(<routeDir>)}。
     *       因此 {@code <workspace>/skills/}、{@code <workspace>/subagents/} 等目录下的模板文件可作为基线内容被读取；
     *       用户产生的修改通过写时复制机制存入远程存储，后续读取时覆盖模板内容。
     *   <li>{@code AGENTS.md}、{@code MEMORY.md}、{@code tools.json} 精确文件路由：
     *       使用 {@link OverlayFilesystem} 封装；**上层**为根路径 {@link RemoteFilesystem}，
     *       **底层**为工作空间根目录下的只读 {@link LocalFilesystem}，工作空间根目录的模板文件作为基线可用。
     *       在遍历/通配查询目录树时，{@link CompositeFilesystem} 不会递归遍历精确文件路由；
     *       仅对联合文件系统执行一次 {@code exists} 判断，两层任意一层存在文件即可命中。
     * </ul>
     */
    public AbstractFilesystem toFilesystem(
            Path workspace, String agentId, NamespaceFactory localNamespaceFactory) {
        if (store == null) {
            throw new IllegalStateException(
                    "RemoteFilesystemSpec has no BaseStore. Either pass one via the constructor"
                            + " or configure a DistributedStore on the HarnessAgent builder.");
        }
        String effectiveAgentId = agentId == null || agentId.isBlank() ? "HarnessAgent" : agentId;
        // sharedLocalWorkspace: same construction as the workspace-template layer below — shared
        // root, no namespace prefix, traversal blocked (#3245).
        AbstractFilesystem local =
                sharedLocalWorkspace
                        ? new LocalFilesystem(workspace, true, 10, null)
                        : new LocalFilesystem(workspace, false, 10, localNamespaceFactory);

        // Read-only workspace-root template view for the exact-file overlays below. The lower
        // technically exposes the entire workspace, but CompositeFilesystem does not recurse into
        // exact-file routes (it does single-key exists/read), so the over-exposure is unreachable.
        // 供下方精确文件联合层使用、基于工作空间根目录的只读模板视图。底层文件系统
        // 理论上可访问整个工作空间，但CompositeFilesystem不会递归遍历精确文件路由
        // （仅执行单路径存在性检查与读取操作），因此不会访问到超出预期的文件。
        LocalFilesystem workspaceTemplate = new LocalFilesystem(workspace, true, 10, null);

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("AGENTS.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("MEMORY.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("tools.json", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put(
                "memory/", overlayRoute(workspace.resolve("memory"), "memory", effectiveAgentId));
        routes.put(
                "skills/", overlayRoute(workspace.resolve("skills"), "skills", effectiveAgentId));
        routes.put(
                "subagents/",
                overlayRoute(workspace.resolve("subagents"), "subagents", effectiveAgentId));
        routes.put(
                "knowledge/",
                overlayRoute(workspace.resolve("knowledge"), "knowledge", effectiveAgentId));
        routes.put("plans/", overlayRoute(workspace.resolve("plans"), "plans", effectiveAgentId));
        routes.put(
                "agents/" + effectiveAgentId + "/sessions/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("sessions"),
                        "sessions",
                        effectiveAgentId));
        routes.put(
                "agents/" + effectiveAgentId + "/tasks/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("tasks"),
                        "tasks",
                        effectiveAgentId));
        for (String extra : extraSharedPrefixes) {
            String segment = routeSegmentFromPrefix(extra);
            routes.put(extra, overlayRoute(workspace.resolve(segment), segment, effectiveAgentId));
        }
        return new CompositeFilesystem(local, routes);
    }

    /**
     * Builds an {@link OverlayFilesystem} for a workspace-prefix route. The upper layer is the
     * per-user {@link RemoteFilesystem} backed by {@link BaseStore}; the lower layer is a read-only
     * {@link LocalFilesystem} rooted at {@code localTemplateDir} so scaffolded template content is
     * visible as the baseline. {@code virtualMode=true} on the lower so it reports paths anchored
     * to its own root, which is what {@link CompositeFilesystem}'s route remapping expects.
     */
    /**
     * 为工作空间路径前缀路由构建 {@link OverlayFilesystem}。上层为依托 {@link BaseStore}、按用户隔离的 {@link RemoteFilesystem}；
     * 底层是以 {@code localTemplateDir} 为根目录的只读 {@link LocalFilesystem}，提供模板基线内容。
     * 底层启用 {@code virtualMode=true}，使其返回路径基于自身根目录，契合 {@link CompositeFilesystem} 的路由重映射逻辑。
     */
    private OverlayFilesystem overlayRoute(
            Path localTemplateDir, String routeSegment, String agentId) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        LocalFilesystem lower = new LocalFilesystem(localTemplateDir, true, 10, null);
        return new OverlayFilesystem(upper, lower);
    }

    /**
     * Builds an {@link OverlayFilesystem} for an exact-file route (e.g. {@code AGENTS.md}).
     * The upper layer is the per-user {@link RemoteFilesystem} on the {@code root} namespace
     * segment; the lower layer is the shared workspace-root {@link LocalFilesystem} so the
     * scaffolded template file ({@code workspace/<filename>}) is visible as the baseline.
     */
    /**
     * 为精确文件路由（例如 {@code AGENTS.md}）构建 {@link OverlayFilesystem}。
     * 上层为根命名空间段下、按用户隔离的 {@link RemoteFilesystem}；
     * 底层是共享的工作空间根目录 {@link LocalFilesystem}，以此加载工作目录下对应的模板文件（{@code workspace/<filename>}）作为基线内容。
     */
    private OverlayFilesystem exactFileOverlay(
            String routeSegment, String agentId, LocalFilesystem workspaceTemplate) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        return new OverlayFilesystem(upper, workspaceTemplate);
    }

    private RemoteFilesystem remoteForRoute(String routeSegment, String agentId) {
        NamespaceFactory base = storeNamespace(agentId);
        NamespaceFactory extended =
                rc -> {
                    List<String> ns = new ArrayList<>(base.getNamespace(rc));
                    ns.add(routeSegment);
                    return ns;
                };
        return new RemoteFilesystem(store, extended).withIndex(workspaceIndex);
    }

    private static String routeSegmentFromPrefix(String normalizedPrefix) {
        String segment = normalizedPrefix;
        while (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        return segment.isEmpty() ? "extra" : segment;
    }

    private NamespaceFactory storeNamespace(String agentId) {
        return rc -> {
            String uid = rc != null ? rc.getUserId() : null;
            String sid = rc != null ? rc.getSessionId() : null;

            return switch (isolationScope) {
                case SESSION -> {
                    String effectiveSid = (sid != null && !sid.isBlank()) ? sid : "default";
                    yield List.of("agents", agentId, "sessions", effectiveSid);
                }
                case USER -> {
                    String effectiveUid = (uid != null && !uid.isBlank()) ? uid : anonymousUserId;
                    yield List.of("agents", agentId, "users", effectiveUid);
                }
                case AGENT -> List.of("agents", agentId, "shared");
                case GLOBAL -> List.of("global");
            };
        };
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
