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
package io.agentscope.harness.agent.session;

import io.agentscope.core.session.JsonSession;
import java.nio.file.Path;

/**
 * Workspace-aware session that stores state under the agent's workspace directory.
 *
 * <p>Storage layout:
 *
 * <pre>
 * &lt;workspace&gt;/agents/&lt;agentId&gt;/context/&lt;sessionId&gt;/{key}.json
 * &lt;workspace&gt;/agents/&lt;agentId&gt;/context/&lt;sessionId&gt;/{key}.jsonl
 * </pre>
 *
 * <p>This extends {@link JsonSession} by computing the base directory as
 * {@code <workspace>/agents/<agentId>/context/}. The {@code sessionId} (carried by
 * {@link io.agentscope.core.state.SessionKey#toIdentifier()}) is appended automatically
 * by the parent class as a subdirectory, producing the full path above.
 *
 * <p>This session is dedicated to ReActAgent runtime state persistence only (for example memory
 * messages, agent metadata). Sandbox lifecycle state is stored separately through
 * {@code SandboxStateStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * WorkspaceSession session = new WorkspaceSession(workspacePath, "my-agent");
 * agent.saveTo(session, SimpleSessionKey.of("sess-001"));
 * // Files written to: <workspace>/agents/my-agent/context/sess-001/
 * }</pre>
 */
/**
 * 绑定工作区的会话实现，会话状态持久化存储至智能体工作目录下。
 *
 * <p>存储目录结构：
 *
 * <pre>
 * &lt;workspace&gt;/agents/&lt;agentId&gt;/context/&lt;sessionId&gt;/{key}.json
 * &lt;workspace&gt;/agents/&lt;agentId&gt;/context/&lt;sessionId&gt;/{key}.jsonl
 * </pre>
 *
 * <p>该类继承 {@link JsonSession}，基准目录固定为
 * {@code <workspace>/agents/<agentId>/context/}。父类会自动拼接会话ID（由
 * {@link io.agentscope.core.state.SessionKey#toIdentifier()} 生成）作为子目录，最终形成上述完整路径。
 *
 * <p>本会话仅用于持久化 ReActAgent 运行时状态（如对话记忆消息、智能体元数据）；
 * 沙箱生命周期状态通过独立的 {@code SandboxStateStore} 存储。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * WorkspaceSession session = new WorkspaceSession(workspacePath, "my-agent");
 * agent.saveTo(session, SimpleSessionKey.of("sess-001"));
 * // 文件将写入路径：<workspace>/agents/my-agent/context/sess-001/
 * }</pre>
 */
public class WorkspaceSession extends JsonSession {

    /**
     * Creates a workspace session for the given agent.
     *
     * @param workspace the workspace root directory (e.g. {@code .agentscope/workspace})
     * @param agentId the agent identifier used in the directory path
     */
    /**
     * 为指定智能体创建工作区会话实例。
     *
     * @param workspace 工作区根目录（示例：{@code .agentscope/workspace}）
     * @param agentId 目录路径中使用的智能体唯一标识
     */
    public WorkspaceSession(Path workspace, String agentId) {
        super(workspace.resolve("agents").resolve(agentId).resolve("context"));
    }
}
