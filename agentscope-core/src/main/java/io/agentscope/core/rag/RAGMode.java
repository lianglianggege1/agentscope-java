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
package io.agentscope.core.rag;

/**
 * RAG (Retrieval-Augmented Generation) mode enumeration.
 * RAG（检索增强生成）模式枚举。
 * 
 *
 * <p>Defines how knowledge retrieval is integrated with the agent:
 *    定义知识检索如何与代理集成：
 * <ul>
 *   <li><b>GENERIC</b>: Knowledge is automatically retrieved and injected before each reasoning step via Hook</li>
 *        <b>GENERIC</b>：在每个推理步骤之前，通过Hook自动检索和注入知识
 *   <li><b>AGENTIC</b>: Agent actively decides when to retrieve knowledge via Tool</li>
 *       <b>智能体</b>：智能体主动决定何时通过工具检索知识
 *   <li><b>NONE</b>: No RAG functionality enabled</li>
 *       未启用RAG功能
 * </ul>
 */
public enum RAGMode {
    /**
     * Generic mode: Knowledge is automatically retrieved and injected
     * before each reasoning step via Hook.
     *
     * <p>In this mode, the system automatically retrieves relevant knowledge
     * based on user queries and injects it into the prompt context.
     * 在每个推理步骤之前，通过Hook自动检索和注入知识。
     * 在这种模式下，系统会根据用户查询自动检索相关知识，并将其注入提示上下文中
     */
    GENERIC,

    /**
     * Agentic mode: Agent decides when to retrieve knowledge via Tool.
     *
     * <p>In this mode, the agent has a tool to retrieve knowledge and
     * actively decides when to use it based on the conversation context.
     * 智能体模式：智能体决定何时通过工具检索知识。
     * 在这种模式下，智能体有一个检索知识的工具，并根据对话上下文主动决定何时使用它。
     */
    AGENTIC,

    /**
     * Disabled mode: No RAG functionality.
     * 禁用模式：无RAG功能。
     *
     * <p>Knowledge retrieval is not enabled for this agent.
     * 禁用模式：无RAG功能。
     * 此智能体未启用知识检索。
     */
    NONE
}
