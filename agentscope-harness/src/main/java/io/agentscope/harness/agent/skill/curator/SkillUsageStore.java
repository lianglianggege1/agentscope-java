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
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord.State;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sidecar telemetry store for per-skill usage and provenance.
 *
 * <p>Persists {@link SkillUsageRecord} instances (one per skill) as a single JSON object at
 * {@code <workspace>/skills/.usage.json}. Reads / writes go through {@link AbstractFilesystem}
 * with {@link RuntimeContext#empty()} — telemetry is agent-scoped, not user-scoped, so we
 * intentionally bypass per-user namespacing.
 *
 * <p><b>Concurrency</b>: a {@link ReentrantLock} serialises in-process read-modify-write cycles.
 * For cross-process safety on a {@code LocalFilesystem} a future milestone will add
 * {@link java.nio.channels.FileLock} on a sibling {@code .usage.json.lock} file; for
 * {@code RemoteFilesystem} a KV CAS path is required. Both are tracked as TODO.
 *
 * <p><b>Provenance gate</b>: counter mutators (bumpView/bumpUse/bumpPatch) silently skip skills
 * that are not agent-created. This avoids polluting telemetry for bundled / hub-installed /
 * user-authored skills, matching the hermes-agent {@code skill_usage._mutate} behavior.
 */
/**
 * 用于存储各技能使用情况与来源信息的Sidecar遥测存储。
 *
 * <p>持久保存若干 {@link SkillUsageRecord} 实例（一技能一条记录），统一存放在
 * {@code <workspace>/skills/.usage.json} 单一JSON文件中。读写操作经由 {@link AbstractFilesystem}
 * 执行，并使用 {@link RuntimeContext#empty()} ——遥测数据作用域为代理实例而非用户，因此刻意绕过用户命名空间隔离。
 *
 * <p><b>并发说明</b>：通过 {@link ReentrantLock} 串行进程内的读-改-写流程。
 * 针对 {@code LocalFilesystem} 的跨进程安全，后续版本将在同级目录新增 {@code .usage.json.lock} 文件，
 * 借助 {@link java.nio.channels.FileLock} 实现；若是 {@code RemoteFilesystem}，则需要基于KV的CAS机制。两项均标记为待实现。
 *
 * <p><b>来源准入规则</b>：计数器更新方法（bumpView/bumpUse/bumpPatch）会直接忽略非代理创建的技能。
 * 避免对内置、平台安装、用户自研技能产生冗余遥测数据，行为对齐 hermes-agent 的 {@code skill_usage._mutate}。
 */
public class SkillUsageStore {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageStore.class);

    /** Default workspace-relative path for the sidecar. */
    public static final String DEFAULT_RELATIVE_PATH = "skills/.usage.json";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    // Tolerate fields the schema doesn't expect — e.g. {@code agentCreated}
                    // which Jackson's default getter discovery emits from {@code
                    // SkillUsageRecord.isAgentCreated()}, plus any field added in a future
                    // schema bump that an older binary needs to load gracefully.
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AbstractFilesystem filesystem;
    private final String relativePath;
    private final ReentrantLock lock = new ReentrantLock();

    public SkillUsageStore(AbstractFilesystem filesystem) {
        this(filesystem, DEFAULT_RELATIVE_PATH);
    }

    public SkillUsageStore(AbstractFilesystem filesystem, String relativePath) {
        this.filesystem = java.util.Objects.requireNonNull(filesystem, "filesystem");
        this.relativePath =
                relativePath != null && !relativePath.isBlank()
                        ? relativePath
                        : DEFAULT_RELATIVE_PATH;
    }

    // ---------------------------------------------------------------------
    //  Read / write
    // ---------------------------------------------------------------------

    /** Load the entire sidecar map. Returns an empty map on missing / unreadable / corrupt. */
    /** 加载完整Sidecar映射表。文件不存在、无法读取或损坏时返回空Map。 */
    public Map<String, SkillUsageRecord> load() {
        try {
            ReadResult rr = filesystem.read(RuntimeContext.empty(), relativePath, 0, 0);
            if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                return new LinkedHashMap<>();
            }
            String body = rr.fileData().content();
            if (body.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, SkillUsageRecord> parsed =
                    JSON.readValue(
                            body, new TypeReference<LinkedHashMap<String, SkillUsageRecord>>() {});
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("SkillUsageStore.load() failed: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Persist the entire sidecar map atomically (full rewrite). Best-effort on failure. */
    /** 原子持久化完整Sidecar映射（全量重写）。写入失败时尽最大努力保证数据一致性。 */
    public void save(Map<String, SkillUsageRecord> data) {
        try {
            String json = JSON.writeValueAsString(data != null ? data : Map.of());
            filesystem.uploadFiles(
                    RuntimeContext.empty(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    relativePath, json.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.warn("SkillUsageStore.save() failed: {}", e.getMessage(), e);
        }
    }

    /** Read a single record by name. */
    /** 根据技能名称读取单条记录。 */
    public Optional<SkillUsageRecord> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(load().get(name));
    }

    /**
     * Apply {@code mutator} to the record for {@code name} under the in-process lock. Creates a
     * fresh {@link SkillUsageRecord#defaults()} record if none exists.
     */
    /**
     * 在进程锁保护下，对指定名称对应的记录执行 {@code mutator} 修改逻辑。
     * 若无对应记录，则新建一条 {@link SkillUsageRecord#defaults()} 默认记录。
     */
    private void mutate(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        lock.lock();
        try {
            Map<String, SkillUsageRecord> all = load();
            SkillUsageRecord current = all.getOrDefault(name, SkillUsageRecord.defaults());
            SkillUsageRecord updated = mutator.apply(current);
            if (updated == null) {
                all.remove(name);
            } else {
                all.put(name, updated);
            }
            save(all);
        } catch (Exception e) {
            log.debug("SkillUsageStore.mutate({}) failed: {}", name, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------------
    //  Counter bumps (provenance-gated: skip skills NOT created by the agent)
    // ---------------------------------------------------------------------

    public void bumpView(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount() + 1,
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                Instant.now(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void bumpUse(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount() + 1,
                                rec.viewCount(),
                                rec.patchCount(),
                                Instant.now(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void bumpPatch(String name) {
        bumpIfAgentTracked(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount() + 1,
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                Instant.now(),
                                rec.createdAt(),
                                rec.state(),
                                rec.pinned(),
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    /**
     * Apply {@code mutator} only if a record for {@code name} exists with non-null {@code
     * createdBy} (i.e. the agent has explicitly tracked this skill). Skipping unknown / external
     * skills keeps the sidecar focused on agent-authored procedural memory.
     */
    /**
     * 仅当存在指定名称的记录，且 {@code createdBy} 不为空时，才执行 {@code mutator} 修改逻辑
     *（即该技能已由代理显式纳入追踪）。跳过未知/外部技能，确保Sidecar仅聚焦代理生成的过程记忆。
     */
    private void bumpIfAgentTracked(String name, UnaryOperator<SkillUsageRecord> mutator) {
        if (name == null || name.isBlank()) {
            return;
        }
        lock.lock();
        try {
            Map<String, SkillUsageRecord> all = load();
            SkillUsageRecord existing = all.get(name);
            if (existing == null || existing.createdBy() == null) {
                return; // provenance gate: do not record telemetry for non-agent skills
            }
            SkillUsageRecord updated = mutator.apply(existing);
            if (updated != null) {
                all.put(name, updated);
                save(all);
            }
        } catch (Exception e) {
            log.debug("SkillUsageStore.bumpIfAgentTracked({}) failed: {}", name, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------------
    //  Provenance / lifecycle setters (always allowed)
    // ---------------------------------------------------------------------

    /** Tag a freshly-created agent draft (called from {@code SkillManageTool.create}). */
    /** 标记新创建的代理草稿技能（由 {@code SkillManageTool.create} 调用）。 */
    public void markAgentDraft(String name, String sessionId) {
        mutate(
                name,
                rec -> {
                    // Preserve any pre-existing telemetry but stamp provenance + DRAFT state.
                    return new SkillUsageRecord(
                            "agent-draft",
                            rec.useCount(),
                            rec.viewCount(),
                            rec.patchCount(),
                            rec.lastUsedAt(),
                            rec.lastViewedAt(),
                            rec.lastPatchedAt(),
                            rec.createdAt() != null ? rec.createdAt() : Instant.now(),
                            State.DRAFT,
                            rec.pinned(),
                            rec.archivedAt(),
                            rec.promotedAt(),
                            rec.promotedBy(),
                            sessionId,
                            List.of("draft"));
                });
    }

    /**
     * Mark a skill as a fully agent-created (i.e. promoted from draft). Used by
     * {@code SkillManageTool} when {@code autoPromote=true} (no staging) and by the future
     * promotion gate.
     */
    /**
     * 将技能标记为正式代理创建技能（即从草稿晋升）。
     * 当 {@code autoPromote=true}（无灰度阶段）时由 {@code SkillManageTool} 调用，同时供后续晋升管控流程使用。
     */
    public void markAgentCreated(String name, String reviewerId, List<String> environments) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                "agent",
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt() != null ? rec.createdAt() : Instant.now(),
                                State.ACTIVE,
                                rec.pinned(),
                                rec.archivedAt(),
                                Instant.now(),
                                reviewerId,
                                rec.sourceSessionId(),
                                environments != null ? List.copyOf(environments) : List.of()));
    }

    public void setState(String name, State newState) {
        if (newState == null) {
            return;
        }
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                newState,
                                rec.pinned(),
                                newState == State.ARCHIVED ? Instant.now() : null,
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    public void setPinned(String name, boolean pinned) {
        mutate(
                name,
                rec ->
                        new SkillUsageRecord(
                                rec.createdBy(),
                                rec.useCount(),
                                rec.viewCount(),
                                rec.patchCount(),
                                rec.lastUsedAt(),
                                rec.lastViewedAt(),
                                rec.lastPatchedAt(),
                                rec.createdAt(),
                                rec.state(),
                                pinned,
                                rec.archivedAt(),
                                rec.promotedAt(),
                                rec.promotedBy(),
                                rec.sourceSessionId(),
                                rec.environments()));
    }

    /** Drop the record entirely. Called when a skill is archived / deleted permanently. */
    /** 彻底移除对应记录。技能归档或永久删除时调用。 */
    public void forget(String name) {
        mutate(name, rec -> null);
    }

    // ---------------------------------------------------------------------
    //  Read views
    // ---------------------------------------------------------------------

    /** All records whose {@code createdBy} is non-null (i.e. agent-authored, draft or live). */
    /** 获取所有 {@code createdBy} 不为空的记录（即代理生成技能，包含草稿与正式上线版本）。 */
    public List<Map.Entry<String, SkillUsageRecord>> agentCreatedReport() {
        Map<String, SkillUsageRecord> all = load();
        List<Map.Entry<String, SkillUsageRecord>> out = new ArrayList<>();
        for (Map.Entry<String, SkillUsageRecord> e : all.entrySet()) {
            if (e.getValue() != null && e.getValue().createdBy() != null) {
                out.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
