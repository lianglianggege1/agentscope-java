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
package io.agentscope.core.skill;

import java.nio.file.Path;

/**
 * Generates skill system prompts for agents to understand available skills.
 * 生成技能系统提示，帮助智能体了解可用技能。
 *
 * <p>This provider creates system prompts containing information about available skills
 * that the LLM can dynamically load and use.
 * 该提供商创建系统提示，其中包含有关可用技能的信息，LLM 可以动态加载和使用这些技能。
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * AgentSkillPromptProvider provider = new AgentSkillPromptProvider(registry);
 * String prompt = provider.getSkillSystemPrompt();
 * }</pre>
 */
public class AgentSkillPromptProvider {
    private final SkillRegistry skillRegistry;
    private final String instruction;
    private final String template;
    private boolean codeExecutionEnabled;
    private String uploadDir;
    private String codeExecutionInstruction;

    // ## 可用技能
    //
    // <usage>
    //
    // 技能提供专业能力和领域知识。当技能与当前任务匹配时，请使用它们。
    //
    // 如何使用技能：
    //
    // - 加载技能：load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
    //
    // - 技能将被激活，并加载包含详细说明的文档
    //
    // - 可以使用同一工具加载其他资源（脚本、资产、参考资料），但路径不同
    //
    // 路径信息：
    //
    // 加载技能时，响应将包含：
    //
    // - 所有技能资源的精确路径
    //
    // - 访问技能文件的代码示例
    //
    // - 该技能的特定使用说明
    //
    // 模板字段说明：
    //
    // - <name>：技能的显示名称
    //
    // - <description>：何时以及如何使用此技能
    //
    // - <skill-id>：load_skill_through_path 工具的唯一标识符
    //
    // </usage>
    //
    // <available_skills>
    public static final String DEFAULT_AGENT_SKILL_INSTRUCTION =
            """
            ## Available Skills

            <usage>
            Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.
            

            How to use skills:
            - Load skill: load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, assets, references) can be loaded using the same tool with different paths

            Example:
            1. User asks to analyze data → find a matching skill below (e.g. <skill-id>data-analysis_builtin</skill-id>)
            2. Load it: load_skill_through_path(skillId="data-analysis_builtin", path="SKILL.md")
            3. Follow the instructions returned by the skill

            Template fields explanation:
            - <name>: The skill's display name
            - <description>: When and how to use this skill
            - <skill-id>: Unique identifier for load_skill_through_path tool
            </usage>

            <available_skills>

            """;

    // Every %s placeholder in the template will be replaced with the uploadDir absolute path
    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute_shell_command tool. When a task can be accomplished by running\s
            a pre-deployed skill script, you MUST execute it yourself using execute_shell_command rather\s
            than describing or suggesting commands to the user.

            Skills root directory: %s
            Each skill's files are located under a subdirectory named by its <skill-id>:
              %s/<skill-id>/scripts/
              %s/<skill-id>/assets/

            Workflow:
            1. After loading a skill, use ls to explore its directory structure and discover available scripts/assets
            2. Once you find the right script, execute it immediately with its absolute path
            3. If execution fails, diagnose and retry — do not fall back to describing the command

            Rules:
            - Always use absolute paths when executing scripts
            - If a script exists for the task, run it directly — do not rewrite its logic inline
            - If asset/data files exist for the task, read them directly — do not recreate them

            Example:
              # Explore what scripts are available for a skill
              execute_shell_command(command="ls %s/data-analysis_builtin/scripts/")

              # Run an existing script with absolute path
              execute_shell_command(command="python3 %s/data-analysis_builtin/scripts/analyze.py")
            </code_execution>
            """;

    // skillName, skillDescription, skillId
    public static final String DEFAULT_AGENT_SKILL_TEMPLATE =
            """
            <skill>
            <name>%s</name>
            <description>%s</description>
            <skill-id>%s</skill-id>
            </skill>

            """;

    /**
     * Creates a skill prompt provider.
     * 创建技能提示提供程序。
     *
     * @param registry The skill registry containing registered skills
     */
    public AgentSkillPromptProvider(SkillRegistry registry) {
        this(registry, null, null);
    }

    /**
     * Creates a skill prompt provider with custom instruction and template.
     * 创建带有自定义指令和模板的技能提示提供程序。
     *
     * @param registry The skill registry containing registered skills
     *                 包含已注册技能的技能注册表
     * @param instruction Custom instruction header (null or blank uses default)
     *                    自定义指令头（空或空白使用默认值）
     * @param template Custom skill template (null or blank uses default)
     *                 自定义技能模板（空或留空使用默认值）
     */
    public AgentSkillPromptProvider(SkillRegistry registry, String instruction, String template) {
        this.skillRegistry = registry;
        this.instruction =
                instruction == null || instruction.isBlank()
                        ? DEFAULT_AGENT_SKILL_INSTRUCTION
                        : instruction;
        this.template =
                template == null || template.isBlank() ? DEFAULT_AGENT_SKILL_TEMPLATE : template;
    }

    /**
     * Gets the skill system prompt for the agent.
     * 获取智能体的技能系统提示。
     *
     * <p>Generates a system prompt containing all registered skills.
     * 生成包含所有已注册技能的系统提示。
     *
     * @return The skill system prompt, or empty string if no skills exist
     *         技能系统提示，如果没有技能则为空字符串。
     */
    public String getSkillSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // Check if there are any skills
        if (skillRegistry.getAllRegisteredSkills().isEmpty()) {
            return "";
        }

        // Add instruction header
        sb.append(instruction);

        // Add each skill
        for (RegisteredSkill registered : skillRegistry.getAllRegisteredSkills().values()) {
            AgentSkill skill = skillRegistry.getSkill(registered.getSkillId());
            sb.append(
                    String.format(
                            template, skill.getName(), skill.getDescription(), skill.getSkillId()));
        }

        // Close available_skills tag
        sb.append("</available_skills>");

        // Conditionally append code execution instructions
        if (codeExecutionEnabled && uploadDir != null) {
            String template =
                    codeExecutionInstruction != null
                            ? codeExecutionInstruction
                            : DEFAULT_CODE_EXECUTION_INSTRUCTION;
            sb.append(template.replace("%s", uploadDir));
        }

        // 按照默认的能生成完整的skills列表的系统提示词
        return sb.toString();
    }

    /**
     * Sets whether code execution instructions are included in the skill system prompt.
     *
     * @param codeExecutionEnabled {@code true} to append code execution instructions
     */
    public void setCodeExecutionEnable(boolean codeExecutionEnabled) {
        this.codeExecutionEnabled = codeExecutionEnabled;
    }

    /**
     * Sets the upload directory whose absolute path replaces every {@code %s}
     * placeholder in the code execution instruction template.
     *
     * @param uploadDir the upload directory path, or {@code null} to disable path substitution
     */
    public void setUploadDir(Path uploadDir) {
        this.uploadDir = uploadDir != null ? uploadDir.toAbsolutePath().toString() : null;
    }

    /**
     * Sets a custom code execution instruction template.
     *
     * <p>Every {@code %s} placeholder in the template will be replaced with
     * the {@code uploadDir} absolute path. Pass {@code null} or blank to
     * fall back to {@link #DEFAULT_CODE_EXECUTION_INSTRUCTION}.
     *
     * @param codeExecutionInstruction the custom template, or {@code null}/blank for default
     */
    public void setCodeExecutionInstruction(String codeExecutionInstruction) {
        this.codeExecutionInstruction =
                codeExecutionInstruction == null || codeExecutionInstruction.isBlank()
                        ? null
                        : codeExecutionInstruction;
    }
}
