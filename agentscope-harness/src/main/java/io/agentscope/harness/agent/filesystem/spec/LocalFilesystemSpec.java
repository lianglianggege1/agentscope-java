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
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Specification for the local filesystem mode (with shell execution).
 *
 * <p>This spec produces a {@link LocalFilesystemWithShell} whose root is the agent workspace and
 * whose shell runs directly on the host as {@code sh -c <command>}. Long-term memory
 * ({@code MEMORY.md}, {@code memory/}) and session logs live on the same local disk.
 *
 * <p>Suitable for single-process deployments (personal assistants, CLI tools, local dev loops)
 * where distributed sharing is not required and the agent is trusted to run host shell commands.
 *
 * <p>For distributed deployments where long-term memory must be shared across replicas, prefer
 * {@link RemoteFilesystemSpec} (no shell) or a sandbox filesystem spec (shell via sandbox).
 */
/**
 * 本地文件系统模式配置（支持Shell命令执行）。
 *
 * <p>该配置会生成 {@link LocalFilesystemWithShell} 实例，文件根目录为智能体工作目录，
 * Shell 命令直接在宿主机以 {@code sh -c <command>} 方式运行。持久记忆文件（{@code MEMORY.md}、{@code memory/} 目录）与会话日志均存储在本地磁盘。
 *
 * <p>适用于单进程部署场景（个人助手、命令行工具、本地开发调试），无需分布式数据共享，且信任智能体具备宿主机Shell执行权限。
 *
 * <p>若为分布式部署、且多副本间需要共享持久记忆，推荐使用 {@link RemoteFilesystemSpec}（无Shell执行能力）或沙箱文件系统配置（通过沙箱执行Shell）。
 */
public class LocalFilesystemSpec {

    private int executeTimeoutSeconds = LocalFilesystemWithShell.DEFAULT_EXECUTE_TIMEOUT;
    private int maxOutputBytes = 100_000;
    private final Map<String, String> env = new LinkedHashMap<>();
    private boolean inheritEnv = false;

    /**
     * Path-resolution policy for the upper {@link LocalFilesystemWithShell}. Defaults to
     * {@link LocalFsMode#ROOTED}, so absolute paths supplied by the agent are accepted only when
     * they fall under one of the configured roots (project + workspace + additionalRoots).
     */
    /**
     * 上层 {@link LocalFilesystemWithShell} 的路径解析策略。默认值为 {@link LocalFsMode#ROOTED}，
     * 即智能体传入的绝对路径仅当位于配置根目录（项目目录、工作目录、附加根目录）范围内时才允许访问。
     */
    private LocalFsMode mode = LocalFsMode.ROOTED;

    private IsolationScope isolationScope;

    /**
     * User project root (lower layer of the resulting {@link OverlayFilesystem}). The agent reads
     * project-authored content (e.g. {@code AGENTS.md}, {@code knowledge/}, {@code skills/}) from
     * this directory and copies-on-write into the agent {@code workspace} when modified. Also
     * the shell {@code pwd} for {@code execute()} so command output matches user expectation.
     *
     * <p>{@code null} until {@link #project(Path)} is called; defaults to
     * {@link System#getProperty(String) System.getProperty("user.dir")} at
     * {@link #toFilesystem} time.
     */
    /**
     * 用户项目根目录（生成的 {@link OverlayFilesystem} 下层文件层）。智能体从此目录读取用户编写的项目内容
     *（例如 {@code AGENTS.md}、{@code knowledge/}、{@code skills/}），修改时采用写时复制机制拷贝至智能体工作目录。
     * 同时该目录为 {@code execute()} 执行Shell命令时的默认工作目录 pwd，保证命令输出符合用户预期。
     *
     * <p>未调用 {@link #project(Path)} 前该值为 {@code null}；执行 {@link #toFilesystem} 时默认取值为
     * {@link System#getProperty(String) System.getProperty("user.dir")}。
     */
    private Path project;

    /**
     * Extra host directories beyond {@code project} and {@code workspace} that the agent is
     * allowed to touch in {@link LocalFsMode#ROOTED} mode. Mirrors Claude Code CLI's
     * {@code --add-dir} flag.
     */
    /**
     * 在 {@link LocalFsMode#ROOTED} 模式下，除项目目录与工作目录外，允许智能体访问操作的额外宿主机目录。
     * 功能对标 Claude Code CLI 的 {@code --add-dir} 参数。
     */
    private final List<Path> additionalRoots = new ArrayList<>();

    /**
     * When {@code true}, the agent's file-write operations for non-workspace paths (i.e. paths
     * that are not workspace metadata like {@code MEMORY.md}, {@code agents/}, {@code skills/})
     * are routed to the project directory instead of the workspace. Workspace metadata paths
     * continue to be written to the workspace.
     *
     * <p>Defaults to {@code false}, preserving the original overlay behaviour where all writes
     * land in the workspace.
     */
    /**
     * 该配置为 {@code true} 时，智能体对非工作区路径（排除 {@code MEMORY.md}、{@code agents/}、{@code skills/} 等工作区元数据路径）的文件写入操作
     * 将定向至项目目录而非工作目录；工作区元数据路径仍会写入工作目录。
     *
     * <p>默认值为 {@code false}，保留原始分层文件系统行为：所有写入操作均落地至工作目录。
     */
    private boolean projectWritable = false;

    /**
     * Sets the default command execution timeout in seconds.
     *
     * @param seconds timeout (must be positive)
     * @return this spec
     */
    /**
     * 设置命令执行默认超时时间，单位秒。
     *
     * @param seconds 超时时长（必须为正数）
     * @return 当前配置实例
     */
    public LocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + seconds);
        }
        this.executeTimeoutSeconds = seconds;
        return this;
    }

    /**
     * Sets the maximum number of output bytes captured from any single shell command.
     *
     * @param bytes byte cap (must be positive)
     * @return this spec
     */
    /**
     * 设置单条Shell命令输出内容的最大捕获字节数。
     *
     * @param bytes 字节上限（必须为正数）
     * @return 当前配置实例
     */
    public LocalFilesystemSpec maxOutputBytes(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive, got " + bytes);
        }
        this.maxOutputBytes = bytes;
        return this;
    }

    /**
     * Adds an environment variable that will be set for every shell command.
     *
     * @param name variable name
     * @param value variable value
     * @return this spec
     */
    /**
     * 新增环境变量，所有Shell命令执行时都会注入该变量。
     *
     * @param name 变量名
     * @param value 变量值
     * @return 当前配置实例
     */
    public LocalFilesystemSpec env(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        this.env.put(name, value);
        return this;
    }

    /**
     * Controls whether the parent process environment is inherited by shell commands. When
     * {@code false} (default), only variables added via {@link #env(String, String)} are visible.
     *
     * @param inherit whether to inherit parent env
     * @return this spec
     */
    /**
     * 控制Shell命令是否继承父进程环境变量。默认值为 {@code false}，此时仅能读取通过 {@link #env(String, String)} 配置的环境变量。
     *
     * @param inherit 是否继承父进程环境变量
     * @return 当前配置实例
     */
    public LocalFilesystemSpec inheritEnv(boolean inherit) {
        this.inheritEnv = inherit;
        return this;
    }

    /**
     * Legacy: {@code true} maps to {@link LocalFsMode#SANDBOXED}, {@code false} to
     * {@link LocalFsMode#UNRESTRICTED}. Prefer {@link #mode(LocalFsMode)} so {@link LocalFsMode#ROOTED}
     * is also reachable.
     *
     * @param virtual whether to enable virtual mode
     * @return this spec
     * @deprecated use {@link #mode(LocalFsMode)} for the full three-way selection
     */
    /**
     * 旧版兼容配置：{@code true} 对应 {@link LocalFsMode#SANDBOXED}，{@code false} 对应 {@link LocalFsMode#UNRESTRICTED}。
     * 推荐使用 {@link #mode(LocalFsMode)} 方法，可完整支持 {@link LocalFsMode#ROOTED} 三种模式选择。
     *
     * @param virtual 是否开启虚拟隔离模式
     * @return 当前配置实例
     * @deprecated 请使用 {@link #mode(LocalFsMode)} 以支持完整三档路径权限模式
     */
    @Deprecated
    public LocalFilesystemSpec virtualMode(boolean virtual) {
        return mode(virtual ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED);
    }

    /**
     * Sets the path-resolution policy for the upper {@link LocalFilesystemWithShell}. Defaults
     * to {@link LocalFsMode#ROOTED}.
     *
     * @param mode policy mode
     * @return this spec
     */
    /**
     * 设置上层 {@link LocalFilesystemWithShell} 的路径解析策略，默认值为 {@link LocalFsMode#ROOTED}。
     *
     * @param mode 路径权限策略模式
     * @return 当前配置实例
     */
    public LocalFilesystemSpec mode(LocalFsMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        return this;
    }

    /**
     * Sets the isolation scope controlling how file paths are namespaced per user, session, or
     * agent. Defaults to {@link IsolationScope#USER} (consistent with
     * {@link RemoteFilesystemSpec} and sandbox specs).
     *
     * @param scope isolation scope
     * @return this spec
     */
    /**
     * 设置文件隔离范围，控制文件路径按用户、会话或智能体进行命名空间隔离。
     * 默认值为 {@link IsolationScope#USER}，与 {@link RemoteFilesystemSpec} 及沙箱配置保持一致。
     *
     * @param scope 隔离级别
     * @return 当前配置实例
     */
    public LocalFilesystemSpec isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    /** Returns the configured isolation scope, or {@code null} to use the default. */
    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * Adds an extra host directory the agent is allowed to access by absolute path under
     * {@link LocalFsMode#ROOTED}. {@code null} entries are ignored.
     *
     * @param root extra root to allow
     * @return this spec
     */
    public LocalFilesystemSpec addRoot(Path root) {
        if (root != null) {
            this.additionalRoots.add(root);
        }
        return this;
    }

    /**
     * Enables or disables project-writable mode. When {@code true}, the agent's file-write
     * operations for non-workspace paths are routed to the project directory.
     *
     * @param writable whether non-workspace writes go to the project directory
     * @return this spec
     */
    public LocalFilesystemSpec projectWritable(boolean writable) {
        this.projectWritable = writable;
        return this;
    }

    /** Returns whether project-writable mode is enabled. */
    public boolean isProjectWritable() {
        return projectWritable;
    }

    /**
     * Replaces the list of extra host directories. See {@link #addRoot(Path)}.
     *
     * @param roots extra roots ({@code null} clears)
     * @return this spec
     */
    public LocalFilesystemSpec additionalRoots(Collection<Path> roots) {
        this.additionalRoots.clear();
        if (roots != null) {
            for (Path r : roots) {
                if (r != null) {
                    this.additionalRoots.add(r);
                }
            }
        }
        return this;
    }

    /**
     * Sets the user project root used as the lower layer of the resulting
     * {@link OverlayFilesystem}. Reads of {@code AGENTS.md}, {@code knowledge/}, {@code skills/}
     * etc. fall back to this directory when the agent {@code workspace} does not contain them;
     * shell {@code execute()} runs with {@code pwd} set to this directory.
     *
     * <p>Defaults to {@code System.getProperty("user.dir")} when not set.
     *
     * @param project project root path
     * @return this spec
     */
    public LocalFilesystemSpec project(Path project) {
        this.project = project;
        return this;
    }

    /**
     * Builds the effective filesystem as an {@link OverlayFilesystem} with the agent
     * {@code workspace} as the upper (read-write, shell host) layer and the user
     * {@link #project(Path)} as the read-only lower layer. Writes always land in
     * {@code workspace}; reads check {@code workspace} first then fall back to {@code project},
     * giving copy-on-write semantics for files that originate in the project tree.
     *
     * @param workspace agent workspace root (becomes overlay upper)
     * @param localNamespaceFactory optional namespace factory for per-user/session folder scoping
     * @return an {@link OverlayFilesystem} wired with the options in this spec
     */
    /** Project root explicitly configured, or {@code null} to fall back to {@code ${user.dir}}. */
    public Path getProject() {
        return project;
    }

    /** Currently configured path-resolution policy mode. */
    public LocalFsMode getMode() {
        return mode;
    }

    /** Snapshot of configured extra roots. */
    public List<Path> getAdditionalRoots() {
        return List.copyOf(additionalRoots);
    }

    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory) {
        Path effectiveProject =
                project != null ? project : Paths.get(System.getProperty("user.dir"));
        List<Path> policyRoots = new ArrayList<>();
        policyRoots.add(effectiveProject);
        policyRoots.add(workspace);
        policyRoots.addAll(additionalRoots);
        PathPolicy pathPolicy = PathPolicy.of(policyRoots);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        mode,
                        pathPolicy,
                        executeTimeoutSeconds,
                        maxOutputBytes,
                        env.isEmpty() ? null : Map.copyOf(env),
                        inheritEnv,
                        localNamespaceFactory,
                        effectiveProject);
        LocalFilesystem lower = new LocalFilesystem(effectiveProject, true, 10, null);
        if (projectWritable) {
            LocalFilesystem projectFs =
                    new LocalFilesystem(
                            effectiveProject, mode, pathPolicy, 10, localNamespaceFactory);
            return new ProjectAwareOverlay(
                    (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
        }
        return OverlayFilesystem.of(upper, lower);
    }
}
