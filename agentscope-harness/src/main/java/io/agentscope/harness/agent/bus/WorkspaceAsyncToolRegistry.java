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
package io.agentscope.harness.agent.bus;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link AsyncToolRegistry} backed by {@link AbstractFilesystem}.
 *
 * <p>Each async tool record is persisted as a JSON file under
 * {@code {registryRoot}/async-tools/{sessionId}/{recordId}.json}. This ensures records survive
 * process crashes — {@link io.agentscope.harness.agent.middleware.InboxMiddleware} can detect
 * stale RUNNING records on session recovery and notify the LLM.
 *
 * <p>Works with any filesystem backend (local, remote, sandbox).
 */
/**
 * 基于 {@link AbstractFilesystem} 实现的异步工具注册器 {@link AsyncToolRegistry}。
 *
 * <p>每条异步工具记录都会持久化为JSON文件，存放路径为
 * {@code {registryRoot}/async-tools/{sessionId}/{recordId}.json}。该持久化机制可保证进程崩溃后记录不丢失；
 * 会话恢复时，{@link io.agentscope.harness.agent.middleware.InboxMiddleware} 能够识别出残留的运行中记录，并通知大语言模型。
 *
 * <p>支持任意文件系统后端（本地、远程、沙箱文件系统均可）。
 */
public class WorkspaceAsyncToolRegistry implements AsyncToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAsyncToolRegistry.class);

    /** 文件系统操作使用的空 RuntimeContext（注册器操作不依赖调用上下文）。 */
    private static final RuntimeContext RC = RuntimeContext.empty();

    /** 底层文件系统抽象（本地/远端/沙箱均可）。 */
    private final AbstractFilesystem fs;

    /** 注册表根目录（末尾斜杠已在构造时去除）。 */
    private final String registryRoot;

    /**
     * @param filesystem   any {@link AbstractFilesystem} implementation
     * @param registryRoot absolute path within the filesystem (e.g. {@code "/bus/async-tools"})
     */
    /**
     * @param filesystem 任意{@link AbstractFilesystem}实现类实例
     * @param registryRoot 文件系统内的绝对路径（示例：{@code "/bus/async-tools"}）
     */
    public WorkspaceAsyncToolRegistry(AbstractFilesystem filesystem, String registryRoot) {
        this.fs = filesystem;
        this.registryRoot =
                registryRoot.endsWith("/")
                        ? registryRoot.substring(0, registryRoot.length() - 1)
                        : registryRoot;
    }

    /**
     * 注册异步任务：把记录序列化为 JSON 写入
     * {@code {registryRoot}/{sessionId}/{recordId}.json}（按会话分目录存放）。
     */
    @Override
    public Mono<Void> register(AsyncToolRecord record) {
        return Mono.fromRunnable(
                () -> {
                    String path = recordPath(record.sessionId(), record.id());
                    ensureDir(sessionDir(record.sessionId()));
                    Map<String, Object> data =
                            Map.of(
                                    "id", record.id(),
                                    "sessionId", record.sessionId(),
                                    "toolName", record.toolName(),
                                    "toolCallId", record.toolCallId(),
                                    "status", record.status(),
                                    "createdAt", record.createdAt().toString());
                    fs.write(RC, path, JsonUtils.getJsonCodec().toJson(data));
                });
    }

    /** 标记任务已完成：更新记录文件中的 status 字段为 COMPLETED。 */
    @Override
    public Mono<Void> complete(String id, String result) {
        return updateStatus(id, AsyncToolRecord.COMPLETED);
    }

    /** 标记任务失败：更新记录文件中的 status 字段为 FAILED。 */
    @Override
    public Mono<Void> fail(String id, String error) {
        return updateStatus(id, AsyncToolRecord.FAILED);
    }

    /** 标记任务超时：更新记录文件中的 status 字段为 TIMEOUT。 */
    @Override
    public Mono<Void> markTimeout(String id) {
        return updateStatus(id, AsyncToolRecord.TIMEOUT);
    }

    /**
     * 查找指定会话下的过期孤立任务：遍历会话目录的全部记录文件，
     * 筛出状态仍为 RUNNING 且创建时间早于（当前时间 - ttl）的记录。
     * 这类任务大概率因进程崩溃而失去宿主，由 InboxMiddleware 在会话恢复时通知模型。
     */
    @Override
    public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
        return Mono.fromCallable(
                () -> {
                    String dir = sessionDir(sessionId);
                    if (!fs.exists(RC, dir)) {
                        return List.<AsyncToolRecord>of();
                    }
                    LsResult ls = fs.ls(RC, dir);
                    if (!ls.isSuccess() || ls.entries() == null) {
                        return List.<AsyncToolRecord>of();
                    }
                    Instant cutoff = Instant.now().minus(ttl);
                    List<AsyncToolRecord> stale = new ArrayList<>();
                    for (FileInfo fi : ls.entries()) {
                        if (fi.isDirectory() || !fi.path().endsWith(".json")) {
                            continue;
                        }
                        AsyncToolRecord rec = readRecord(fi.path());
                        if (rec != null
                                && AsyncToolRecord.RUNNING.equals(rec.status())
                                && rec.createdAt().isBefore(cutoff)) {
                            stale.add(rec);
                        }
                    }
                    return stale;
                });
    }

    /**
     * 状态更新核心逻辑：跨会话目录定位记录文件 → 读出原记录 →
     * 删除旧文件后以新状态重写（文件系统无原子改写，采用删后重写）。
     * 记录不存在或读取失败时静默跳过。
     */
    private Mono<Void> updateStatus(String id, String newStatus) {
        return Mono.fromRunnable(
                () -> {
                    String path = findRecordPath(id);
                    if (path == null) {
                        return;
                    }
                    AsyncToolRecord rec = readRecord(path);
                    if (rec == null) {
                        return;
                    }
                    fs.delete(RC, path);
                    Map<String, Object> data =
                            Map.of(
                                    "id", rec.id(),
                                    "sessionId", rec.sessionId(),
                                    "toolName", rec.toolName(),
                                    "toolCallId", rec.toolCallId(),
                                    "status", newStatus,
                                    "createdAt", rec.createdAt().toString());
                    fs.write(RC, path, JsonUtils.getJsonCodec().toJson(data));
                });
    }

    /** 读取并反序列化单条记录文件；读取失败或 JSON 解析异常时返回 null。 */
    private AsyncToolRecord readRecord(String path) {
        ReadResult rr = fs.read(RC, path, 0, Integer.MAX_VALUE);
        if (!rr.isSuccess() || rr.fileData() == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data =
                    JsonUtils.getJsonCodec().fromJson(rr.fileData().content(), Map.class);
            return new AsyncToolRecord(
                    (String) data.get("id"),
                    (String) data.get("sessionId"),
                    (String) data.get("toolName"),
                    (String) data.get("toolCallId"),
                    (String) data.get("status"),
                    Instant.parse((String) data.get("createdAt")));
        } catch (Exception e) {
            log.warn("Failed to parse async tool record at {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 按记录 ID 全局定位文件路径：记录按会话分目录存放，
     * 而状态更新只带 ID 不带会话，故需遍历全部会话目录查找同名文件。
     */
    private String findRecordPath(String id) {
        LsResult rootLs = fs.ls(RC, registryRoot);
        if (!rootLs.isSuccess() || rootLs.entries() == null) {
            return null;
        }
        for (FileInfo sessionDir : rootLs.entries()) {
            if (!sessionDir.isDirectory()) {
                continue;
            }
            String candidate = sessionDir.path() + "/" + id + ".json";
            if (fs.exists(RC, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 会话目录路径：{registryRoot}/{清洗后的 sessionId}。 */
    private String sessionDir(String sessionId) {
        return registryRoot + "/" + sanitize(sessionId);
    }

    /** 记录文件路径：{会话目录}/{记录 ID}.json。 */
    private String recordPath(String sessionId, String id) {
        return sessionDir(sessionId) + "/" + id + ".json";
    }

    /** 确保目录存在：不存在时写入一个 .keep 占位文件创建目录。 */
    private void ensureDir(String dir) {
        if (!fs.exists(RC, dir)) {
            fs.write(RC, dir + "/.keep", "");
        }
    }

    /** 清洗字符串为安全目录名：非法字符替换为下划线；null 时用 "default" 兜底。 */
    private static String sanitize(String s) {
        return s != null ? s.replaceAll("[^a-zA-Z0-9._-]", "_") : "default";
    }
}
