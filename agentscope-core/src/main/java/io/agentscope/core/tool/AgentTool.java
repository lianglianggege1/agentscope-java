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

import io.agentscope.core.message.ToolResultBlock;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Interface for agent tools that can be called by models.
 * 模型可以调用的智能体工具的接口。
 *
 * <p>Agent tools are functions that AI agents can invoke to perform actions or retrieve
 * information. They bridge the gap between the agent's reasoning and the external world.
 * 智能体工具是AI agent可以调用以执行操作或检索信息的功能。它们弥合了智能体的推理和外部世界之间的差距。
 *
 * <p><b>Implementation Guidelines:</b>
 * <ul>
 *   <li>Tools should have clear, descriptive names 工具应具有清晰、描述性的名称</li>
 *   <li>Descriptions should explain what the tool does and when to use it 说明应解释工具的功能以及何时使用</li>
 *   <li>Parameter schemas must follow JSON Schema format 参数模式必须遵循JSON模式格式</li>
 *   <li>All operations should be implemented asynchronously using Reactor Mono 所有操作都应使用Reactor Mono异步执行</li>
 * </ul>
 *
 * @see Tool
 * @see ToolParam
 * @see Toolkit
 */
public interface AgentTool {

    /**
     * Gets the name of the tool.
     *
     * <p>The name should be unique within a toolkit and follow snake_case convention for
     * compatibility with various LLM providers.
     *
     * @return The tool name (never null)
     */
    String getName();

    /**
     * Gets the description of the tool.
     *
     * <p>The description should clearly explain what the tool does, when it should be used, and
     * what kind of results it returns. This helps the LLM decide when to invoke the tool.
     *
     * @return The tool description (never null)
     */
    String getDescription();

    /**
     * Gets the parameters schema for this tool in JSON Schema format.
     *
     * <p>The schema defines the structure of the input parameters that this tool accepts. It
     * should include:
     * <ul>
     *   <li>type: "object"</li>
     *   <li>properties: Map of parameter names to their schemas</li>
     *   <li>required: List of required parameter names</li>
     * </ul>
     *
     * @return Map representing the JSON Schema for tool parameters (never null)
     */
    Map<String, Object> getParameters();

    /**
     * Execute the tool with the given parameters (asynchronous).
     * 执行工具（异步）。
     *
     * <p>This method accepts a {@link ToolCallParam} object containing all necessary context for
     * tool execution, including:
     * <ul>
     *   <li>toolUseBlock: Contains tool call ID and name for tracking</li>
     *   <li>input: Input parameters for the tool</li>
     *   <li>agent: The calling agent (may be null), provides access to agent context</li>
     * </ul>
     *
     * @param param The tool call parameters
     * @return Mono containing ToolResultBlock
     */
    Mono<ToolResultBlock> callAsync(ToolCallParam param);
}
