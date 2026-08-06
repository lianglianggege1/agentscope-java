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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * LLM-driven generator for {@link SubagentDeclaration} markdown specs.
 *
 * <p>Given a free-form description of an agent's purpose, prompts the supplied {@link Model} to
 * emit a markdown document with YAML frontmatter conforming to {@link AgentSpecLoader}'s schema.
 * The output is parseable round-trip via {@link AgentSpecLoader#parse}.
 *
 * <p>This is the Java analogue of opencode's {@code Agent.generate} flow ({@code
 * opencode/packages/opencode/src/agent/agent.ts}). Intended to be called from a CLI scaffolder or
 * from {@link io.agentscope.harness.agent.tool.AgentGenerateTool} during a live session.
 *
 * <p>Reuses the same {@code model.stream(messages, null, null).reduce(...)} pattern used by
 * {@link io.agentscope.harness.agent.memory.compaction.ConversationCompactor} for one-shot LLM
 * calls, so behaviour stays consistent with other internal LLM users in the harness.
 */
/**
 * 由大语言模型驱动、用于生成{@link SubagentDeclaration} Markdown 配置规范的生成器。
 *
 * <p>输入一段描述智能体用途的自由文本，调用传入的{@link Model}大模型，输出带YAML头部元数据的Markdown文档，格式遵循{@link AgentSpecLoader}定义的校验规范。
 * 生成结果可通过{@link AgentSpecLoader#parse}完整解析，支持生成、解析双向无损流转。
 *
 * <p>该模块等价于OpenCode项目中{@code Agent.generate}流程（对应源码文件 {@code opencode/packages/opencode/src/agent/agent.ts}）的Java实现。
 * 可在命令行脚手架工具中调用，也支持会话运行时由{@link io.agentscope.harness.agent.tool.AgentGenerateTool}触发执行。
 *
 * <p>底层复用与{@link io.agentscope.harness.agent.memory.compaction.ConversationCompactor}一致的调用模板：
 * {@code model.stream(messages, null, null).reduce(...)}，用于一次性大模型调用，保证框架内所有大模型调用逻辑行为统一。
 */
public final class SubagentSpecGenerator {

    /**
     * 生成子智能体配置规范所用的大模型提示词模板
     */
    /*private static final String PROMPT_TEMPLATE =
    """
    你正在为 agentscope-java 框架设计子智能体配置规范。

    用户对该智能体用途的描述：
    ---
    %s
    ---

    已存在的智能体ID（禁止复用，大小写不同也不行）：%s

    输出带有YAML头部元数据的Markdown文档，必须遵循以下结构规范：

    ---
    description: <一句话说明调度器何时将任务委派给该智能体>
    mode: subagent
    hidden: false
    # 可选模型超参（省略则继承父智能体配置）：
    # temperature: <0.0~2.0>
    # top_p: <0.0~1.0>
    # steps: <正整数，默认值10>
    # tools: [<继承的工具名称>, ...]   # 可选工具白名单；空列表代表继承全部工具
    ---

    <Markdown格式系统提示正文：说明智能体角色、能力、输出格式与约束要求。内容需精炼，该文本将直接作为子智能体的系统提示词。>

    输出约束规则：
    - 仅返回目标Markdown文档，禁止额外说明、代码块、注释文字。
    - 不要添加 `name:` 字段，调用方会根据文件名自动生成名称。
    - description 描述文字长度不超过200字符。
    - 未指定 workspace.path 时，正文内容不能为空。
    """;*/

    private static final String PROMPT_TEMPLATE =
            """
            You are designing a subagent specification for the agentscope-java framework.

            User description of the agent's purpose:
            ---
            %s
            ---

            Existing agent ids (DO NOT reuse, even with different casing): %s

            Produce a Markdown document with YAML frontmatter that matches this schema:

            ---
            description: <one sentence describing when the orchestrator should delegate to this agent>
            mode: subagent
            hidden: false
            # Optional model hyperparameters (omit to inherit parent):
            # temperature: <0.0..2.0>
            # top_p: <0.0..1.0>
            # steps: <positive integer, default 10>
            # tools: [<inherited tool name>, ...]   # optional allowlist; empty = inherit all
            ---

            <System-prompt body in Markdown: describe the agent's role, capabilities, output
             format, and constraints. Keep the body focused — this becomes the subagent's sysPrompt.>

            Output rules:
            - Return ONLY the markdown document. No prose, no code fences, no commentary.
            - Do NOT include a `name:` field — the caller derives the name from the filename.
            - The description must be at most 200 characters.
            - The body must not be empty when no workspace.path is specified.
            """;

    private final Model model;

    public SubagentSpecGenerator(Model model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Generates a markdown spec for a new subagent without validation. Prefer
     * {@link #generateAndValidate} unless you need to inspect or massage the markdown before it
     * goes through {@link AgentSpecLoader}.
     *
     * @param description free-form description of what the agent should do
     * @param existingIds names already registered; the model is told to avoid these
     * @return raw markdown returned by the model (trimmed; otherwise unprocessed)
     */
    /**
     * 生成新子智能体的Markdown配置文件，不做校验。
     * 若无特殊需求，优先使用 {@link #generateAndValidate}；仅当你需要在交给 {@link AgentSpecLoader} 解析前查看、预处理Markdown内容时，才使用本方法。
     *
     * @param description 对智能体功能的自由文本描述
     * @param existingIds 已注册的智能体标识列表，大模型会被要求避开这些标识
     * @return 大模型返回的原始Markdown内容（已去除首尾空白，无其他处理）
     */
    public Mono<String> generateMarkdown(String description, Collection<String> existingIds) {
        String existingList =
                existingIds == null || existingIds.isEmpty()
                        ? "(none)"
                        : String.join(", ", existingIds);
        String prompt = String.format(PROMPT_TEMPLATE, description, existingList);
        List<Msg> input =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(prompt).build())
                                .build());
        return model.stream(input, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, resp) -> {
                            if (resp.getContent() != null) {
                                for (ContentBlock block : resp.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .map(StringBuilder::toString)
                .map(String::strip);
    }

    /**
     * Generates a markdown spec and validates it by round-tripping through
     * {@link AgentSpecLoader#parse}. Emits an {@link IllegalStateException} if the LLM output is
     * malformed (missing frontmatter terminator, missing required {@code description}, etc.).
     *
     * @param description free-form description
     * @param agentName name to assign the parsed declaration (also reserved against
     *     {@code existingIds})
     * @param existingIds existing agent ids
     * @param mainWorkspace workspace root for resolving any relative {@code workspace.path}; may
     *     be {@code null}
     * @return tuple of the raw markdown and the parsed {@link SubagentDeclaration}
     */
    /**
     * 生成子智能体Markdown配置，并通过 {@link AgentSpecLoader#parse} 完成往返解析校验。
     * 若大模型输出格式异常（缺失YAML头部分隔符、必填description字段缺失等），抛出 {@link IllegalStateException}。
     *
     * @param description 智能体功能自由描述文本
     * @param agentName 分配给解析后配置的智能体名称，同时会纳入已存在ID列表做重名校验
     * @param existingIds 系统已存在的全部智能体标识
     * @param mainWorkspace 用于解析配置内 workspace.path 相对路径的工作区根目录，允许传 {@code null}
     * @return 二元组：原始Markdown文本 + 解析完成的 {@link SubagentDeclaration} 配置对象
     */
    public Mono<GeneratedSpec> generateAndValidate(
            String description,
            String agentName,
            Collection<String> existingIds,
            Path mainWorkspace) {
        return generateMarkdown(description, existingIds)
                .map(
                        md -> {
                            SubagentDeclaration decl =
                                    AgentSpecLoader.parse(md, agentName, mainWorkspace);
                            if (decl == null) {
                                throw new IllegalStateException(
                                        "LLM produced a malformed subagent spec for '"
                                                + agentName
                                                + "'; could not parse frontmatter");
                            }
                            return new GeneratedSpec(md, decl);
                        });
    }

    /** Pair of the raw markdown and the parsed {@link SubagentDeclaration}. */
    public record GeneratedSpec(String markdown, SubagentDeclaration declaration) {}
}
