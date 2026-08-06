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
package io.agentscope.core.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Toolkit manages the registration, retrieval, and execution of agent tools.
 * This class acts as a facade, delegating specific responsibilities to specialized managers:
 *
 * <p><b>Managers:</b>
 * <ul>
 *   <li>ToolRegistry: Tool registration and lookup</li>
 *   <li>ToolGroupManager: Tool group CRUD operations and active group management</li>
 *   <li>ToolSchemaProvider: Tool schema generation with group filtering</li>
 *   <li>McpClientManager: MCP client lifecycle and tool registration</li>
 *   <li>MetaToolFactory: Creates meta tools for dynamic group control</li>
 * </ul>
 *
 * <p><b>Core Components:</b>
 * <ul>
 *   <li>ToolSchemaGenerator: Generates JSON schemas for tool parameters</li>
 *   <li>ToolMethodInvoker: Handles method invocation and parameter conversion</li>
 *   <li>ToolResultConverter: Converts method results to ToolResultBlock</li>
 *   <li>ToolExecutor: Handles parallel/sequential tool execution with validation</li>
 * </ul>
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Tool group management for dynamic tool activation</li>
 *   <li>State management via StateModule interface (activeGroups persistence)</li>
 *   <li>Meta tool for runtime tool group control (reset_equipped_tools)</li>
 *   <li>MCP (Model Context Protocol) client support for external tool providers</li>
 * </ul>
 */
/**
 * Toolkit 管理代理工具的注册、检索和执行。
 * 该类是一个外观层（facade），将各项职责委派给专门的管理器组件：
 *
 * <p><b>管理器（Managers）：</b>
 * <ul>
 *   <li>ToolRegistry：工具的注册与查找</li>
 *   <li>ToolGroupManager：工具组的 CRUD 操作与激活组管理</li>
 *   <li>ToolSchemaProvider：按工具组过滤的工具 schema 生成</li>
 *   <li>McpClientManager：MCP 客户端生命周期管理与对应工具的注册</li>
 *   <li>MetaToolFactory：构造用于运行时动态控制工具组的元工具</li>
 * </ul>
 *
 * <p><b>核心组件（Core Components）：</b>
 * <ul>
 *   <li>ToolSchemaGenerator：为工具参数生成 JSON Schema</li>
 *   <li>ToolMethodInvoker：负责工具方法的调用与参数转换</li>
 *   <li>ToolResultConverter：将方法返回值转换为 ToolResultBlock</li>
 *   <li>ToolExecutor：根据配置执行并行/串行调用，并完成参数校验</li>
 * </ul>
 *
 * <p><b>主要特性（Features）：</b>
 * <ul>
 *   <li>支持通过工具组实现动态工具激活</li>
 *   <li>通过 StateModule 接口持久化激活组状态（activeGroups 持久化）</li>
 *   <li>提供运行时控制工具组的元工具（reset_equipped_tools）</li>
 *   <li>支持 MCP（Model Context Protocol）客户端以接入外部工具提供方</li>
 * </ul>
 */
public class Toolkit {

    private static final Logger logger = LoggerFactory.getLogger(Toolkit.class);

    /** Manages tool groups: CRUD operations and activation state. */
    /**
     * 工具组管理器：负责工具组的 CRUD、激活状态以及工具与组的关联关系。
     */
    private final ToolGroupManager groupManager = new ToolGroupManager();

    /** Tool registry: maps tool names to AgentTool instances. */
    /**
     * 工具注册表：保存工具名到 AgentTool 实例的映射，是查找工具的唯一入口。
     */
    private final ToolRegistry toolRegistry = new ToolRegistry();

    /** Tool schema provider: produces JSON schemas honoring the active groups. */
    /**
     * 工具 schema 提供器：负责按当前激活的工具组输出可见工具的 JSON schema。
     */
    private final ToolSchemaProvider schemaProvider;

    /** Meta tool factory: builds reset_equipped_tools and similar group-control tools. */
    /**
     * 元工具工厂：用于构建 reset_equipped_tools 等管理工具组的元工具。
     */
    private final MetaToolFactory metaToolFactory;

    /** MCP client manager: handles MCP client lifecycle and per-tool registration. */
    /**
     * MCP 客户端管理器：负责 MCP 客户端的注册、移除以及相关工具的注册。
     */
    private final McpClientManager mcpClientManager;

    /** Tool schema generator: derives parameter JSON schema from the @Tool annotation. */
    /**
     * 工具参数 schema 生成器：从 @Tool 注解中生成参数的 JSON schema。
     */
    private final ToolSchemaGenerator schemaGenerator = new ToolSchemaGenerator();

    /** Tool method invoker: reflectively invokes tool methods and converts params/results. */
    /**
     * 工具方法调用器：负责反射调用工具方法，并完成参数与结果的转换。
     */
    private final ToolMethodInvoker methodInvoker;

    /** Toolkit runtime configuration (execution config, parallel flag, allowToolDeletion...). */
    /**
     * Toolkit 运行配置（含执行配置、并行开关、是否允许删除工具等）。
     */
    private final ToolkitConfig config;

    /** Tool executor: runs calls in parallel or sequentially per configuration, with validation. */
    /**
     * 工具执行器：根据配置决定并行或串行执行，并对每次调用进行校验。
     */
    private final ToolExecutor executor;

    /**
     * Create a Toolkit with default configuration (sequential execution using Reactor).
     */
    /**
     * 使用默认配置创建 Toolkit：通过 Reactor 实现串行执行。
     */
    public Toolkit() {
        this(ToolkitConfig.defaultConfig());
    }

    /**
     * Create a Toolkit with custom configuration.
     *
     * @param config Toolkit configuration (if null, uses defaultConfig())
     */
    /**
     * 使用自定义配置创建 Toolkit。
     *
     * @param config Toolkit 配置（传入 null 时将回退到 defaultConfig()）
     */
    public Toolkit(ToolkitConfig config) {
        this.config = config != null ? config : ToolkitConfig.defaultConfig();
        this.methodInvoker = new ToolMethodInvoker(new DefaultToolResultConverter());
        this.schemaProvider = new ToolSchemaProvider(toolRegistry, groupManager);
        this.metaToolFactory = new MetaToolFactory(groupManager, toolRegistry);
        this.mcpClientManager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParameters) ->
                                registerAgentTool(
                                        tool, groupName, null, mcpClientName, presetParameters));

        // 根据配置创建执行器：有自定义 ExecutorService 时使用它，否则由 ToolExecutor 自行管理
        if (config != null && config.hasCustomExecutor()) {
            this.executor =
                    new ToolExecutor(
                            this,
                            toolRegistry,
                            groupManager,
                            this.config,
                            config.getExecutorService());
        } else {
            this.executor = new ToolExecutor(this, toolRegistry, groupManager, this.config);
        }
    }

    /**
     * Create a fluent builder for registering tools with optional configuration.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Register tool object
     * toolkit.registration()
     *     .tool(myToolObject)
     *     .group("myGroup")
     *     .presetParameters(Map.of(
     *         "myTool", Map.of("apiKey", "secret")
     *     ))
     *     .apply();
     *
     * // Register MCP client
     * toolkit.registration()
     *     .mcpClient(mcpClientWrapper)
     *     .enableTools(List.of("tool1", "tool2"))
     *     .group("mcpGroup")
     *     .presetParameters(Map.of(
     *         "tool1", Map.of("apiKey", "key1")
     *     ))
     *     .apply();
     * }</pre>
     *
     * @return A new ToolRegistration builder
     */
    /**
     * @return 返回新的 ToolRegistration 流式构造器
     */
    public ToolRegistration registration() {
        return new ToolRegistration(this);
    }

    /**
     * Register a tool object by scanning for methods annotated with @Tool.
     *
     * @param toolObject the object containing tool methods
     */
    /**
     * 注册工具对象：扫描该对象中标有 @Tool 注解的方法。
     *
     * @param toolObject 包含工具方法的对象
     */
    public void registerTool(Object toolObject) {
        registerTool(toolObject, null, null, null);
    }

    /**
     * Internal method: Register a tool object with group, extended model, and preset parameters.
     */
    /**
     * 内部方法：以分组、扩展模型和预置参数注册工具对象。
     */
    private void registerTool(
            Object toolObject,
            String groupName,
            ExtendedModel extendedModel,
            Map<String, Map<String, Object>> presetParameters) {
        if (toolObject == null) {
            throw new IllegalArgumentException("Tool object cannot be null");
        }

        // 优先处理 AgentTool 实例：直接走单实例注册路径，避免反射扫描
        if (toolObject instanceof AgentTool) {
            AgentTool agentTool = (AgentTool) toolObject;
            String toolName = agentTool.getName();
            Map<String, Object> toolPresets =
                    (presetParameters != null && presetParameters.containsKey(toolName))
                            ? presetParameters.get(toolName)
                            : null;
            registerAgentTool(agentTool, groupName, extendedModel, null, toolPresets);
            return;
        }

        // POJO 模式：反射扫描对象的所有声明方法，过滤出标有 @Tool 的方法逐一注册
        Class<?> clazz = toolObject.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            // 仅处理标有 @Tool 注解的方法（即工具方法入口）
            if (method.isAnnotationPresent(Tool.class)) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                // 优先使用 @Tool 注解显式指定的名称；缺省时回退到方法名
                String toolName =
                        toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                // 按工具名筛选出该方法专属的预置参数
                Map<String, Object> toolPresets =
                        (presetParameters != null && presetParameters.containsKey(toolName))
                                ? presetParameters.get(toolName)
                                : null;
                registerToolMethod(toolObject, method, groupName, extendedModel, toolPresets);
            }
        }
    }

    /**
     * Register an AgentTool instance directly.
     *
     * @param tool the AgentTool to register
     */
    /**
     * 直接注册一个 AgentTool 实例。
     *
     * @param tool 待注册的 AgentTool 实例
     */
    public void registerAgentTool(AgentTool tool) {
        registerAgentTool(tool, null, null, null, null);
    }

    /**
     * Internal method to register AgentTool with full metadata including preset parameters.
     */
    /**
     * 内部方法：注册 AgentTool 并附带完整元数据（含预置参数）。
     */
    private void registerAgentTool(
            AgentTool tool,
            String groupName,
            ExtendedModel extendedModel,
            String mcpClientName,
            Map<String, Object> presetParameters) {
        if (tool == null) {
            throw new IllegalArgumentException("AgentTool cannot be null");
        }

        String toolName = tool.getName();

        // 若指定了工具组，先校验其存在，避免后续关联到不存在的组
        if (groupName != null) {
            groupManager.validateGroupExists(groupName);
        }

        // 用 RegisteredToolFunction 包装 tool，承载扩展模型、mcp 来源、预置参数等元数据
        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, extendedModel, mcpClientName, presetParameters);

        // 写入工具注册表，供后续按名查找
        toolRegistry.registerTool(toolName, tool, registered);

        // 将工具加入指定组，便于按组激活/停用
        if (groupName != null) {
            groupManager.addToolToGroup(groupName, toolName);
        }

        logger.info(
                "Registered tool '{}' in group '{}'",
                toolName,
                groupName != null ? groupName : "ungrouped");
    }

    /**
     * Retrieves a tool by its name.
     *
     * @param name The name of the tool to retrieve
     * @return The AgentTool instance, or null if not found
     */
    /**
     * 按名称获取已注册的工具。
     *
     * @param name 待获取的工具名称
     * @return 对应的 AgentTool 实例；未找到时返回 null
     */
    public AgentTool getTool(String name) {
        return toolRegistry.getTool(name);
    }

    /**
     * Gets the names of all registered tools.
     *
     * @return A set of all tool names (never null, may be empty)
     */
    /**
     * 获取全部已注册工具的名称集合。
     *
     * @return 全部工具名称的集合（不为 null，可能为空集）
     */
    public Set<String> getToolNames() {
        return toolRegistry.getToolNames();
    }

    // ==================== External Tool Support ====================

    /**
     * Register an external tool using only its schema definition.
     *
     * <p>External tools are tools that will be executed outside the framework. When a model
     * returns a call to an external tool, the framework will not execute it but instead
     * return the tool call to the user via a message with
     * {@link io.agentscope.core.message.GenerateReason#TOOL_SUSPENDED}.
     *
     * <p>Example usage:
     * <pre>{@code
     * ToolSchema schema = ToolSchema.builder()
     *     .name("query_database")
     *     .description("Query external database")
     *     .parameters(Map.of(
     *         "type", "object",
     *         "properties", Map.of("sql", Map.of("type", "string")),
     *         "required", List.of("sql")
     *     ))
     *     .build();
     *
     * toolkit.registerSchema(schema);
     * }</pre>
     *
     * @param schema The tool schema containing name, description, and parameters
     * @throws NullPointerException if schema is null
     * @see SchemaOnlyTool
     * @see #isExternalTool(String)
     */
    /**
     * 仅以 schema 定义注册一个外部工具（不在框架内执行）。
     *
     * @param schema 待注册工具的 schema（含名称、描述、参数定义）
     * @throws NullPointerException schema 为 null 时抛出
     * @see SchemaOnlyTool
     * @see #isExternalTool(String)
     */
    public void registerSchema(ToolSchema schema) {
        registerAgentTool(new SchemaOnlyTool(schema));
    }

    /**
     * Register multiple external tools using their schema definitions.
     *
     * @param schemas List of tool schemas to register
     * @throws NullPointerException if schemas is null
     * @see #registerSchema(ToolSchema)
     */
    /**
     * 批量注册外部工具（仅以 schema 定义）。
     *
     * @param schemas 待注册的工具 schema 列表
     * @throws NullPointerException schemas 为 null 时抛出
     * @see #registerSchema(ToolSchema)
     */
    public void registerSchemas(List<ToolSchema> schemas) {
        if (schemas != null) {
            schemas.forEach(this::registerSchema);
        }
    }

    /**
     * Check if a tool is an external tool (requires execution outside the framework).
     *
     * <p>A tool is considered external when it extends {@link ToolBase} and reports
     * {@code isExternalTool() == true} — for example {@link SchemaOnlyTool}, or any
     * {@code @Tool(externalTool=true)} method. When this returns true, the framework will skip
     * execution and surface the tool call to the user via {@code TOOL_SUSPENDED}.
     *
     * @param toolName The name of the tool to check
     * @return true if the tool is an external tool, false otherwise
     */
    /**
     * 判断指定工具是否为外部工具（需要在框架外执行）。
     *
     * @param toolName 待判断的工具名称
     * @return 若是外部工具则返回 true，否则返回 false
     */
    public boolean isExternalTool(String toolName) {
        AgentTool tool = getTool(toolName);
        return tool instanceof ToolBase tb && tb.isExternalTool();
    }

    /**
     * Get tool schemas as ToolSchema objects.
     * Updated to respect active tool groups.
     *
     * @return List of ToolSchema objects
     */
    /**
     * 以 ToolSchema 对象形式获取全部工具的 schema 定义（已更新为尊重当前激活的工具组）。
     *
     * @return ToolSchema 对象列表
     */
    public List<ToolSchema> getToolSchemas() {
        return schemaProvider.getToolSchemas();
    }

    /**
     * Get tool schemas filtered by an explicitly supplied set of active group names, independent
     * of this toolkit's shared per-group activation flags.
     *
     * <p>Per-call / stateless variant of {@link #getToolSchemas()}: callers that track activated
     * groups in their own per-{@code (userId, sessionId)} state (e.g. {@code ReActAgent}) use this
     * so the model's tool surface is resolved from the call's own slot rather than from the shared,
     * concurrently-mutated toolkit activation flags.
     *
     * @param activeGroups the group names to treat as active for this resolution
     * @return List of ToolSchema objects visible for the supplied groups (plus all ungrouped tools)
     */
    /**
     * 按调用方指定的激活组集合解析可见工具的 schema（不依赖 Toolkit 内部的共享激活标志）。
     *
     * @param activeGroups 本次解析视为已激活的工具组名称集合
     * @return 在指定激活组以及全部未分组工具下可见的 ToolSchema 列表
     */
    public List<ToolSchema> getToolSchemas(java.util.Collection<String> activeGroups) {
        return schemaProvider.getToolSchemas(activeGroups);
    }

    /**
     * Register a tool method with group, extended model, and preset parameters.
     *
     * <p>Builds a {@link ReflectiveFunctionTool} (a {@link ToolBase} subclass) so the registered
     * tool participates in permission evaluation, the {@link ToolExecutor} safe-flag machinery,
     * and the agent's pending-confirmation flow alongside MCP and built-in tools.
     */
    /**
     * 以分组、扩展模型和预置参数注册一个工具方法。
     *
     * <p>构造一个 {@link ReflectiveFunctionTool}（{@link ToolBase} 子类），从而使注册的工具能够
     * 与 MCP、内置工具一起参与权限评估、{@link ToolExecutor} 的安全标记机制以及代理的待确认流程。
     */
    private void registerToolMethod(
            Object toolObject,
            Method method,
            String groupName,
            ExtendedModel extendedModel,
            Map<String, Object> presetParameters) {
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        // 工具名优先取 @Tool 注解值；缺省回退到方法名
        String toolName =
                !toolAnnotation.name().isEmpty() ? toolAnnotation.name() : method.getName();
        // 工具描述同上：注解优先，缺省补一个通用描述
        String description =
                !toolAnnotation.description().isEmpty()
                        ? toolAnnotation.description()
                        : "Tool: " + toolName;

        // 从 @Tool 注解读取自定义结果转换器
        ToolResultConverter customConverter = parseConverterFromAnnotation(toolAnnotation);

        // 收集预置参数键集合，用于 ReflectiveFunctionTool 在反射调用时跳过这些入参
        Set<String> presetParamNames =
                presetParameters != null ? presetParameters.keySet() : Collections.emptySet();

        // 通过反射工厂构造 AgentTool，便于统一接入权限/安全标记/待确认流程
        AgentTool tool =
                ReflectiveFunctionTool.create(
                        toolObject,
                        method,
                        toolAnnotation,
                        toolName,
                        description,
                        schemaGenerator,
                        methodInvoker,
                        customConverter,
                        presetParamNames);

        registerAgentTool(tool, groupName, extendedModel, null, presetParameters);
    }

    /**
     * Parses and instantiates converter from @Tool annotation.
     *
     * @param toolAnnotation The Tool annotation
     * @return A ToolResultConverter instance, or null to use default
     */
    /**
     * 解析 @Tool 注解并实例化对应的结果转换器。
     *
     * @param toolAnnotation @Tool 注解
     * @return 转换器实例；返回 null 表示使用默认转换器
     */
    private ToolResultConverter parseConverterFromAnnotation(Tool toolAnnotation) {
        if (toolAnnotation == null) {
            return null;
        }

        try {
            Class<? extends ToolResultConverter> converterClass = toolAnnotation.converter();
            // 若注解显式指定为默认转换器，返回 null 以走默认路径（避免重复实例化）
            if (converterClass == DefaultToolResultConverter.class) {
                return null;
            }
            return instantiateConverter(converterClass);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create converter from @Tool annotation", e);
        }
    }

    /**
     * Instantiates a converter class with proper constructor resolution. Tries: 1) no-arg
     * constructor, 2) constructor with ObjectMapper
     *
     * @param clazz The converter class to instantiate
     * @return A new converter instance
     */
    /**
     * 通过合适的构造器解析方式实例化转换器类。依次尝试：1）无参构造器；2）带 ObjectMapper 的构造器。
     *
     * @param clazz 待实例化的转换器类
     * @return 新创建的转换器实例
     */
    private ToolResultConverter instantiateConverter(Class<? extends ToolResultConverter> clazz)
            throws Exception {
        // 优先尝试无参构造器；当前约定仅支持这一种
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Converter " + clazz.getName() + " must have either a no-arg constructor");
        }
    }

    /**
     * Set the chunk callback for streaming tool responses.
     *
     * <p>This callback is preserved when the toolkit is deep-copied and will be invoked whenever
     * tools emit progress updates via ToolEmitter. When the toolkit is used by ReActAgent, the
     * user callback is invoked in addition to the framework's internal chunk callback.
     *
     * @param callback Callback to invoke when tools emit chunks via ToolEmitter
     */
    /**
     * 设置流式工具响应的分片回调。
     *
     * @param callback 当工具通过 ToolEmitter 推送增量分片时触发的回调函数
     */
    public void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        executor.setChunkCallback(callback);
    }

    /**
     * Set the framework-internal chunk callback for streaming tool responses.
     *
     * <p>This method is used by ReActAgent to forward tool chunks into ActingChunkEvent hooks
     * without overwriting any user callback configured via {@link #setChunkCallback(BiConsumer)}.
     *
     * <p><b>Internal API - Not recommended for external use.</b> This method is intended for
     * framework components such as {@link io.agentscope.core.ReActAgent}. External callers should
     * use {@link #setChunkCallback(BiConsumer)} instead.
     *
     * @param callback Internal callback to invoke when tools emit chunks via ToolEmitter
     */
    /**
     * 设置框架内部使用的流式工具响应分片回调。
     *
     * @param callback 框架内部回调函数；当工具通过 ToolEmitter 推送增量分片时触发
     */
    public void setInternalChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        executor.setInternalChunkCallback(callback);
    }

    /**
     * Execute a tool with the given parameters.
     *
     * <p>Example usage:
     *
     * <pre>{@code
     * // Simple call
     * ToolCallParam param = ToolCallParam.builder()
     *     .toolUseBlock(toolCall)
     *     .build();
     * toolkit.callTool(param);
     *
     * // With agent and context
     * ToolCallParam param = ToolCallParam.builder()
     *     .toolUseBlock(toolCall)
     *     .agent(agent)
     *     .context(context)
     *     .build();
     * toolkit.callTool(param);
     * }</pre>
     *
     * @param param Tool call parameters containing execution information
     * @return Mono containing execution result
     */
    /**
     * 执行单个工具调用。
     *
     * @param param 工具调用参数（含待执行的工具调用信息）
     * @return 携带工具执行结果的 Mono
     */
    public Mono<ToolResultBlock> callTool(ToolCallParam param) {
        return executor.execute(param);
    }

    /**
     * Execute multiple tools asynchronously with agent-level context (internal use by
     * ReActAgent).
     *
     * <p><b>Internal API - Not recommended for external use.</b> This method is primarily
     * intended for use by {@link io.agentscope.core.ReActAgent} and other framework components.
     *
     * <p>This method handles parallel/sequential execution based on toolkit configuration and
     * applies execution config (timeout, retry) from multiple levels. The agent context is
     * merged with toolkit default context during tool execution.
     *
     * @param toolCalls List of tool calls to execute
     * @param agentExecutionConfig Execution config from agent level (can be null)
     * @param agent The agent making the calls (may be null)
     * @param agentRuntimeContext The agent-level runtime context (may be null)
     * @return Mono containing list of tool responses
     */
    /**
     * 异步执行多个工具调用（ReActAgent 内部使用）。
     *
     * @param toolCalls 待执行的工具调用列表
     * @param agentExecutionConfig 代理层面的执行配置（可为 null）
     * @param agent 发起本次调用的代理（可为 null）
     * @param agentRuntimeContext 代理层面的运行时上下文（可为 null）
     * @return 携带工具执行结果列表的 Mono
     */
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            io.agentscope.core.agent.RuntimeContext agentRuntimeContext) {
        // 合并多级执行配置：代理级 > Toolkit 级 > 系统默认；后者覆盖前者（数值取更严格的）
        ExecutionConfig effectiveConfig =
                ExecutionConfig.mergeConfigs(
                        agentExecutionConfig,
                        ExecutionConfig.mergeConfigs(
                                config.getExecutionConfig(), ExecutionConfig.TOOL_DEFAULTS));

        return executor.executeAll(
                toolCalls, config.isParallel(), effectiveConfig, agent, agentRuntimeContext);
    }

    // ==================== MCP Client Registration (Delegated) ====================

    /**
     * Registers an MCP client and all its tools.
     *
     * <p>For more complex registration scenarios (filtering, groups, preset parameters),
     * use the builder API: {@code toolkit.registration().mcpClient(...).apply()}
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    /**
     * 注册一个 MCP 客户端及其全部工具。
     *
     * @param mcpClientWrapper MCP 客户端封装对象
     * @return 注册完成时结束的 Mono
     */
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper);
    }

    /**
     * Removes an MCP client and all its tools.
     *
     * @param mcpClientName the name of the MCP client to remove
     * @return Mono that completes when removal is finished
     */
    /**
     * 移除一个 MCP 客户端及其全部工具。
     *
     * @param mcpClientName 待移除的 MCP 客户端名称
     * @return 移除完成时结束的 Mono
     */
    public Mono<Void> removeMcpClient(String mcpClientName) {
        return mcpClientManager.removeMcpClient(mcpClientName);
    }

    // ==================== Tool Group Management (Delegated) ====================

    /**
     * Create a new tool group with specified activation status and default META scope.
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group
     * @param active Whether the group should be active by default
     * @throws IllegalArgumentException if group already exists
     */
    /**
     * 创建一个新的工具组，并指定激活状态（默认 META scope）。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 是否在创建时默认激活
     * @throws IllegalArgumentException 工具组已存在时抛出
     */
    public void createToolGroup(String groupName, String description, boolean active) {
        groupManager.createToolGroup(groupName, description, active);
    }

    /**
     * Create a new tool group with specified activation status and scope.
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group
     * @param active Whether the group should be active by default
     * @param scope Whether the group is managed by the meta tool ({@link ToolGroupScope#META})
     *              or by developer code ({@link ToolGroupScope#EXTERNAL})
     * @throws IllegalArgumentException if group already exists
     */
    /**
     * 创建一个新的工具组，并显式指定管理范围（scope）。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 是否在创建时默认激活
     * @param scope 工具组管理范围：由元工具（{@link ToolGroupScope#META}）还是由开发者代码（{@link ToolGroupScope#EXTERNAL}）管理
     * @throws IllegalArgumentException 工具组已存在时抛出
     */
    public void createToolGroup(
            String groupName, String description, boolean active, ToolGroupScope scope) {
        groupManager.createToolGroup(groupName, description, active, scope);
    }

    /**
     * Create a new tool group (active by default, META scope).
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group
     * @throws IllegalArgumentException if group already exists
     */
    /**
     * 创建一个新的工具组（默认激活、META scope）。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @throws IllegalArgumentException 工具组已存在时抛出
     */
    public void createToolGroup(String groupName, String description) {
        groupManager.createToolGroup(groupName, description);
    }

    /**
     * Create a {@link SkillToolGroup} bound to a specific skill.
     *
     * <p>The group defaults to {@link ToolGroupScope#META} scope so the agent can manage it
     * via {@code reset_equipped_tools}. The description shown to the model will include a
     * reminder that this group must be activated when the bound skill is in use.
     *
     * @param groupName Name of the tool group
     * @param description Description of the tool group
     * @param active Whether the group should be active by default
     * @param activateOnSkill The skill name that this group is bound to
     * @throws IllegalArgumentException if group already exists
     */
    /**
     * 创建一个绑定到具体技能的 {@link SkillToolGroup}。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 是否在创建时默认激活
     * @param activateOnSkill 当前工具组所绑定的技能名称
     * @throws IllegalArgumentException 工具组已存在时抛出
     */
    public void createSkillToolGroup(
            String groupName, String description, boolean active, String activateOnSkill) {
        groupManager.createSkillToolGroup(groupName, description, active, activateOnSkill);
    }

    /**
     * Register a pre-built {@link ToolGroup} instance (including subclasses).
     *
     * <p>Use this method when you need full control over the ToolGroup construction,
     * e.g., for custom subclasses like {@link SkillToolGroup}.
     *
     * @param group The tool group to register
     * @throws IllegalArgumentException if a group with the same name already exists
     */
    /**
     * 注册一个已经构造好的 {@link ToolGroup} 实例（含子类）。
     *
     * @param group 待注册的工具组实例
     * @throws IllegalArgumentException 存在同名工具组时抛出
     */
    public void registerToolGroup(ToolGroup group) {
        groupManager.registerToolGroup(group);
    }

    /**
     * Update the activation status of tool groups.
     *
     * <p>When {@code allowToolDeletion} is disabled and {@code active} is false, the deactivation
     * will be ignored and a warning will be logged.
     *
     * @param groupNames List of tool group names to update
     * @param active Whether to activate (true) or deactivate (false) the groups
     * @throws IllegalArgumentException if any group doesn't exist
     */
    /**
     * 批量更新工具组的激活状态。
     *
     * @param groupNames 待更新的工具组名称列表
     * @param active true 表示激活，false 表示停用
     * @throws IllegalArgumentException 任意工具组不存在时抛出
     */
    public void updateToolGroups(List<String> groupNames, boolean active) {
        if (!active && !config.isAllowToolDeletion()) {
            logger.warn(
                    "Tool deletion is disabled - ignoring deactivation of tool groups: {}",
                    groupNames);
            return;
        }
        groupManager.updateToolGroups(groupNames, active);
    }

    /**
     * Remove a tool by name from the toolkit.
     *
     * @param toolName Name of the tool to remove
     */
    /**
     * 从 Toolkit 中移除指定工具。
     *
     * @param toolName 待移除的工具名称
     */
    public void removeTool(String toolName) {
        if (!config.isAllowToolDeletion()) {
            logger.warn("Tool deletion is disabled - ignoring removal of tool: {}", toolName);
            return;
        }
        toolRegistry.removeTool(toolName);
    }

    /**
     * Atomically remove a tool only if the registered instance is the expected one.
     *
     * @param toolName Name of the tool to remove
     * @param expected The expected AgentTool instance (identity comparison)
     * @return true if the tool was removed, false if it was already replaced or absent
     */
    /**
     * 仅当当前注册的实例与期望实例一致时，才原子地移除该工具。
     *
     * @param toolName 待移除的工具名称
     * @param expected 期望的 AgentTool 实例（按对象身份比较）
     * @return 成功移除则返回 true；若已被替换或不存在则返回 false
     */
    public boolean removeToolIfSame(String toolName, AgentTool expected) {
        if (!config.isAllowToolDeletion()) {
            logger.warn("Tool deletion is disabled - ignoring removal of tool: {}", toolName);
            return false;
        }
        return toolRegistry.removeToolIfSame(toolName, expected);
    }

    /**
     * Remove tool groups and all tools within them.
     *
     * <p>When {@code allowToolDeletion} is disabled, the removal will be ignored and a warning
     * will be logged.
     *
     * @param groupNames List of tool group names to remove
     */
    /**
     * 移除指定工具组及其包含的全部工具。
     *
     * @param groupNames 待移除的工具组名称列表
     */
    public void removeToolGroups(List<String> groupNames) {
        if (!config.isAllowToolDeletion()) {
            logger.warn(
                    "Tool deletion is disabled - ignoring removal of tool groups: {}", groupNames);
            return;
        }
        // 先从组管理器取到受影响的工具名集合
        Set<String> toolsToRemove = groupManager.removeToolGroups(groupNames);
        // 再把这些工具从注册表中一并清理
        toolRegistry.removeTools(toolsToRemove);
    }

    /**
     * Get active tool group names.
     *
     * <p>Returns a list of all currently active tool group names. Only tools belonging to active
     * groups can be called by agents. This method is useful for debugging tool availability
     * and verifying group activation state.
     *
     * @return List of active group names, never null but may be empty
     */
    /**
     * 获取当前已激活的工具组名称列表。
     *
     * @return 当前已激活的工具组名称列表（不为 null，可能为空）
     */
    public List<String> getActiveGroups() {
        return groupManager.getActiveGroups();
    }

    /**
     * Set the active tool groups.
     *
     * <p>This method is typically called by ReActAgent when restoring state from a session.
     *
     * @param groups List of group names to set as active
     */
    /**
     * 直接设置当前激活的工具组集合。
     *
     * @param groups 将被设置为激活状态的工具组名称列表
     */
    public void setActiveGroups(List<String> groups) {
        groupManager.setActiveGroups(groups);
    }

    /**
     * Get a tool group by name.
     *
     * @param groupName Name of the tool group
     * @return ToolGroup or null if not found
     */
    /**
     * 按名称获取已注册的工具组。
     *
     * @param groupName 待查询的工具组名称
     * @return 工具组实例；不存在时返回 null
     */
    public ToolGroup getToolGroup(String groupName) {
        return groupManager.getToolGroup(groupName);
    }

    // ==================== Meta Tool Registration ====================

    /**
     * Register the meta tool that allows agents to dynamically manage tool groups.
     *
     * This creates a tool that wraps the toolkit's resetEquippedTools method,
     * allowing the agent to activate tool groups during execution.
     */
    /**
     * 注册允许代理动态管理工具组的元工具（reset_equipped_tools）。
     *
     * 该方法会构造一个包装 toolkit 内 resetEquippedTools 方法的工具，
     * 使代理能够在执行过程中激活工具组。
     */
    public void registerMetaTool() {
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();

        // 元工具始终注册为无组，确保代理任何时候都能调用 reset_equipped_tools
        registerAgentTool(metaTool, null, null, null, null);

        logger.info("Registered meta tool: reset_equipped_tools");
    }

    /**
     * Update preset parameters for a registered tool at runtime.
     *
     * <p>This method allows dynamic modification of preset parameters without re-registering the
     * tool. This is useful for updating session-specific context (like session IDs or timestamps)
     * or refreshing credentials.
     *
     * @param toolName The name of the tool to update
     * @param newPresetParameters The new preset parameters (null will be treated as empty map)
     * @throws IllegalArgumentException if the tool is not found
     */
    /**
     * 运行时更新已注册工具的预置参数。
     *
     * @param toolName 待更新工具的名称
     * @param newPresetParameters 新的预置参数（传入 null 时将被视为空 Map）
     * @throws IllegalArgumentException 未找到对应工具时抛出
     */
    public void updateToolPresetParameters(
            String toolName, Map<String, Object> newPresetParameters) {
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolName);
        if (registered == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        registered.updatePresetParameters(newPresetParameters);
        logger.debug("Updated preset parameters for tool '{}'", toolName);
    }

    // ==================== Deep Copy ====================

    /**
     * Create a deep copy of this toolkit.
     *
     * <p>Note: User-defined chunk callbacks are preserved during copy so they continue to work
     * when the toolkit is passed into ReActAgent.Builder and copied internally.
     *
     * @return A new Toolkit instance with copied state
     */
    /**
     * 创建当前 Toolkit 的深拷贝。
     *
     * @return 包含当前 Toolkit 状态副本的新 Toolkit 实例
     */
    public Toolkit copy() {
        Toolkit copy = new Toolkit(this.config);

        // 复制全部已注册工具到新实例
        this.toolRegistry.copyTo(copy.toolRegistry);

        // 复制全部工具组及其激活状态
        this.groupManager.copyTo(copy.groupManager);

        // 跨 Toolkit 拷贝保留用户自定义的分片回调（修复 Issue #870）
        copy.executor.setChunkCallback(this.executor.getChunkCallback());

        return copy;
    }

    /**
     * Fluent builder for registering tools with optional configuration.
     *
     * <p>This builder provides a clear, type-safe way to register tools with various options
     * without method proliferation.
     */
    /**
     * 用于以可选配置注册工具的流式构建器。
     *
     * <p>该构建器提供一种清晰、类型安全的方式来注册工具，避免引入大量重载方法。
     */
    public static class ToolRegistration {
        /** Owning Toolkit; all registrations are delegated to it. */
        /**
         * 构建器所属的 Toolkit 实例，最终注册操作均由该实例代理执行。
         */
        private final Toolkit toolkit;

        /** Tool POJO passed via tool(); scanned for @Tool methods on apply(). */
        /**
         * 通过 tool(...) 注册的 POJO 类型工具对象，构建时延迟扫描其 @Tool 方法。
         */
        private Object toolObject;

        /** AgentTool instance passed via agentTool(); mutually exclusive with toolObject/mcpClient/subAgent. */
        /**
         * 通过 agentTool(...) 注册的 AgentTool 实例（与 toolObject/mcpClient/subAgent 互斥）。
         */
        private AgentTool agentTool;

        /** MCP client wrapper passed via mcpClient(). */
        /**
         * 通过 mcpClient(...) 注册的 MCP 客户端封装对象。
         */
        private McpClientWrapper mcpClientWrapper;

        /** Sub-agent provider passed via subAgent(); instantiated per call or per session. */
        /**
         * 通过 subAgent(...) 注册的子代理提供器（用于按需创建子代理实例）。
         */
        private SubAgentProvider<?> subAgentProvider;

        /** Sub-agent tool configuration; null falls back to {@link SubAgentConfig#defaults()}. */
        /**
         * 子代理工具的配置项；为 null 时使用 {@link SubAgentConfig#defaults()} 默认配置。
         */
        private SubAgentConfig subAgentConfig;

        /** Target tool group name; null means the tool is ungrouped (always visible). */
        /**
         * 注册时归属的工具组名称；为 null 表示注册到无组（默认始终可见）空间。
         */
        private String groupName;

        /** Map of tool name -> preset parameters; filtered by tool name at apply() time. */
        /**
         * 工具名 → 该工具预置参数映射 的全局预置参数表，apply() 时按工具名筛选应用。
         */
        private Map<String, Map<String, Object>> presetParameters;

        /** Extended model for dynamic schema extension. */
        /**
         * 扩展模型（用于动态扩展 schema）；非 @Tool 反射工具路径上较少使用。
         */
        private ExtendedModel extendedModel;

        /** MCP allow-list; null means enable all tools. */
        /**
         * MCP 客户端启用工具白名单；为 null 表示启用全部工具。
         */
        private List<String> enableTools;

        /** MCP deny-list; applied after enableTools. */
        /**
         * MCP 客户端禁用工具黑名单；在 enableTools 之后生效。
         */
        private List<String> disableTools;

        private ToolRegistration(Toolkit toolkit) {
            this.toolkit = toolkit;
        }

        /**
         * Set the tool object to register (scans for @Tool methods).
         *
         * @param toolObject Object containing @Tool annotated methods
         * @return This builder for chaining
         */
        /**
         * 设置待注册的工具对象（自动扫描其中的 @Tool 注解方法）。
         *
         * @param toolObject 包含 @Tool 注解方法的对象
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration tool(Object toolObject) {
            this.toolObject = toolObject;
            return this;
        }

        /**
         * Set the AgentTool instance to register.
         *
         * @param agentTool The AgentTool instance
         * @return This builder for chaining
         */
        /**
         * 设置待注册的 AgentTool 实例。
         *
         * @param agentTool 待注册的 AgentTool 实例
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration agentTool(AgentTool agentTool) {
            this.agentTool = agentTool;
            return this;
        }

        /**
         * Set the MCP client to register.
         *
         * @param mcpClientWrapper The MCP client wrapper
         * @return This builder for chaining
         */
        /**
         * 设置待注册的 MCP 客户端。
         *
         * @param mcpClientWrapper MCP 客户端封装对象
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            this.mcpClientWrapper = mcpClientWrapper;
            return this;
        }

        /**
         * Register a sub-agent as a tool with default configuration.
         *
         * <p>The tool name and description are derived from the agent's properties. Uses a single
         * "task" string parameter by default.
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
        /**
         * 以默认配置将子代理注册为工具。
         *
         * @param provider 创建代理实例的工厂（每次调用都会触发）
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration subAgent(SubAgentProvider<?> provider) {
            return subAgent(provider, null);
        }

        /**
         * Register a sub-agent as a tool with custom configuration.
         *
         * <p>Sub-agents support multi-turn conversation with session-based state management. The
         * tool exposes two parameters: {@code message} (required) and {@code session_id} (optional,
         * for continuing existing conversations).
         *
         * <p>Example with custom tool name and description:
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
         *             .stateStore(new JsonFileAgentStateStore(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider Factory for creating agent instances (called for each session)
         * @param config Configuration for the sub-agent tool, or null to use defaults (tool name
         *     derived from agent name, InMemoryAgentStateStore for state, events forwarded)
         * @return This builder for chaining
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        /**
         * 以自定义配置将子代理注册为工具。
         *
         * @param provider 创建代理实例的工厂（每个会话都会触发一次）
         * @param config 子代理工具的配置；传入 null 时将使用默认配置（工具名取自代理名称、
         *     使用 InMemoryAgentStateStore 作为状态存储、事件默认转发）
         * @return 当前构建器自身，便于链式调用
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        public ToolRegistration subAgent(SubAgentProvider<?> provider, SubAgentConfig config) {
            this.subAgentProvider = provider;
            this.subAgentConfig = config;
            return this;
        }

        /**
         * Set the list of tools to enable from the MCP client.
         *
         * <p>Only applicable when using mcpClient(). If not specified, all tools are enabled.
         *
         * @param enableTools List of tool names to enable
         * @return This builder for chaining
         */
        /**
         * 设置从 MCP 客户端启用的工具白名单。
         *
         * @param enableTools 需要启用的工具名称列表
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration enableTools(List<String> enableTools) {
            this.enableTools = enableTools;
            return this;
        }

        /**
         * Set the list of tools to disable from the MCP client.
         *
         * <p>Only applicable when using mcpClient().
         *
         * @param disableTools List of tool names to disable
         * @return This builder for chaining
         */
        /**
         * 设置从 MCP 客户端禁用的工具黑名单。
         *
         * @param disableTools 需要禁用的工具名称列表
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration disableTools(List<String> disableTools) {
            this.disableTools = disableTools;
            return this;
        }

        /**
         * Set the tool group name.
         *
         * @param groupName The group name (null for ungrouped)
         * @return This builder for chaining
         */
        /**
         * 设置工具组名称。
         *
         * @param groupName 工具组名称（传入 null 表示不归属任何组）
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration group(String groupName) {
            this.groupName = groupName;
            return this;
        }

        /**
         * Set preset parameters that will be automatically injected during tool execution.
         *
         * <p>These parameters are not exposed in the JSON schema.
         *
         * <p>The map should have tool names as keys and parameter maps as values:
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
        /**
         * 设置将在工具执行时自动注入的预置参数。
         *
         * @param presetParameters 工具名称到对应预置参数的映射
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration presetParameters(
                Map<String, Map<String, Object>> presetParameters) {
            this.presetParameters = presetParameters;
            return this;
        }

        /**
         * Set the extended model for dynamic schema extension.
         *
         * @param extendedModel The extended model
         * @return This builder for chaining
         */
        /**
         * 设置用于动态扩展 schema 的扩展模型。
         *
         * @param extendedModel 扩展模型实例
         * @return 当前构建器自身，便于链式调用
         */
        public ToolRegistration extendedModel(ExtendedModel extendedModel) {
            this.extendedModel = extendedModel;
            return this;
        }

        /**
         * Apply the registration with all configured options.
         *
         * @throws IllegalStateException if none of tool(), agentTool(), mcpClient() or subAgent() was set
         * @throws IllegalStateException if set multiple of: tool(), agentTool(), mcpClient(), or subAgent().
         */
        /**
         * 应用全部已配置的注册选项。
         *
         * @throws IllegalStateException 未调用 tool()、agentTool()、mcpClient() 或 subAgent() 中任意一个时抛出
         * @throws IllegalStateException 同时设置了多个注册来源（tool()、agentTool()、mcpClient()、subAgent()）时抛出
         */
        public void apply() {
            // 统计已配置的注册来源数量，确保恰好为 1
            int toolCount = 0;
            if (toolObject != null) toolCount++;
            if (agentTool != null) toolCount++;
            if (mcpClientWrapper != null) toolCount++;
            if (subAgentProvider != null) toolCount++;

            if (toolCount == 0) {
                // 调用方未指明注册来源
                throw new IllegalStateException(
                        "Must call one of: tool(), agentTool(), mcpClient(), or subAgent() before"
                                + " apply()");
            }
            if (toolCount > 1) {
                // 多种注册来源互斥，不允许混用
                throw new IllegalStateException(
                        "Cannot set multiple registration types. Use only one of: tool(),"
                                + " agentTool(), mcpClient(), or subAgent().");
            }

            // 按唯一选中的注册来源分派给 Toolkit 内部的对应方法
            if (toolObject != null) {
                // POJO 模式：扫描其中的 @Tool 注解方法
                toolkit.registerTool(toolObject, groupName, extendedModel, presetParameters);
            } else if (agentTool != null) {
                // 单实例 AgentTool 模式
                String toolName = agentTool.getName();
                Map<String, Object> toolPresets =
                        (presetParameters != null && presetParameters.containsKey(toolName))
                                ? presetParameters.get(toolName)
                                : null;
                toolkit.registerAgentTool(agentTool, groupName, extendedModel, null, toolPresets);
            } else if (mcpClientWrapper != null) {
                // MCP 客户端模式：阻塞等待注册完成（apply() 是同步语义）
                toolkit.mcpClientManager
                        .registerMcpClient(
                                mcpClientWrapper,
                                enableTools,
                                disableTools,
                                groupName,
                                presetParameters)
                        .block();
            } else if (subAgentProvider != null) {
                // 子代理模式：包装为 SubAgentTool 后注册
                SubAgentTool subAgentTool = new SubAgentTool(subAgentProvider, subAgentConfig);
                toolkit.registerAgentTool(subAgentTool, groupName, extendedModel, null, null);
            }
        }
    }
}
