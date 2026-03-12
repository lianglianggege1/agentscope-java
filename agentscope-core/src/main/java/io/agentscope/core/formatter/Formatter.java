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
package io.agentscope.core.formatter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;

/**
 * Formatter interface for converting between AgentScope and provider-specific formats.
 * This is an internal interface used by Model implementations.
 * 用于在AgentScope和提供商特定格式之间进行转换的格式化器接口
 *
 * <p>Formatters are responsible for:
 * 1. Converting Msg objects to provider-specific request format
 * 将Msg对象转化为提供商特定的请求格式
 * 2. Converting provider-specific responses back to AgentScope ChatResponse
 * 将提供商特定的响应转换回AgentScope ChatResponse
 * 3. Applying generation options to provider-specific request builders
 * 将生成选项应用于提供商特定的请求构建器
 * 4. Applying tool schemas to provider-specific request builders
 * 将工具模式应用于特定于提供商的请求构建器
 *
 * <p>Each formatter is type-safe and handles the exact types expected by the provider's SDK.
 * 每个格式化器都是类型安全的，并且能够处理提供商SDK所期望的确切类型
 *
 * @param <TReq> Provider-specific request message type (e.g., com.alibaba.dashscope.common.Message
 *               for DashScope, or ChatCompletionMessageParam for OpenAI)
 * 提供商特定的请求消息类型（例如，DashScope 的 com.alibaba.dashscope.common.Message，
 * 或 OpenAI 的 ChatCompletionMessageParam）
 * @param <TResp> Provider-specific response type (e.g., GenerationResult for DashScope,
 *                or ChatCompletion/ChatCompletionChunk for OpenAI)
 * 提供商特定的响应类型
 * （例如，DashScope 的 GenerationResult，或 OpenAI 的 ChatCompletion/ChatCompletionChunk）
 * @param <TParams> Provider-specific request parameters builder type (e.g.,
 *                  GenerationParam for DashScope, or ChatCompletionCreateParams.Builder for OpenAI)
 * 提供商特定的请求参数构建器类型（例如，DashScope 的 GenerationParam，或 OpenAI 的 ChatCompletionCreateParams.Builder）
 */
public interface Formatter<TReq, TResp, TParams> {

    /**
     * Format AgentScope messages to provider-specific request format.
     * 将 AgentScope 消息格式化为提供商特定的请求格式。
     *
     * @param msgs List of AgentScope messages
     *        AgentScope 消息列表
     * @return List of provider-specific request messages
     *         提供商特定请求消息列表
     */
    List<TReq> format(List<Msg> msgs);

    /**
     * Parse provider-specific response to AgentScope ChatResponse.
     * 解析 AgentScope ChatResponse 中特定提供商的响应。
     *
     * @param response Provider-specific response object
     * @param startTime Request start time for calculating duration 请求开始时间以计算持续时间
     * @return AgentScope ChatResponse
     */
    ChatResponse parseResponse(TResp response, Instant startTime);

    /**
     * Apply generation options to provider-specific request parameters.
     * 将生成选项应用于提供商特定的请求参数。
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    void applyOptions(
            TParams paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions);

    /**
     * Apply tool schemas to provider-specific request parameters.
     * 将工具模式应用到特定于提供商的请求参数中。
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    void applyTools(TParams paramsBuilder, List<ToolSchema> tools);

    /**
     * Apply tool schemas to provider-specific request parameters with provider compatibility handling.
     * 将工具模式应用于特定于提供商的请求参数，并处理提供商兼容性问题。
     *
     * <p>This method allows formatters to detect the provider from baseUrl/modelName and adjust
     * tool definitions for compatibility (e.g., removing unsupported parameters like 'strict').
     * 此方法允许格式化程序从 baseUrl/modelName 检测提供程序，并调整
     * 工具定义以实现兼容性（例如，删除不支持的参数，如“strict”）。
     *
     * <p>The default implementation delegates to {@code applyTools(TParams, List)}.
     * Formatters that support provider-specific tool handling should override this method.
     * 默认实现会将操作委托给 applyTools(TParams, List)。支持特定提供程序工具处理的格式化程序应重写此方法。
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param tools Tool schemas to apply (may be null or empty)
     * @param baseUrl API base URL for provider detection (null for default)
     * @param modelName Model name for provider detection fallback (null)
     */
    default void applyTools(
            TParams paramsBuilder, List<ToolSchema> tools, String baseUrl, String modelName) {
        // Default implementation: delegate to the simpler method
        applyTools(paramsBuilder, tools);
    }

    /**
     * Apply tool choice configuration to provider-specific request parameters.
     * 将工具选择配置应用于提供商特定的请求参数。
     *
     * <p>This method controls how the model uses tools. The default implementation does nothing,
     * allowing formatters to override for providers that support tool choice configuration.
     * <p>此方法控制模型如何使用工具。默认实现不执行任何操作，允许格式化程序为支持工具选择配置的提供程序覆盖此设置。
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * 提供商特定请求参数构建器
     * @param toolChoice Tool choice configuration (null means provider default)
     * 工具选择配置（null 表示提供商默认值）
     */
    default void applyToolChoice(TParams paramsBuilder, ToolChoice toolChoice) {
        // Default implementation: do nothing
        // Subclasses can override to provide provider-specific behavior
    }

    /**
     * Apply tool choice configuration to provider-specific request parameters with provider detection.
     * 将工具选择配置应用于提供商特定的请求参数，并进行提供商检测。
     *
     * <p>This method allows formatters to detect the provider from baseUrl/modelName and adjust
     * tool_choice format or gracefully degrade when the provider doesn't support certain options.
     * <p>此方法允许格式化程序从 baseUrl/modelName 检测提供程序，并调整 tool_choice 格式，或者在提供程序不支持某些选项时优雅地降级。
     *
     * <p>The default implementation delegates to {@code applyToolChoice(TParams, ToolChoice)}.
     * Formatters that support provider-specific tool_choice handling should override this method.
     * <p>默认实现委托给 {@code applyToolChoice(TParams, ToolChoice)}。支持特定于提供程序的 tool_choice 处理的格式化程序应重写此方法。
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param toolChoice Tool choice configuration (null means provider default)
     * @param baseUrl API base URL for provider detection (null for default)
     * @param modelName Model name for provider detection fallback (null)
     */
    default void applyToolChoice(
            TParams paramsBuilder, ToolChoice toolChoice, String baseUrl, String modelName) {
        // Default implementation: delegate to the simpler method
        applyToolChoice(paramsBuilder, toolChoice);
    }
}
