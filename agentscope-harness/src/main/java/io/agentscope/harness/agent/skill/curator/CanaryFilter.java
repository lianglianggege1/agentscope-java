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
import java.nio.charset.StandardCharsets;

/**
 * Percentage-based rollout for agent-authored skills. Hashes {@code userId × skillName}, and
 * admits the skill iff {@code hash mod 100 < percent}.
 *
 * <p>Only applies to agent-authored skills (via {@link AbstractAgentCreatedFilter}). Hand-
 * authored / hub-installed skills always pass through.
 *
 * <p>The {@code rampUpDays} parameter is currently a placeholder — accepted on construction
 * but the current implementation does not yet ramp; it always uses the configured static
 * percentage. A future version will interpolate using {@code SkillUsageRecord.promotedAt}.
 */
/**
 * 针对代理创建技能的百分比灰度放量过滤器。
 * 对 {@code userId × skillName} 进行哈希运算，当 {@code hash mod 100 < percent} 时放行该技能。
 *
 * <p>仅作用于代理创建技能（继承 {@link AbstractAgentCreatedFilter}）。人工编写、市场安装的技能将直接放行。
 *
 * <p>{@code rampUpDays} 参数目前为预留占位参数：构造方法可接收该参数，但当前实现暂不支持渐进放量，
 * 始终使用配置的固定百分比。后续版本将结合 {@code SkillUsageRecord.promotedAt} 实现梯度放量。
 */
public class CanaryFilter extends AbstractAgentCreatedFilter {

    private final int percent;
    private final int rampUpDays;

    public CanaryFilter(int percent, SkillUsageStore usageStore) {
        this(percent, 0, usageStore);
    }

    public CanaryFilter(int percent, int rampUpDays, SkillUsageStore usageStore) {
        super(usageStore);
        this.percent = clamp(percent);
        this.rampUpDays = Math.max(0, rampUpDays);
    }

    private static int clamp(int p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected boolean shouldPassForAgentCreated(
            AgentSkill skill, SkillUsageRecord rec, RuntimeContext ctx) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key =
                (ctx != null && ctx.getUserId() != null ? ctx.getUserId() : "anonymous")
                        + "|"
                        + skill.getName();
        int bucket = stableBucket(key, 100);
        return bucket < percent;
    }

    /** Stable hash → bucket. Uses Java's String.hashCode so behavior is deterministic. */
    /** 稳定哈希映射至桶。采用Java原生String.hashCode，行为具备确定性。 */
    static int stableBucket(String key, int modulus) {
        // Avoid signed-overflow surprises; use absolute value of a 32-bit rolling hash.
        int h = 0;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + (b & 0xFF);
        }
        if (h < 0) {
            h = -(h + 1); // Math.abs(Integer.MIN_VALUE) edge case
        }
        return h % modulus;
    }

    public int getRampUpDays() {
        return rampUpDays;
    }
}
