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
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Enforces plan mode: while {@code AgentState.planModeContext.planActive} is {@code true}, mutating
 * tool calls are denied so the agent can only read / investigate and draft a plan.
 *
 * <p>Enforcement is deterministic and happens in {@link #onActing}: each tool call is allowed iff
 * it is read-only or one of the plan-control tools ({@code plan_enter} / {@code plan_write} /
 * {@code plan_exit} / {@code todo_write}); everything else gets a synthetic {@code DENIED} tool
 * result (written to context and streamed as events) without being executed. This intentionally
 * does <em>not</em> reuse {@code PermissionMode.EXPLORE}: that mode is snapshotted immutably by the
 * {@code PermissionEngine} at construction and cannot be toggled at runtime, whereas plan mode must
 * switch dynamically.
 *
 * <p>{@link #onSystemPrompt} injects a plan-mode banner so the model knows it is in the read-only
 * design phase.
 */
/**
 * 规划模式（PLAN mode）强制中间件：当 {@code AgentState.planModeContext.planActive}
 * 为 {@code true} 时，拒绝一切变更类工具调用，智能体只能读取/调研并起草计划。
 *
 * <p>强制是确定性的，发生在 {@link #onActing}：每个工具调用只有满足"只读"
 * 或属于计划控制工具（{@code plan_enter} / {@code plan_write} / {@code plan_exit} /
 * {@code todo_write} 等白名单）才被放行；其余一律返回合成的 {@code DENIED}
 * 工具结果（写入上下文并以事件流形式广播），且根本不执行。
 * 这里刻意<em>不</em>复用 {@code PermissionMode.EXPLORE}：该模式会被
 * {@code PermissionEngine} 在构造时快照为不可变状态，无法在运行时切换，
 * 而规划模式必须支持动态进出。
 *
 * <p>{@link #onSystemPrompt} 会注入规划模式横幅，让模型知道自己处于只读设计阶段；
 * 退出规划模式后若存在已批准的计划文件，还会注入 BUILD 模式提示引导模型执行计划。
 */
public class PlanModeMiddleware implements HarnessRuntimeMiddleware {

    // 规划模式下永远放行的工具白名单：计划控制工具 + 待办写入 + 子智能体/任务协调工具
    // （这些本身不改变外部状态，且是规划流程必需的交互手段）
    private static final Set<String> ALWAYS_ALLOWED =
            Set.of(
                    PlanModeTools.PLAN_ENTER,
                    PlanModeTools.PLAN_WRITE,
                    PlanModeTools.PLAN_EXIT,
                    "todo_write",
                    "agent_spawn",
                    "agent_send",
                    "agent_list",
                    "task_output",
                    "task_list");

    // 拒绝变更类调用时返回给模型的提示：说明当前处于只读模式及可用的操作路径
    private static final String DENY_MESSAGE =
            "Blocked: you are in PLAN mode (read-only). You may investigate and run read-only"
                    + " tools, record your plan with plan_write, and call plan_exit when ready to"
                    + " execute. Do not modify files or run mutating commands until the plan is"
                    + " approved.";

    // 规划模式激活时注入系统提示词的横幅模板：告知计划文件路径、只读约束、
    // plan_write/plan_exit 的正确用法，以及信息不足时应提问而非臆造计划
    private static final String PLAN_BANNER_TEMPLATE =
            """

            <system-reminder>
            PLAN MODE is active (read-only). Plan file: %s
            Investigate the problem and draft a plan, but do NOT modify files, run mutating commands,
            or otherwise change state. Record your plan with the plan_write tool. When the plan is
            complete, call plan_exit to ask the user for approval; only after approval will you return
            to BUILD mode and be able to make changes.
            ACT, do not just narrate: when you decide to record or finish the plan, call plan_write
            (or plan_exit) in the SAME step — never say you "will write the plan" without actually
            calling the tool, and never claim a plan exists unless you have called plan_write.
            If you cannot produce a concrete plan because you lack information (for example the system
            you were asked to work on is not present in this workspace), STOP and ask the user one
            specific clarifying question instead of inventing a plan or assuming details.
            </system-reminder>\
            """;

    // 从 PLAN 切回 BUILD 模式后的提示：只读限制解除、指出已批准计划文件路径、
    // 要求按计划逐步执行直到完成（防止模型只写计划不动手）
    private static final String BUILD_MODE_PLAN_HINT =
            "\n\n<system-reminder>You have switched from PLAN to BUILD mode; the read-only"
                    + " restriction is lifted. An approved plan exists at %s — read it for the"
                    + " details, then EXECUTE it step by step until the task is complete. Do NOT"
                    + " stop after merely producing the plan. If a todo list is available, capture"
                    + " the plan's steps with the todo_write tool and keep exactly one task"
                    + " in_progress as you work through them.</system-reminder>";

    // 存在额外放行工具时的补充提示：告知模型这些工具在规划模式下可用，
    // 但仅限只读用途（如 cat/ls/grep/git 查看），禁止执行变更命令
    private static final String PLAN_EXTRA_TOOLS_HINT =
            "\n\n<system-reminder>The following tool(s) are additionally available during PLAN"
                    + " mode for read-only investigation: %s. Use them ONLY to read/inspect (e.g."
                    + " cat, ls, grep, git log/diff/show/status). Do NOT run mutating commands"
                    + " (file writes, installs, git commit, rm, mv, network side effects, etc.)"
                    + " until the plan is approved.</system-reminder>";

    /** 规划模式状态/计划文件的共享协调器（判断是否激活、解析计划文件路径）。 */
    private final PlanModeManager manager;

    /** 只读判定器：按工具名判断该工具是否只读，决定规划模式下能否放行。 */
    private final Predicate<String> readOnlyResolver;

    /** 额外放行工具集：即使非只读也在规划模式下允许的逃生舱（显式选择加入）。 */
    private final Set<String> additionalAllowed;

    /**
     * @param manager shared plan-mode state/file coordinator
     * @param readOnlyResolver resolves whether a tool (by name) is read-only; used to decide which
     *     calls are permitted while plan mode is active
     */
    /**
     * @param manager 共享的规划模式状态/文件协调器
     * @param readOnlyResolver 按工具名解析是否只读；用于决定规划模式下哪些调用被允许
     */
    public PlanModeMiddleware(PlanModeManager manager, Predicate<String> readOnlyResolver) {
        this(manager, readOnlyResolver, Set.of());
    }

    /**
     * @param manager shared plan-mode state/file coordinator
     * @param readOnlyResolver resolves whether a tool (by name) is read-only; used to decide which
     *     calls are permitted while plan mode is active
     * @param additionalAllowed extra tool names that are permitted while plan mode is active even
     *     when not read-only (opt-in escape hatch — e.g. {@code execute} for shell-based
     *     investigation). The model is instructed via the plan banner to use them read-only only.
     */
    /**
     * @param manager 共享的规划模式状态/文件协调器
     * @param readOnlyResolver 按工具名解析是否只读；用于决定规划模式下哪些调用被允许
     * @param additionalAllowed 规划模式下即使非只读也额外允许的工具名
     *     （显式选择加入的逃生舱——例如供 shell 调研用的 {@code execute}）。
     *     通过规划横幅提示模型只能以只读方式使用它们。
     */
    public PlanModeMiddleware(
            PlanModeManager manager,
            Predicate<String> readOnlyResolver,
            Set<String> additionalAllowed) {
        this.manager = manager;
        this.readOnlyResolver = readOnlyResolver != null ? readOnlyResolver : name -> false;
        this.additionalAllowed =
                additionalAllowed == null || additionalAllowed.isEmpty()
                        ? Set.of()
                        : new LinkedHashSet<>(additionalAllowed);
    }

    /**
     * 系统提示词注入：规划模式激活时追加只读横幅（含计划文件路径与额外工具提示）；
     * BUILD 模式下若存在已批准计划文件则追加执行提示；其余情况原样返回。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String base = currentPrompt != null ? currentPrompt : "";

        if (manager.isPlanActive(state)) {
            String path = manager.planFilePath(state);
            String banner = base + PLAN_BANNER_TEMPLATE.formatted(path);
            if (!additionalAllowed.isEmpty()) {
                String tools = additionalAllowed.stream().collect(Collectors.joining(", "));
                banner += PLAN_EXTRA_TOOLS_HINT.formatted(tools);
            }
            return Mono.just(banner);
        }

        // BUILD mode: if a plan file was previously written, surface its path so the model
        // can re-read it after compaction without needing to remember the original tool call.
        // BUILD 模式：如果之前写过计划文件，把它的路径再次呈现出来，
        // 这样模型在上下文压缩之后无需记住原始工具调用也能重新读取计划。
        String planFile = state != null ? state.getPlanModeContext().getCurrentPlanFile() : null;
        if (planFile != null && !planFile.isBlank()) {
            return Mono.just(base + BUILD_MODE_PLAN_HINT.formatted(planFile));
        }
        return Mono.just(base);
    }

    /**
     * 规划模式的核心强制点：把本轮工具调用分为放行（allowed）与拒绝（denied）两组。
     * 被拒的调用生成合成 DENIED 结果（写上下文 + 发事件，但不执行）；
     * 放行的调用（若有）拼在被拒结果之后照常执行。
     * 规划模式未激活或无工具调用时零开销透传。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (!manager.isPlanActive(state) || input.toolCalls() == null) {
            return next.apply(input);
        }

        // 按白名单 + 只读判定把调用二分为放行/拒绝
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isPermitted(call.getName())) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
        }

        if (denied.isEmpty()) {
            return next.apply(input);
        }

        String replyId = state.getReplyId();
        String agentName = agent.getName();

        // Deferred so the context mutation + events run once, at subscription time.
        // 用 Flux.defer 包裹：上下文写入与事件生成只在订阅时执行一次（保证幂等）。
        Flux<AgentEvent> deniedFlux =
                Flux.defer(
                        () -> {
                            List<AgentEvent> events = new ArrayList<>();
                            for (ToolUseBlock call : denied) {
                                // 为每个被拒调用合成 DENIED 状态的 ToolResultBlock：
                                // 写入上下文让模型知晓，同时发射三事件供前端渲染
                                ToolResultBlock result =
                                        ToolResultBlock.text(DENY_MESSAGE)
                                                .withIdAndName(call.getId(), call.getName())
                                                .withState(ToolResultState.DENIED);
                                Msg msg =
                                        ToolResultMessageBuilder.buildToolResultMsg(
                                                result, call, agentName);
                                state.contextMutable().add(msg);
                                events.add(
                                        new ToolResultStartEvent(
                                                replyId, call.getId(), call.getName()));
                                events.add(
                                        new ToolResultTextDeltaEvent(
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                DENY_MESSAGE));
                                events.add(
                                        new ToolResultEndEvent(
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                ToolResultState.DENIED));
                            }
                            return Flux.fromIterable(events);
                        });

        if (allowed.isEmpty()) {
            return deniedFlux;
        }
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    /**
     * 放行判定：工具名命中永久白名单、命中额外放行集、或被只读判定器判为只读，
     * 三者满足其一即允许执行。
     */
    private boolean isPermitted(String toolName) {
        if (toolName == null) {
            return false;
        }
        return ALWAYS_ALLOWED.contains(toolName)
                || additionalAllowed.contains(toolName)
                || readOnlyResolver.test(toolName);
    }
}
