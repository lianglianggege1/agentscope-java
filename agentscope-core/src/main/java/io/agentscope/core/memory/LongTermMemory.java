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
package io.agentscope.core.memory;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for long-term memory implementations.
 * 基本抽象类长期记忆实现
 *
 * <p>This class provides a time-series memory management system that persists information
 * beyond individual conversation sessions. Long-term memory enables agents to:
 * 这个类提供一个时间序列记忆管理系统，该系统在会话会话之间持久化信息。
 * <ul>
 *   <li>Remember user preferences, habits, and personal information across sessions
 *       记住用户偏好、习惯和个人信息
 *   <li>Learn from past interactions and improve over time
 *       从过去进行学习并随着时间的推移而改进
 *   <li>Maintain context for long-running tasks or projects
 *       保持长 Running 任务或项目的上下文
 *   <li>Build personalized experiences based on historical data
 *       构建基于历史数据的个性化体验
 * </ul>
 *
 * <p>This class defines the core memory API for framework-level integration:
 *    这个类定义框架级集成的核心记忆API：
 * <ul>
 *   <li>{@link #record(List)} - Record messages to memory (called by framework) 记录信息到记忆（由框架调用）
 *   <li>{@link #retrieve(Msg)} - Retrieve relevant memories (called by framework) 取回相关记忆（由框架调用）
 * </ul>
 *
 * <p>For agent-controlled memory operations (AGENT_CONTROL mode), use {@link LongTermMemoryTools}
 * which provides tool functions that adapt these core methods for agent use.
 * 对于代理控制记忆操作（AGENT_CONTROL模式），请使用LongTermMemoryTools，该工具将这些核心方法适配为代理使用。
 *
 * <p>All methods are asynchronous and return Reactor {@link Mono} types for non-blocking
 * integration with the agent framework.
 * 全部方法都是异步的，并返回Reactor的Mono类型，用于非阻塞集成到代理框架中。
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create long-term memory instance
 * LongTermMemoryBase longTermMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .build();
 *
 * // Use in ReActAgent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(longTermMemory)
 *     .longTermMemoryMode(LongTermMemoryMode.BOTH)
 *     .build();
 * }</pre>
 *
 * @see LongTermMemoryMode
 * @see io.agentscope.core.ReActAgent
 */
public interface LongTermMemory {

    /**
     * Records messages to long-term memory.
     *
     * <p>This is a developer-facing method designed to be called by the framework
     * (e.g., automatically at the end of each agent reply). Implementations should
     * extract meaningful information from the messages and persist it to the underlying
     * memory store.
     *
     * <p>The method filters out null messages before processing. Empty lists are handled
     * gracefully without error.
     *
     * <p><b>Framework Integration:</b> When {@link LongTermMemoryMode#STATIC_CONTROL} or
     * {@link LongTermMemoryMode#BOTH} is configured, this method is called automatically
     * after each agent reply to record the conversation.
     *
     * @param msgs List of messages to record (null entries are filtered out)
     * @return A Mono that completes when recording is finished
     */
    Mono<Void> record(List<Msg> msgs);

    /**
     * Retrieves relevant information from long-term memory based on the input message.
     *
     * <p>This is a developer-facing method designed to be called by the framework
     * (e.g., automatically at the beginning of each agent reply). Implementations should
     * use the message content to search for relevant memories and return them as text.
     *
     * <p>The returned text is typically added to the agent's system prompt to provide
     * context from previous interactions.
     *
     * <p><b>Framework Integration:</b> When {@link LongTermMemoryMode#STATIC_CONTROL} or
     * {@link LongTermMemoryMode#BOTH} is configured, this method is called automatically
     * before each agent reasoning step to inject relevant context.
     *
     * @param msg The message to use as a query for memory retrieval
     * @return A Mono emitting the retrieved memory text (may be empty)
     */
    Mono<String> retrieve(Msg msg);
}
