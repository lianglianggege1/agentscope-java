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
package io.agentscope.harness.agent.memory.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages an append-only JSONL session tree (pi-mono-inspired).
 *
 * <p>The session file is a JSONL file where each line is a JSON-serialized {@link SessionEntry}.
 * Entries form a tree via {@code id}/{@code parentId} links. A companion {@code .log.jsonl} file
 * stores the full history for grep-ability (dual-file pattern from pi-mono mom).
 *
 * <h2>File layout</h2>
 * <pre>
 *   agents/{agentId}/sessions/{sessionId}.jsonl      — LLM context (compacted)
 *   agents/{agentId}/sessions/{sessionId}.log.jsonl   — full history (append-only, never compacted)
 * </pre>
 *
 * <h2>Persistence model</h2>
 * The local file is the working copy; the remote {@link AbstractFilesystem} (when configured) is
 * the cross-replica mirror. On every {@link #load()}, remote content is fetched and union-merged
 * with the local file so that entries written on another machine are visible to the current one.
 * On every {@link #flush()}, pending entries are appended to the local files synchronously and
 * then mirrored to the remote filesystem asynchronously (fire-and-forget, best-effort).
 *
 * <h2>Deferred persistence</h2>
 * Entries are buffered in memory and only flushed to disk on the first call to {@link #flush()}
 * (typically after the first assistant message). This avoids partial session files from
 * failed/short interactions.
 */
/**
 * 管理仅追加写入的JSONL会话树（参考pi-mono设计）。
 *
 * <p>会话文件为JSONL格式，每行是序列化后的{@link SessionEntry}对象。
 * 条目通过{@code id}/{@code parentId}关联形成树形结构。配套的{@code .log.jsonl}文件
 * 保存完整历史记录，便于检索（源自pi-mono mom的双文件架构）。
 *
 * <h2>文件布局</h2>
 * <pre>
 *   agents/{agentId}/sessions/{sessionId}.jsonl      — 大模型上下文（经过压缩）
 *   agents/{agentId}/sessions/{sessionId}.log.jsonl   — 完整历史（仅追加，永不压缩）
 * </pre>
 *
 * <h2>持久化模型</h2>
 * 本地文件作为工作副本；远端{@link AbstractFilesystem}（配置启用时）
 * 用于跨副本数据镜像。每次调用{@link #load()}时，拉取远端数据并与本地文件合并，
 * 使当前实例能够读取其他机器写入的条目。
 * 每次调用{@link #flush()}时，待落地条目同步追加至本地文件，
 * 随后异步镜像至远端文件系统（发后即忘、尽力投递）。
 *
 * <h2>延迟持久化</h2>
 * 条目先缓存于内存，首次调用{@link #flush()}（通常在首条助手消息生成后）才写入磁盘。
 * 避免交互异常或提前终止产生残缺会话文件。
 */
public class SessionTree {

    /**
     * Captured at construction time (or via {@link #setRuntimeContext}); used as the
     * {@code RuntimeContext} for all remote filesystem operations so that namespace-aware stores
     * resolve to the writer's namespace even when invoked from the async mirror thread.
     */
    /**
     * 在构造时（或通过 {@link #setRuntimeContext}）捕获，作为所有远端文件系统操作的 {@code RuntimeContext}。
     * 以此确保具备命名空间感知的存储能够解析至写入方所属命名空间，即便操作由异步镜像线程发起。
     */
    private volatile RuntimeContext fsRc = RuntimeContext.empty();

    private static final Logger log = LoggerFactory.getLogger(SessionTree.class);

    /**
     * Daemon executor used for fire-and-forget remote mirrors so that flush() never blocks callers
     * on remote I/O. A single thread is intentional: serialises uploads for the same session.
     */
    /**
     * 用于执行发后即忘式远端镜像同步的守护线程执行器，保证 flush() 不会因远端IO阻塞调用方。
     * 刻意采用单线程：对同一会话的上传操作串行执行。
     */
    private static final ExecutorService MIRROR_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "session-tree-mirror");
                        t.setDaemon(true);
                        return t;
                    });

    private final Path contextFile;
    private final Path logFile;
    private final Path workspaceRoot;
    private final AbstractFilesystem filesystem;
    private final WorkspaceIndex index;
    private final String contextRelativePath;
    private final String logRelativePath;

    private final Map<String, SessionEntry> entriesById = new LinkedHashMap<>();
    private final List<SessionEntry> appendOrder = new ArrayList<>();
    private final List<SessionEntry> pendingWrites = new ArrayList<>();

    private String lastCompactionFirstKeptId;
    private String lastSummaryEntryId;
    private boolean loaded = false;
    private boolean flushed = false;

    /**
     * Creates a SessionTree backed by the given filesystem for remote mirroring.
     *
     * @param contextFile  path to the {@code .jsonl} context file (LLM-facing, compacted)
     * @param workspaceRoot root of the agent workspace; used to derive workspace-relative paths
     * @param filesystem   {@link AbstractFilesystem} used for remote read/write; may be
     *                     {@code null} to disable remote mirroring (local-only mode)
     */
    /**
     * 创建由指定文件系统提供远端镜像能力的会话树实例。
     *
     * @param contextFile  {@code .jsonl}上下文文件路径（面向大模型，经过压缩）
     * @param workspaceRoot 智能体工作空间根目录；用于生成工作空间相对路径
     * @param filesystem   用于远端读写的{@link AbstractFilesystem}；可传入{@code null}
     *                     关闭远端镜像功能（本地运行模式）
     */
    public SessionTree(Path contextFile, Path workspaceRoot, AbstractFilesystem filesystem) {
        this(contextFile, workspaceRoot, filesystem, null, null);
    }

    public SessionTree(
            Path contextFile,
            Path workspaceRoot,
            AbstractFilesystem filesystem,
            WorkspaceIndex index) {
        this(contextFile, workspaceRoot, filesystem, index, null);
    }

    /**
     * Creates a SessionTree with optional best-effort workspace index support.
     *
     * @param contextFile          path to the {@code .jsonl} context file
     * @param workspaceRoot        root of the agent workspace
     * @param filesystem           remote filesystem; may be {@code null}
     * @param index                best-effort workspace index; may be {@code null}
     * @param contextRelativePath  workspace-relative path for the context file WITHOUT namespace
     *                             prefix (e.g. {@code agents/X/sessions/Y.jsonl}); when non-null,
     *                             used for filesystem mirror/restore instead of computing via
     *                             {@link #toWorkspaceRelative(Path)}
     */
    /**
     * 创建会话树实例，可选启用尽力型工作空间索引能力。
     *
     * @param contextFile          {@code .jsonl}上下文文件路径
     * @param workspaceRoot        智能体工作空间根目录
     * @param filesystem           远端文件系统；允许传入 {@code null}
     * @param index                尽力型工作空间索引；允许传入 {@code null}
     * @param contextRelativePath  上下文文件相对于工作空间的路径，**不含命名空间前缀**
     *                             （例如 {@code agents/X/sessions/Y.jsonl}）；非空时，
     *                             文件系统镜像/还原操作将直接使用该路径，不再通过
     *                             {@link #toWorkspaceRelative(Path)} 计算生成
     */
    public SessionTree(
            Path contextFile,
            Path workspaceRoot,
            AbstractFilesystem filesystem,
            WorkspaceIndex index,
            String contextRelativePath) {
        this.contextFile = contextFile;
        String name = contextFile.getFileName().toString();
        String baseName = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        this.logFile = contextFile.resolveSibling(baseName + ".log.jsonl");
        this.workspaceRoot = workspaceRoot;
        this.filesystem = filesystem;
        this.index = index;
        this.contextRelativePath = contextRelativePath;
        if (contextRelativePath != null) {
            String dir =
                    contextRelativePath.contains("/")
                            ? contextRelativePath.substring(
                                    0, contextRelativePath.lastIndexOf('/') + 1)
                            : "";
            this.logRelativePath = dir + baseName + ".log.jsonl";
        } else {
            this.logRelativePath = null;
        }
    }

    /**
     * Binds a {@link RuntimeContext} to this tree so that all subsequent remote filesystem reads
     * and writes (including async mirror operations) propagate the caller's identity into
     * namespace-aware stores. Defaults to {@link RuntimeContext#empty()} when never set.
     *
     * @param rc the runtime context to bind; {@code null} resets to empty
     * @return this tree, for fluent chaining
     */
    /**
     * 为此会话树绑定 {@link RuntimeContext}，后续所有远端文件系统读写操作
     *（包括异步镜像任务）都会传递调用方身份至具备命名空间感知的存储组件。
     * 若从未设置，默认使用 {@link RuntimeContext#empty()}。
     *
     * @param rc 待绑定的运行时上下文；传入 {@code null} 则重置为空上下文
     * @return 当前会话树实例，支持流式链式调用
     */
    public SessionTree setRuntimeContext(RuntimeContext rc) {
        this.fsRc = rc != null ? rc : RuntimeContext.empty();
        return this;
    }

    /**
     * Loads existing entries from the local context file into the in-memory tree.
     *
     * <p>This is a <b>local-only, zero-network</b> operation. If the local file is absent, the
     * tree starts empty. To additionally pull and union-merge entries from the remote filesystem
     * (e.g., before a write that may follow a cross-machine handoff), call
     * {@link #syncFromRemote()} after this method.
     *
     * <p>Safe to call multiple times; only loads once.
     */
    /**
     * 从本地上下文文件加载已有条目至内存会话树。
     *
     * <p>该操作**仅访问本地、无网络请求**。若本地文件不存在，则会话树初始为空。
     * 如果需要额外拉取远端文件系统数据并执行合并（例如跨机器交接后的写入前置操作），
     * 请在调用本方法后执行 {@link #syncFromRemote()}。
     *
     * <p>支持多次调用；实际仅执行一次加载。
     */
    public void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        // Cold-start restore: if local file is absent but remote has a copy, pull it down once.
        if (!Files.isRegularFile(contextFile)) {
            restoreFromMirror(contextFile);
        }

        List<SessionEntry> localEntries = readLocalEntries(contextFile);
        for (SessionEntry entry : localEntries) {
            entriesById.put(entry.getId(), entry);
            appendOrder.add(entry);

            if (entry instanceof SessionEntry.CompactionEntry ce) {
                lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                lastSummaryEntryId = ce.getSummaryEntryId();
            }
        }
    }

    /**
     * Pulls the remote context file and union-merges any entries not yet present locally.
     *
     * <p>Remote is treated as the authoritative base: remote entries come first, followed by any
     * local-only entries (written but not yet mirrored). If the remote has entries the local file
     * does not, the local file is overwritten with the merged content and the new entries are
     * appended to the local log file.
     *
     * <p>This is a <b>network operation</b> — call it only when cross-machine consistency is
     * required (typically in write paths such as
     * {@link io.agentscope.harness.agent.memory.MemoryFlushManager}). Read-only tools should use
     * {@link #load()} alone to keep queries fast and local.
     *
     * <p>No-op if no filesystem is configured or the remote read fails (failures are logged as
     * warnings).
     *
     * <p>{@link #load()} must be called before this method.
     */
    /**
     * 拉取远端上下文文件，并将本地尚未存在的条目进行合并。
     *
     * <p>远端视为权威基准：优先载入远端条目，再追加仅存在于本地（已写入但尚未同步）的条目。
     * 若远端包含本地缺失的条目，则使用合并后内容覆盖本地文件，并将新增条目追加至本地日志文件。
     *
     * <p>该操作**涉及网络请求**，仅在需要跨机器数据一致性时调用（常见于写入链路，
     * 例如 {@link io.agentscope.harness.agent.memory.MemoryFlushManager}）。
     * 只读查询场景应仅使用 {@link #load()}，保证访问为本地快速操作。
     *
     * <p>未配置远端文件系统或远端读取失败时，该方法无实际动作（异常仅记录警告日志）。
     *
     * <p>调用本方法前必须先执行 {@link #load()}。
     */
    public void syncFromRemote() {
        if (filesystem == null || workspaceRoot == null) {
            return;
        }

        List<SessionEntry> remoteEntries = pullRemoteEntries(contextFile);
        if (remoteEntries.isEmpty()) {
            return;
        }

        Set<String> localIds =
                appendOrder.stream().map(SessionEntry::getId).collect(Collectors.toSet());

        List<SessionEntry> remoteNewEntries =
                remoteEntries.stream().filter(re -> !localIds.contains(re.getId())).toList();
        if (remoteNewEntries.isEmpty()) {
            return;
        }

        // Rebuild merged list: remote base + local-only extras at the end.
        // 重构合并条目列表：远端基准条目在前，仅本地新增条目追加至末尾。
        Set<String> remoteIds =
                remoteEntries.stream()
                        .map(SessionEntry::getId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<SessionEntry> merged = new ArrayList<>(remoteEntries);
        for (SessionEntry e : appendOrder) {
            if (!remoteIds.contains(e.getId())) {
                merged.add(e);
            }
        }

        overwriteFile(contextFile, merged);
        appendToFile(logFile, remoteNewEntries);

        // Update in-memory state with the newly discovered remote entries.
        // 使用新拉取到的远端条目更新内存状态。
        for (SessionEntry entry : remoteNewEntries) {
            entriesById.put(entry.getId(), entry);
        }
        // Re-build appendOrder to match the merged order (remote base first).
        // 重建追加顺序，与合并序列保持一致（远端基准条目优先）。
        appendOrder.clear();
        appendOrder.addAll(merged);
        for (SessionEntry entry : remoteNewEntries) {
            if (entry instanceof SessionEntry.CompactionEntry ce) {
                lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                lastSummaryEntryId = ce.getSummaryEntryId();
            }
        }

        log.info(
                "syncFromRemote: merged {} new remote entries into local session file {}",
                remoteNewEntries.size(),
                contextFile.getFileName());
    }

    /**
     * Appends an entry to the in-memory tree. The entry will be written to disk
     * on the next {@link #flush()} call.
     *
     * @return the entry (for chaining)
     */
    /**
     * 向内存会话树追加一条条目。该条目将在下一次调用 {@link #flush()} 时持久化至磁盘。
     *
     * @return 当前条目（支持链式调用）
     */
    public SessionEntry append(SessionEntry entry) {
        entriesById.put(entry.getId(), entry);
        appendOrder.add(entry);
        pendingWrites.add(entry);

        if (entry instanceof SessionEntry.CompactionEntry ce) {
            lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
            lastSummaryEntryId = ce.getSummaryEntryId();
        }

        return entry;
    }

    /**
     * Flushes all pending entries to both the local context file and the local log file
     * synchronously, then schedules an asynchronous best-effort mirror to the remote filesystem.
     *
     * <p>The remote mirror is fire-and-forget: failures are logged as warnings and do not affect
     * the return of this method. The local write is always the primary guarantee.
     */
    /**
     * 将所有待落地条目同步写入本地上下文文件与本地日志文件，随后调度异步任务尽力同步镜像至远端文件系统。
     *
     * <p>远端镜像采用发后即忘模式：同步失败仅记录警告日志，不会影响本方法返回。本地写入作为首要数据保障。
     */
    public void flush() {
        if (pendingWrites.isEmpty()) {
            return;
        }

        flushed = true;
        List<SessionEntry> toWrite = new ArrayList<>(pendingWrites);
        pendingWrites.clear();

        appendToFile(contextFile, toWrite);
        appendToFile(logFile, toWrite);
        scheduleMirror();
    }

    /**
     * Returns whether {@link #flush()} has been called at least once.
     */
    /**
     * 返回 {@link #flush()} 是否至少被调用过一次。
     */
    public boolean isFlushed() {
        return flushed;
    }

    /**
     * Builds the LLM-visible context from the session tree.
     *
     * <p>Returns entries that the LLM should see:
     * <ul>
     *   <li>If compaction has occurred, starts with the summary entry, then all entries
     *       from {@code firstKeptEntryId} onward</li>
     *   <li>If no compaction, returns all message entries in order</li>
     * </ul>
     */
    /**
     * 基于会话树构建对大模型可见的上下文。
     *
     * <p>返回大模型能够读取的条目集合：
     * <ul>
     *   <li>若已执行压缩：以摘要条目作为起始，随后展示 {@code firstKeptEntryId} 及之后的所有条目</li>
     *   <li>若无压缩操作：按顺序返回全部消息条目</li>
     * </ul>
     */
    public List<SessionEntry> buildContext() {
        if (appendOrder.isEmpty()) {
            return Collections.emptyList();
        }

        if (lastCompactionFirstKeptId == null) {
            return new ArrayList<>(appendOrder);
        }

        List<SessionEntry> context = new ArrayList<>();

        if (lastSummaryEntryId != null) {
            SessionEntry summary = entriesById.get(lastSummaryEntryId);
            if (summary != null) {
                context.add(summary);
            }
        }

        boolean found = false;
        for (SessionEntry entry : appendOrder) {
            if (entry.getId().equals(lastCompactionFirstKeptId)) {
                found = true;
            }
            if (found && entry instanceof SessionEntry.MessageEntry) {
                context.add(entry);
            }
        }

        return context;
    }

    /**
     * Returns all entries in append order (full history).
     */
    /**
     * 按追加顺序返回全部条目（完整历史记录）。
     */
    public List<SessionEntry> getAllEntries() {
        return Collections.unmodifiableList(appendOrder);
    }

    /**
     * Returns only message entries in append order.
     */
    /**
     * 按追加顺序仅返回消息类型条目。
     */
    public List<SessionEntry.MessageEntry> getMessageEntries() {
        return appendOrder.stream()
                .filter(e -> e instanceof SessionEntry.MessageEntry)
                .map(e -> (SessionEntry.MessageEntry) e)
                .toList();
    }

    public int size() {
        return appendOrder.size();
    }

    public Path getContextFile() {
        return contextFile;
    }

    public Path getLogFile() {
        return logFile;
    }

    /**
     * Syncs entries from the log file that are not yet in the context file.
     * This handles offline messages that were appended to the log while the
     * agent was inactive.
     *
     * @return the number of new entries synced
     */
    /**
     * 同步日志文件中尚未存在于上下文文件的条目。
     * 用于处理智能体休眠期间追加至日志文件的离线消息。
     *
     * @return 已同步的新增条目数量
     */
    public int syncFromLog() {
        restoreFromMirror(logFile);
        if (!Files.isRegularFile(logFile)) {
            return 0;
        }

        int syncCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    SessionEntry entry =
                            JsonUtils.getJsonCodec().fromJson(line, SessionEntry.class);
                    if (!entriesById.containsKey(entry.getId())) {
                        entriesById.put(entry.getId(), entry);
                        appendOrder.add(entry);
                        pendingWrites.add(entry);
                        syncCount++;

                        if (entry instanceof SessionEntry.CompactionEntry ce) {
                            lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                            lastSummaryEntryId = ce.getSummaryEntryId();
                        }
                    }
                } catch (Exception e) {
                    log.debug("Skipping malformed log entry during sync: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to sync from log file {}: {}", logFile, e.getMessage());
        }

        if (syncCount > 0) {
            log.info("Synced {} offline entries from log to context", syncCount);
        }
        return syncCount;
    }

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

    /**
     * Schedules an asynchronous, best-effort mirror of both session files to the remote
     * filesystem. Uses a daemon single-thread executor to serialise uploads and avoid
     * blocking the caller on remote I/O.
     */
    /**
     * 调度异步尽力同步任务，将两份会话文件镜像至远端文件系统。
     * 采用守护单线程执行器串行处理上传任务，避免远端IO阻塞调用方。
     */
    private void scheduleMirror() {
        if (filesystem == null || workspaceRoot == null) {
            return;
        }
//        MIRROR_EXECUTOR.execute(
//                () -> {
                    mirrorToFilesystem(contextFile, resolveRelativePath(contextFile));
                    mirrorToFilesystem(logFile, resolveRelativePath(logFile));
//                });
    }

    /**
     * Fetches the remote copy of {@code file} and parses it as JSONL session entries.
     * Returns an empty list if no filesystem is configured or the remote read fails.
     */
    /**
     * 拉取 {@code file} 的远端副本并解析为JSONL会话条目。
     * 未配置文件系统或远端读取失败时返回空列表。
     */
    private List<SessionEntry> pullRemoteEntries(Path file) {
        if (filesystem == null || workspaceRoot == null) {
            return List.of();
        }
        String relativePath = resolveRelativePath(file);
        if (relativePath == null || relativePath.isBlank()) {
            return List.of();
        }
        ReadResult read = filesystem.read(fsRc, relativePath, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || read.fileData().content() == null) {
            return List.of();
        }
        return parseJsonlEntries(read.fileData().content());
    }

    /**
     * Reads and parses the local copy of {@code file} as JSONL session entries.
     * Returns an empty list if the file does not exist or cannot be read.
     */
    /**
     * 读取并解析 {@code file} 本地副本，转换为JSONL会话条目。
     * 文件不存在或读取失败时返回空列表。
     */
    private List<SessionEntry> readLocalEntries(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parseJsonlEntries(sb.toString());
        } catch (IOException e) {
            log.warn("Failed to read local session file {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /** Parses a JSONL string into a list of {@link SessionEntry} objects, skipping bad lines. */
    /**
     * 将JSONL字符串解析为 {@link SessionEntry} 对象列表，跳过格式异常行。
     */
    private List<SessionEntry> parseJsonlEntries(String content) {
        List<SessionEntry> result = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                result.add(JsonUtils.getJsonCodec().fromJson(line, SessionEntry.class));
            } catch (Exception e) {
                log.warn("Skipping malformed session entry: {}", e.getMessage());
            }
        }
        return result;
    }

    /** Overwrites {@code file} with the serialised form of {@code entries} (TRUNCATE + WRITE). */
    /**
     * 使用条目序列化内容覆盖写入 {@code file}（先清空文件，再写入数据）。
     */
    private void overwriteFile(Path file, List<SessionEntry> entries) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (BufferedWriter writer =
                    Files.newBufferedWriter(
                            file,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                for (SessionEntry entry : entries) {
                    writer.write(JsonUtils.getJsonCodec().toJson(entry));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to overwrite session file {}: {}", file, e.getMessage());
        }
    }

    private void appendToFile(Path file, List<SessionEntry> entries) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (BufferedWriter writer =
                    Files.newBufferedWriter(
                            file,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)) {
                for (SessionEntry entry : entries) {
                    String json = JsonUtils.getJsonCodec().toJson(entry);
                    writer.write(json);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to append to session file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Uploads {@code file} to the remote filesystem (full-file upload). Only called from the
     * mirror executor thread; failures are logged as warnings.
     */
    /**
     * 将 {@code file} 完整上传至远端文件系统。仅由镜像执行线程调用；上传失败仅记录警告日志。
     */
    private void mirrorToFilesystem(Path file, String relativePath) {
        if (filesystem == null || workspaceRoot == null || !Files.isRegularFile(file)) {
            return;
        }
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            filesystem.uploadFiles(fsRc, List.of(Map.entry(relativePath, bytes)));
            // Best-effort: the local file already exists — update index with its current stats
            if (index != null) {
                index.upsertFromLocalFile(relativePath, file);
            }
        } catch (IOException e) {
            log.warn("Failed to mirror session file {} to filesystem: {}", file, e.getMessage());
        }
    }

    /**
     * Restores {@code file} from the remote filesystem mirror when the local file is absent.
     * Used by {@link #syncFromLog()} to ensure the log file is available locally before reading.
     */
    /**
     * 本地文件缺失时，从远端文件镜像恢复 {@code file}。
     * 由 {@link #syncFromLog()} 调用，确保读取前本地存在日志文件。
     */
    private void restoreFromMirror(Path file) {
        if (filesystem == null || workspaceRoot == null || Files.isRegularFile(file)) {
            return;
        }
        String relativePath = resolveRelativePath(file);
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        ReadResult read = filesystem.read(fsRc, relativePath, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || read.fileData().content() == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(
                    file,
                    read.fileData().content(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            // Best-effort: record restored file in local index
            if (index != null) {
                index.upsertFromLocalFile(relativePath, file);
            }
        } catch (IOException e) {
            log.warn(
                    "Failed to restore session file {} from filesystem mirror: {}",
                    file,
                    e.getMessage());
        }
    }

    private String resolveRelativePath(Path file) {
        if (contextRelativePath != null) {
            if (file.equals(contextFile)) {
                return contextRelativePath;
            }
            if (file.equals(logFile)) {
                return logRelativePath;
            }
        }
        return toWorkspaceRelative(file);
    }

    private String toWorkspaceRelative(Path file) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }
        return root.relativize(candidate).toString().replace('\\', '/');
    }
}
