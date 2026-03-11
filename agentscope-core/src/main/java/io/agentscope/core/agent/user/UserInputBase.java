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
package io.agentscope.core.agent.user;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Strategy interface for handling user input from different sources.
 * 不同来源处理用户输入的策略接口
 * Enables pluggable input implementations such as terminal console, web UI, or programmatic
 * sources. Implementations convert raw user input into UserInputData containing both content
 * blocks and optional structured data, maintaining consistency across different input channels.
 * 启用可插拔输入实现，如终端控制台、Web UI或编程来源。
 * 实现将原始用户输入转换为包含内容块和可选结构化数据的UserInputData，
 * 在不同输入渠道之间保持一致性。
 */
public interface UserInputBase {

    /**
     * Handle user input and return the input data.
     * 处理用户输入并返回输入数据
     *
     * @param agentId The agent identifier
     * @param agentName The agent name
     * @param contextMessages Optional messages to display before prompting (e.g., assistant
     *     response)
     * 上下文消息，在提示之前显示（例如，助手响应）
     * @param structuredModel Optional class for structured input format
     * 结构化输入格式的可选类
     * @return Mono containing the user input data
     * 返回包含用户输入数据的Mono
     */
    Mono<UserInputData> handleInput(
            String agentId, String agentName, List<Msg> contextMessages, Class<?> structuredModel);
}
