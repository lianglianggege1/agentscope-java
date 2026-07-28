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
package io.agentscope.harness.agent.skill;

import io.agentscope.core.agent.RuntimeContext;

/**
 * Optional marker for {@link io.agentscope.core.skill.repository.AgentSkillRepository}
 * implementations that can supply a {@link SkillResources} accessor in addition to the
 * in-memory {@code AgentSkill.resources} map.
 *
 * <p>Detected via {@code instanceof} by the harness skill runtime: if a repository implements
 * this interface, the runtime will request a {@link SkillResources} per skill and consult it
 * as a fallback when {@code load_skill_through_path} misses the in-memory map. This is what
 * lets {@link WorkspaceSkillRepository} keep skills lazy on disk (or in sandbox) without
 * preloading every byte at registration time.
 *
 * <p>Repositories that preload all resources into {@code AgentSkill.resources} (e.g. core's
 * {@code FileSystemSkillRepository}, {@code ClasspathSkillRepository}, and most third-party
 * marketplace extensions) do not need to implement this — the in-memory map already covers
 * every path.
 */
/**
 * 供 {@link io.agentscope.core.skill.repository.AgentSkillRepository} 实现类选用的标记接口。
 * 实现该接口后，除内存中的 {@code AgentSkill.resources} 映射表外，还可对外提供 {@link SkillResources} 访问器。
 *
 * <p>技能运行时框架通过 {@code instanceof} 检测此接口：若仓库实现该接口，运行时会按技能维度获取 {@link SkillResources}。
 * 当 {@code load_skill_through_path} 在内存映射表中查找失败时，将以此作为兜底查询方案。
 * 依托该机制，{@link WorkspaceSkillRepository} 能够将技能惰性存储在磁盘（或沙箱）中，无需在注册阶段预加载全部字节内容。
 *
 * <p>对于将所有资源预加载至 {@code AgentSkill.resources} 的仓库（例如内核中的
 * {@code FileSystemSkillRepository}、{@code ClasspathSkillRepository} 以及绝大多数第三方市场扩展实现），
 * 无需实现此接口，内存映射表已可覆盖全部资源路径。
 */
public interface LazyResourceCapable {

    /**
     * Returns a lazy resource accessor for the named skill.
     *
     * <p>The returned accessor MUST capture or honor the given {@code ctx} so per-user
     * namespacing remains correct across calls.
     *
     * @param skillName the skill's {@code name} (not {@code skillId})
     * @param ctx       current runtime context; never {@code null} (callers pass
     *                  {@link RuntimeContext#empty()} when no context is available)
     * @return accessor for that skill's resource tree, never {@code null}
     *         (return {@link SkillResources#empty()} if the skill does not belong to this
     *         repository)
     */
    /**
     * 返回指定名称技能的惰性资源访问器。
     *
     * <p>返回的访问器必须捕获并遵循传入的 {@code ctx}，保证多次调用时按用户隔离的命名空间保持正确。
     *
     * @param skillName 技能名称 {@code name}（并非 {@code skillId}）
     * @param ctx       当前运行时上下文；永远不为 {@code null}（无可用上下文时调用方传入
     *                  {@link RuntimeContext#empty()}）
     * @return 该技能资源树的访问器，永不为 {@code null}；
     *         若技能不属于当前仓库，则返回 {@link SkillResources#empty()}
     */
    SkillResources resourcesFor(String skillName, RuntimeContext ctx);
}
