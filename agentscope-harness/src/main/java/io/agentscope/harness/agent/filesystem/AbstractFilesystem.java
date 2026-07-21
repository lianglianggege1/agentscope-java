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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import java.util.Map;

/**
 * Abstract filesystem API for agents: list, read, write, edit, grep, glob, upload, download.
 *
 * <p>Implementations may target the local disk, a sandbox, a key-value store, or other storage.
 * Host-rooted types {@link LocalFilesystem} and {@link LocalFilesystemWithShell} also expose
 * constructors that take the workspace root as a {@link String} path (same semantics as
 * {@link java.nio.file.Path}).
 *
 * <p>Every operation accepts a {@link RuntimeContext} so stores can scope work to the current
 * session, user, or sandbox. Callers that are not inside a tool/agent call with a merged context
 * should pass {@link RuntimeContext#empty()}.
 */
/**
 * 面向智能体的文件系统抽象接口，提供列举、读取、写入、编辑、文本检索、通配匹配、上传、下载能力。
 *
 * <p>该接口的实现可对接本地磁盘、沙箱、键值存储或其他存储介质。
 * 宿主机文件系统实现类 {@link LocalFilesystem} 与 {@link LocalFilesystemWithShell} 均提供接收字符串格式工作区根路径的构造方法，行为与 {@link java.nio.file.Path} 保持一致。
 *
 * <p>所有操作均支持传入 {@link RuntimeContext}，存储层可基于该上下文隔离不同会话、用户或沙箱的数据。
 * 若调用方并非处于携带合并上下文的工具/智能体调用链路中，需传入 {@link RuntimeContext#empty()}。
 */
public interface AbstractFilesystem {

    /**
     * List all files in a directory with metadata.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param path absolute path to the directory to list (must start with '/')
     * @return LsResult with directory entries or error
     */
    /**
     * 列出目录下所有文件并附带文件元信息。
     *
     * @param runtimeContext 单次调用对应的智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param path 待列举目录的绝对路径（必须以 '/' 开头）
     * @return 包含目录条目或错误信息的LsResult对象
     */
    LsResult ls(RuntimeContext runtimeContext, String path);

    /**
     * Read file content with optional line-based pagination.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param filePath absolute path to the file to read (must start with '/')
     * @param offset line number to start reading from (0-indexed). Default: 0
     * @param limit maximum number of lines to read. Default: 2000
     * @return ReadResult with file data on success or error on failure
     */
    /**
     * 读取文件内容，支持按行分页读取。
     *
     * @param runtimeContext 单次调用对应的智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param filePath 待读取文件的绝对路径（必须以 '/' 开头）
     * @param offset 读取起始行号（从0开始计数），默认值：0
     * @param limit 最大读取行数，默认值：2000
     * @return 读取结果对象，成功时携带文件内容，失败时携带错误信息
     */
    ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit);

    /**
     * Write content to a new file, error if file already exists.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param filePath absolute path where the file should be created
     * @param content string content to write to the file
     * @return WriteResult with path on success, or error if the file already exists or write fails
     */
    /**
     * 创建新文件并写入内容，若文件已存在则返回错误。
     *
     * @param runtimeContext 单次调用对应的智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param filePath 待创建文件的绝对路径
     * @param content 需要写入文件的文本内容
     * @return 写入结果对象；创建成功返回文件路径，文件已存在或写入失败则返回错误信息
     */
    WriteResult write(RuntimeContext runtimeContext, String filePath, String content);

    /**
     * Perform exact string replacements in an existing file.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param filePath absolute path to the file to edit
     * @param oldString exact string to search for and replace
     * @param newString string to replace oldString with (must be different from oldString)
     * @param replaceAll if true, replace all occurrences; if false, oldString must be unique
     * @return EditResult with path and occurrence count on success, or error on failure
     */
    /**
     * 对已有文件执行完整字符串替换操作。
     *
     * @param runtimeContext 单次调用智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param filePath 待编辑文件的绝对路径
     * @param oldString 待查找替换的完整原始字符串
     * @param newString 替换用新字符串（不能与原字符串相同）
     * @param replaceAll true 代表替换全部匹配内容；false 要求原文中该字符串仅出现一处
     * @return 编辑结果对象，执行成功返回文件路径与匹配次数，执行失败返回错误信息
     */
    EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll);

    /**
     * Search for a literal text pattern in files.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param pattern literal string to search for (not regex)
     * @param path optional directory path to search in (null searches current working directory)
     * @param glob optional glob pattern to filter which files to search (e.g., "*.java")
     * @return GrepResult with matches or error
     */
    /**
     * 在文件中检索纯文本内容。
     *
     * @param runtimeContext 单次调用对应的智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param pattern 要检索的原始文本（非正则表达式）
     * @param path 可选检索目录路径；传null则检索当前工作目录
     * @param glob 可选通配符，用于过滤待检索文件（例如 "*.java"）
     * @return 检索结果对象，包含匹配内容或错误信息
     */
    GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob);

    /**
     * Find files matching a glob pattern.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param pattern glob pattern with wildcards to match file paths
     * @param path base directory to search from (default: "/")
     * @return GlobResult with matching files or error
     */
    /**
     * 查找符合通配符匹配规则的文件。
     *
     * @param runtimeContext 单次调用智能体运行上下文，无上下文时传入 {@link RuntimeContext#empty()}
     * @param pattern 带通配符、用于匹配文件路径的glob表达式
     * @param path 检索起始根目录，默认值为 "/"
     * @return GlobResult 结果对象，返回匹配的文件列表或错误信息
     */
    GlobResult glob(RuntimeContext runtimeContext, String pattern, String path);

    /**
     * Upload multiple files.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param files list of path-to-content mappings to upload
     * @return list of FileUploadResponse objects, one per input file (order matches input order)
     */
    /**
     * 批量上传多个文件。
     *
     * @param runtimeContext 单次调用智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param files 待上传的路径与文件内容映射列表
     * @return 文件上传响应对象列表，与入参文件顺序一一对应
     */
    List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files);

    /**
     * Download multiple files.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param paths list of file paths to download
     * @return list of FileDownloadResponse objects, one per input path (order matches input order)
     */
    /**
     * 批量下载多个文件。
     *
     * @param runtimeContext 单次调用智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param paths 待下载的文件路径列表
     * @return 文件下载响应对象列表，与入参路径顺序一一对应
     */
    List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths);

    /**
     * Delete a file or directory (recursive for directories).
     *
     * <p>Idempotent: deleting a path that does not exist is treated as success.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param path absolute path to the file or directory to delete
     * @return WriteResult success when deleted (or already absent), failure on I/O error
     */
    /**
     * 删除文件或目录（删除目录时会递归删除内部内容）。
     *
     * <p>接口具备幂等性：删除不存在的路径视为操作成功。
     *
     * @param runtimeContext 单次调用智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param path 待删除文件或目录的绝对路径
     * @return 操作结果对象，文件已删除/原本不存在均返回成功，IO异常则返回失败
     */
    WriteResult delete(RuntimeContext runtimeContext, String path);

    /**
     * Move (rename) a file or directory from {@code fromPath} to {@code toPath}.
     *
     * <p>Implementations that span multiple stores (e.g. {@code CompositeFilesystem}) may
     * fall back to a read + write + delete sequence when source and destination live in
     * different backend filesystems.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param fromPath absolute source path
     * @param toPath absolute destination path
     * @return WriteResult success on completion, failure on I/O error or missing source
     */
    /**
     * 将文件或目录从源路径 {@code fromPath} 移动（重命名）至目标路径 {@code toPath}。
     *
     * <p>跨多存储层的实现类（例如 {@code CompositeFilesystem}），若源与目标分属不同后端文件系统，
     * 会降级为「读取+写入+删除」的流程完成迁移。
     *
     * @param runtimeContext 单次调用智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param fromPath 源文件/目录绝对路径
     * @param toPath 目标绝对路径
     * @return 操作结果对象，执行完成返回成功，IO异常或源文件不存在则返回失败
     */
    WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath);

    /**
     * Check whether a file or directory exists.
     *
     * <p>Implementations may approximate this with a lightweight read probe where a dedicated
     * {@code exists} API is unavailable, but should avoid reading full file content.
     *
     * @param runtimeContext per-call agent runtime; {@link RuntimeContext#empty()} when none
     * @param path absolute path to check
     * @return {@code true} if the path exists, {@code false} otherwise
     */
    /**
     * 判断文件或目录是否存在。
     *
     * <p>若底层存储无专用exists接口，实现类可采用轻量探测方式判断，但禁止读取完整文件内容。
     *
     * @param runtimeContext 单次调用智能体运行上下文；无上下文时传入 {@link RuntimeContext#empty()}
     * @param path 待校验的绝对路径
     * @return 路径存在返回 {@code true}，不存在返回 {@code false}
     */
    boolean exists(RuntimeContext runtimeContext, String path);

    // ==================== Path validation utility ====================

    /**
     * Validates that {@code path} is safe (non-null, non-blank, no {@code ..} traversal).
     *
     * @param path the path to validate
     * @throws IllegalArgumentException if the path is invalid
     */
    static void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("Path traversal ('..') not allowed: " + path);
        }
    }
}
