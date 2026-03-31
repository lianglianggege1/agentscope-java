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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Event fired before LLM reasoning.
 * 在LLM推理之前执行的事件
 *
 * <p><b>Modifiable:</b> Yes - {@link #setInputMessages(List)}
 *       可修改：Yes - {@link #setInputMessages(List)}
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance 智能体实例</li>
 *   <li>{@link #getMemory()} - Agent's memory 智能体的记忆</li>
 *   <li>{@link #getModelName()} - The model name (e.g., "qwen-plus") 模型的名称（例如： "qwen-plus"）</li>
 *   <li>{@link #getGenerateOptions()} - The generation options (temperature, etc.) 可生成的选项（温度等）</li>
 *   <li>{@link #getInputMessages()} - Messages to send to LLM (modifiable)要发送给LLM的消息（可修改） </li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 *       使用用例：
 * <ul>
 *   <li>Inject hints or additional context into the prompt</li>
 *       在提示中插入提示信息或补充上下文
 *   <li>Filter or modify existing messages</li>
 *       筛选或修改现有消息
 *   <li>Add system instructions dynamically</li>
 *       动态添加系统指令
 *   <li>Log reasoning input</li>
 *       记录推理输入
 * </ul>
 */
public final class PreReasoningEvent extends ReasoningEvent {

    private List<Msg> inputMessages;
    private GenerateOptions overriddenGenerateOptions;

    /**
     * Constructor for PreReasoningEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param inputMessages The messages to send to LLM (must not be null)
     * @throws NullPointerException if agent, modelName, or inputMessages is null
     */
    public PreReasoningEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            List<Msg> inputMessages) {
        super(HookEventType.PRE_REASONING, agent, modelName, generateOptions);
        this.inputMessages =
                new ArrayList<>(
                        Objects.requireNonNull(inputMessages, "inputMessages cannot be null"));
    }

    /**
     * Get the messages that will be sent to LLM for reasoning.
     *
     * @return The input messages
     */
    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    /**
     * Modify the messages to send to LLM.
     *
     * @param inputMessages The new message list (must not be null)
     * @throws NullPointerException if inputMessages is null
     */
    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = Objects.requireNonNull(inputMessages, "inputMessages cannot be null");
    }

    /**
     * Get the effective generation options.
     *
     * <p>Returns the overridden options if set via {@link #setGenerateOptions(GenerateOptions)},
     * otherwise returns the original options from the parent class.
     *
     * @return The effective generation options
     */
    public GenerateOptions getEffectiveGenerateOptions() {
        return overriddenGenerateOptions != null
                ? overriddenGenerateOptions
                : super.getGenerateOptions();
    }

    /**
     * Set custom generation options for this reasoning call.
     *
     * <p>This allows hooks to override the default generation options, for example to set
     * a specific tool_choice for structured output.
     *
     * @param generateOptions The custom generation options
     */
    public void setGenerateOptions(GenerateOptions generateOptions) {
        this.overriddenGenerateOptions = generateOptions;
    }
}
