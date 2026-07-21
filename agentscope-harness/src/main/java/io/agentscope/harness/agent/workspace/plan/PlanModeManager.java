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
package io.agentscope.harness.agent.workspace.plan;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.Objects;

/**
 * Coordinates plan mode for a single agent.
 *
 * <p>State (whether plan mode is active and which markdown file holds the current plan) lives in
 * {@link PlanModeContextState} inside the agent's {@link AgentState}, so it is persisted with the
 * session and survives restarts / cross-node hand-offs — this is the mechanism that makes dynamic
 * (runtime) plan-mode switching work in a distributed setting, where an in-process boolean would be
 * lost.
 *
 * <p>The plan markdown file is written exclusively through {@link WorkspaceManager}, never via
 * {@code java.nio.file.Files}, so it lands on whatever backend (local, sandbox, remote) the agent's
 * filesystem is configured with. The logical path stays {@code <planDir>/PLAN.md} for every
 * isolation scope; per-scope isolation is applied transparently by the filesystem (store namespace
 * for remote, snapshot key for sandbox) rather than by encoding it into the path.
 */
/**
 * 单个智能体规划模式的协调管理器。
 *
 * <p>规划模式相关状态（是否启用规划模式、当前规划内容存储在哪份 Markdown 文件）
 * 保存在智能体 {@link AgentState} 内部的 {@link PlanModeContextState} 中，
 * 该状态会随会话持久化，支持服务重启、跨节点移交后不丢失状态。
 * 正是依靠这套持久化机制，分布式环境下才能在运行时动态切换规划模式；
 * 如果仅使用进程内布尔变量存储开关状态，重启/节点切换后状态就会丢失。
 *
 * <p>规划对应的 Markdown 文件仅允许通过 {@link WorkspaceManager} 写入，
 * 禁止直接使用 {@code java.nio.file.Files} 操作文件；
 * 文件会自动落盘至当前智能体文件系统配置的底层存储（本地磁盘、沙箱、远端对象存储等）。
 * 所有资源隔离作用域下，规划文件逻辑路径统一为 {@code <planDir>/PLAN.md}；
 * 各隔离域的数据隔离由文件系统底层透明实现（远端存储使用独立命名空间、沙箱使用快照Key区分），
 * 不会把隔离标识硬编码到文件路径字符串里。
 */
public final class PlanModeManager {

    /** Default workspace-relative directory for plan files. */
    public static final String DEFAULT_PLAN_DIR = "plans";

    private final WorkspaceManager workspaceManager;
    private final String planDir;

    public PlanModeManager(WorkspaceManager workspaceManager, String planDir) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
        String dir = planDir == null || planDir.isBlank() ? DEFAULT_PLAN_DIR : planDir.trim();
        // Normalise: strip leading/trailing slashes.
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        this.planDir = dir.isEmpty() ? DEFAULT_PLAN_DIR : dir;
    }

    public boolean isPlanActive(AgentState state) {
        return state != null && state.getPlanModeContext().isPlanActive();
    }

    /**
     * Enters plan mode. Idempotent. Ensures a plan file path is recorded (default if none set).
     *
     * @return the workspace-relative plan file path
     */
    /**
     * 切换至规划模式，接口幂等。确保规划文件路径已记录（未指定时使用默认路径）。
     *
     * @return 工作区相对路径格式的规划文件路径
     */
    public String enter(AgentState state) {
        Objects.requireNonNull(state, "state");
        PlanModeContextState ctx = state.getPlanModeContext();
        ctx.setPlanActive(true);
        if (ctx.getCurrentPlanFile() == null || ctx.getCurrentPlanFile().isBlank()) {
            ctx.setCurrentPlanFile(defaultPlanFile());
        }
        return ctx.getCurrentPlanFile();
    }

    /** Exits plan mode (back to BUILD). Idempotent. Keeps {@code currentPlanFile} for reference. */
    /** 退出规划模式，切回构建模式（BUILD），操作幂等。保留 {@code currentPlanFile} 供后续读取引用。 */
    public void exit(AgentState state) {
        Objects.requireNonNull(state, "state");
        state.getPlanModeContext().setPlanActive(false);
    }

    /** Workspace-relative path of the plan file currently associated with this agent. */
    /** 当前智能体绑定的规划文件，采用工作区相对路径存储。 */
    public String planFilePath(AgentState state) {
        if (state != null) {
            String current = state.getPlanModeContext().getCurrentPlanFile();
            if (current != null && !current.isBlank()) {
                return current;
            }
        }
        return defaultPlanFile();
    }

    /**
     * Writes (creates or overwrites) the plan markdown file through the workspace filesystem and
     * records its path in state.
     *
     * @return the workspace-relative path written
     */
    /**
     * 通过工作区文件系统写入规划Markdown文件（不存在则新建，已存在则覆盖），并将该文件路径持久化至状态中。
     *
     * @return 本次写入文件对应的工作区相对路径
     */
    public String writePlan(RuntimeContext rc, AgentState state, String content) {
        Objects.requireNonNull(state, "state");
        String path = planFilePath(state);
        RuntimeContext effective = rc != null ? rc : RuntimeContext.empty();
        workspaceManager.writeUtf8WorkspaceRelative(
                effective, path, content == null ? "" : content);
        state.getPlanModeContext().setCurrentPlanFile(path);
        return path;
    }

    private String defaultPlanFile() {
        return planDir + "/PLAN.md";
    }
}
