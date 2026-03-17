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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.agent.Agent;

/**
 * Factory interface for creating agent instances.
 * 用于创建代理实例的工厂接口。
 *
 * <p>Since ReActAgent is not thread-safe, this provider pattern ensures that each tool call gets a
 * fresh agent instance. This is similar to Spring's ObjectProvider pattern.
 * 由于 ReActAgent 不是线程安全的，这种提供程序模式确保每次工具调用都会获得一个全新的代理实例。
 * 这类似于 Spring 的 ObjectProvider 模式。
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * SubAgentProvider<ReActAgent> provider = () -> ReActAgent.builder()
 *     .name("ResearchAgent")
 *     .model(model)
 *     .sysPrompt("You are a research expert...")
 *     .build();
 *
 * toolkit.registration()
 *     .subAgent(provider)
 *     .apply();
 * }</pre>
 *
 * @param <T> The type of agent this provider creates
 */
@FunctionalInterface
public interface SubAgentProvider<T extends Agent> {

    /**
     * Provides a new agent instance.
     *
     * <p>This method is called for each tool invocation to ensure thread safety. Implementations
     * should create a new agent instance each time this method is called.
     *
     * @return A new agent instance
     */
    T provide();
}
