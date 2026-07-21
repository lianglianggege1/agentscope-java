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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;

/**
 * Filesystem abstraction that adds shell command execution (sandbox or remote host).
 *
 * <p>Extends {@link AbstractFilesystem} with {@link #execute} and {@link #id()}.
 */
/**
 * 提供Shell命令执行能力的文件系统抽象层（支持沙箱或远程主机）。
 *
 * <p>继承 {@link AbstractFilesystem}，新增 {@link #execute} 与 {@link #id()} 方法。
 */
public interface AbstractSandboxFilesystem extends AbstractFilesystem {

    /**
     * Unique identifier for this filesystem/sandbox instance.
     *
     * @return id string
     */
    /**
     * 当前文件系统/沙箱实例的唯一标识。
     *
     * @return 标识字符串
     */
    String id();

    /**
     * Execute a shell command in the environment backing this filesystem.
     *
     * @param runtimeContext per-call agent context; may be {@code null} when unavailable
     * @param command full shell command string to execute
     * @param timeoutSeconds maximum time in seconds to wait for the command to complete;
     *                       {@code null} uses the filesystem's default timeout
     * @return ExecuteResponse with combined output, exit code, and truncation flag
     */
    /**
     * 在当前文件系统对应的运行环境中执行Shell命令。
     *
     * @param runtimeContext 单次调用智能体上下文；无可用上下文时可为 {@code null}
     * @param command 待执行的完整Shell命令字符串
     * @param timeoutSeconds 命令执行最大等待秒数；传入 {@code null} 则使用文件系统默认超时时间
     * @return 执行结果响应对象，包含合并输出、退出码与输出截断标记
     */
    ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds);
}
