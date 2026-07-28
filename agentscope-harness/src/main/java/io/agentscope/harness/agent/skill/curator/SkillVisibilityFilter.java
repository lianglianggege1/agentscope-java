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
import java.util.List;

/**
 * Runtime gate that decides which skills are exposed to the model on a per-call basis. Hooked
 * after {@code DynamicSkillMiddleware} composes the merged repository view, so it sees the
 * final-list of candidate skills before they reach the system prompt.
 *
 * <p>The standard implementations live in this package:
 * <ul>
 *   <li>{@link EnvironmentFilter} — environment matching (applies to every skill)</li>
 *   <li>{@link CanaryFilter} — userId-keyed percentage rollout (agent-created only)</li>
 *   <li>{@link AllowListFilter} — explicit name allow-list (agent-created only)</li>
 *   <li>{@link CompositeFilter} — chains other filters with AND semantics</li>
 * </ul>
 */
/**
 * 运行时网关，基于单次调用判定向模型暴露哪些技能。
 * 挂载时机位于 {@code DynamicSkillMiddleware} 组装完成合并仓库视图之后，
 * 能够在候选技能列表送入系统提示词之前获取最终候选清单。
 *
 * <p>标准实现均位于当前包内：
 * <ul>
 *   <li>{@link EnvironmentFilter} — 环境匹配过滤（适用于所有技能）</li>
 *   <li>{@link CanaryFilter} — 基于用户ID的灰度放量过滤（仅代理创建技能生效）</li>
 *   <li>{@link AllowListFilter} — 技能名称显式白名单过滤（仅代理创建技能生效）</li>
 *   <li>{@link CompositeFilter} — 组合多个过滤器，采用逻辑与规则链式执行</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public interface SkillVisibilityFilter {

    /** Filter the skill list down to those visible for the current runtime context. */
    /** 将技能列表过滤为当前运行上下文可见的技能集合。 */
    List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx);
}
