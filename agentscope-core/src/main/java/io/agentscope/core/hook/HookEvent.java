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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.Memory;
import java.util.Objects;

/**
 * Base class for all hook events.
 * 所有hook事件的基类
 *
 * <p>This is a sealed class - only the predefined event types are permitted.
 * 这是一个密封类 - 只允许预定义的事件类型。
 * This enables exhaustive pattern matching in switch expressions.
 * 启用 exhaustive pattern matching 在 switch 表达式中。
 *
 * <p>All events provide access to common context:
 *    所有事件都提供了获取共同背景信息的途径：
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance 智能体实例</li>
 *   <li>{@link #getMemory()} - Convenient access to agent's memory (may be null) 方便的访问智能体的记忆（可能为空） </li>
 *   <li>{@link #getType()} - The event type 事件类型</li>
 *   <li>{@link #getTimestamp()} - When the event occurred 事件发生时</li>
 * </ul>
 *
 * <p><b>Modifiability:</b> Whether an event allows modification is determined by
 * the presence of setter methods in the concrete event class.
 * <b>Modifiability:</b> 确定事件是否允许修改的方式是，在具体事件类中存在设置方法。
 *
 * @see Hook
 * @see HookEventType
 */
public abstract sealed class HookEvent
        permits PreCallEvent, PostCallEvent, ReasoningEvent, ActingEvent, SummaryEvent, ErrorEvent {

    private final HookEventType type;
    private final Agent agent;
    private final long timestamp;

    /**
     * Constructor for HookEvent.
     *
     * @param type The event type (must not be null)
     * @param agent The agent instance (must not be null)
     * @throws NullPointerException if type or agent is null
     */
    protected HookEvent(HookEventType type, Agent agent) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Get the event type.
     *
     * @return The event type
     */
    public final HookEventType getType() {
        return type;
    }

    /**
     * Get the agent instance.
     *
     * @return The agent instance (never null)
     */
    public final Agent getAgent() {
        return agent;
    }

    /**
     * Get the timestamp when event was created.
     *
     * @return The timestamp (milliseconds since epoch)
     */
    public final long getTimestamp() {
        return timestamp;
    }

    /**
     * Convenient access to agent's memory.
     *
     * @return The memory, or null if agent doesn't have memory
     */
    public final Memory getMemory() {
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent.getMemory();
        }
        return null;
    }
}
