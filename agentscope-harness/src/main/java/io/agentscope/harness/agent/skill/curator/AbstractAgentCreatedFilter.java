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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for visibility filters that should ONLY constrain agent-authored skills. User-
 * authored / hub-installed / pre-existing skills (those with {@code createdBy == null} in the
 * sidecar) pass through untouched, so a {@link CanaryFilter} doesn't accidentally hide the
 * hand-crafted skills the agent's owners shipped.
 *
 * <p>Subclasses implement {@link #shouldPassForAgentCreated} for the agent-tracked skills.
 */
/**
 * 可见性过滤器基类，仅对代理创建的技能生效。
 * 用户创建、市场安装、预置内置技能（Sidecar 中 {@code createdBy == null}）会直接放行，
 * 避免 {@link CanaryFilter} 误隐藏运营方预置的定制技能。
 *
 * <p>子类实现 {@link #shouldPassForAgentCreated} 方法，处理由代理管理的技能过滤逻辑。
 */
@SuppressWarnings("deprecation")
public abstract class AbstractAgentCreatedFilter implements SkillVisibilityFilter {

    protected final SkillUsageStore usageStore;

    protected AbstractAgentCreatedFilter(SkillUsageStore usageStore) {
        this.usageStore = usageStore;
    }

    @Override
    public final List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty()) {
            return all == null ? List.of() : all;
        }
        List<AgentSkill> out = new ArrayList<>(all.size());
        for (AgentSkill skill : all) {
            if (skill == null || skill.getName() == null) {
                continue;
            }
            SkillUsageRecord rec =
                    usageStore != null ? usageStore.get(skill.getName()).orElse(null) : null;
            if (rec == null || !"agent".equals(rec.createdBy())) {
                // Pass-through: external / pre-existing skill, do not gate.
                // 直通放行：外部/已存在技能，不做准入管控。
                out.add(skill);
                continue;
            }
            if (shouldPassForAgentCreated(skill, rec, ctx)) {
                out.add(skill);
            }
        }
        return out;
    }

    /**
     * Return {@code true} to keep an agent-authored skill visible for this context, {@code
     * false} to filter it out. {@code rec} is guaranteed non-null with {@code createdBy ==
     * "agent"}.
     */
    /**
     * 返回 {@code true} 表示当前上下文可见该代理创建技能；返回 {@code false} 则过滤移除。
     * {@code rec} 保证不为 null，且 {@code createdBy == "agent"}。
     */
    protected abstract boolean shouldPassForAgentCreated(
            AgentSkill skill, SkillUsageRecord rec, RuntimeContext ctx);
}
