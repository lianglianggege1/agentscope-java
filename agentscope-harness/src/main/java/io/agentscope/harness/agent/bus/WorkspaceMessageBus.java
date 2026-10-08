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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link MessageBus} implementation backed by {@link AbstractFilesystem}.
 *
 * <p>Works with any filesystem backend (local, remote, sandbox). Cross-process delivery only
 * works when the filesystem backend is actually shared (for example a remote/KV-backed store);
 * a pure local disk backend remains single-process.
 *
 * <p>Mode D (pub/sub) is degraded to polling: {@link #subscribe} returns a {@code Flux} that emits
 * an empty signal every 3 seconds; {@link #publish} is a no-op.
 *
 * <p>File layout under {@code busRoot}:
 * <pre>
 * {busRoot}/
 *   queues/{key-hash}/
 *     {entryId}.json       — Mode A entries (drain = ls + read + delete)
 *   logs/{key-hash}/
 *     {entryId}.json       — Mode C entries (append-only)
 * </pre>
 */
/**
 * 基于 {@link AbstractFilesystem} 实现的消息总线 {@link MessageBus}。
 *
 * <p>可适配任意文件系统底层（本地、远端、沙箱），适用于多JVM共享同一工作目录的跨进程场景。
 *
 * <p>D模式（发布订阅）降级为轮询机制：{@link #subscribe} 返回的数据流每3秒发送一次空信号；
 * {@link #publish} 方法为空实现，无实际作用。
 *
 * <p>总线根目录 {@code busRoot} 下文件结构：
 * <pre>
 * {busRoot}/
 *   queues/{key-hash}/
 *     {entryId}.json       — A模式消息条目（消费逻辑：列出+读取+删除）
 *   logs/{key-hash}/
 *     {entryId}.json       — C模式消息条目（仅追加写入）
 * </pre>
 */
public class WorkspaceMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMessageBus.class);

    /** 文件系统操作使用的空 RuntimeContext（总线操作不依赖调用上下文）。 */
    private static final RuntimeContext RC = RuntimeContext.empty();

    /** D 模式降级轮询的间隔时间（每 3 秒发出一次空信号）。 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    /** 底层文件系统抽象（本地/远端/沙箱均可）。 */
    private final AbstractFilesystem fs;

    /** 总线数据根目录（末尾斜杠已在构造时去除）。 */
    private final String busRoot;

    /** 条目 ID 序列生成器，以启动时刻纳秒值为种子保证多进程下尽量不冲突。 */
    private final AtomicLong seq = new AtomicLong(System.nanoTime());

    /**
     * @param filesystem any {@link AbstractFilesystem} implementation
     * @param busRoot    absolute path within the filesystem for bus data (e.g. {@code "/bus"})
     */
    /**
     * @param filesystem 任意 {@link AbstractFilesystem} 实现实例
     * @param busRoot 文件系统内存储总线数据的绝对路径（示例：{@code "/bus"}）
     */
    public WorkspaceMessageBus(AbstractFilesystem filesystem, String busRoot) {
        this.fs = filesystem;
        this.busRoot = busRoot.endsWith("/") ? busRoot.substring(0, busRoot.length() - 1) : busRoot;
    }

    // ---- Mode A: drain queue ----
    // ---- A 模式：消费队列 ----

    /**
     * A 模式入队：生成条目 ID，把载荷序列化为 JSON 写入
     * {@code queues/{key-hash}/{entryId}.json}。
     */
    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                () -> {
                    String entryId = nextEntryId();
                    String dir = queueDir(key);
                    ensureDir(dir);
                    String path = dir + "/" + entryId + ".json";
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    fs.write(RC, path, json);
                    return entryId;
                });
    }

    /**
     * A 模式消费：按文件名（即条目 ID）升序列出队列目录，最多取 maxCount 条，
     * 逐条"读取 → 反序列化 → 删除文件"，实现读取即确认。
     */
    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String dir = queueDir(key);
                    List<FileInfo> files = listSorted(dir);
                    List<BusEntry> result = new ArrayList<>();
                    int count = Math.min(maxCount, files.size());
                    for (int i = 0; i < count; i++) {
                        FileInfo fi = files.get(i);
                        String content = readFileContent(fi.path());
                        if (content == null) {
                            // Transient read failure (race with concurrent drain, I/O error).
                            // Do NOT delete — preserve the entry so the next drain can retry it.
                            // 瞬时读取失败（与并发消费的竞态或 I/O 错误）。
                            // 不要删除——保留条目，让下次消费可以重试。
                            log.warn(
                                    "queueDrain: failed to read entry {}, skipping without delete",
                                    fi.path());
                            continue;
                        }
                        String entryId = extractEntryId(fi.path());
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(content, Map.class);
                            result.add(new BusEntry(entryId, payload));
                        } catch (Exception e) {
                            // Corrupt JSON is unrecoverable; delete to avoid an infinite drain
                            // loop on the same bad entry, but log so it can be investigated.
                            // 损坏的 JSON 无法恢复；删除以避免在同一条坏消息上
                            // 陷入无限消费循环，同时记日志便于排查。
                            log.warn(
                                    "queueDrain: corrupt JSON in entry {} ({}), deleting to"
                                            + " unblock queue",
                                    fi.path(),
                                    e.getMessage());
                            fs.delete(RC, fi.path());
                            continue;
                        }
                        fs.delete(RC, fi.path());
                    }
                    return result;
                });
    }

    /** A 模式删除队列：直接删除整个队列目录（幂等）。 */
    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.fromRunnable(() -> fs.delete(RC, queueDir(key)));
    }

    /** A 模式探测：目录下存在条目文件即认为队列非空（不消费）。 */
    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(() -> !listSorted(queueDir(key)).isEmpty());
    }

    // ---- Mode C: replay log ----
    // ---- C 模式：回放日志 ----

    /**
     * C 模式追加：与入队类似的写文件，但条目保留不删除；
     * maxLen > 0 且超出上限时，按顺序删除最旧的超额条目（近似裁剪）。
     */
    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.fromCallable(
                () -> {
                    String entryId = nextEntryId();
                    String dir = logDir(key);
                    ensureDir(dir);
                    String path = dir + "/" + entryId + ".json";
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    fs.write(RC, path, json);

                    if (maxLen > 0) {
                        List<FileInfo> files = listSorted(dir);
                        if (files.size() > maxLen) {
                            int excess = files.size() - maxLen;
                            for (int i = 0; i < excess; i++) {
                                fs.delete(RC, files.get(i).path());
                            }
                        }
                    }
                    return entryId;
                });
    }

    /**
     * C 模式读取：按条目 ID 排序后定位游标 since 的下一个位置，
     * 最多读取 maxCount 条；非破坏性读取，条目文件保持不动。
     * 游标未命中（已被裁剪）时从头开始读。
     */
    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String dir = logDir(key);
                    List<FileInfo> files = listSorted(dir);
                    if (files.isEmpty()) {
                        return List.<BusEntry>of();
                    }

                    int startIdx = 0;
                    if (since != null) {
                        for (int i = 0; i < files.size(); i++) {
                            if (extractEntryId(files.get(i).path()).equals(since)) {
                                startIdx = i + 1;
                                break;
                            }
                        }
                    }

                    List<BusEntry> result = new ArrayList<>();
                    int endIdx = Math.min(startIdx + maxCount, files.size());
                    for (int i = startIdx; i < endIdx; i++) {
                        FileInfo fi = files.get(i);
                        String content = readFileContent(fi.path());
                        if (content != null) {
                            String entryId = extractEntryId(fi.path());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(content, Map.class);
                            result.add(new BusEntry(entryId, payload));
                        }
                    }
                    return result;
                });
    }

    /** C 模式裁剪：删除整个日志目录及其全部条目（幂等）。 */
    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.fromRunnable(() -> fs.delete(RC, logDir(key)));
    }

    // ---- Mode D: degraded pub/sub ----
    // ---- D 模式：降级版发布/订阅 ----

    /**
     * D 模式发布：文件系统无实时推送能力，此处为空操作。
     * 事件的实际传递依赖 C 模式回放日志（见 sessionPublishEvent）。
     */
    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.empty();
    }

    /**
     * D 模式订阅：降级为每 3 秒一次的定时空信号，
     * 订阅者收到信号后应主动去读对应的队列/日志（轮询模型）。
     */
    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.interval(POLL_INTERVAL, Schedulers.boundedElastic())
                .map(tick -> Map.<String, Object>of());
    }

    // ---- Internal helpers ----
    // ---- 内部辅助方法 ----

    /** A 模式队列目录：{busRoot}/queues/{key 哈希目录名}。 */
    private String queueDir(String key) {
        return busRoot + "/queues/" + hashKey(key);
    }

    /** C 模式日志目录：{busRoot}/logs/{key 哈希目录名}。 */
    private String logDir(String key) {
        return busRoot + "/logs/" + hashKey(key);
    }

    /** 生成 20 位零填充的递增条目 ID（字典序即入队顺序，可直接当游标）。 */
    private String nextEntryId() {
        return String.format("%020d", seq.incrementAndGet());
    }

    /**
     * 把任意 key 转为安全的目录名：非法字符替换为下划线，
     * 并追加 hashCode 十六进制后缀避免不同 key 转义后撞名。
     */
    static String hashKey(String key) {
        int hash = key.hashCode() & 0x7fffffff;
        return key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + Integer.toHexString(hash);
    }

    /** 从文件路径反解条目 ID（去除目录前缀与 .json 后缀）。 */
    private String extractEntryId(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".json")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    /**
     * 列出目录下的全部 .json 条目文件并按路径（即条目 ID）升序排序；
     * 目录不存在或列举失败时返回空列表。
     */
    private List<FileInfo> listSorted(String dir) {
        if (!fs.exists(RC, dir)) {
            return List.of();
        }
        LsResult ls = fs.ls(RC, dir);
        if (!ls.isSuccess() || ls.entries() == null) {
            return List.of();
        }
        return ls.entries().stream()
                .filter(f -> !f.isDirectory() && f.path().endsWith(".json"))
                .sorted(Comparator.comparing(FileInfo::path))
                .toList();
    }

    /** 读取条目文件全文内容；读取失败返回 null（由调用方决定跳过或重试）。 */
    private String readFileContent(String path) {
        ReadResult rr = fs.read(RC, path, 0, Integer.MAX_VALUE);
        if (!rr.isSuccess() || rr.fileData() == null) {
            return null;
        }
        return rr.fileData().content();
    }

    /** 确保目录存在：不存在时写入一个 .keep 占位文件创建目录。 */
    private void ensureDir(String dir) {
        if (!fs.exists(RC, dir)) {
            fs.write(RC, dir + "/.keep", "");
        }
    }
}
