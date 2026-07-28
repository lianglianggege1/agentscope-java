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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool that generates a new subagent spec from a natural-language description and either persists
 * it to {@code subagents/<name>.md} (where {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware}
 * will pick it up on the next reasoning step) or returns the markdown for human review.
 *
 * <p>Wraps {@link SubagentSpecGenerator}; takes care of name validation, collision detection
 * against the live {@link DefaultAgentManager} registry, and dry-run handling.
 *
 * <p>Not registered by {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware} by
 * default; callers must opt in via {@code SubagentsMiddleware#enableAgentGenerateTool(...)}.
 */
/**
 * 可供智能体调用的工具，依据自然语言描述生成子智能体配置文档。
 * 可将配置持久化保存至 {@code subagents/<name>.md}，
 * 下一轮推理时 {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware} 会自动加载；
 * 也可直接返回Markdown文本供人工审核。
 *
 * <p>内部封装 {@link SubagentSpecGenerator}，负责名称合法性校验、
 * 与运行态 {@link DefaultAgentManager} 注册表进行重名冲突检测，同时支持试运行模式。
 *
 * <p>默认情况下不会由 {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware} 注册；
 * 需要使用者主动调用 {@code SubagentsMiddleware#enableAgentGenerateTool(...)} 开启该工具。
 */
public class AgentGenerateTool {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerateTool.class);

    /** Subagent ids must be kebab-case identifiers; matches {@code AgentSpecLoader} expectations. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final SubagentSpecGenerator generator;
    private final DefaultAgentManager agentManager;
    private final AbstractFilesystem filesystem;

    public AgentGenerateTool(
            SubagentSpecGenerator generator,
            DefaultAgentManager agentManager,
            AbstractFilesystem filesystem) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.filesystem = filesystem;
    }

    /*
    @Tool(
            name = "agent_generate",
            description =
                    "基于自然语言描述生成全新子智能体配置。"
                        + "按照子智能体声明规范校验大模型输出内容，并写入 subagents/<name>.md，"
                        + "下一轮推理时 DynamicSubagentsMiddleware 将自动加载该配置。"
                        + "设置 dry_run=true 可预览生成的 Markdown，不执行持久化写入。")
    public Mono<String> agentGenerate(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "name",
                            description =
                                    "短横线命名格式的子智能体标识（例如 code-reviewer），"
                                            + "不可与已有智能体重名。")
                    String name,
            @ToolParam(
                            name = "description",
                            description =
                                    "子智能体职责说明：包含目标、预期输出以及适用场景。")
                    String description,
            @ToolParam(
                            name = "dry_run",
                            description =
                                    "设为 true 时仅返回生成的 Markdown 文本，不持久化保存（默认 false）。",
                            required = false)
                    Boolean dryRun) {}
     */
    @Tool(
            name = "agent_generate",
            description =
                    "Generate a new subagent spec from a natural-language description. Validates"
                        + " the LLM output against the SubagentDeclaration schema and writes it to"
                        + " subagents/<name>.md so DynamicSubagentsMiddleware picks it up on the"
                        + " next reasoning step. Use dry_run=true to preview the markdown without"
                        + " writing.")
    public Mono<String> agentGenerate(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "name",
                            description =
                                    "Kebab-case subagent id (e.g. code-reviewer). Must not"
                                            + " collide with an existing agent.")
                    String name,
            @ToolParam(
                            name = "description",
                            description =
                                    "What the agent should do — its purpose, expected output, and"
                                            + " when to use it.")
                    String description,
            @ToolParam(
                            name = "dry_run",
                            description =
                                    "If true, return the generated markdown without persisting it"
                                            + " (default false).",
                            required = false)
                    Boolean dryRun) {

        if (name == null || name.isBlank()) {
            return Mono.just("Error: name is required");
        }
        String trimmed = name.trim();
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            return Mono.just(
                    "Error: name '"
                            + trimmed
                            + "' is not a valid kebab-case identifier (lowercase, digits, '-')");
        }
        if (description == null || description.isBlank()) {
            return Mono.just("Error: description is required");
        }
        if (agentManager.hasAgent(trimmed)) {
            return Mono.just("Error: agent '" + trimmed + "' already exists");
        }
        boolean dry = Boolean.TRUE.equals(dryRun);

        return generator
                .generateAndValidate(
                        description,
                        trimmed,
                        agentManager.getAgentFactories().keySet(),
                        agentManager.getWorkspaceManager() != null
                                ? agentManager.getWorkspaceManager().getWorkspace()
                                : null)
                .map(
                        spec -> {
                            if (dry) {
                                return "dry_run=true; spec below was NOT persisted\n\n"
                                        + spec.markdown();
                            }
                            if (filesystem == null) {
                                return "Error: no filesystem configured for AgentGenerateTool —"
                                        + " cannot persist spec. Use dry_run=true to preview.";
                            }
                            String path = "subagents/" + trimmed + ".md";
                            WriteResult wr =
                                    filesystem.write(runtimeContext, path, spec.markdown());
                            if (!wr.isSuccess()) {
                                log.warn(
                                        "Failed to write generated subagent spec to {}: {}",
                                        path,
                                        wr.error());
                                return "Error: write failed for "
                                        + path
                                        + ": "
                                        + wr.error()
                                        + "\n\nGenerated spec:\n"
                                        + spec.markdown();
                            }
                            return "Wrote subagent spec to " + wr.path() + "\n\n" + spec.markdown();
                        })
                .onErrorResume(
                        e -> {
                            String msg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            log.warn("agent_generate failed for name={}: {}", trimmed, msg);
                            return Mono.just("Error: " + msg);
                        });
    }
}
