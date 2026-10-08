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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;

/**
 * File system tools backed by a {@link AbstractFilesystem}, exposing read/write/edit/grep/glob
 * operations as agent-callable tools.
 */
/**
 * 基于 {@link AbstractFilesystem} 实现的文件系统工具集，对外提供可读、写、编辑、检索、通配符匹配等操作，
 * 可供智能体直接调用。
 */
public class FilesystemTool {

    static final int DEFAULT_GREP_LIMIT = 100;
    static final int DEFAULT_GLOB_LIMIT = 200;
    static final int MAX_SEARCH_LIMIT = 1_000;

    private final AbstractFilesystem abstractFilesystem;
    private final WorkspacePathNormalizer pathNormalizer;

    /** 构造器：仅注入文件系统实现，不做路径归一化。 */
    public FilesystemTool(AbstractFilesystem abstractFilesystem) {
        this(abstractFilesystem, null);
    }

    /** 构造器：同时注入文件系统实现与可选的路径归一化器。 */
    public FilesystemTool(
            AbstractFilesystem abstractFilesystem, WorkspacePathNormalizer pathNormalizer) {
        this.abstractFilesystem = abstractFilesystem;
        this.pathNormalizer = pathNormalizer;
    }

    static final int MAX_LISTING_ENTRIES = 200;
    static final int MAX_LISTING_CHARS = 16000;

    private static String boundedListing(
            java.util.stream.Stream<String> lines, int total, int limit, String resultLabel) {
        StringBuilder out = new StringBuilder();
        var iterator = lines.iterator();
        int count = 0;
        boolean characterLimitReached = false;
        while (count < limit && iterator.hasNext()) {
            String line = iterator.next();
            int separatorLength = count > 0 ? 1 : 0;
            if (out.length() + line.length() + separatorLength > MAX_LISTING_CHARS) {
                characterLimitReached = true;
                break;
            }
            if (count++ > 0) out.append('\n');
            out.append(line);
        }
        if (count == total) {
            return out.toString();
        }
        String guidance =
                characterLimitReached
                        ? "Output character limit of "
                                + MAX_LISTING_CHARS
                                + " reached; narrow the path/pattern."
                        : "entries".equals(resultLabel)
                                ? "Listing limit of " + limit + " reached; narrow the directory."
                                : limit < MAX_SEARCH_LIMIT
                                        ? "Narrow the path/pattern or increase limit (hard maximum:"
                                                + " "
                                                + MAX_SEARCH_LIMIT
                                                + ")."
                                        : "Hard maximum of "
                                                + MAX_SEARCH_LIMIT
                                                + " reached; narrow the path/pattern to retrieve"
                                                + " more targeted results.";
        return out
                + "\n[Results truncated: showing "
                + count
                + " of "
                + total
                + " "
                + resultLabel
                + ". "
                + guidance
                + "]";
    }

    /** 路径归一化入口：配置了归一化器则按其规则处理（含运行时上下文），否则原样返回。 */
    private String norm(String path, RuntimeContext runtimeContext) {
        return pathNormalizer != null ? pathNormalizer.normalize(path, runtimeContext) : path;
    }

    /*
    @Tool(
    name = "read_file",
    readOnly = true,
    description = "读取带行号的文件内容，支持 offset、limit 分页。")
    public String readFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "待读取文件路径") String path,
            @ToolParam(name = "offset", description = "起始行（0索引），默认0") int offset,
            @ToolParam(name = "limit", description = "最大返回行数，默认0表示读取全部") int limit) {}
     */
    /** {@code read_file}：读取带行号的文件内容，支持 offset/limit 分页。 */
    @Tool(
            name = "read_file",
            readOnly = true,
            description =
                    "Read file content with line numbers. Supports pagination via offset and"
                            + " limit.")
    public String readFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "File path to read") String path,
            @ToolParam(
                            name = "offset",
                            description = "Start line (0-indexed). Default: 0 (from beginning)",
                            required = false)
                    Integer offset,
            @ToolParam(
                            name = "limit",
                            description = "Max lines to return. Default: 0 (all lines)",
                            required = false)
                    Integer limit) {
        int off = offset != null ? offset : 0;
        int lim = limit != null ? limit : 0;
        ReadResult r =
                abstractFilesystem.read(runtimeContext, norm(path, runtimeContext), off, lim);
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        return r.fileData() != null ? r.fileData().content() : "";
    }

    /*
    @Tool(
            name = "write_file",
            description = "向新文件写入内容，必要时自动创建上级目录。")
    public String writeFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "目标文件路径") String path,
            @ToolParam(name = "content", description = "待写入的文件内容") String content) {}
     */
    /** {@code write_file}：写入文件内容，必要时自动创建上级目录。 */
    @Tool(
            name = "write_file",
            description = "Write content to a new file, creating parent directories if needed.")
    public String writeFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Target file path") String path,
            @ToolParam(name = "content", description = "File content to write") String content) {
        WriteResult r =
                abstractFilesystem.write(runtimeContext, norm(path, runtimeContext), content);
        return r.isSuccess() ? "Written to " + r.path() : "Error: " + r.error();
    }

    /*
    @Tool(
            name = "edit_file",
            description =
                    "在文件内执行精确字符串替换。除非开启 replace_all，否则待查找文本必须唯一。")
    public String editFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "待编辑文件路径") String path,
            @ToolParam(name = "old_string", description = "待查找文本") String oldString,
            @ToolParam(name = "new_string", description = "替换文本") String newString,
            @ToolParam(
                            name = "replace_all",
                            description = "替换全部匹配项（默认 false）",
                            required = false)
                    Boolean replaceAll) {}
     */
    /**
     * {@code edit_file}：文件内精确字符串替换。
     * 除非 {@code replace_all=true}，否则待查找文本必须在文件中唯一。
     */
    @Tool(
            name = "edit_file",
            description =
                    "Perform exact string replacement in a file. The old_string must be unique"
                            + " unless replace_all is true.")
    public String editFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "File to edit") String path,
            @ToolParam(name = "old_string", description = "Text to find") String oldString,
            @ToolParam(name = "new_string", description = "Replacement text") String newString,
            @ToolParam(
                            name = "replace_all",
                            description = "Replace all occurrences (default: false)",
                            required = false)
                    Boolean replaceAll) {
        boolean shouldReplaceAll = Boolean.TRUE.equals(replaceAll);
        EditResult r =
                abstractFilesystem.edit(
                        runtimeContext,
                        norm(path, runtimeContext),
                        oldString,
                        newString,
                        shouldReplaceAll);
        return r.isSuccess()
                ? "Edited " + r.path() + " (" + r.occurrences() + " replacement(s))"
                : "Error: " + r.error();
    }

    /*
    @Tool(
            name = "grep_files",
            readOnly = true,
            description = "按纯文本内容检索文件。")
    public String grepFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "需要检索的纯文本")
                    String pattern,
            @ToolParam(name = "path", description = "待检索目录或文件路径") String path,
            @ToolParam(name = "glob", description = "可选文件通配符过滤（例如 *.java）")
                    String glob) {}
     */
    /** {@code grep_files}：按纯文本内容检索文件，结果以 {@code "路径:行号:内容"} 格式输出。 */
    @Tool(
            name = "grep_files",
            readOnly = true,
            description =
                    "Search file contents for a literal text pattern. Returns at most 100 matches"
                            + " by default to keep tool output bounded.")
    public String grepFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Literal text pattern to search for")
                    String pattern,
            @ToolParam(name = "path", description = "Directory or file to search", required = false)
                    String path,
            @ToolParam(
                            name = "glob",
                            description = "Optional file glob filter (e.g., *.java)",
                            required = false)
                    String glob,
            @ToolParam(
                            name = "limit",
                            description =
                                    "Maximum matches to return (default/recommended: 100; hard"
                                            + " maximum: 1000)",
                            required = false)
                    Integer limit) {
        int effectiveLimit = effectiveLimit(limit, DEFAULT_GREP_LIMIT);
        if (effectiveLimit < 1) {
            return "Error: limit must be greater than 0";
        }
        GrepResult r =
                abstractFilesystem.grep(runtimeContext, pattern, norm(path, runtimeContext), glob);
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<GrepMatch> matches = r.matches();
        if (matches == null || matches.isEmpty()) {
            return "No matches found";
        }
        return boundedListing(
                matches.stream().map(m -> m.path() + ":" + m.line() + ":" + m.text()),
                matches.size(),
                effectiveLimit,
                "matches");
    }

    /** Search using the default result limit. */
    public String grepFiles(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return grepFiles(runtimeContext, pattern, path, glob, null);
    }

    @Tool(
            name = "glob_files",
            readOnly = true,
            description =
                    "Find files matching a glob pattern. Returns at most 200 files by default to"
                            + " keep tool output bounded.")

    /* 中文译文（当前未启用，保留备查）
    @Tool(name = "glob_files", readOnly = true, description = "查找匹配通配符规则的文件。")
    public String globFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "通配符表达式（例如 **.java）")
        String pattern,
        @ToolParam(name = "path", description = "检索起始根目录") String path) {}
    */
    /** {@code glob_files}：按通配符模式查找文件，输出文件数量受 limit 限制（默认 200）。 */
    public String globFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Glob pattern (e.g., **/*.java)")
                    String pattern,
            @ToolParam(
                            name = "path",
                            description = "Base directory to search from",
                            required = false)
                    String path,
            @ToolParam(
                            name = "limit",
                            description =
                                    "Maximum files to return (default/recommended: 200; hard"
                                            + " maximum: 1000)",
                            required = false)
                    Integer limit) {
        int effectiveLimit = effectiveLimit(limit, DEFAULT_GLOB_LIMIT);
        if (effectiveLimit < 1) {
            return "Error: limit must be greater than 0";
        }
        GlobResult r = abstractFilesystem.glob(runtimeContext, pattern, norm(path, runtimeContext));
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<FileInfo> files = r.matches();
        if (files == null || files.isEmpty()) {
            return "No matching files found";
        }
        return boundedListing(
                files.stream()
                        .map(f -> f.path() + (f.isDirectory() ? "/" : " (" + f.size() + " bytes)")),
                files.size(),
                effectiveLimit,
                "files");
    }

    /** Search using the default result limit. */
    public String globFiles(RuntimeContext runtimeContext, String pattern, String path) {
        return globFiles(runtimeContext, pattern, path, null);
    }

    private static int effectiveLimit(Integer requestedLimit, int defaultLimit) {
        if (requestedLimit == null) {
            return defaultLimit;
        }
        return Math.min(requestedLimit, MAX_SEARCH_LIMIT);
    }

    /*
    @Tool(
            name = "list_files",
            readOnly = true,
            description = "列出指定路径下的文件与目录。")
    public String listFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "待浏览目录路径") String path) {}
     */
    /** {@code list_files}：列出指定路径下的文件与目录，带 [DIR]/[FILE] 类型标记。 */
    @Tool(
            name = "list_files",
            readOnly = true,
            description = "List files and directories at the given path.")
    public String listFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Directory path to list") String path) {
        LsResult r = abstractFilesystem.ls(runtimeContext, norm(path, runtimeContext));
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<FileInfo> infos = r.entries();
        if (infos == null || infos.isEmpty()) {
            return "Empty directory: " + path;
        }
        return boundedListing(
                infos.stream()
                        .map(
                                f ->
                                        (f.isDirectory() ? "[DIR]  " : "[FILE] ")
                                                + f.path()
                                                + (f.isDirectory()
                                                        ? ""
                                                        : " (" + f.size() + " bytes)")),
                infos.size(),
                MAX_LISTING_ENTRIES,
                "entries");
    }
}
