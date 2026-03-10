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
package io.agentscope.core.agent;

import io.agentscope.core.message.Msg;

/**
 * Complete agent interface combining all capabilities.
 * 包含所有能力的代理接口
 *
 * <p>This interface defines the core contract for agents, combining:
 * 这个接口定义了agent的核心功能，包含以下内容
 * <ul>
 *   <li>{@link CallableAgent} - Process messages and generate responses</li>
 *   处理消息并生成响应
 *   <li>{@link StreamableAgent} - Stream events during execution</li>
 *   持续流式执行
 *   <li>{@link ObservableAgent} - Observe messages without responding</li>
 *   观察消息，不生成响应
 * </ul>
 *
 * <p>Design Philosophy:
 * 设计理念
 * <ul>
 *   <li>Memory management is NOT part of the core Agent interface - it's the responsibility
 *       of specific agent implementations (e.g., ReActAgent)</li>
 *   内存管理并非agent核心接口的一部分-而是特定代理实现的职责(如ReActAgent)
 *   <li>Structured output is a specialized capability provided by specific agents</li>
 *   结构化输出是特定agent的特殊功能
 *   <li>Observe pattern allows agents to receive messages without generating a reply,
 *       enabling multi-agent collaboration</li>
 *   观察模式允许代理接收消息而不生成回复，从而实现多代理合作
 * </ul>
 *
 * <p>All agents in the AgentScope framework should implement this interface.
 * AgentScope框架中的所有agent都会实现了这个接口
 *
 */
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {

    /**
     * Get the unique identifier for this agent.
     * 获取此代理的唯一标识符
     *
     * @return Agent ID
     */
    String getAgentId();

    /**
     * Get the name of this agent.
     * 获取此代理的名称
     *
     * @return Agent name
     */
    String getName();

    /**
     * Get the description of this agent.
     * 获取此代理的描述
     *
     * @return Agent description
     */
    default String getDescription() {
        return "Agent(" + getAgentId() + ") " + getName();
    }

    /**
     * Interrupt the current agent execution.
     * 中断当前正在agent的执行
     * This method sets an interrupt flag that will be checked by the agent at appropriate
     * checkpoints during execution. The interruption is cooperative and may not take effect
     * immediately.
     * 此方法设置了一个中断标志，代理将在执行过程中的适当检查点检查该标志。中断是协作的，可能不会立即生效。
     */
    void interrupt();

    /**
     * Interrupt the current agent execution with a user message.
     * This method sets an interrupt flag and associates a user message with the interruption.
     * The interruption is cooperative and may not take effect immediately.
     * 使用消息来中断当前agent的执行，此方法设置一个中断标志，并关联一个用户消息与中断。中断是协作的，可能不会立即生效。
     *
     * @param msg User message associated with the interruption
     *            与中断相关的用户消息
     */
    void interrupt(Msg msg);
}
