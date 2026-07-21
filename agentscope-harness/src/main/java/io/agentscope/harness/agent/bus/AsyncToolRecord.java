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
package io.agentscope.harness.agent.bus;

import java.time.Instant;

/**
 * Tracks the lifecycle of an async tool execution that has been offloaded to the background.
 *
 * @param id          unique identifier for this async tool execution
 * @param sessionId   the session that initiated the tool call
 * @param toolName    name of the tool being executed
 * @param toolCallId  the original tool_call id from the LLM
 * @param status      current status: RUNNING, COMPLETED, FAILED, or TIMEOUT
 * @param createdAt   when the async execution was registered
 */
/**
 * 用于记录后台异步执行工具任务的全生命周期信息。
 *
 * @param id          本条异步工具执行任务的唯一标识
 * @param sessionId   发起该工具调用的会话ID
 * @param toolName    待执行工具名称
 * @param toolCallId  大模型下发的原始工具调用ID
 * @param status      当前状态：运行中、已完成、执行失败、已超时
 * @param createdAt   该异步任务的注册时间
 */
public record AsyncToolRecord(
        String id,
        String sessionId,
        String toolName,
        String toolCallId,
        String status,
        Instant createdAt) {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String TIMEOUT = "TIMEOUT";
}
