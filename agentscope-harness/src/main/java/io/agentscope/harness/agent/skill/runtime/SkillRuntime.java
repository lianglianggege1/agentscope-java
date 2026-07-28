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

import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates the per-call {@link SkillCatalog}, the singleton {@link SkillLoadTool}, and the
 * {@link SkillPromptBuilder}. {@link io.agentscope.harness.agent.middleware.HarnessSkillMiddleware}
 * owns one instance and updates the catalog every {@code onSystemPrompt} pass.
 *
 * <p>The {@code load_skill_through_path} tool is registered on the toolkit exactly once, on the
 * first install. The tool instance holds the {@link AtomicReference} to the catalog, so swapping
 * the catalog from later rounds takes effect immediately without re-registering.
 */
/**
 * 聚合单次调用使用的 {@link SkillCatalog}、单例 {@link SkillLoadTool} 与 {@link SkillPromptBuilder}。
 * {@link io.agentscope.harness.agent.middleware.HarnessSkillMiddleware} 持有该类实例，并在每轮
 * {@code onSystemPrompt} 流程中更新技能目录。
 *
 * <p>{@code load_skill_through_path} 工具仅在首次安装时向工具集注册一次。工具实例内部持有指向
 * 技能目录的 {@link AtomicReference}，后续轮次替换目录可立即生效，无需重新注册工具。
 */
public final class SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private final AtomicReference<SkillCatalog> catalogRef =
            new AtomicReference<>(SkillCatalog.empty());
    private final AtomicBoolean toolInstalled = new AtomicBoolean(false);
    private final SkillLoadTool loadTool;
    private final SkillPromptBuilder promptBuilder;

    public SkillRuntime() {
        this(new SkillPromptBuilder());
    }

    public SkillRuntime(SkillPromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder != null ? promptBuilder : new SkillPromptBuilder();
        this.loadTool = new SkillLoadTool(catalogRef);
    }

    /** Snapshot accessor mainly for tests; not for runtime mutation. */
    /** 快照访问器，主要用于测试；运行时请勿修改。 */
    public SkillCatalog currentCatalog() {
        return catalogRef.get();
    }

    /** Underlying tool instance. Use {@link #install(SkillCatalog, Toolkit)} for normal flow. */
    /** 底层工具实例。常规流程请使用 {@link #install(SkillCatalog, Toolkit)}。 */
    public AgentTool loadTool() {
        return loadTool;
    }

    /**
     * Update the current catalog and ensure the load tool is registered on the toolkit (idempotent).
     *
     * @param catalog the new snapshot; pass {@link SkillCatalog#empty()} to clear visibility
     * @param toolkit the toolkit to install onto; may be {@code null} (then only catalog is updated)
     */
    /**
     * 更新当前技能目录，并确保加载工具已注册至工具集（幂等操作）。
     *
     * @param catalog 新快照；传入 {@link SkillCatalog#empty()} 可清空可见技能
     * @param toolkit 待安装工具的工具集；允许为 {@code null}（仅更新目录，不处理工具注册）
     */
    public void install(SkillCatalog catalog, Toolkit toolkit) {
        catalogRef.set(catalog != null ? catalog : SkillCatalog.empty());
        if (toolkit == null) {
            return;
        }
        if (toolInstalled.compareAndSet(false, true)) {
            try {
                AgentTool existing = toolkit.getTool(SkillLoadTool.TOOL_NAME);
                if (existing != null && existing != loadTool) {
                    toolkit.removeTool(SkillLoadTool.TOOL_NAME);
                }
                toolkit.registerAgentTool(loadTool);
            } catch (Exception e) {
                // Don't permanently latch installed=true if the registration failed: allow a
                // retry on the next call.
                toolInstalled.set(false);
                log.warn("Failed to register {}: {}", SkillLoadTool.TOOL_NAME, e.getMessage());
            }
        }
    }

    /** Renders the {@code <available_skills>} block + (when applicable) the code-execution prompt. */
    public String renderPrompt(SkillCatalog catalog, SkillFilter filter) {
        return promptBuilder.render(
                catalog != null ? catalog : SkillCatalog.empty(),
                filter != null ? filter : SkillFilter.all());
    }
}
