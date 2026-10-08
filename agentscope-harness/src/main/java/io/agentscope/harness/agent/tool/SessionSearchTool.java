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
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for searching past session transcripts and viewing session history.
 *
 * <p>Operates exclusively on the local session cache. Remote synchronisation is handled by
 * {@link io.agentscope.harness.agent.memory.session.SessionTree#load()} in write paths
 * (e.g. {@link io.agentscope.harness.agent.memory.MemoryFlushManager}), keeping this tool
 * lightweight and fast for in-process search.
 */
/**
 * 用于检索历史会话记录、查看会话日志的工具。
 *
 * <p>仅基于本地会话缓存执行检索。远程同步逻辑由写入链路中的
 * {@link io.agentscope.harness.agent.memory.session.SessionTree#load()}
 *（例如 {@link io.agentscope.harness.agent.memory.MemoryFlushManager}）负责处理，
 * 保证本工具检索轻量化、进程内查询响应迅速。
 */
public class SessionSearchTool {

    private static final Logger log = LoggerFactory.getLogger(SessionSearchTool.class);

    private final WorkspaceManager workspaceManager;

    /** 构造器：注入工作空间管理器，用于定位会话目录与读取会话文件。 */
    public SessionSearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public String sessionSearch(
            RuntimeContext runtimeContext, String query, String agentId, Integer maxResults) {
        return sessionSearch(runtimeContext, query, agentId, maxResults, null);
    }

    /* 中文译文（当前未启用，保留备查）
    @Tool(
            name = "session_search",
            readOnly = true,
            description =
                    "在历史会话记录中检索关键词或语句，返回附带会话上下文的匹配条目。")
    public String sessionSearch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "query", description = "检索条件（关键词或短语）")
                    String query,
            @ToolParam(
                            name = "agentId",
                            description = "待检索会话所属智能体ID",
                            required = false)
                    String agentId,
            @ToolParam(
                            name = "maxResults",
                            description = "最大返回结果数量（默认：10）",
                            required = false)
                    Integer maxResults) {}
    */
    /**
     * {@code session_search} 工具方法：在历史会话记录中检索关键词或短语。
     *
     * <p>执行流程：收集目标智能体（或全部智能体）的 {@code .log.jsonl} 文件 →
     * 逐文件加载对应 {@link SessionTree} 做大小写不敏感子串匹配 →
     * 命中条目附文件路径、消息 ID、角色与内容预览（截断至 200 字符）。
     */
    @Tool(
            name = "session_search",
            readOnly = true,
            description =
                    "Search past session transcripts for a keyword or phrase."
                            + " Returns matching entries with session context.")
    public String sessionSearch(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "query",
                            description =
                                    "Literal phrase, or whitespace-separated keywords when"
                                            + " matchMode is all/any; no automatic Chinese word"
                                            + " segmentation")
                    String query,
            @ToolParam(
                            name = "agentId",
                            description = "Agent ID to search sessions for",
                            required = false)
                    String agentId,
            @ToolParam(
                            name = "maxResults",
                            description = "Maximum number of results to return (default: 10)",
                            required = false)
                    Integer maxResults,
            @ToolParam(
                            name = "matchMode",
                            description =
                                    "phrase (default): exact substring; all: every keyword in the"
                                        + " same session entry; any: at least one keyword in that"
                                        + " entry. Case-insensitive literal matching.",
                            required = false)
                    String matchMode) {
        if (query == null || query.isBlank()) {
            return "Error: query is required";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        int limit = maxResults != null && maxResults > 0 ? maxResults : 10;
        String effectiveAgentId = agentId != null && !agentId.isBlank() ? agentId : null;
        Predicate<String> matcher;
        try {
            Predicate<String> compiled =
                    KeywordMatcher.compile(
                            query,
                            matchMode,
                            term -> {
                                String lowerTerm = term.toLowerCase();
                                return text -> text.contains(lowerTerm);
                            });
            matcher = text -> compiled.test(text.toLowerCase());
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        List<String> results = new ArrayList<>();

        List<Path> sessionFiles = listLogFiles(rc, effectiveAgentId);
        for (Path file : sessionFiles) {
            if (results.size() >= limit) {
                break;
            }
            searchInSessionFile(file, matcher, results, limit);
        }

        if (results.isEmpty()) {
            return "No matches found for: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d matches for \"%s\":\n\n", results.size(), query));
        for (String result : results) {
            sb.append(result).append("\n");
        }
        return sb.toString();
    }

    /*
    @Tool(
            name = "session_list",
            readOnly = true,
            description = "列出指定智能体可用会话，展示会话ID与元信息。")
    public String sessionList(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "待列举会话的智能体ID")
                    String agentId) {}
     */
    /**
     * {@code session_list} 工具方法：列出指定智能体的可用会话。
     * 优先读取结构化会话存储索引文件；不存在时回退到扫描本地缓存目录。
     */
    @Tool(
            name = "session_list",
            readOnly = true,
            description = "List available sessions for an agent, showing session IDs and metadata.")
    public String sessionList(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID to list sessions for")
                    String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return "Error: agentId is required";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();

        // Prefer the structured session-store index (already two-layer: remote then local).
        // 优先使用结构化会话存储索引（已实现双层查询：先远端后本地）。
        String storeContent =
                workspaceManager.readManagedWorkspaceFileUtf8(
                        rc,
                        WorkspaceConstants.AGENTS_DIR
                                + "/"
                                + agentId
                                + "/"
                                + WorkspaceConstants.SESSIONS_DIR
                                + "/"
                                + WorkspaceConstants.SESSIONS_STORE);
        if (!storeContent.isBlank()) {
            return storeContent;
        }

        // List sessions from local cache only — remote sync is handled at write time.
        // 仅从本地缓存列举会话——远端同步在写入链路中处理。
        Path sessionDir = workspaceManager.getSessionDir(rc, agentId);
        if (!Files.isDirectory(sessionDir)) {
            return "No sessions found for agent: " + agentId;
        }

        List<Path> sessionFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.list(sessionDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(
                            p ->
                                    p.getFileName()
                                            .toString()
                                            .endsWith(WorkspaceConstants.SESSION_CONTEXT_EXT))
                    .forEach(sessionFiles::add);
        } catch (IOException e) {
            log.debug("Could not list local session dir for agent {}: {}", agentId, e.getMessage());
        }

        if (sessionFiles.isEmpty()) {
            return "No sessions found for agent: " + agentId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sessions for agent ").append(agentId).append(":\n");
        for (Path file : sessionFiles) {
            String name = file.getFileName().toString();
            String sessionId = name.replace(WorkspaceConstants.SESSION_CONTEXT_EXT, "");
            sb.append("  - ").append(sessionId).append("\n");
        }
        return sb.toString();
    }

    /*
    @Tool(
            name = "session_history",
            readOnly = true,
            description =
                    "获取指定会话的对话历史，返回该会话内所有消息。")
    public String sessionHistory(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "智能体ID") String agentId,
            @ToolParam(name = "sessionId", description = "AgentStateStore 会话标识") String sessionId,
            @ToolParam(
                            name = "lastN",
                            description = "需要返回的最近消息条数（默认：20）",
                            required = false)
                    Integer lastN) {}
     */
    /**
     * {@code session_history} 工具方法：获取指定会话的对话历史。
     *
     * <p>执行流程：定位会话上下文文件 → 缺失时回退到旧版 {@code .json} 会话文件 →
     * 加载 {@link SessionTree} 取最近 lastN 条消息 →
     * 单条内容超 500 字符时截断，以 {@code "[角色]: 内容"} 格式输出。
     */
    @Tool(
            name = "session_history",
            readOnly = true,
            description =
                    "Get the conversation history for a specific session."
                            + " Returns the messages in the session.")
    public String sessionHistory(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agentId", description = "Agent ID") String agentId,
            @ToolParam(name = "sessionId", description = "AgentStateStore Session ID")
                    String sessionId,
            @ToolParam(
                            name = "lastN",
                            description = "Number of recent messages to return (default: 20)",
                            required = false)
                    Integer lastN) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "Error: agentId and sessionId are required";
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        int limit = lastN != null && lastN > 0 ? lastN : 20;

        Path contextFile = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
        if (!Files.isRegularFile(contextFile)) {
            @SuppressWarnings("deprecation")
            Path legacyFile = workspaceManager.resolveSessionFile(rc, agentId, sessionId);
            if (Files.isRegularFile(legacyFile)) {
                log.debug("Falling back to legacy .json session file for {}", sessionId);
                return readLegacySession(legacyFile, limit);
            }
            return "AgentStateStore not found: " + sessionId;
        }

        SessionTree tree = new SessionTree(contextFile, workspaceManager.getWorkspace(), null);
        tree.load();

        List<SessionEntry.MessageEntry> messages = tree.getMessageEntries();
        int start = Math.max(0, messages.size() - limit);

        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "AgentStateStore %s (%d total messages, showing last %d):\n\n",
                        sessionId, messages.size(), Math.min(limit, messages.size())));
        for (int i = start; i < messages.size(); i++) {
            SessionEntry.MessageEntry msg = messages.get(i);
            String content = msg.getContent();
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "... [truncated]";
            }
            sb.append(String.format("[%s]: %s\n", msg.getRole(), content));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

    /**
     * Collects all {@code .log.jsonl} files under the sessions directory for the given agent
     * (or all agents when {@code agentId} is {@code null}).
     * Only scans the local disk; remote-only sessions are handled via sessionList / sessionHistory.
     */
    /**
     * 收集指定智能体会话目录下所有 {@code .log.jsonl} 文件
     * 若 {@code agentId} 为 null，则收集全部智能体的相关文件。
     * 仅扫描本地磁盘；仅存在远端的会话需通过 sessionList / sessionHistory 接口处理。
     */
    private List<Path> listLogFiles(RuntimeContext rc, String agentId) {
        List<Path> files = new ArrayList<>();
        Path agentsDir = workspaceManager.resolveRuntimeDataPath(rc, WorkspaceConstants.AGENTS_DIR);
        if (!Files.isDirectory(agentsDir)) {
            return files;
        }

        if (agentId != null) {
            Path sessionDir = agentsDir.resolve(agentId).resolve(WorkspaceConstants.SESSIONS_DIR);
            collectLogFiles(sessionDir, files);
            return files;
        }

        try (Stream<Path> walk = Files.list(agentsDir)) {
            walk.filter(Files::isDirectory)
                    .forEach(
                            agentDir ->
                                    collectLogFiles(
                                            agentDir.resolve(WorkspaceConstants.SESSIONS_DIR),
                                            files));
        } catch (IOException e) {
            // ignore
        }
        return files;
    }

    /** 收集指定会话目录下的 {@code .log.jsonl} 日志文件到收集器列表。 */
    private void collectLogFiles(Path sessionDir, List<Path> collector) {
        if (!Files.isDirectory(sessionDir)) {
            return;
        }
        try (Stream<Path> walk = Files.list(sessionDir)) {
            walk.filter(p -> p.toString().endsWith(WorkspaceConstants.SESSION_LOG_EXT))
                    .filter(Files::isRegularFile)
                    .forEach(collector::add);
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * 在单个会话日志文件内检索：由日志文件名推导对应上下文文件，
     * 加载 {@link SessionTree} 后逐条消息做小写子串匹配；
     * 损坏的文件静默跳过。
     */
    private void searchInSessionFile(
            Path logFile, Predicate<String> matcher, List<String> results, int limit) {
        try {
            Path contextFile =
                    logFile.resolveSibling(
                            logFile.getFileName()
                                    .toString()
                                    .replace(
                                            WorkspaceConstants.SESSION_LOG_EXT,
                                            WorkspaceConstants.SESSION_CONTEXT_EXT));
            SessionTree tree = new SessionTree(contextFile, workspaceManager.getWorkspace(), null);
            tree.load();

            String relPath = workspaceManager.getWorkspace().relativize(logFile).toString();
            for (SessionEntry entry : tree.getAllEntries()) {
                if (results.size() >= limit) {
                    break;
                }
                String content = searchableText(entry);
                if (content != null && matcher.test(content)) {
                    String preview =
                            content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    String roleLabel =
                            entry instanceof SessionEntry.MessageEntry me
                                    ? me.getRole()
                                    : entry instanceof SessionEntry.ToolUseEntry
                                            ? "TOOL_USE"
                                            : entry instanceof SessionEntry.ToolResultEntry
                                                    ? "TOOL_RESULT"
                                                    : entry.getClass().getSimpleName();
                    results.add(
                            String.format(
                                    "  [%s] %s — [%s]: %s",
                                    relPath, entry.getId(), roleLabel, preview));
                }
            }
        } catch (Exception e) {
            // skip corrupted files
        }
    }

    private static String searchableText(SessionEntry entry) {
        if (entry instanceof SessionEntry.MessageEntry me) {
            return me.getContent();
        }
        if (entry instanceof SessionEntry.ToolUseEntry use) {
            return use.getName() + " " + (use.getInput() != null ? use.getInput().toString() : "");
        }
        if (entry instanceof SessionEntry.ToolResultEntry result) {
            return (result.getName() != null ? result.getName() + " " : "")
                    + (result.getOutput() != null ? result.getOutput() : "");
        }
        return null;
    }

    /** 读取旧版 {@code .json} 会话文件：按行返回最近 limit 条记录（兼容历史格式）。 */
    private String readLegacySession(Path file, int limit) {
        try {
            String content = Files.readString(file);
            String[] lines = content.split("\n");
            int start = Math.max(0, lines.length - limit);
            StringBuilder sb = new StringBuilder();
            sb.append(
                    String.format(
                            "Legacy session (%d lines, showing last %d):\n",
                            lines.length, Math.min(limit, lines.length)));
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Error reading session file: " + e.getMessage();
        }
    }
}
