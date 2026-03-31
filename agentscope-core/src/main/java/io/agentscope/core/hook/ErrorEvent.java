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
import java.util.Objects;

/**
 * Event fired when an error occurs during agent execution.
 * 智能体执行过程中发生错误时触发的事件。
 *
 * <p><b>Modifiable:</b> No (notification-only)
 *       可修改：否（仅通知）
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getError()} - The error that occurred</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Log errors with context</li>
 *       使用上下文记录错误
 *   <li>Send error notifications</li>
 *       发送错误通知
 *   <li>Collect error metrics</li>
 *       收集错误指标
 *   <li>Implement custom error handling</li>
 *       实现自定义错误处理
 * </ul>
 */
public final class ErrorEvent extends HookEvent {

    private final Throwable error;

    /**
     * Constructor for ErrorEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param error The error that occurred (must not be null)
     * @throws NullPointerException if agent or error is null
     */
    public ErrorEvent(Agent agent, Throwable error) {
        super(HookEventType.ERROR, agent);
        this.error = Objects.requireNonNull(error, "error cannot be null");
    }

    /**
     * Get the error that occurred.
     *
     * @return The error
     */
    public Throwable getError() {
        return error;
    }
}
