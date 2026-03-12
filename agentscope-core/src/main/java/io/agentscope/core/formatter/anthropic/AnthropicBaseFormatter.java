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
package io.agentscope.core.formatter.anthropic;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;

/**
 * Abstract base formatter for Anthropic API with shared logic for handling Anthropic-specific
 * requirements.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>System message extraction and application (Anthropic requires system via system parameter)
 *   <li>Tool choice configuration with GenerateOptions
 * </ul>
 */
public abstract class AnthropicBaseFormatter
        extends AbstractBaseFormatter<MessageParam, Object, MessageCreateParams.Builder> {

    protected final AnthropicMessageConverter messageConverter;

    /** Thread-local storage for generation options (passed from applyOptions to applyTools). */
    // 用于生成选项的线程本地存储（从 applyOptions 传递给 applyTools）。
    private final ThreadLocal<GenerateOptions> currentOptions = new ThreadLocal<>();

    protected AnthropicBaseFormatter() {
        this.messageConverter = new AnthropicMessageConverter(this::convertToolResultToString);
    }

    /**
     * Apply generation options to Anthropic request parameters.
     * 将生成选项应用于 Anthropic 请求参数。
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    @Override
    public void applyOptions(
            MessageCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // Save options for applyTools
        currentOptions.set(options);

        // Apply other options
        AnthropicToolsHelper.applyOptions(paramsBuilder, options, defaultOptions);
    }

    /**
     * Apply tool schemas to Anthropic request parameters. This method uses the options saved from
     * applyOptions to apply tool choice configuration.
     * 将工具模式应用于 Anthropic 请求参数。此方法使用从 `applyOptions` 保存的选项来应用工具选择配置。
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    @Override
    public void applyTools(MessageCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            currentOptions.remove();
            return;
        }

        // Use saved options to apply tools with tool choice
        GenerateOptions options = currentOptions.get();
        AnthropicToolsHelper.applyTools(paramsBuilder, tools, options);

        // Clean up thread-local storage
        currentOptions.remove();
    }

    /**
     * Extract and apply system message if present. Anthropic API requires system message to be set
     * via the system parameter, not as a message.
     * 提取并应用系统消息（如有）。Anthropic API 要求系统消息通过系统参数设置，而不是作为消息本身
     * 
     * <p>This method is called by Model to extract the first system message from the messages list
     * and apply it to the system parameter.
     * 此方法由模型调用，用于从消息列表中提取第一条系统消息并将其应用于系统参数。
     *
     * @param paramsBuilder Anthropic request parameters builder
     * @param messages All messages including potential system message
     */
    public void applySystemMessage(MessageCreateParams.Builder paramsBuilder, List<Msg> messages) {
        String systemMessage = messageConverter.extractSystemMessage(messages);
        if (systemMessage != null && !systemMessage.isEmpty()) {
            paramsBuilder.system(systemMessage);
        }
    }
}
