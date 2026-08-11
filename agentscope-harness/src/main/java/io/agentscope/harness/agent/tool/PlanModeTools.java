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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * The three model-facing tools that drive plan mode, all backed by a shared {@link
 * PlanModeManager}.
 *
 * <ul>
 *   <li>{@code plan_enter} — switch into read-only plan mode to investigate and design before
 *       touching anything.
 *   <li>{@code plan_write} — create / overwrite the plan markdown blueprint (allowed in plan mode).
 *   <li>{@code plan_exit} — request to leave plan mode and start executing. Gated by an {@code ASK}
 *       self-check, so the user confirms the plan (via the existing permission HITL flow) before
 *       the agent regains write access.
 * </ul>
 *
 * <p>{@code plan_enter} / {@code plan_write} / {@code plan_exit} are always permitted by
 * {@code PlanModeMiddleware}; every other mutating tool is denied while plan mode is active.
 */
/**
 * 面向模型、支撑规划模式的三类工具，全部由共享 {@link PlanModeManager} 提供底层能力。
 *
 * <ul>
 *   <li>{@code plan_enter} — 切换至只读规划模式，在执行变更前开展调研与方案设计。
 *   <li>{@code plan_write} — 创建/覆盖规划Markdown草案（仅规划模式下允许调用）。
 *   <li>{@code plan_exit} — 请求退出规划模式并开始执行。受内部自检{@code ASK}管控，
 *       智能体获得写权限前，需经由现有人工权限流程由用户确认方案。
 * </ul>
 *
 * <p>{@code plan_enter} / {@code plan_write} / {@code plan_exit} 始终可通过 {@code PlanModeMiddleware} 放行；
 * 规划模式激活期间，其余所有具备修改能力的工具均被拦截。
 */
public final class PlanModeTools {

    /** Tool names that {@code PlanModeMiddleware} always allows, even in plan mode. */
    /** {@code PlanModeMiddleware} 始终放行的工具名（即使在规划模式下）。 */
    public static final String PLAN_ENTER = "plan_enter";

    public static final String PLAN_WRITE = "plan_write";
    public static final String PLAN_EXIT = "plan_exit";

    /** 工具类入口，不可实例化。 */
    private PlanModeTools() {}

    /** 从工具调用参数中解析智能体状态（优先运行时上下文，其次参数中的智能体引用）。 */
    private static AgentState stateOf(ToolCallParam param) {
        return RuntimeContext.resolveAgentState(param.getRuntimeContext(), param.getAgent());
    }

    /** 构造携带原工具调用 ID 与名称的文本结果块。 */
    private static ToolResultBlock result(ToolCallParam param, String text) {
        return ToolResultBlock.text(text)
                .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
    }

    /** {@code plan_enter}: enter read-only plan mode. */
    /** {@code plan_enter}：进入只读规划模式。 */
    public static final class PlanEnterTool extends ToolBase {
        private final PlanModeManager manager;

        public PlanEnterTool(PlanModeManager manager) {
            super(
                    ToolBase.builder()
                            .name(PLAN_ENTER)
                            .description(
                                    "Enter PLAN mode: a read-only phase for investigating the"
                                        + " codebase and designing an approach before making any"
                                        + " changes. While in plan mode you cannot edit files or"
                                        + " run mutating commands; use plan_write to record your"
                                        + " plan and plan_exit when you are ready to execute. Use"
                                        + " this for non-trivial, multi-step, or ambiguous tasks.")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.manager = manager;
        }

        /** 执行进入规划模式：切换智能体状态为只读，并返回规划文件的存放路径提示。 */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            AgentState state = stateOf(param);
            if (state == null) {
                return Mono.just(result(param, "Error: agent state unavailable."));
            }
            String path = manager.enter(state);
            return Mono.just(
                    result(
                            param,
                            "Entered PLAN mode (read-only). Investigate freely, then call"
                                    + " plan_write to record your plan at \""
                                    + path
                                    + "\". When the plan is ready, call plan_exit to request"
                                    + " approval and begin executing."));
        }
    }

    /** {@code plan_write}: create / overwrite the plan markdown file. */
    /** {@code plan_write}：创建/覆盖规划 Markdown 文件。 */
    public static final class PlanWriteTool extends ToolBase {
        private final PlanModeManager manager;

        public PlanWriteTool(PlanModeManager manager) {
            super(
                    ToolBase.builder()
                            .name(PLAN_WRITE)
                            .description(
                                    "Create or overwrite the current plan as a markdown document."
                                        + " Pass the COMPLETE plan content; it replaces the file."
                                        + " Use clear sections (goal, steps, risks, verification).")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "content",
                                                    Map.of(
                                                            "type",
                                                            "string",
                                                            "description",
                                                            "The full markdown plan content.")),
                                            "required",
                                            List.of("content")))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.manager = manager;
        }

        /** 执行写入规划：把入参 content 整体写入规划 Markdown 文件（全量覆盖）。 */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            AgentState state = stateOf(param);
            if (state == null) {
                return Mono.just(result(param, "Error: agent state unavailable."));
            }
            Object content = param.getInput().get("content");
            String text = content == null ? "" : content.toString();
            RuntimeContext rc = param.getRuntimeContext();
            String path = manager.writePlan(rc, state, text);
            return Mono.just(result(param, "Plan saved to \"" + path + "\"."));
        }
    }

    /** {@code plan_exit}: request approval, then leave plan mode for BUILD. */
    /** {@code plan_exit}：请求用户批准，随后退出规划模式进入执行（BUILD）模式。 */
    public static final class PlanExitTool extends ToolBase {
        private final PlanModeManager manager;

        public PlanExitTool(PlanModeManager manager) {
            super(
                    ToolBase.builder()
                            .name(PLAN_EXIT)
                            .description(
                                    "Finish planning and request permission to start executing the"
                                        + " plan. This pauses for the user to approve your plan. On"
                                        + " approval you return to BUILD mode and may modify files;"
                                        + " on rejection you stay in PLAN mode and should revise.")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "summary",
                                                    Map.of(
                                                            "type",
                                                            "string",
                                                            "description",
                                                            "Optional short summary of the plan for"
                                                                    + " the user to approve."))))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.manager = manager;
        }

        /**
         * Always ASK: leaving plan mode is a deliberate hand-off that the user confirms. The
         * lightweight permission path honours an explicit ASK self-check even when no permission
         * rules are configured, so this works out of the box.
         */
        /**
         * 始终返回 ASK：退出规划模式是一次需要用户确认的刻意交接。
         * 即使未配置任何权限规则，轻量级权限路径也会遵循显式的 ASK 自检，
         * 因此开箱即用。
         */
        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(
                    PermissionDecision.ask(
                            "The agent wants to finish planning and start executing. Approve to"
                                    + " continue in BUILD mode, or reject to keep planning."));
        }

        /** Runs only after the user approves the ASK above. */
        /** 仅在用户批准上述 ASK 请求后执行：退出规划模式并提示初始化任务清单。 */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            AgentState state = stateOf(param);
            if (state == null) {
                return Mono.just(result(param, "Error: agent state unavailable."));
            }
            manager.exit(state);
            return Mono.just(
                    result(
                            param,
                            "Plan approved. You are now in BUILD mode and may modify files and run"
                                + " commands. Start executing the plan now. Seed your task list"
                                + " with todo_write (one item per plan step), keep exactly one task"
                                + " in_progress, and update it as you go."));
        }
    }
}
