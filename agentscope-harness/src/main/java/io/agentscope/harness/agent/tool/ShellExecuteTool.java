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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

/**
 * Shell execution tool backed by a {@link AbstractSandboxFilesystem}.
 */
/**
 * 由 {@link AbstractSandboxFilesystem} 提供底层支持的 Shell 执行工具。
 */
public class ShellExecuteTool {

    /** Registered tool name (derived from the {@link #execute} method name). */
    /** 注册工具名称（由 {@link #execute} 方法名推导得出）。 */
    public static final String NAME = "execute";

    private final AbstractSandboxFilesystem sandbox;

    /** 构造器：注入沙箱文件系统实现，所有命令均在其中执行。 */
    public ShellExecuteTool(AbstractSandboxFilesystem sandbox) {
        this.sandbox = sandbox;
    }

    /*
    @Tool(
            description =
                    "执行Shell命令。适用于git、npm、构建、测试及其他终端操作。"
                            + "返回合并输出内容与退出码。")
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "待执行的Shell命令") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "工作目录（相对于工作空间根路径，可选参数）")
                    String workingDirectory,
            @ToolParam(name = "timeout", description = "超时时间，单位秒（默认：30）")
                    int timeout) {}
     */
    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument);
     *                       may be {@code null} when no merged context is available
     */
    /**
     * @param runtimeContext 框架注入的单次调用智能体运行时上下文（不属于大模型入参）；
     *     无合并上下文时可为 {@code null}
     */
    /**
     * {@code execute} 工具方法：在沙箱中执行 Shell 命令。
     *
     * <p>执行流程：若提供 working_directory，先校验其为工作空间内相对路径
     * （拒绝绝对路径、{@code ~} 与 {@code ..}），再以 {@code cd '<目录>' && 命令}
     * 形式拼接；随后交由沙箱执行（默认超时 30 秒），最终返回
     * 退出码、合并输出及截断提示。
     */
    @Tool(
            description =
                    "Execute a shell command. Use for git, npm, build, test, and other terminal"
                        + " operations. Returns combined output and exit code. If a dedicated tool"
                        + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                        + " commands.")
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Working directory (relative to workspace root, optional)",
                            required = false)
                    String workingDirectory,
            @ToolParam(
                            name = "timeout",
                            description = "Timeout in seconds (default: 30)",
                            required = false)
                    Integer timeout) {
        String effectiveCommand = command;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String wd = workingDirectory.strip();
            // 路径穿越防护：只允许工作空间内的相对路径。
            if (wd.startsWith("/") || wd.startsWith("~") || wd.contains("..")) {
                return "Error: working_directory must be a relative path within the workspace"
                        + " (absolute paths, '~', and '..' are not allowed).";
            }
            // 对目录中的单引号做 shell 转义后拼接 cd 前缀（Windows 使用 cd /d 形式）。
            effectiveCommand =
                    commandWithWorkingDirectory(
                            wd,
                            command,
                            System.getProperty("os.name").toLowerCase().contains("win"));
        }

        int timeoutSeconds = timeout != null && timeout > 0 ? timeout : 30;
        ExecuteResponse result = sandbox.execute(runtimeContext, effectiveCommand, timeoutSeconds);

        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(result.exitCode()).append("\n");
        if (result.output() != null && !result.output().isBlank()) {
            sb.append("\n").append(result.output());
        }
        if (result.truncated()) {
            sb.append("\n(output was truncated)");
        }
        return sb.toString();
    }

    static String commandWithWorkingDirectory(
            String workingDirectory, String command, boolean windows) {
        if (windows) {
            return "cd /d \"" + workingDirectory.replace("\"", "\"\"") + "\" && " + command;
        }
        return "cd '" + workingDirectory.replace("'", "'\\''") + "' && " + command;
    }
}
