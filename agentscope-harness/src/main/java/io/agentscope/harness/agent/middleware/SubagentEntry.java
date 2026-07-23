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
 */
public record SubagentEntry(
        String name, String description, SubagentFactory factory, SubagentDeclaration declaration) {
    public SubagentEntry(String name, String description, SubagentFactory factory) {
        this(name, description, factory, null);
    }
}
