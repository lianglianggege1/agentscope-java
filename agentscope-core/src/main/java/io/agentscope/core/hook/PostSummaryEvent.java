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

/**
 * Event fired after summary generation completes.
 * 摘要生成完成后触发事件。
 *
 * <p><b>Modifiable:</b> Yes - {@link #setSummaryMessage(Msg)}
 *       可修改的- {@link #setSummaryMessage(Msg)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getModelName()} - The model name</li>
 *   <li>{@link #getGenerateOptions()} - The generation options</li>
 *   <li>{@link #getSummaryMessage()} - The summary result (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> This event is fired after the summary has been generated, allowing hooks
 * to modify the final summary message before it's returned.
 * 此事件在摘要生成后触发，允许hook在返回最终摘要消息之前对其进行修改。
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Filter or modify the summary content</li>
 *       过滤或修改摘要内容
 *   <li>Add metadata to the summary message</li>
 *       添加元数据到摘要消息中
 *   <li>Log the summary result</li>
 *       记录摘要结果
 *   <li>Request to stop the agent via {@link #stopAgent()}</li>
 *       请求通过 {@link #stopAgent()} 停止
 * </ul>
 */
public final class PostSummaryEvent extends SummaryEvent {

    private Msg summaryMessage;
    private boolean stopRequested = false;

    /**
     * Constructor for PostSummaryEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param summaryMessage The summary result message (may be null)
     */
    public PostSummaryEvent(
            Agent agent, String modelName, GenerateOptions generateOptions, Msg summaryMessage) {
        super(HookEventType.POST_SUMMARY, agent, modelName, generateOptions);
        this.summaryMessage = summaryMessage;
    }

    /**
     * Get the summary result message.
     *
     * @return The summary message, may be null
     */
    public Msg getSummaryMessage() {
        return summaryMessage;
    }

    /**
     * Modify the summary result message.
     *
     * @param summaryMessage The new summary message
     */
    public void setSummaryMessage(Msg summaryMessage) {
        this.summaryMessage = summaryMessage;
    }

    /**
     * Request to stop the agent after this summary phase.
     * 请求智能体在此摘要阶段结束后停止。
     *
     * <p>When called, the agent will return the summary message as the final result.
     * This is primarily for consistency with other event types; since summary is typically
     * the last phase, this mainly serves as a signal for logging or metrics purposes.
     * 被调用时，代理会将摘要消息作为最终结果返回。
     * 这主要是为了与其他事件类型保持一致；由于摘要通常是最后一个阶段，因此它主要用作日志记录或指标分析的信号。
     */
    public void stopAgent() {
        this.stopRequested = true;
    }

    /**
     * Check if a stop has been requested.
     * 检查是否已请求停车。
     *
     * @return true if {@link #stopAgent()} has been called, false otherwise
     */
    public boolean isStopRequested() {
        return stopRequested;
    }
}
