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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Per-skill operational telemetry plus enterprise-staging audit fields. Persisted as a single
 * JSON object inside the workspace sidecar {@code skills/.usage.json} (one record per skill,
 * keyed by skill name).
 *
 * <p>Field naming intentionally mirrors hermes-agent's {@code tools/skill_usage.py} sidecar
 * shape (snake_case via {@link JsonProperty}) so the two ecosystems can share telemetry files
 * if a user copies skills between them.
 *
 * <p>{@code createdBy} provenance values:
 * <ul>
 *   <li>{@code null} — user-authored / hub-installed / pre-existing skill (NOT eligible for
 *       curator auto-management or canary gating)</li>
 *   <li>{@code "agent-draft"} — agent created via {@code skill_manage} but not yet promoted</li>
 *   <li>{@code "agent"} — agent-created skill that has been promoted to the live skills root</li>
 * </ul>
 */
/**
 * 单技能运行遥测数据以及企业场景审计字段。以独立JSON对象持久存储于工作区Sidecar文件
 * {@code skills/.usage.json}（每条记录对应一个技能，以技能名称作为键）。
 *
 * <p>字段命名刻意对齐 hermes-agent 中 {@code tools/skill_usage.py} 的Sidecar结构
 *（通过 {@link JsonProperty} 序列化为下划线命名），便于用户跨环境迁移技能时，两套体系共用遥测文件。
 *
 * <p>{@code createdBy} 来源标识说明：
 * <ul>
 *   <li>{@code null} — 用户编写/平台安装/已有存量技能（不纳入自动运维管理与灰度准入管控）</li>
 *   <li>{@code "agent-draft"} — 由代理通过 {@code skill_manage} 创建，尚未完成晋升的草稿技能</li>
 *   <li>{@code "agent"} — 代理创建、且已晋升至正式技能目录的上线技能</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillUsageRecord(
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("use_count") long useCount,
        @JsonProperty("view_count") long viewCount,
        @JsonProperty("patch_count") long patchCount,
        @JsonProperty("last_used_at") Instant lastUsedAt,
        @JsonProperty("last_viewed_at") Instant lastViewedAt,
        @JsonProperty("last_patched_at") Instant lastPatchedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("state") State state,
        @JsonProperty("pinned") boolean pinned,
        @JsonProperty("archived_at") Instant archivedAt,
        @JsonProperty("promoted_at") Instant promotedAt,
        @JsonProperty("promoted_by") String promotedBy,
        @JsonProperty("source_session_id") String sourceSessionId,
        @JsonProperty("environments") List<String> environments) {

    public enum State {
        @JsonProperty("draft")
        DRAFT,
        @JsonProperty("active")
        ACTIVE,
        @JsonProperty("stale")
        STALE,
        @JsonProperty("archived")
        ARCHIVED
    }

    /** Default record for a newly-tracked skill (just appeared on disk). */
    /** 新纳入追踪的技能默认记录（文件刚被检测到时使用）。 */
    public static SkillUsageRecord defaults() {
        return new SkillUsageRecord(
                null,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                Instant.now(),
                State.ACTIVE,
                false,
                null,
                null,
                null,
                null,
                List.of());
    }

    /** Default record for a freshly created agent draft. */
    /** 全新创建的代理草稿技能默认记录。 */
    public static SkillUsageRecord newAgentDraft(String sessionId) {
        return new SkillUsageRecord(
                "agent-draft",
                0L,
                0L,
                0L,
                null,
                null,
                null,
                Instant.now(),
                State.DRAFT,
                false,
                null,
                null,
                null,
                sessionId,
                List.of("draft"));
    }

    /** Jackson constructor for tolerant decoding (missing fields default to null/0/false). */
    /** Jackson 反序列化构造方法，支持容错解析（缺失字段默认置为 null/0/false）。 */
    @JsonCreator
    public static SkillUsageRecord ofJson(
            @JsonProperty("created_by") String createdBy,
            @JsonProperty("use_count") Long useCount,
            @JsonProperty("view_count") Long viewCount,
            @JsonProperty("patch_count") Long patchCount,
            @JsonProperty("last_used_at") Instant lastUsedAt,
            @JsonProperty("last_viewed_at") Instant lastViewedAt,
            @JsonProperty("last_patched_at") Instant lastPatchedAt,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("state") State state,
            @JsonProperty("pinned") Boolean pinned,
            @JsonProperty("archived_at") Instant archivedAt,
            @JsonProperty("promoted_at") Instant promotedAt,
            @JsonProperty("promoted_by") String promotedBy,
            @JsonProperty("source_session_id") String sourceSessionId,
            @JsonProperty("environments") List<String> environments) {
        return new SkillUsageRecord(
                createdBy,
                useCount == null ? 0L : useCount,
                viewCount == null ? 0L : viewCount,
                patchCount == null ? 0L : patchCount,
                lastUsedAt,
                lastViewedAt,
                lastPatchedAt,
                createdAt == null ? Instant.now() : createdAt,
                state == null ? State.ACTIVE : state,
                pinned != null && pinned,
                archivedAt,
                promotedAt,
                promotedBy,
                sourceSessionId,
                environments == null ? List.of() : environments);
    }

    /** Latest of {@code last_used_at}, {@code last_viewed_at}, {@code last_patched_at}. */
    /** {@code last_used_at}、{@code last_viewed_at}、{@code last_patched_at} 中的最新时间。 */
    @JsonIgnore
    public Instant latestActivityAt() {
        Instant latest = null;
        for (Instant t : new Instant[] {lastUsedAt, lastViewedAt, lastPatchedAt}) {
            if (t != null && (latest == null || t.isAfter(latest))) {
                latest = t;
            }
        }
        return latest;
    }

    /** Sum of {@code use_count}, {@code view_count}, {@code patch_count}. */
    @JsonIgnore
    public long activityCount() {
        return useCount + viewCount + patchCount;
    }

    /** Whether this skill is eligible for curator auto-management / canary visibility filtering. */
    @JsonIgnore
    public boolean isAgentCreated() {
        return "agent".equals(createdBy);
    }
}
