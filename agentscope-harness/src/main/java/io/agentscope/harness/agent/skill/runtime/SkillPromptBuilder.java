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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders the harness {@code <available_skills>} system-prompt block from a {@link SkillCatalog}.
 *
 * <p>Differences from the legacy {@code AgentSkillPromptProvider}:
 *
 * <ul>
 *   <li>Each {@code <skill>} optionally carries a {@code <files-root>} child giving the
 *       absolute path to that skill's files. The middleware decides whether to populate this
 *       based on shell availability and skill source.
 *   <li>The {@code <code_execution>} section is emitted only when at least one entry in the
 *       catalog has a non-null {@code filesRoot} (i.e. shell is available and at least one
 *       skill's files are reachable). The new instruction tells the LLM to use each skill's
 *       {@code <files-root>} rather than a single hardcoded root.
 *   <li>Resource fallback for non-SKILL.md paths is implemented by
 *       {@link SkillLoadTool}; the prompt does not need to mention it.
 * </ul>
 */
/**
 * 根据 {@link SkillCatalog} 渲染框架 {@code <available_skills>} 系统提示词区块。
 *
 * <p>与旧版 {@code AgentSkillPromptProvider} 的差异：
 *
 * <ul>
 *   <li>每个 {@code <skill>} 可选择携带 {@code <files-root>} 子节点，提供该技能文件的绝对路径。
 *       中间件根据 Shell 是否可用、技能来源决定是否填充此字段。
 *   <li>仅当目录中至少存在一条记录拥有非空 {@code filesRoot} 时，才输出 {@code <code_execution>}
 *       区块（即可用Shell，且至少有一个技能文件可访问）。新指令告知大模型使用各技能自身的
 *       {@code <files-root>}，而非单一硬编码根路径。
 *   <li>非 SKILL.md 路径的资源降级读取逻辑由 {@link SkillLoadTool} 实现；提示词无需体现该逻辑。
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class SkillPromptBuilder {

    private static final String INDENT = "  ";
    private static final Pattern XML_TAG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    /*
    public static final String DEFAULT_HEADER =
            """
            ## 可用技能

            <usage>
            技能提供专用能力与领域知识，任务匹配时请选用对应技能。

            技能使用方式：
            - 加载技能：load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - 技能将被激活并加载文档，内含详细指引
            - 可使用同一工具搭配其他路径加载额外资源（脚本、静态资源、参考资料等）

            使用示例：
            1. 用户提出数据分析需求 → 在下方找到匹配技能（例如 <skill-id>data-analysis_workspace</skill-id>）
            2. 执行加载：load_skill_through_path(skillId="data-analysis_workspace", path="SKILL.md")
            3. 按照技能返回的指引执行操作

            每条 <skill> 节点下以XML形式展示元数据：
            - 标量元数据直接作为子节点
            - 嵌套Map转换为嵌套XML节点
            - 列表转换为多条 <item> 节点
            - 始终附带 <skill-id>，用于工具加载
            - 若存在 <files-root>，代表执行该技能脚本所需的Shell绝对路径
            </usage>

            <available_skills>

            """;
     */
    public static final String DEFAULT_HEADER =
            """
            ## Available Skills

            <usage>
            Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.

            How to use skills:
            - Load skill: load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, assets, references) can be loaded with the same tool and other paths

            Example:
            1. User asks to analyze data -> find a matching skill below (e.g. <skill-id>data-analysis_workspace</skill-id>)
            2. Load it: load_skill_through_path(skillId="data-analysis_workspace", path="SKILL.md")
            3. Follow the instructions returned by the skill

            Metadata is rendered as XML under each <skill> element:
            - scalar metadata becomes a simple child element
            - nested maps become nested XML elements
            - lists become repeated <item> elements
            - <skill-id> is always appended for tool loading
            - <files-root>, when present, gives the absolute path for shell-executing this skill's scripts
            </usage>

            <available_skills>

            """;

    /*
    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
            """

            ## 代码执行

            <code_execution>
            你可以使用 execute_shell_command 工具。<available_skills> 内每项技能均包含 <files-root>，
            代表该技能文件所在的绝对路径。

            执行流程：
            1. 加载技能后，查看 <available_skills> 中该技能对应的 <files-root>
            2. 列出文件：    ls <files-root>/
            3. 运行脚本：     python3 <files-root>/scripts/<script-name>
            4. 必须基于 <files-root> 拼接绝对路径，禁止自行编造路径
            5. 若任务已有配套脚本，直接运行脚本，不要在代码内重写脚本逻辑
            </code_execution>
            """;
     */
    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute_shell_command tool. Each skill in <available_skills>
            includes a <files-root> element giving the absolute path to that skill's files.

            Workflow:
            1. After loading a skill, look at its <files-root> in <available_skills>
            2. List its files:    ls <files-root>/
            3. Run scripts:       python3 <files-root>/scripts/<script-name>
            4. Always use absolute paths derived from <files-root>; never invent paths
            5. If a script exists for the task, run it directly — do not rewrite its logic inline
            </code_execution>
            """;

    private final String header;
    private final String codeExecutionInstruction;
    private final boolean exposeAllMetadata;

    public SkillPromptBuilder() {
        this(null, null, true);
    }

    public SkillPromptBuilder(
            String header, String codeExecutionInstruction, boolean exposeAllMetadata) {
        this.header = (header == null || header.isBlank()) ? DEFAULT_HEADER : header;
        this.codeExecutionInstruction =
                (codeExecutionInstruction == null || codeExecutionInstruction.isBlank())
                        ? DEFAULT_CODE_EXECUTION_INSTRUCTION
                        : codeExecutionInstruction;
        this.exposeAllMetadata = exposeAllMetadata;
    }

    /**
     * Render the prompt block. Returns an empty string when no skills pass the filter, so the
     * caller can no-op concatenation.
     *
     * @param catalog the per-call snapshot (non-null)
     * @param filter  visibility filter applied per skillId (non-null; use {@link SkillFilter#all()})
     * @return prompt text, or empty string when nothing is visible
     */
    /**
     * 渲染提示词区块。若无技能通过过滤条件则返回空字符串，调用方可直接跳过拼接操作。
     *
     * @param catalog 单次调用的技能快照（不可为 null）
     * @param filter  按 skillId 生效的可见性过滤器（不可为 null；如需不过滤可使用 {@link SkillFilter#all()}）
     * @return 提示文本；无可见技能时返回空字符串
     */
    public String render(SkillCatalog catalog, SkillFilter filter) {
        if (catalog == null || catalog.isEmpty()) {
            return "";
        }
        SkillFilter effective = filter != null ? filter : SkillFilter.all();

        StringBuilder sb = new StringBuilder();
        boolean any = false;
        boolean anyWithFilesRoot = false;

        for (HarnessSkillEntry entry : catalog.all()) {
            String skillName = entry.skill().getName();
            if (!effective.isAllowed(skillName)) {
                continue;
            }
            if (!any) {
                sb.append(header);
                any = true;
            }
            appendSkill(sb, entry);
            if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
                anyWithFilesRoot = true;
            }
        }

        if (!any) {
            return "";
        }
        sb.append("</available_skills>");

        // Only emit the code-execution section when at least one visible skill is shell-reachable.
        // No filesRoot anywhere => no shell tool registered (or all skills are unreachable),
        // so the instruction would mislead the LLM.
        if (anyWithFilesRoot) {
            sb.append(codeExecutionInstruction);
        }

        return sb.toString();
    }

    /** Convenience overload used by the middleware when no extra filter is active. */
    public String render(SkillCatalog catalog) {
        return render(catalog, SkillFilter.all());
    }

    private void appendSkill(StringBuilder sb, HarnessSkillEntry entry) {
        AgentSkill skill = entry.skill();
        sb.append("<skill>\n");
        for (Map.Entry<String, Object> e : metadataView(skill).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            appendXmlNode(sb, e.getKey(), e.getValue(), 1);
        }
        appendXmlNode(sb, "skill-id", skill.getSkillId(), 1);
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            appendXmlNode(sb, "files-root", entry.filesRoot(), 1);
        }
        sb.append("</skill>\n\n");
    }

    private Map<String, Object> metadataView(AgentSkill skill) {
        if (exposeAllMetadata) {
            return skill.getMetadata();
        }
        java.util.LinkedHashMap<String, Object> trimmed = new java.util.LinkedHashMap<>();
        trimmed.put("name", skill.getName());
        trimmed.put("description", skill.getDescription());
        return trimmed;
    }

    private void appendXmlNode(StringBuilder sb, String key, Object value, int indentLevel) {
        if (value == null) {
            return;
        }
        String indent = INDENT.repeat(indentLevel);
        boolean validTag = isValidXmlTagName(key);
        String openTag = validTag ? "<" + key + ">" : "<entry key=\"" + escapeXml(key) + "\">";
        String closeTag = validTag ? "</" + key + ">" : "</entry>";

        if (isScalarValue(value)) {
            sb.append(indent)
                    .append(openTag)
                    .append(escapeXml(String.valueOf(value)))
                    .append(closeTag)
                    .append("\n");
            return;
        }

        sb.append(indent).append(openTag).append("\n");
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> e : mapValue.entrySet()) {
                appendXmlNode(sb, String.valueOf(e.getKey()), e.getValue(), indentLevel + 1);
            }
        } else if (value instanceof Collection<?> collValue) {
            for (Object item : collValue) {
                appendXmlNode(sb, "item", item, indentLevel + 1);
            }
        } else {
            sb.append(INDENT.repeat(indentLevel + 1))
                    .append(escapeXml(String.valueOf(value)))
                    .append("\n");
        }
        sb.append(indent).append(closeTag).append("\n");
    }

    private boolean isScalarValue(Object value) {
        return !(value instanceof Map<?, ?>) && !(value instanceof Collection<?>);
    }

    private boolean isValidXmlTagName(String value) {
        return value != null && XML_TAG_NAME.matcher(value).matches();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
