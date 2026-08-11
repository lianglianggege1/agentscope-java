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
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;

/**
 * Tool for reading specific lines from memory files, typically used after
 * {@link MemorySearchTool} to fetch surrounding context.
 */
/**
 * 用于从记忆文件读取指定行的工具，通常配合 {@link MemorySearchTool} 使用，
 * 获取检索结果周边上下文。
 */
public class MemoryGetTool {

    private final WorkspaceManager workspaceManager;

    /** 构造器：注入工作空间管理器，用于定位并读取记忆文件。 */
    public MemoryGetTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /*
    @Tool(
            name = "memory_get",
            readOnly = true,
            description =
                    "读取记忆文件指定行。配合 memory_search 使用，获取匹配行附近完整上下文。"
                            + "路径为工作空间相对路径。")
    public String memoryGet(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "path",
                            description =
                                    "记忆文件相对路径（示例：MEMORY.md 或 memory/2026-04-01.md）")
                    String path,
            @ToolParam(name = "startLine", description = "起始行号（从1开始，闭区间）")
                    int startLine,
            @ToolParam(name = "endLine", description = "结束行号（从1开始，闭区间）")
                    int endLine) {}
     */
    /**
     * {@code memory_get} 工具方法：读取记忆文件指定行区间的上下文。
     *
     * <p>执行流程：校验路径非空 → 路径穿越防护（归一化后必须仍位于工作空间内）→
     * 读取文件全文 → 截取 [startLine, endLine] 闭区间（1 基）→
     * 以 {@code "行号|内容"} 格式逐行输出。
     */
    @Tool(
            name = "memory_get",
            readOnly = true,
            description =
                    "Read specific lines from a memory file. Use after memory_search to pull"
                            + " full context around matched lines. Path is relative to workspace.")
    public String memoryGet(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "path",
                            description =
                                    "Relative path to the memory file (e.g., MEMORY.md or"
                                            + " memory/2026-04-01.md)")
                    String path,
            @ToolParam(name = "startLine", description = "Start line number (1-based, inclusive)")
                    int startLine,
            @ToolParam(name = "endLine", description = "End line number (1-based, inclusive)")
                    int endLine) {
        if (path == null || path.isBlank()) {
            return "Error: path is required";
        }

        Path resolved = workspaceManager.getWorkspace().resolve(path).normalize();
        if (!resolved.startsWith(workspaceManager.getWorkspace())) {
            return "Error: path traversal not allowed";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        String text = workspaceManager.readManagedWorkspaceFileUtf8(rc, path);
        if (text == null || text.isBlank()) {
            return "Error: file not found: " + path;
        }

        List<String> lines = List.of(text.split("\n", -1));
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.size(), endLine);

        if (start >= lines.size()) {
            return "Error: startLine " + startLine + " exceeds file length " + lines.size();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(String.format("%d|%s%n", i + 1, lines.get(i)));
        }
        return sb.toString();
    }
}
