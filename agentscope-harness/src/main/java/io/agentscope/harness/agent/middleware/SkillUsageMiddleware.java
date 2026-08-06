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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bumps {@link SkillUsageStore} counters whenever the agent invokes a SkillBox-registered skill
 * loader (e.g. {@code load_skill_through_path}). Provenance gating happens inside the store —
 * only skills tagged {@code created_by="agent"} or {@code "agent-draft"} are recorded.
 *
 * <p>Counter bumping is best-effort and runs eagerly when {@code onActing} is entered (before
 * the actual tool call returns). The semantics are "the model decided to invoke this skill on
 * this turn" — not "the call succeeded". This matches hermes-agent's telemetry shape and is
 * cheap to compute.
 */
/**
 * 当智能体调用 SkillBox 注册的技能加载工具（如 {@code load_skill_through_path}）时，
 * 递增 {@link SkillUsageStore} 中的使用计数。来源过滤在存储层内部完成——
 * 仅记录标记为 {@code created_by="agent"} 或 {@code "agent-draft"} 的技能。
 *
 * <p>计数递增采用尽力而为策略，在进入 {@code onActing} 时立即执行（早于工具调用返回）。
 * 其语义是"模型在本轮决定调用了该技能"，而非"调用成功"。
 * 该口径与 hermes-agent 的遥测形态一致，且计算成本低。
 */
public class SkillUsageMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageMiddleware.class);

    /** Tool names whose invocation counts as a "view" of a skill. */
    /** 调用即计为技能"浏览"的工具名集合。 */
    private static final Set<String> VIEW_TOOL_NAMES =
            Set.of("load_skill_through_path", "read_skill");

    /**
     * Tool names whose invocation counts as a "use" of a skill. Reserved for future SkillBox
     * surfaces (e.g. an explicit {@code use_skill}); empty today so {@code bumpUse} stays unused.
     */
    /**
     * 调用即计为技能"使用"的工具名集合。为未来的 SkillBox 能力预留
     * （如显式的 {@code use_skill}）；当前阶段 {@code bumpUse} 实际不会被触发。
     */
    private static final Set<String> USE_TOOL_NAMES = Set.of("use_skill");

    /** 技能使用统计存储，负责计数持久化与来源过滤。 */
    private final SkillUsageStore usageStore;

    /**
     * @param usageStore 技能使用统计存储，不允许为 null
     */
    public SkillUsageMiddleware(SkillUsageStore usageStore) {
        this.usageStore = java.util.Objects.requireNonNull(usageStore, "usageStore");
    }

    /**
     * 执行钩子：在工具实际执行前遍历本轮全部工具调用，
     * 对命中技能加载/使用工具的调用记录计数。不修改输入，直接透传。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                trackInvocation(call);
            }
        }
        return next.apply(input);
    }

    /**
     * If {@code call} targets a SkillBox loader / user, extract the {@code skillId} parameter
     * (or fallback equivalents) and bump the right counter on the store. Failures are swallowed
     * — telemetry must never break the agent loop.
     */
    /**
     * 若 {@code call} 指向 SkillBox 的技能加载/使用工具，则提取 {@code skillId} 参数
     * （或等价的后备参数），并递增存储中对应的计数器。失败被静默吞掉——
     * 遥测统计绝不能破坏智能体主循环。
     */
    private void trackInvocation(ToolUseBlock call) {
        if (call == null || call.getName() == null) {
            return;
        }
        String toolName = call.getName();
        boolean isView = VIEW_TOOL_NAMES.contains(toolName);
        boolean isUse = USE_TOOL_NAMES.contains(toolName);
        if (!isView && !isUse) {
            // 非技能相关工具，不统计
            return;
        }
        String skillName = extractSkillName(call);
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        try {
            if (isView) {
                usageStore.bumpView(skillName);
            } else {
                usageStore.bumpUse(skillName);
            }
        } catch (Exception e) {
            log.debug(
                    "SkillUsageMiddleware bump for tool={} skill={} failed: {}",
                    toolName,
                    skillName,
                    e.getMessage());
        }
    }

    /**
     * Pulls the skill identifier out of a tool-use block. {@code skillId} is the canonical key
     * used by the SkillBox loader; {@code skill_id} / {@code name} are tolerated as fallbacks
     * for forward compatibility with potential renames.
     */
    /**
     * 从工具调用块中提取技能标识。{@code skillId} 是 SkillBox 加载工具使用的规范键；
     * {@code skill_id} / {@code name} 作为后备键按序尝试，
     * 以便在参数改名时保持向前兼容。
     */
    private static String extractSkillName(ToolUseBlock call) {
        Map<String, Object> input = call.getInput();
        if (input == null) {
            return null;
        }
        for (String key : new String[] {"skillId", "skill_id", "name"}) {
            Object v = input.get(key);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    // Suppress unused warning for the future-proof "use" channel.
    // 抑制为未来"use"通道预留代码的未使用警告。
    @SuppressWarnings("unused")
    private static Mono<Void> noop() {
        return Mono.empty();
    }
}
