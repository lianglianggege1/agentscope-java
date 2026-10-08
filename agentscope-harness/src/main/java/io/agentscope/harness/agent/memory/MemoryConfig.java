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
package io.agentscope.harness.agent.memory;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.Objects;

/**
 * Unified configuration for the long-term memory pipeline (flush + consolidation +
 * maintenance). Pair with {@link CompactionConfig} for the in-context summarization pipeline.
 *
 * <p>The harness has three LLM-driven memory operations, each with its own prompt and
 * triggering rules:
 *
 * <ol>
 *   <li><b>Flush</b> — extracts long-term memories from a conversation window into today's
 *       daily ledger ({@code memory/YYYY-MM-DD.md}). Prompt: {@link #flushPrompt()},
 *       defaults to {@link MemoryFlushManager#DEFAULT_FLUSH_PROMPT}. Trigger:
 *       {@link #flushTrigger()}.</li>
 *   <li><b>Consolidation</b> — periodically merges daily ledgers into the curated
 *       {@code MEMORY.md}. Prompt: {@link #consolidationPrompt()}, defaults to
 *       {@link MemoryConsolidator#DEFAULT_CONSOLIDATION_PROMPT}. Run cadence:
 *       {@link #consolidationMinGap()}.</li>
 *   <li><b>Compaction summary</b> — distills the conversation prefix into one summary
 *       message before reasoning. Prompt lives on {@link CompactionConfig#getSummaryPrompt()};
 *       configure via {@code .compaction(CompactionConfig...)} rather than here.</li>
 * </ol>
 *
 * <p>Flush and consolidation use independent throttle windows. A throttled policy allows the
 * first eligible call immediately; its minimum gap applies only between subsequent runs and is
 * not an initial delay. {@link FlushTrigger#never()} disables only the per-call flush.
 *
 * <p>All fields have sensible defaults; {@link #defaults()} returns a config equivalent
 * to the harness's historical behavior so adopting this class is a no-op upgrade.
 */
/**
 * 长期记忆流水线统一配置（包含持久落盘、整合、维护流程）。
 * 上下文摘要流水线请搭配 {@link CompactionConfig} 使用。
 *
 * <p>运行框架包含三类由大模型驱动的记忆操作，各自独立配置提示词与触发规则：
 *
 * <ol>
 *   <li><b>持久落盘(Flush)</b> — 从会话窗口提取长期记忆，写入当日记忆账本
 *       ({@code memory/YYYY-MM-DD.md})。提示词：{@link #flushPrompt()}，
 *       默认值 {@link MemoryFlushManager#DEFAULT_FLUSH_PROMPT}。触发规则：
 *       {@link #flushTrigger()}。</li>
 *   <li><b>记忆整合(Consolidation)</b> — 周期性合并每日账本，生成整理后的
 *       {@code MEMORY.md}。提示词：{@link #consolidationPrompt()}，默认值
 *       {@link MemoryConsolidator#DEFAULT_CONSOLIDATION_PROMPT}。执行间隔：
 *       {@link #consolidationMinGap()}。</li>
 *   <li><b>会话精简摘要(Compaction summary)</b> — 在推理前将前置会话浓缩为
 *       单条摘要消息。提示词位于 {@link CompactionConfig#getSummaryPrompt()}；
 *       通过 {@code .compaction(CompactionConfig...)} 配置，不在当前类设置。</li>
 * </ol>
 *
 * <p>所有配置项均提供合理默认值；{@link #defaults()} 返回的配置与框架原有行为保持一致，
 * 接入本类可实现无感知升级。
 */
public final class MemoryConfig {

    /** Default {@code consolidationMaxTokens}. */
    /** 默认 {@code consolidationMaxTokens} 参数值。 */
    public static final int DEFAULT_CONSOLIDATION_MAX_TOKENS = 4_000;

    /** Default {@code consolidationMinGap} — matches {@code MemoryMaintenanceMiddleware}. */
    /** 默认 {@code consolidationMinGap}，与 {@code MemoryMaintenanceMiddleware} 保持一致。 */
    public static final Duration DEFAULT_CONSOLIDATION_MIN_GAP = Duration.ofMinutes(30);

    /** Default retention before a daily ledger is archived. */
    /** 每日记忆账本归档前的默认保留时长。 */
    public static final int DEFAULT_DAILY_FILE_RETENTION_DAYS = 90;

    /** Default retention before a session JSONL log is pruned. */
    /** 会话JSONL日志清理前的默认保留时长。 */
    public static final int DEFAULT_SESSION_RETENTION_DAYS = 180;

    /** Strategy for the per-call flush hook. See {@link FlushTrigger}. */
    /** 单次会话记忆落盘钩子触发策略，参见 {@link FlushTrigger}。 */
    public enum FlushMode {
        /** Flush after every agent call. */
        /** 每次智能体调用完成后执行记忆落盘。 */
        ALWAYS,
        /** Disable per-call flush entirely (offload still runs). */
        /** 完全关闭单次调用记忆落盘（后台离线任务仍正常执行）。 */
        NEVER,
        /** Flush at most once per {@link FlushTrigger#minGap()}. */
        /** 每个时间间隔内最多执行一次记忆落盘，间隔由 {@link FlushTrigger#minGap()} 指定。 */
        THROTTLED
    }

    /**
     * Trigger policy for {@link io.agentscope.harness.agent.middleware.MemoryFlushMiddleware}.
     *
     * <p>A throttled trigger allows the first eligible call immediately. Its minimum gap is
     * measured between subsequent per-call flushes, independently of consolidation maintenance.
     * {@link #never()} disables only this per-call flush path.
     *
     * <p>{@code throttled(Duration.ZERO)} normalises to {@link #always()} so callers do not
     * need a special branch for the degenerate case.
     */
    /**
     * {@link io.agentscope.harness.agent.middleware.MemoryFlushMiddleware} 的触发策略。
     *
     * <p>当 {@code throttled(Duration.ZERO)} 时将等价归一为 {@link #always()}，调用方无需针对该边界场景编写特殊分支逻辑。
     */
    public static final class FlushTrigger {

        private static final FlushTrigger ALWAYS_INSTANCE =
                new FlushTrigger(FlushMode.ALWAYS, Duration.ZERO);
        private static final FlushTrigger NEVER_INSTANCE =
                new FlushTrigger(FlushMode.NEVER, Duration.ZERO);

        private final FlushMode mode;
        private final Duration minGap;

        private FlushTrigger(FlushMode mode, Duration minGap) {
            this.mode = mode;
            this.minGap = minGap;
        }

        public static FlushTrigger always() {
            return ALWAYS_INSTANCE;
        }

        public static FlushTrigger never() {
            return NEVER_INSTANCE;
        }

        public static FlushTrigger throttled(Duration minGap) {
            if (minGap == null) {
                throw new IllegalArgumentException("minGap must not be null");
            }
            if (minGap.isNegative()) {
                throw new IllegalArgumentException("minGap must not be negative");
            }
            if (minGap.isZero()) {
                return ALWAYS_INSTANCE;
            }
            return new FlushTrigger(FlushMode.THROTTLED, minGap);
        }

        public FlushMode mode() {
            return mode;
        }

        public Duration minGap() {
            return minGap;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FlushTrigger other)) return false;
            return mode == other.mode && Objects.equals(minGap, other.minGap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, minGap);
        }

        @Override
        public String toString() {
            return mode == FlushMode.THROTTLED
                    ? "FlushTrigger{THROTTLED, minGap=" + minGap + "}"
                    : "FlushTrigger{" + mode + "}";
        }
    }

    private final Model model;
    private final String flushPrompt;
    private final String consolidationPrompt;
    private final int consolidationMaxTokens;
    private final Duration consolidationMinGap;
    private final int dailyFileRetentionDays;
    private final int sessionRetentionDays;
    private final FlushTrigger flushTrigger;

    private MemoryConfig(Builder b) {
        this.model = b.model;
        this.flushPrompt = b.flushPrompt;
        this.consolidationPrompt = b.consolidationPrompt;
        this.consolidationMaxTokens = b.consolidationMaxTokens;
        this.consolidationMinGap = b.consolidationMinGap;
        this.dailyFileRetentionDays = b.dailyFileRetentionDays;
        this.sessionRetentionDays = b.sessionRetentionDays;
        this.flushTrigger = b.flushTrigger;
    }

    /**
     * Optional model override for memory operations (flush + consolidation).
     * {@code null} means use the agent's primary model.
     */
    /**
     * 记忆相关操作（落盘、整合）可选的模型覆盖配置。
     * 若为 {@code null}，则使用智能体主模型。
     */
    public Model model() {
        return model;
    }

    /**
     * Override for the flush prompt. {@code null} means use
     * {@link MemoryFlushManager#DEFAULT_FLUSH_PROMPT}.
     */
    /**
     * 记忆落盘提示词覆盖配置。若为 {@code null}，
     * 将使用 {@link MemoryFlushManager#DEFAULT_FLUSH_PROMPT}。
     */
    public String flushPrompt() {
        return flushPrompt;
    }

    /**
     * Override for the consolidation prompt. {@code null} means use
     * {@link MemoryConsolidator#DEFAULT_CONSOLIDATION_PROMPT}.
     *
     * <p>Custom prompts must contain exactly two {@code %d} placeholders (max-tokens and
     * max-chars, in that order). The Builder enforces this at construction time.
     */
    /**
     * 记忆整合提示词覆盖配置。若为 {@code null}，
     * 将使用 {@link MemoryConsolidator#DEFAULT_CONSOLIDATION_PROMPT}。
     *
     * <p>自定义提示词必须包含恰好两个 {@code %d} 占位符（顺序依次为最大Token数、最大字符数）。
     * 构建器会在实例创建阶段校验该约束。
     */
    public String consolidationPrompt() {
        return consolidationPrompt;
    }

    public int consolidationMaxTokens() {
        return consolidationMaxTokens;
    }

    /**
     * Minimum gap between consolidation/maintenance runs. The first eligible call runs
     * immediately; this duration is not an initial delay and is independent of
     * {@link #flushTrigger()}.
     */
    public Duration consolidationMinGap() {
        return consolidationMinGap;
    }

    public int dailyFileRetentionDays() {
        return dailyFileRetentionDays;
    }

    public int sessionRetentionDays() {
        return sessionRetentionDays;
    }

    public FlushTrigger flushTrigger() {
        return flushTrigger;
    }

    /** Returns a config equivalent to the harness's historical defaults. */
    /** 返回与框架原有默认行为保持一致的配置实例。 */
    public static MemoryConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Model model = null;
        private String flushPrompt = null;
        private String consolidationPrompt = null;
        private int consolidationMaxTokens = DEFAULT_CONSOLIDATION_MAX_TOKENS;
        private Duration consolidationMinGap = DEFAULT_CONSOLIDATION_MIN_GAP;
        private int dailyFileRetentionDays = DEFAULT_DAILY_FILE_RETENTION_DAYS;
        private int sessionRetentionDays = DEFAULT_SESSION_RETENTION_DAYS;
        private FlushTrigger flushTrigger = FlushTrigger.always();

        /**
         * Sets a dedicated model for memory operations (flush + consolidation),
         * allowing a lighter/cheaper model than the agent's primary reasoning model.
         * When not set, the agent's primary model is used.
         */
        /**
         * 为记忆操作（落盘、整合）设置独立模型，
         * 可选用比智能体主推理模型更轻量、低成本的模型。
         * 如不配置，则使用智能体主模型。
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Sets a dedicated model for memory operations by model id string
         * (e.g. {@code "openai:gpt-4.1-mini"}).
         *
         * @see ModelRegistry#resolve(String)
         */
        /**
         * 通过模型标识字符串为记忆操作设置独立模型
         *（例如 {@code "openai:gpt-4.1-mini"}）。
         *
         * @see ModelRegistry#resolve(String)
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * Overrides the prompt used by {@link MemoryFlushManager#flushMemories}.
         * {@code null} restores the default. The text is passed verbatim as a SYSTEM
         * message — it does not need any placeholders.
         */
        /**
         * 覆盖 {@link MemoryFlushManager#flushMemories} 使用的提示词。
         * 设置为 {@code null} 将恢复默认提示词。文本会直接作为系统消息传入，无需占位符。
         */
        public Builder flushPrompt(String flushPrompt) {
            this.flushPrompt = flushPrompt;
            return this;
        }

        /**
         * Overrides the prompt used by {@link MemoryConsolidator#consolidate}. The prompt
         * is rendered via {@link String#format} with two {@code int} arguments
         * (max-tokens, max-chars), so a custom prompt MUST contain exactly two {@code %d}
         * placeholders. {@code null} restores the default.
         *
         * @throws IllegalArgumentException if the prompt does not contain exactly two
         *     {@code %d} placeholders
         */
        /**
         * 覆盖 {@link MemoryConsolidator#consolidate} 使用的提示词。该提示词通过 {@link String#format}
         * 渲染，接收两个整型参数（最大token数量、最大字符数量），因此自定义提示词必须恰好包含两个
         * {@code %d} 占位符。设置为 {@code null} 将恢复默认提示词。
         *
         * @throws IllegalArgumentException 当提示词不含恰好两个 {@code %d} 占位符时抛出
         */
        public Builder consolidationPrompt(String consolidationPrompt) {
            if (consolidationPrompt != null) {
                int count = countOccurrences(consolidationPrompt, "%d");
                if (count != 2) {
                    throw new IllegalArgumentException(
                            "consolidationPrompt must contain exactly two %d placeholders "
                                    + "(max-tokens, max-chars), found "
                                    + count);
                }
            }
            this.consolidationPrompt = consolidationPrompt;
            return this;
        }

        /** Token budget passed to the consolidation prompt. Must be positive. */
        /** 传入记忆整合提示词的Token配额，必须大于0。 */
        public Builder consolidationMaxTokens(int consolidationMaxTokens) {
            if (consolidationMaxTokens <= 0) {
                throw new IllegalArgumentException(
                        "consolidationMaxTokens must be positive, got " + consolidationMaxTokens);
            }
            this.consolidationMaxTokens = consolidationMaxTokens;
            return this;
        }

        /**
         * Minimum gap between consolidation/maintenance runs. The first eligible call runs
         * immediately; this duration is not an initial delay and is independent of
         * {@link MemoryConfig#flushTrigger()}. Must not be null.
         */
        /** Minimum gap between two consolidation/maintenance runs. Must not be null. */
        /** 两次记忆整合/维护任务之间的最小间隔，禁止为null。 */
        public Builder consolidationMinGap(Duration consolidationMinGap) {
            if (consolidationMinGap == null) {
                throw new IllegalArgumentException("consolidationMinGap must not be null");
            }
            if (consolidationMinGap.isNegative()) {
                throw new IllegalArgumentException("consolidationMinGap must not be negative");
            }
            this.consolidationMinGap = consolidationMinGap;
            return this;
        }

        /** Days before a daily ledger is moved to {@code memory/archive/}. */
        /** 每日记忆账本迁移至 {@code memory/archive/} 前的保留天数。 */
        public Builder dailyFileRetentionDays(int dailyFileRetentionDays) {
            if (dailyFileRetentionDays <= 0) {
                throw new IllegalArgumentException(
                        "dailyFileRetentionDays must be positive, got " + dailyFileRetentionDays);
            }
            this.dailyFileRetentionDays = dailyFileRetentionDays;
            return this;
        }

        /** Days before a session JSONL log is pruned. */
        /** 会话JSONL日志被清理前的保留天数。 */
        public Builder sessionRetentionDays(int sessionRetentionDays) {
            if (sessionRetentionDays <= 0) {
                throw new IllegalArgumentException(
                        "sessionRetentionDays must be positive, got " + sessionRetentionDays);
            }
            this.sessionRetentionDays = sessionRetentionDays;
            return this;
        }

        /** Trigger policy for the per-call flush hook. Must not be null. */
        /** 单次调用记忆落盘钩子的触发策略，禁止为null。 */
        public Builder flushTrigger(FlushTrigger flushTrigger) {
            if (flushTrigger == null) {
                throw new IllegalArgumentException("flushTrigger must not be null");
            }
            this.flushTrigger = flushTrigger;
            return this;
        }

        public MemoryConfig build() {
            return new MemoryConfig(this);
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) != -1) {
                count++;
                idx += needle.length();
            }
            return count;
        }
    }
}
