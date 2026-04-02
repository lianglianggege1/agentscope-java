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

import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ExtendedModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.CommandValidator;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkillBox implements StateModule {
    private static final Logger logger = LoggerFactory.getLogger(SkillBox.class);
    private static final String BASE64_PREFIX = "base64:";

    // 注册技能
    private final SkillRegistry skillRegistry = new SkillRegistry();
    // 技能提示词提供者
    private final AgentSkillPromptProvider skillPromptProvider;
    // 技能工具包工厂
    private final SkillToolFactory skillToolFactory;
    // 技能工具包
    private Toolkit toolkit;
    // 技能工作目录
    private Path workDir;
    // 上传目录
    private Path uploadDir;
    // 文件过滤器
    private SkillFileFilter fileFilter;
    // 自动上传技能
    private boolean autoUploadSkill = true;

    /**
     * Creates a SkillBox without a toolkit.
     * 创建一个没有工具包的技能箱。
     *
     * <p>This constructor will be removed in the next release. A SkillBox must hold a
     * {@link Toolkit} to operate correctly. Relying on automatic toolkit assignment makes
     * behavior less explicit and harder to reason about.
     * 此构造函数将在下一个版本中移除。
     * SkillBox 必须包含 Toolkit 才能正常运行。
     * 依赖自动分配 Toolkit 会使行为变得不明确，也更难理解。
     */
    @Deprecated
    public SkillBox() {
        this(null, null, null);
    }

    public SkillBox(Toolkit toolkit) {
        this(toolkit, null, null);
    }

    /**
     * Creates a SkillBox with custom skill prompt instruction and template.
     * 创建带有自定义技能提示说明和模板的技能框。
     *
     * @param instruction Custom instruction header (null or blank uses default)
     *                    自定义指令头（空或空白使用默认值）
     * @param template Custom skill template (null or blank uses default)
     *                 自定义技能模板（空或留空使用默认值）
     */
    public SkillBox(String instruction, String template) {
        this(null, instruction, template);
    }

    /**
     * Creates a SkillBox with a toolkit and custom skill prompt instruction and template.
     * 创建一个包含工具包、自定义技能提示说明和模板的技能盒。
     *
     * @param toolkit The toolkit to bind 关联工具包
     * @param instruction Custom instruction header (null or blank uses default) 自定义指令头（空或空白使用默认值）
     * @param template Custom skill template (null or blank uses default) 自定义技能模板（空或留空使用默认值）
     */
    public SkillBox(Toolkit toolkit, String instruction, String template) {
        this.skillPromptProvider =
                new AgentSkillPromptProvider(skillRegistry, instruction, template);
        this.skillToolFactory = new SkillToolFactory(skillRegistry, toolkit);
        this.toolkit = toolkit;
    }

    /**
     * Gets the skill system prompt for registered skills.
     * 获取已注册技能的技能系统提示。
     *
     * <p>This prompt provides information about available skills that the agent
     * can dynamically load and use during execution.
     * 此提示提供有关代理在执行过程中可以动态加载和使用的可用技能的信息。
     *
     * @return The skill system prompt, or empty string if no skills exist
     */
    public String getSkillPrompt() {
        return skillPromptProvider.getSkillSystemPrompt();
    }

    /**
     * Create a fluent builder for registering skills with optional configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Register skill
     * skillBox.registration()
     *     .skill(skill)
     *     .apply();
     *
     * // Register skill with tool
     * skillBox.registration()
     *     .skill(skill) // same reference skill will not be registered again
     *     .tool(toolObject)
     *     .apply();
     * }</pre>
     *
     * @return A new ToolRegistration builder
     */
    public SkillRegistration registration() {
        return new SkillRegistration(this);
    }

    /**
     * Binds a toolkit to the skill box.
     * 绑定工具包到技能箱。
     *
     * <p>
     * This method binds the toolkit to both the skill box and its internal skill
     * tool factory.
     * Since ReActAgent uses a deep copy of the Toolkit, rebinding is necessary to
     * ensure the
     * skill tool factory references the correct toolkit instance.
     * 此方法将工具包绑定到技能框及其内部技能工具工厂。
     * 由于 ReActAgent 使用的是工具包的深拷贝，
     * 因此需要重新绑定以确保技能工具工厂引用正确的工具包实例。
     *
     * @param toolkit The toolkit to bind to the skill box
     * @throws IllegalArgumentException if the toolkit is null
     */
    public void bindToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }
        this.toolkit = toolkit;
        // ReActAgent uses a deep copy of Toolkit, so we need to rebind it here
        this.skillToolFactory.bindToolkit(toolkit);
    }

    /**
     * Synchronize tool group states based on skill activation status with a specific toolkit.
     * 根据特定工具包的技能激活状态，同步工具组状态。
     *
     * <p>Updates the toolkit's tool groups to reflect the current activation state of skills.
     * Active skills will have their tool groups enabled, inactive skills will have their
     * tool groups disabled.
     * 更新工具包中的工具组，以反映技能的当前激活状态。激活的技能的工具组将被启用，未激活的技能的工具组将被禁用。
     */
    public void syncToolGroupStates() {
        if (toolkit == null) {
            return;
        }
        // 禁用列表
        List<String> inactiveSkillToolGroups = new ArrayList<>();
        // 激活列表
        List<String> activeSkillToolGroups = new ArrayList<>();

        // Dynamically update active/inactive tool groups based on skills' states
        // 根据技能状态动态更新激活/非激活工具组
        for (RegisteredSkill registeredSkill : skillRegistry.getAllRegisteredSkills().values()) {
            if (toolkit.getToolGroup(registeredSkill.getToolsGroupName()) == null) {
                continue; // Skip uncreated skill tools
            }
            if (!registeredSkill.isActive()) {
                inactiveSkillToolGroups.add(registeredSkill.getToolsGroupName());
                continue; // Skip inactive skill's tools, its tools won't be included
            }
            activeSkillToolGroups.add(registeredSkill.getToolsGroupName());
        }
        toolkit.updateToolGroups(inactiveSkillToolGroups, false);
        toolkit.updateToolGroups(activeSkillToolGroups, true);
        logger.debug(
                "Active Skill Tool Groups updated {}, inactive Skill Tool Groups updated {}",
                activeSkillToolGroups,
                inactiveSkillToolGroups);
    }

    /**
     * Where the skill is active. If a skill is active, this means skill is being using by LLM.
     * LLM use load tool activate the skill.
     * 技能处于激活状态。
     * 如果技能处于激活状态，则表示 LLM 可以使用该技能。
     * LLM 使用加载工具激活该技能。
     * @param skillId
     * @return true if the skill is active
     */
    public boolean isSkillActive(String skillId) {
        RegisteredSkill registeredSkill = skillRegistry.getRegisteredSkill(skillId);
        if (registeredSkill == null) {
            return false;
        }
        return registeredSkill.isActive();
    }

    // ==================== Skill Management ====================

    /**
     * Registers an agent skill.
     * 注册一个代理技能
     *
     * <p>Skills can be dynamically loaded by agents using skill access tools.
     * When a skill is loaded, its associated tools become available to the agent.
     * 智能体可以使用技能访问工具动态加载技能。
     * 技能加载完成后，其关联的工具即可供智能体使用。
     *
     * <p><b>Version Management:</b>
     *       版本管理：
     * <ul>
     *   <li>First registration: Creates initial version of the skill</li>
     *       首次注册：创建技能的初始版本
     *   <li>Subsequent registrations with same skill object (by reference): No new version created</li>
     *       后续使用同一技能对象（通过引用）进行的注册：未创建新版本
     *   <li>Registrations with different skill object: Creates new version (snapshot)</li>
     *       注册不同技能对象：创建新版本（快照）
     * </ul>
     *
     * <p><b>Usage example:</b>
     *       使用示例：
     * <pre>{@code
     * AgentSkill mySkill = new AgentSkill("my_skill", "Description", "Content", null);
     *
     * skillBox.registerSkill(mySkill);
     * skillBox.registerSkill(my_skill); // do nothing
     * }</pre>
     *
     * @param skill The agent skill to register
     * @throws IllegalArgumentException if skill is null
     */
    public void registerSkill(AgentSkill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("AgentSkill cannot be null");
        }

        String skillId = skill.getSkillId();

        // Create registered wrapper
        RegisteredSkill registered = new RegisteredSkill(skillId);

        // Register in skillRegistry
        skillRegistry.registerSkill(skillId, skill, registered);

        logger.info("Registered skill '{}'", skillId);
    }

    /**
     * Gets all skill IDs.
     * @return All skill IDs
     */
    public Set<String> getAllSkillIds() {
        return skillRegistry.getSkillIds();
    }

    /**
     * Gets a skill by ID (latest version).
     *
     * @param skillId The skill ID
     * @return The skill instance, or null if not found
     * @throws IllegalArgumentException if skillId is null
     */
    public AgentSkill getSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.getSkill(skillId);
    }

    /**
     * Removes a skill completely.
     *
     * @param skillId The skill ID
     * @throws IllegalArgumentException if skillId is null
     */
    public void removeSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        skillRegistry.removeSkill(skillId);
        logger.info("Removed skill '{}'", skillId);
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillId The skill ID
     * @return true if the skill exists, false otherwise
     * @throws IllegalArgumentException if skillId is null
     */
    public boolean exists(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.exists(skillId);
    }

    /**
     * Deactivates all skills.
     * 停用所有技能。
     *
     * <p>This method sets all registered skills to inactive state, which means their associated
     * tool groups will not be available to the agent until the skills are accessed again
     * via skill access tools.
     * 此方法会将所有已注册的技能设置为非活动状态，
     * 这意味着在通过技能访问工具再次访问这些技能之前，
     * 代理将无法使用与其关联的工具组。
     *
     * <p>This is typically called at the start of each agent call to ensure a clean state.
     * 通常在每次代理调用开始时调用此函数，以确保系统处于干净状态。
     */
    public void deactivateAllSkills() {
        skillRegistry.setAllSkillsActive(false);
        logger.debug("Deactivated all skills");
    }

    /**
     * Fluent builder for registering skills with optional configuration.
     * 用于注册技能的流畅构建器，支持可选配置。
     *
     * <p>This builder provides a clear, type-safe way to register skills with various options
     * without method proliferation.
     * 该构建器提供了一种清晰、类型安全的方式来注册具有各种选项的技能，
     * 而不会出现方法过多的情况。
     */
    public static class SkillRegistration {
        // 技能包
        private final SkillBox skillBox;
        // 工具包
        private Toolkit toolkit;
        // 技能
        private AgentSkill skill;
        // 工具对象
        private Object toolObject;
        // 智能体工具
        private AgentTool agentTool;
        // Mcp客户端包装
        private McpClientWrapper mcpClientWrapper;
        // 子代理提供者
        private SubAgentProvider<?> subAgentProvider;
        // 子代理配置
        private SubAgentConfig subAgentConfig;
        // 技能参数
        private Map<String, Map<String, Object>> presetParameters;
        // 技能模型
        private ExtendedModel extendedModel;
        // 启用工具
        private List<String> enableTools;
        // 禁用工具
        private List<String> disableTools;

        public SkillRegistration(SkillBox skillBox) {
            this.skillBox = skillBox;
        }

        /**
         * Set the skill to register.
         *
         * @param skill The skill to register
         * @return This builder for chaining
         */
        public SkillRegistration skill(AgentSkill skill) {
            this.skill = skill;
            return this;
        }

        public SkillRegistration toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Set the tool object to register (scans for @Tool methods).
         *
         * @param toolObject Object containing @Tool annotated methods
         * @return This builder for chaining
         */
        public SkillRegistration tool(Object toolObject) {
            this.toolObject = toolObject;
            return this;
        }

        /**
         * Set the AgentTool instance to register.
         *
         * @param agentTool The AgentTool instance
         * @return This builder for chaining
         */
        public SkillRegistration agentTool(AgentTool agentTool) {
            this.agentTool = agentTool;
            return this;
        }

        /**
         * Set the MCP client to register.
         *
         * @param mcpClientWrapper The MCP client wrapper
         * @return This builder for chaining
         */
        public SkillRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            this.mcpClientWrapper = mcpClientWrapper;
            return this;
        }

        /**
         * Register a sub-agent as a tool with default configuration.
         * 将子代理注册为具有默认配置的工具。
         *
         * <p>The tool name and description are derived from the agent's properties. Uses a single
         * "task" string parameter by default.
         * 工具名称和描述源自代理的属性。默认情况下使用单个“任务”字符串参数。
         *
         * <p>Example:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(() -> ReActAgent.builder()
         *         .name("ResearchAgent")
         *         .model(model)
         *         .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each invocation)
         * @return This builder for chaining
         */
        public SkillRegistration subAgent(SubAgentProvider<?> provider) {
            return subAgent(provider, null);
        }

        /**
         * Register a sub-agent as a tool with custom configuration.
         * 将子代理注册为具有自定义配置的工具。
         *
         * <p>Sub-agents support multi-turn conversation with session-based state management. The
         * tool exposes two parameters: {@code message} (required) and {@code session_id} (optional,
         * for continuing existing conversations).
         * 子代理支持基于会话的状态管理的多轮对话。
         * 该工具公开两个参数：message（必需）和 session_id（可选，用于继续现有对话）。
         *
         * <p>Example with custom tool name and description:
         *    自定义工具名称和描述的示例：
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Expert").model(model).build(),
         *         SubAgentConfig.builder()
         *             .toolName("ask_expert")
         *             .description("Ask the domain expert a question")
         *             .build())
         *     .apply();
         * }</pre>
         *
         * <p>Example with persistent session for cross-process conversations:
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Assistant").model(model).build(),
         *         SubAgentConfig.builder()
         *             .session(new JsonSession(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each session)
         *                 用于创建代理实例的工厂（每次会话都会调用）
         * @param config Configuration for the sub-agent tool, or null to use defaults (tool name
         *     derived from agent name, InMemorySession for state, events forwarded)
         *               子代理工具的配置，或使用 null 以使用默认值（工具名称源自代理名称，状态为 InMemorySession，事件转发）。
         * @return This builder for chaining
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        public SkillRegistration subAgent(SubAgentProvider<?> provider, SubAgentConfig config) {
            if (this.toolObject != null
                    || this.agentTool != null
                    || this.mcpClientWrapper != null) {
                throw new IllegalStateException(
                        "Cannot set multiple registration types. Use only one of: tool(),"
                                + " agentTool(), mcpClient(), or subAgent().");
            }
            this.subAgentProvider = provider;
            this.subAgentConfig = config;
            return this;
        }

        /**
         * Set the list of tools to enable from the MCP client.
         * 设置要从 MCP 客户端启用的工具列表。
         *
         * <p>Only applicable when using mcpClient(). If not specified, all tools are enabled.
         *    仅在使用 mcpClient() 时适用。如果未指定，则启用所有工具。
         *
         * @param enableTools List of tool names to enable
         * @return This builder for chaining
         */
        public SkillRegistration enableTools(List<String> enableTools) {
            this.enableTools = enableTools;
            return this;
        }

        /**
         * Set the list of tools to disable from the MCP client.
         * 设置要从 MCP 客户端禁用的工具列表。
         *
         * <p>Only applicable when using mcpClient().
         *    仅在使用 mcpClient() 时适用。
         *
         * @param disableTools List of tool names to disable
         * @return This builder for chaining
         */
        public SkillRegistration disableTools(List<String> disableTools) {
            this.disableTools = disableTools;
            return this;
        }

        /**
         * Set preset parameters that will be automatically injected during tool execution.
         * 设置预设参数，这些参数将在工具执行期间自动注入。
         *
         * <p>These parameters are not exposed in the JSON schema.
         *    这些参数未在 JSON 模式中公开。
         *
         * <p>The map should have tool names as keys and parameter maps as values:
         *    该映射表应以工具名称作为键，以参数映射表作为值：
         * <pre>{@code
         * Map.of(
         *     "toolName1", Map.of("param1", "value1", "param2", "value2"),
         *     "toolName2", Map.of("param1", "value3")
         * )
         * }</pre>
         *
         * @param presetParameters Map from tool name to its preset parameters
         * @return This builder for chaining
         */
        public SkillRegistration presetParameters(
                Map<String, Map<String, Object>> presetParameters) {
            this.presetParameters = presetParameters;
            return this;
        }

        /**
         * Set the extended model for dynamic schema extension.
         * 设置动态模式扩展的扩展模型。
         *
         * @param extendedModel The extended model
         * @return This builder for chaining
         */
        public SkillRegistration extendedModel(ExtendedModel extendedModel) {
            this.extendedModel = extendedModel;
            return this;
        }

        /**
         * Apply the registration with all configured options.
         * 使用所有已配置的选项进行注册。
         *
         * @throws IllegalStateException if none of skill() was set, or toolkit() is required but not set
         */
        public void apply() {
            if (skill == null) {
                throw new IllegalStateException("Must call skill() before apply()");
            }
            skillBox.registerSkill(skill);

            if (toolObject != null
                    || agentTool != null
                    || mcpClientWrapper != null
                    || subAgentProvider != null) {
                if (toolkit == null && (toolkit = skillBox.toolkit) == null) {
                    throw new IllegalStateException(
                            "Must bind toolkit or call toolkit() before apply()");
                }
                String skillToolGroup = skill.getSkillId() + "_skill_tools";
                if (toolkit.getToolGroup(skillToolGroup) == null) {
                    toolkit.createToolGroup(skillToolGroup, skillToolGroup, false);
                }
                toolkit.registration()
                        .group(skillToolGroup)
                        .presetParameters(presetParameters)
                        .extendedModel(extendedModel)
                        .enableTools(enableTools)
                        .disableTools(disableTools)
                        .agentTool(agentTool)
                        .tool(toolObject)
                        .mcpClient(mcpClientWrapper)
                        .subAgent(subAgentProvider, subAgentConfig)
                        .apply();
            }
        }
    }

    // ==================== Skill Build-In Tools ====================

    /**
     * Registers skill access tools to the provided toolkit.
     * 将技能访问工具注册到提供的工具包中。
     *
     * <p>This method registers the following tool:
     *    此方法注册以下工具：
     * <ul>
     *   <li>load_skill_through_path - Load skill resources or SKILL.md content. When a resource
     *       is not found, it automatically returns a list of available resources with SKILL.md
     *       as the first item.</li>
     *       加载技能资源或 SKILL.md 内容。
     *       如果找不到资源，则会自动返回可用资源列表，其中 SKILL.md 为第一项。
     * </ul>
     *
     * @throws IllegalArgumentException if toolkit is null
     */
    public void registerSkillLoadTool() {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }

        if (toolkit.getToolGroup("skill-build-in-tools") == null) {
            toolkit.createToolGroup(
                    "skill-build-in-tools",
                    "skill build-in tools, could contain(load_skill_through_path)");
        }

        toolkit.registration()
                .agentTool(skillToolFactory.createSkillAccessToolAgentTool())
                .group("skill-build-in-tools")
                .apply();

        logger.info("Registered skill load tools to toolkit");
    }

    // ==================== Code Execution ====================

    /**
     * Create a fluent builder for configuring code execution with custom options.
     *  创建一个流畅的构建器，用于配置带有自定义选项的代码执行。
     *
     * <p>This is the recommended way to enable code execution capabilities for skills.
     * The builder allows selective enabling of tools and customization of ShellCommandTool.
     * 这是启用技能代码执行功能的推荐方法。
     * 该构建器允许选择性地启用工具并自定义 ShellCommandTool。
     *
     * <p>Example usage:
     *    使用示例：
     * <pre>{@code
     * // Simple - enable all tools with default configuration
     * 启用所有工具的默认配置
     * skillBox.codeExecution()
     *     .withShell()
     *     .withRead()
     *     .withWrite()
     *     .enable();
     *
     * // Custom shell tool with approval callback
     * ShellCommandTool customShell = new ShellCommandTool(
     *     null,  // baseDir will be overridden
     *     Set.of("python3", "node", "npm"),
     *     command -> askUserApproval(command)
     * );
     *
     * skillBox.codeExecution()
     *     .workDir("/path/to/workdir")
     *     .withShell(customShell)  // Clone with workDir
     *     .withRead()
     *     .withWrite()
     *     .enable();
     *
     * // Only enable read and write tools
     * skillBox.codeExecution()
     *     .withRead()
     *     .withWrite()
     *     .enable();
     * }</pre>
     *
     * @return A new CodeExecutionBuilder for configuration
     */
    public CodeExecutionBuilder codeExecution() {
        return new CodeExecutionBuilder(this);
    }

    /**
     * Sets whether skill files are automatically uploaded.
     * 设置是否自动上传技能文件。
     *
     * @param autoUploadSkill true to automatically upload skill files
     */
    public void setAutoUploadSkill(boolean autoUploadSkill) {
        this.autoUploadSkill = autoUploadSkill;
    }

    /**
     * Checks whether skill files are automatically uploaded.
     * 检查技能文件是否自动上传。
     *
     * @return true if skill files are automatically uploaded
     */
    public boolean isAutoUploadSkill() {
        return autoUploadSkill;
    }

    /**
     * Gets the working directory for code execution.
     * 获取代码执行的工作目录。
     *
     * @return The working directory path, or null if using temporary directory
     */
    public Path getCodeExecutionWorkDir() {
        return workDir;
    }

    /**
     * Gets the upload directory for skill files.
     * 获取技能文件的上传目录。
     *
     * @return The upload directory path, or null if not configured
     */
    public Path getUploadDir() {
        return uploadDir;
    }

    /**
     * Ensures the working directory exists, creating it if necessary.
     * 确保工作目录存在，必要时创建它。
     *
     * @return The working directory path
     * @throws RuntimeException if failed to create the directory
     */
    private Path ensureWorkDirExists() {
        if (this.workDir == null) {
            // Create temporary directory
            try {
                this.workDir = Files.createTempDirectory("agentscope-code-execution-");

                SkillFileSystemHelper.registerTempDirectoryCleanup(workDir);

                logger.info("Created temporary working directory: {}", workDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temporary working directory", e);
            }
        } else {
            // Create directory if it doesn't exist
            if (!Files.exists(workDir)) {
                try {
                    Files.createDirectories(workDir);
                    logger.info("Created working directory: {}", workDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create working directory", e);
                }
            }
        }

        return this.workDir;
    }

    /**
     * Ensures the upload directory exists, creating it if necessary.
     * 确保上传目录存在，必要时创建该目录。
     *
     * @return The upload directory path
     */
    private Path ensureUploadDirExists() {
        if (uploadDir == null) {
            Path resolvedWorkDir = ensureWorkDirExists();
            uploadDir = resolvedWorkDir.resolve("skills");
        }

        if (!Files.exists(uploadDir)) {
            try {
                Files.createDirectories(uploadDir);
                logger.info("Created upload directory: {}", uploadDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create upload directory", e);
            }
        }

        return uploadDir;
    }

    /**
     * Uploads skill files to the upload directory with the configured filter.
     * 使用配置的过滤器将技能文件上传到上传目录。
     *
     * <p>Upload directory resolution:
     * 上传目录解决：
     * <ul>
     *   <li>If uploadDir is configured, use it.</li>
     *       如果已配置 uploadDir，使用它。
     *   <li>Otherwise, use workDir/skills (workDir may be a temporary directory).</li>
     *       否则，请使用 workDir/skills（workDir 可能是一个临时目录）。
     * </ul>
     *
     * <p>If a file already exists, it will be overwritten.
     *    如果文件已存在，则会被覆盖。
     *
     */
    public void uploadSkillFiles() {
        Path targetDir = ensureUploadDirExists();
        SkillFileFilter filter = fileFilter != null ? fileFilter : SkillFileFilter.acceptAll();
        int fileCount = 0;

        for (String skillId : getAllSkillIds()) {
            AgentSkill skill = getSkill(skillId);
            Set<String> resourcePaths = skill.getResourcePaths();

            if (resourcePaths.isEmpty()) {
                continue;
            }

            Path skillDir = targetDir.resolve(skillId);

            for (String resourcePath : resourcePaths) {
                if (!filter.accept(resourcePath)) {
                    continue;
                }

                String content = skill.getResource(resourcePath);
                if (content == null) {
                    logger.warn("Resource not found: {} in skill {}", resourcePath, skillId);
                    continue;
                }

                Path targetPath = skillDir.resolve(resourcePath).normalize();

                // Security check: Prevent path traversal attacks
                if (!targetPath.startsWith(skillDir)) {
                    logger.warn("Skipping file with invalid path: {}", resourcePath);
                    continue;
                }

                try {
                    if (targetPath.getParent() != null) {
                        Files.createDirectories(targetPath.getParent());
                    }
                    if (content.startsWith(BASE64_PREFIX)) {
                        String encoded = content.substring(BASE64_PREFIX.length());
                        byte[] decoded = Base64.getDecoder().decode(encoded);
                        Files.write(targetPath, decoded);
                    } else {
                        Files.writeString(targetPath, content, StandardCharsets.UTF_8);
                    }
                    logger.debug("Uploaded file: {}", targetPath);
                    fileCount++;
                } catch (IOException | IllegalArgumentException e) {
                    logger.error("Failed to upload file {}: {}", resourcePath, e.getMessage());
                }
            }
        }

        logger.info("Uploaded {} skill files to: {}", fileCount, targetDir);
    }

    private static class DefaultSkillFileFilter implements SkillFileFilter {
        // 包含文件夹
        private final Set<String> includeFolders;
        // 包含扩展名
        private final Set<String> includeExtensions;

        private DefaultSkillFileFilter(Set<String> includeFolders, Set<String> includeExtensions) {
            this.includeFolders = includeFolders != null ? includeFolders : Set.of();
            this.includeExtensions = includeExtensions != null ? includeExtensions : Set.of();
        }

        @Override
        public boolean accept(String resourcePath) {
            if (resourcePath == null || resourcePath.isBlank()) {
                return false;
            }

            String normalizedPath = resourcePath.replace("\\", "/");

            if (!includeFolders.isEmpty()) {
                for (String folder : includeFolders) {
                    if (normalizedPath.startsWith(folder)) {
                        return true;
                    }
                }
            }

            if (!includeExtensions.isEmpty()) {
                for (String extension : includeExtensions) {
                    if (normalizedPath.endsWith(extension)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    // ==================== Code Execution Builder ====================

    /**
     * Fluent builder for configuring code execution with custom options.
     * 流构建器，用于使用自定义选项配置代码执行。
     *
     * <p>This builder provides a flexible way to enable code execution capabilities
     * with selective tool enabling and custom ShellCommandTool configuration.
     * 此构建器提供了一种灵活的方法，通过选择性工具启用和自定义ShellCommandTool配置来启用代码执行功能。
     *
     * <p>Key features:
     *    关键功能：
     * <ul>
     *   <li>Selective tool enabling: choose which tools (shell/read/write) to enable</li>
     *       选择性启用工具：选择要启用的工具（shell/读取/写入）。
     *   <li>Custom ShellCommandTool: provide your own tool with custom security policies</li>
     *       自定义 ShellCommandTool：提供您自己的工具以及自定义安全策略
     *   <li>WorkDir enforcement: all tools use the same working directory</li>
     *       工作目录强制执行：所有工具使用同一个工作目录
     *   <li>Tool cloning: custom ShellCommandTool is cloned with workDir override</li>
     *       工具克隆：自定义 ShellCommandTool 已克隆，并覆盖了 workDir 设置。
     * </ul>
     */
    public static class CodeExecutionBuilder {
        // 默认包含文件夹
        private static final Set<String> DEFAULT_INCLUDE_FOLDERS = Set.of("scripts/", "assets/");
        // 默认包含扩展名
        private static final Set<String> DEFAULT_INCLUDE_EXTENSIONS = Set.of(".py", ".js", ".sh");

        // 技能包
        private final SkillBox skillBox;
        // 工作目录
        private String workDir;
        // 上传目录
        private String uploadDir;
        // 自定义文件过滤器
        private SkillFileFilter customFilter;
        // 包含文件夹
        private Set<String> includeFolders;
        // 包含扩展名
        private Set<String> includeExtensions;
        // 自定义 ShellCommandTool
        private ShellCommandTool customShellTool;
        // 是否已调用 withShell
        private boolean withShellCalled = false;
        // 是否启用读取
        private boolean enableRead = false;
        // 是否启用写入
        private boolean enableWrite = false;

        CodeExecutionBuilder(SkillBox skillBox) {
            this.skillBox = skillBox;
        }

        /**
         * Set the working directory for code execution.
         * 设置代码执行的工作目录。
         *
         * <p>All code execution tools (shell, read, write) will use this directory.
         * If not set, a temporary directory will be created when files are uploaded.
         * 所有代码执行工具（shell、读取、写入）都将使用此目录。如果未设置，则会在上传文件时创建一个临时目录。
         *
         * @param workDir The working directory path (null or empty for temporary directory)
         *                工作目录路径（临时目录为空或null）
         * @return This builder for chaining
         */
        public CodeExecutionBuilder workDir(String workDir) {
            this.workDir = workDir;
            return this;
        }

        /**
         * Set the upload directory for skill files.
         * 设置技能文件的上传目录。
         *
         * <p>If not set, the upload directory defaults to workDir/skills.
         *    如果未设置，则上传目录默认为 workDir/skills。
         *
         * @param uploadDir The upload directory path
         * @return This builder for chaining
         */
        public CodeExecutionBuilder uploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
            return this;
        }

        /**
         * Set a custom file filter for skill file uploads.
         * 为技能文件上传设置自定义文件过滤器。
         *
         * @param filter The custom filter to use
         * @return This builder for chaining
         * @throws IllegalArgumentException if filter is null
         */
        public CodeExecutionBuilder fileFilter(SkillFileFilter filter) {
            if (filter == null) {
                throw new IllegalArgumentException("SkillFileFilter cannot be null");
            }
            this.customFilter = filter;
            return this;
        }

        /**
         * Set the folders to include for uploads.
         * 设置要包含上传文件的文件夹。
         *
         * @param folders Folder paths to include
         * @return This builder for chaining
         */
        public CodeExecutionBuilder includeFolders(Set<String> folders) {
            this.includeFolders = folders;
            return this;
        }

        /**
         * Set the file extensions to include for uploads.
         * 设置上传所包含的文件扩展名。
         *
         * @param extensions File extensions to include
         * @return This builder for chaining
         */
        public CodeExecutionBuilder includeExtensions(Set<String> extensions) {
            this.includeExtensions = extensions;
            return this;
        }

        /**
         * Enable shell command execution with default configuration.
         * 启用 shell 命令执行，并使用默认配置。
         *
         * <p>Default configuration:
         *    默认配置：
         * <ul>
         *   <li>Allowed commands: python, python3, node, nodejs</li>
         *       允许的命令：python、python3、node、nodejs
         *   <li>No approval callback</li>
         *       没有批准回电
         *   <li>Platform-specific validator (Unix or Windows)</li>
         *       平台特定验证器（Unix 或 Windows）
         * </ul>
         *
         * @return This builder for chaining
         */
        public CodeExecutionBuilder withShell() {
            this.withShellCalled = true;
            this.customShellTool = null;
            return this;
        }

        /**
         * Enable shell command execution with a custom ShellCommandTool.
         * 使用自定义 ShellCommandTool 启用 shell 命令执行。
         *
         * <p>The provided tool will be cloned with the following behavior:
         *    所提供的工具将被克隆，并具有以下行为：
         * <ul>
         *   <li>allowedCommands: copied from the source tool</li>
         *       allowedCommands：从源工具复制
         *   <li>approvalCallback: copied from the source tool</li>
         *       审批回调：从源工具复制
         *   <li>commandValidator: copied from the source tool</li>
         *       commandValidator：从源工具复制
         *   <li>baseDir: OVERRIDDEN with the builder's workDir</li>
         *       baseDir：已使用构建器的工作目录覆盖
         * </ul>
         *
         * <p>This ensures all code execution tools use the same working directory
         * while preserving your custom security policies.
         * 这样可以确保所有代码执行工具使用相同的工作目录，同时保留您的自定义安全策略。
         *
         * @param shellTool The custom ShellCommandTool to clone (must not be null)
         *                  要克隆的自定义 ShellCommandTool（不能为空）
         * @return This builder for chaining
         * @throws IllegalArgumentException if shellTool is null
         */
        public CodeExecutionBuilder withShell(ShellCommandTool shellTool) {
            if (shellTool == null) {
                throw new IllegalArgumentException("ShellCommandTool cannot be null");
            }
            this.withShellCalled = true;
            this.customShellTool = shellTool;
            return this;
        }

        /**
         * Enable file reading capabilities.
         * 启用文件读取功能。
         *
         * <p>Registers ReadFileTool with the builder's workDir as base directory.
         *    将 ReadFileTool 注册为构建器的工作目录作为基本目录。
         *
         * @return This builder for chaining
         */
        public CodeExecutionBuilder withRead() {
            this.enableRead = true;
            return this;
        }

        /**
         * Enable file writing capabilities.
         * 启用文件写入功能。
         *
         * <p>Registers WriteFileTool with the builder's workDir as base directory.
         *    将 WriteFileTool 注册为构建器的工作目录作为基本目录。
         *
         * @return This builder for chaining
         */
        public CodeExecutionBuilder withWrite() {
            this.enableWrite = true;
            return this;
        }

        /**
         * Apply the configuration and enable code execution.
         * 应用配置并启用代码执行。
         *
         * <p>This method:
         *    这种方法：
         * <ul>
         *   <li>Validates toolkit is bound</li>
         *       验证工具包是否已绑定
         *   <li>Removes existing code execution configuration if present</li>
         *       如果存在，则移除现有的代码执行配置。
         *   <li>Creates the code execution tool group</li>
         *       创建代码执行工具组
         *   <li>Registers selected tools (shell, read, write)</li>
         *       注册选定的工具（shell、读取、写入）
         * </ul>
         *
         * @throws IllegalStateException if toolkit is not bound
         */
        public void enable() {
            if (skillBox.toolkit == null) {
                throw new IllegalStateException("Must bind toolkit before enabling code execution");
            }

            if (customFilter != null && (includeFolders != null || includeExtensions != null)) {
                throw new IllegalStateException(
                        "Cannot use fileFilter() with includeFolders() or includeExtensions()");
            }

            // Handle replacement: remove existing tool group if present
            if (skillBox.toolkit != null
                    && skillBox.toolkit.getToolGroup("skill_code_execution_tool_group") != null) {
                skillBox.toolkit.removeToolGroups(List.of("skill_code_execution_tool_group"));
                logger.info("Replacing existing code execution configuration");
            }

            // Set workDir
            if (workDir == null || workDir.isEmpty()) {
                skillBox.workDir = null;
            } else {
                skillBox.workDir = Paths.get(workDir).toAbsolutePath().normalize();
            }

            // Set uploadDir
            if (uploadDir == null || uploadDir.isBlank()) {
                skillBox.uploadDir =
                        skillBox.workDir != null ? skillBox.workDir.resolve("skills") : null;
            } else {
                skillBox.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
            }

            // Set file filter
            if (customFilter != null) {
                skillBox.fileFilter = customFilter;
            } else {
                Set<String> folders =
                        includeFolders != null ? includeFolders : DEFAULT_INCLUDE_FOLDERS;
                Set<String> extensions =
                        includeExtensions != null ? includeExtensions : DEFAULT_INCLUDE_EXTENSIONS;
                skillBox.fileFilter = new DefaultSkillFileFilter(folders, extensions);
            }

            // Create tool group
            skillBox.toolkit.createToolGroup(
                    "skill_code_execution_tool_group", "Code execution tools for skills", true);

            String workDirStr = skillBox.workDir != null ? skillBox.workDir.toString() : null;

            boolean shellEnabled = false;

            // Shell Tool - check if withShell() was called
            if (withShellCalled) {
                ShellCommandTool shellTool;
                if (customShellTool != null) {
                    // Clone custom tool with workDir override
                    shellTool = cloneShellToolWithWorkDir(customShellTool, workDirStr);
                } else {
                    // Create default shell tool
                    shellTool =
                            new ShellCommandTool(
                                    workDirStr,
                                    Set.of("python", "python3", "node", "nodejs"),
                                    null);
                }
                skillBox.toolkit
                        .registration()
                        .agentTool(shellTool)
                        .group("skill_code_execution_tool_group")
                        .apply();
                shellEnabled = true;
            }

            // Read Tool
            if (enableRead) {
                ReadFileTool readTool = new ReadFileTool(workDirStr);
                skillBox.toolkit
                        .registration()
                        .tool(readTool)
                        .group("skill_code_execution_tool_group")
                        .apply();
            }

            // Write Tool
            if (enableWrite) {
                WriteFileTool writeTool = new WriteFileTool(workDirStr);
                skillBox.toolkit
                        .registration()
                        .tool(writeTool)
                        .group("skill_code_execution_tool_group")
                        .apply();
            }

            logger.info(
                    "Code execution enabled with workDir: {}, uploadDir: {}, tools: [shell={},"
                            + " read={}, write={}]",
                    skillBox.workDir != null ? skillBox.workDir : "temporary",
                    skillBox.uploadDir != null ? skillBox.uploadDir : "workDir/skills",
                    shellEnabled,
                    enableRead,
                    enableWrite);
        }

        /**
         * Clone a ShellCommandTool with a new base directory.
         * 克隆一个 ShellCommandTool，并创建一个新的基本目录。
         *
         * <p>This ensures all code execution tools use the same working directory
         * while preserving the custom security policies from the source tool.
         * 这样可以确保所有代码执行工具使用相同的工作目录，同时保留源工具的自定义安全策略。
         *
         * @param source The source ShellCommandTool to clone 要克隆的源 ShellCommandTool
         * @param workDir The new working directory (can be null for temporary) 新的工作目录（临时目录可以为空）
         * @return A new ShellCommandTool with the same configuration but different baseDir 一个新的 ShellCommandTool，配置相同，但 baseDir 不同
         */
        private ShellCommandTool cloneShellToolWithWorkDir(
                ShellCommandTool source, String workDir) {
            // Get configuration from source tool
            Set<String> allowedCommands = source.getAllowedCommands();
            Function<String, Boolean> approvalCallback = source.getApprovalCallback();
            CommandValidator validator = source.getCommandValidator();

            // Create new instance with workDir override
            return new ShellCommandTool(workDir, allowedCommands, approvalCallback, validator);
        }
    }
}
