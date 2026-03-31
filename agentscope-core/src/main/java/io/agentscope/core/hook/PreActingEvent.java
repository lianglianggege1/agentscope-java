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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/**
 * Event fired before tool execution.
 * 在工具执行之前触发的事件
 *
 * <p><b>Modifiable:</b> Yes - {@link #setToolUse(ToolUseBlock)}
 *       可修改 - {@link #setToolUse(ToolUseBlock)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getToolkit()} - The toolkit instance</li>
 *   <li>{@link #getToolUse()} - The tool call to execute (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> This is called once per tool. If the reasoning result contains
 * multiple tool calls, this event fires multiple times.
 * 每个工具只会调用一次此事件。如果推理结果包含多个工具调用，则此事件会触发多次。
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Validate or modify tool parameters for each tool call</li>
 *       验证或修改每个工具调用的参数
 *   <li>Add authentication or context to individual tool calls</li>
 *       为单个工具调用添加身份验证或上下文
 *   <li>Implement per-tool authorization checks</li>
 *       实现每个工具授权检查
 *   <li>Log or monitor individual tool invocations</li>
 *       跟踪或监控单个工具调用
 * </ul>
 */
public final class PreActingEvent extends ActingEvent {

    /**
     * Constructor for PreActingEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param toolkit The toolkit instance (must not be null)
     * @param toolUse The tool call to execute (must not be null)
     * @throws NullPointerException if agent, toolkit, or toolUse is null
     */
    public PreActingEvent(Agent agent, Toolkit toolkit, ToolUseBlock toolUse) {
        super(HookEventType.PRE_ACTING, agent, toolkit, toolUse);
    }

    /**
     * Modify the tool call (e.g., change parameters).
     *
     * @param toolUse The new tool use block (must not be null)
     * @throws NullPointerException if toolUse is null
     */
    public void setToolUse(ToolUseBlock toolUse) {
        this.toolUse = Objects.requireNonNull(toolUse, "toolUse cannot be null");
    }
}
