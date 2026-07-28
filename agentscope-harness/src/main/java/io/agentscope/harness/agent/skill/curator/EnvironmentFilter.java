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
 * Filter that admits a skill only when its sidecar's {@code environments} list contains the
 * configured environment ({@code prod}, {@code staging}, …). Unlike the canary / allow-list
 * filters, this one applies to <em>every</em> skill — including pre-existing ones — so the
 * deployment-environment story is consistent across both agent-authored and hand-crafted
 * skills.
 *
 * <p>For hand-crafted skills that have no sidecar entry, this filter passes them through
 * unconditionally. The expectation is that authoritative ops will set
 * {@code environments: [prod]} on the sidecar entry by hand once they've been audited.
 */
/**
 * 过滤器：仅当技能对应的Sidecar配置中 {@code environments} 列表包含当前环境（{@code prod}、{@code staging} 等）时放行该技能。
 * 不同于灰度过滤器、白名单过滤器，本过滤器作用于**所有技能**（包含预置技能），保证代理创建技能与人工定制技能遵循统一的部署环境管控逻辑。
 *
 * <p>无Sidecar配置项的人工定制技能会无条件放行。
 * 规范要求：人工审核完成后，运维人员需手动在Sidecar条目配置 {@code environments: [prod]}。
 */
@SuppressWarnings("deprecation")
public class EnvironmentFilter implements SkillVisibilityFilter {

    private final String env;
    private final SkillUsageStore usageStore;

    public EnvironmentFilter(String env, SkillUsageStore usageStore) {
        this.env = env != null ? env : "prod";
        this.usageStore = usageStore;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty() || usageStore == null) {
            return all == null ? List.of() : all;
        }
        List<AgentSkill> out = new ArrayList<>(all.size());
        for (AgentSkill skill : all) {
            if (skill == null || skill.getName() == null) {
                continue;
            }
            SkillUsageRecord rec = usageStore.get(skill.getName()).orElse(null);
            if (rec == null) {
                // No sidecar entry → external / pre-existing skill; let through.
                out.add(skill);
                continue;
            }
            List<String> envs = rec.environments();
            if (envs == null || envs.isEmpty() || envs.contains(env)) {
                out.add(skill);
            }
            // else filter out (e.g. environments=[draft] never matches env=prod)
        }
        return out;
    }
}
