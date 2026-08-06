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
package io.agentscope.harness.agent.middleware;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;

/**
 * Descriptor for a subagent identified by agent id, with its description,
 * {@link SubagentFactory}, and optional {@link SubagentDeclaration} (for
 * remote URL and headers).
 */
/**
 * 子智能体描述器，包含智能体ID、描述信息、{@link SubagentFactory}，
 * 以及可选的 {@link SubagentDeclaration}（用于远程地址与请求头配置）。
 *
 * @param name 子智能体标识（即 agent_id），用于创建与寻址
 * @param description 面向大模型展示的能力描述，用于系统提示词中的可用智能体列表
 * @param factory 子智能体工厂，负责实际实例化子智能体
 * @param declaration 可选的声明元数据（来自 subagents/*.md 文件），可为 null
 */
public record SubagentEntry(
        String name, String description, SubagentFactory factory, SubagentDeclaration declaration) {
    /** 便捷构造器：不带声明元数据（编程式注册的子智能体使用）。 */
    public SubagentEntry(String name, String description, SubagentFactory factory) {
        this(name, description, factory, null);
    }
}
