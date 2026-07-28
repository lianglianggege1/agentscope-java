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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.nio.file.Path;

/**
 * Resolves the absolute {@code filesRoot} path emitted in {@code <available_skills>} and
 * {@code load_skill_through_path} responses, given a skill's {@link StageResult} and the
 * current shell mode.
 *
 * <p>Three modes the policy knows about:
 *
 * <ul>
 *   <li>{@link Mode#NO_SHELL} — no {@code ShellExecuteTool} registered. Returns {@code null}
 *       for every skill so the prompt omits {@code <files-root>} and the code-execution
 *       instruction block entirely.
 *   <li>{@link Mode#SANDBOX} — sandbox-backed filesystem. Paths use the sandbox-internal
 *       prefix ({@code /workspace/}).
 *   <li>{@link Mode#LOCAL_WITH_SHELL} — {@code LocalFilesystemWithShell}. Paths use the
 *       absolute host workspace root.
 * </ul>
 */
/**
 * 根据技能的 {@link StageResult} 与当前Shell运行模式，解析在 {@code <available_skills>}
 * 与 {@code load_skill_through_path} 返回结果中输出的绝对 {@code filesRoot} 路径。
 *
 * <p>策略支持三种运行模式：
 *
 * <ul>
 *   <li>{@link Mode#NO_SHELL} — 未注册 {@code ShellExecuteTool}。所有技能均返回 {@code null}，
 *       提示词中将移除 {@code <files-root>} 以及完整代码执行指令区块。
 *   <li>{@link Mode#SANDBOX} — 基于沙箱的文件系统。路径使用沙箱内部前缀（{@code /workspace/}）。
 *   <li>{@link Mode#LOCAL_WITH_SHELL} — {@code LocalFilesystemWithShell}。路径使用宿主机工作区绝对根路径。
 * </ul>
 */
public final class ShellPathPolicy {

    public enum Mode {
        NO_SHELL,
        SANDBOX,
        LOCAL_WITH_SHELL
    }

    public static final String SANDBOX_WORKSPACE_PREFIX = "/workspace";

    private final Mode mode;
    private final Path workspaceRoot;
    private final String sandboxPrefix;

    private ShellPathPolicy(Mode mode, Path workspaceRoot, String sandboxPrefix) {
        this.mode = mode;
        this.workspaceRoot = workspaceRoot;
        this.sandboxPrefix = sandboxPrefix;
    }

    /** No shell is available; every {@code filesRoot} resolves to {@code null}. */
    /** 无可用Shell；所有 {@code filesRoot} 均解析为 {@code null}。 */
    public static ShellPathPolicy noShell() {
        return new ShellPathPolicy(Mode.NO_SHELL, null, null);
    }

    /** Sandbox mode — paths under the default {@code /workspace/} prefix. */
    /** 沙箱模式 — 路径使用默认 {@code /workspace/} 前缀。 */
    public static ShellPathPolicy sandbox() {
        return sandbox(SANDBOX_WORKSPACE_PREFIX);
    }

    /**
     * Sandbox mode with a custom workspace prefix. Use this when the sandbox backend mounts the
     * workspace at a non-default location (e.g. {@code /home/agentscope/workspace} for AgentRun).
     *
     * @param workspacePrefix absolute path of the workspace root inside the sandbox
     */
    /**
     * 带自定义工作区前缀的沙箱模式。当沙箱后端将工作区挂载至非默认路径时使用
     *（例如 AgentRun 场景下的 {@code /home/agentscope/workspace}）。
     *
     * @param workspacePrefix 沙箱内部工作区根目录绝对路径
     */
    public static ShellPathPolicy sandbox(String workspacePrefix) {
        return new ShellPathPolicy(
                Mode.SANDBOX,
                null,
                workspacePrefix != null ? workspacePrefix : SANDBOX_WORKSPACE_PREFIX);
    }

    /** Local-with-shell mode — paths absolute on the host. */
    /** 本地带Shell模式 — 路径为宿主机上的绝对路径。 */
    public static ShellPathPolicy localWithShell(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot required for LOCAL_WITH_SHELL");
        }
        return new ShellPathPolicy(Mode.LOCAL_WITH_SHELL, workspaceRoot, null);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Returns the absolute {@code filesRoot} for the given skill, or {@code null} when shell
     * is unavailable / the skill has no shell-reachable representation.
     *
     * @param skillName the skill's {@code name}
     * @param stage     staging outcome from {@link MarketplaceStager#stage}
     */
    /**
     * 返回指定技能对应的绝对 {@code filesRoot}；若无可用Shell或该技能不存在Shell可访问载体时返回 {@code null}。
     *
     * @param skillName 技能名称
     * @param stage      由 {@link MarketplaceStager#stage} 得到的暂存结果
     */
    public String resolve(String skillName, StageResult stage) {
        if (mode == Mode.NO_SHELL || stage == null || stage instanceof StageResult.None) {
            return null;
        }
        if (stage instanceof StageResult.WorkspaceNative) {
            return joinSkills(skillName);
        }
        if (stage instanceof StageResult.Cached cached) {
            return joinCache(cached.sourceNamespace(), cached.skillName());
        }
        return null;
    }

    private String joinSkills(String skillName) {
        return switch (mode) {
            case SANDBOX -> sandboxPrefix + "/skills/" + skillName;
            case LOCAL_WITH_SHELL ->
                    workspaceRoot.resolve("skills").resolve(skillName).toAbsolutePath().toString();
            case NO_SHELL -> null;
        };
    }

    private String joinCache(String sourceNs, String skillName) {
        return switch (mode) {
            case SANDBOX ->
                    sandboxPrefix
                            + "/"
                            + MarketplaceStager.CACHE_DIR
                            + "/"
                            + sourceNs
                            + "/"
                            + skillName;
            case LOCAL_WITH_SHELL ->
                    workspaceRoot
                            .resolve(MarketplaceStager.CACHE_DIR)
                            .resolve(sourceNs)
                            .resolve(skillName)
                            .toAbsolutePath()
                            .toString();
            case NO_SHELL -> null;
        };
    }
}
