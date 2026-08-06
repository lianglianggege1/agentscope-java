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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import java.util.Set;

/**
 * Configuration for conversation compaction (summarization).
 *
 * <ul>
 *   <li><b>trigger</b> — when to run compaction (by token count or message count)</li>
 *   <li><b>keep</b> — how many recent messages to preserve verbatim after compaction</li>
 * </ul>
 *
 * <p>Defaults (dynamic mode):
 * <ul>
 *   <li>Trigger dynamically when estimated token count reaches {@code model.contextWindow - reserved(20k)}.
 *       Falls back to {@value #FALLBACK_TRIGGER_TOKENS} tokens when the model does not report its context
 *       window, or at 50 messages (whichever comes first)</li>
 *   <li>Keep tail: dynamically computed as {@code min(8k, max(2k, usable * 0.25))} tokens.
 *       Falls back to 20 messages when the model does not report its context window</li>
 *   <li>Prune: enabled by default — aggregates old tool result outputs and trims them when the
 *       prunable total exceeds 20k tokens (protects the most recent 40k tokens)</li>
 *   <li>Summarization is enabled; memory flush and offload are both enabled before summary</li>
 * </ul>
 *
 * <h2>Memory prompt landscape</h2>
 *
 * The harness has three LLM-driven memory operations, each with its own prompt; they live
 * in two complementary config classes:
 *
 * <ul>
 *   <li><b>Compaction summary</b> — distills the conversation prefix into one summary
 *       message before reasoning. Prompt: {@link #getSummaryPrompt()} on <em>this</em>
 *       class.</li>
 *   <li><b>Flush</b> — extracts long-term memories into today's daily ledger. Prompt:
 *       {@code MemoryConfig.flushPrompt()}.</li>
 *   <li><b>Consolidation</b> — periodically merges daily ledgers into {@code MEMORY.md}.
 *       Prompt: {@code MemoryConfig.consolidationPrompt()}.</li>
 * </ul>
 *
 * Configure the latter two via {@code HarnessAgent.builder().memory(MemoryConfig...)}.
 *
 * @see io.agentscope.harness.agent.memory.MemoryConfig
 */
/**
 * 对话压缩（摘要）相关配置。
 *
 * <ul>
 *   <li><b>trigger（触发条件）</b> — 执行压缩的时机（依据token数量或消息条数）</li>
 *   <li><b>keep（保留策略）</b> — 压缩后需要完整保留的近期消息数量</li>
 * </ul>
 *
 * <p>默认配置（动态模式）：
 * <ul>
 *   <li>当预估token数量达到 {@code model.contextWindow - reserved(20k)} 时动态触发压缩。
 *       若模型未上报上下文窗口上限，则降级至 {@value #FALLBACK_TRIGGER_TOKENS} token，
 *       或达到50条消息（二者任一条件先满足即触发）</li>
 *   <li>尾部保留：动态计算公式 {@code min(8k, max(2k, usable * 0.25))} token。
 *       模型未上报上下文窗口时，降级保留20条消息</li>
 *   <li>裁剪功能默认开启：可裁剪内容总token超过20k时，聚合并精简历史工具返回结果，
 *       保护最近40k token内容不被裁剪</li>
 *   <li>摘要功能启用；执行摘要前，内存落盘与离线卸载功能均开启</li>
 * </ul>
 *
 * <h2>内存提示词整体架构</h2>
 *
 * 运行框架包含三类由大模型驱动的内存操作，各自配备独立提示词，由两套互补配置类管理：
 *
 * <ul>
 *   <li><b>对话压缩摘要</b> — 在推理前将前置对话提炼为一条摘要消息。
 *       提示词：本类中的 {@link #getSummaryPrompt()}</li>
 *   <li><b>持久化落盘</b> — 将短期记忆提取至当日记忆台账。
 *       提示词：{@code MemoryConfig.flushPrompt()}</li>
 *   <li><b>记忆合并固化</b> — 定期将每日台账整合写入 {@code MEMORY.md}。
 *       提示词：{@code MemoryConfig.consolidationPrompt()}</li>
 * </ul>
 *
 * 后两项配置通过 {@code HarnessAgent.builder().memory(MemoryConfig...)} 设置。
 *
 * @see io.agentscope.harness.agent.memory.MemoryConfig
 */
public class CompactionConfig {

    /**
     * Fallback trigger threshold (in tokens) when the model does not report its context window
     * and {@code triggerTokens} is set to dynamic mode (0).
     */
    /**
     * 模型未上报上下文窗口、且{@code triggerTokens}设为动态模式（0）时使用的降级触发阈值（单位：token）。
     */
    public static final int FALLBACK_TRIGGER_TOKENS = 160_000;

    /** 采用结构化格式的默认摘要提示词 */
    /*
    public static final String DEFAULT_SUMMARY_PROMPT =
            """
            <role>
            上下文提取助手
            </role>

            <primary_objective>
            本次任务唯一目标：从下方对话历史中提取价值最高、关联性最强的上下文信息。
            </primary_objective>

            <objective_information>
            当前输入token量即将达到上限，你必须从对话历史筛选核心关键信息。提取出的内容将直接替换原有对话记录，因此仅保留对完成整体目标至关重要的信息。
            </objective_information>

            <instructions>
            下方对话历史将会被你本次提炼的上下文替换。务必记录已完成操作，避免重复执行；提取内容需要围绕整体目标，聚焦关键信息。

            摘要严格按照以下板块组织（无相关内容填写“无”）：

            ## SESSION INTENT（会话目标）
            用户的核心诉求与主要目标。

            ## SUMMARY（内容摘要）
            关键上下文、决策内容、推理过程、被否决的备选方案。

            ## ARTIFACTS（产出物）
            创建、修改、访问过的文件与资源（附带具体路径及变更内容）。

            ## NEXT STEPS（后续任务）
            完成会话目标仍需执行的具体事项。
            </instructions>

            完整阅读下方全部对话历史，提取最重要的上下文。**仅输出提炼后的内容，不要额外补充说明。**

            <messages>
            {messages}
            </messages>
            """;
     */

    /** Default summary prompt with structured format. */
    public static final String DEFAULT_SUMMARY_PROMPT =
            """
            <role>
            Context Extraction Assistant
            </role>

            <primary_objective>
            Your sole objective in this task is to extract the highest quality/most relevant \
            context from the conversation history below.
            </primary_objective>

            <objective_information>
            You're nearing the total number of input tokens you can accept, so you must extract \
            the highest quality/most relevant pieces of information from your conversation history.
            This context will then overwrite the conversation history presented below. Because of \
            this, ensure the context you extract is only the most important information to \
            continue working toward your overall goal.
            </objective_information>

            <instructions>
            The conversation history below will be replaced with the context you extract in this \
            step. You want to ensure that you don't repeat any actions you've already completed, \
            so the context you extract from the conversation history should be focused on the \
            most important information to your overall goal.

            Structure your summary using these sections (populate each or write "None"):

            ## SESSION INTENT
            What is the user's primary goal or request?

            ## SUMMARY
            The most important context, decisions, reasoning, and rejected options.

            ## ARTIFACTS
            Files or resources created, modified, or accessed (with specific paths and changes).

            ## NEXT STEPS
            Specific tasks remaining to achieve the session intent.
            </instructions>

            Carefully read through the entire conversation history below and extract the most \
            important context. Respond ONLY with the extracted context.

            <messages>
            {messages}
            </messages>\
            """;

    private final int triggerMessages;
    private final int triggerTokens;
    private final int reserved;
    private final int keepMessages;
    private final int keepTokens;
    private final int keepTokensMin;
    private final int keepTokensMax;
    private final double keepTokensRatio;
    private final String summaryPrompt;
    private final boolean flushBeforeCompact;
    private final boolean offloadBeforeCompact;
    private final TruncateArgsConfig truncateArgsConfig;
    private final PruneConfig pruneConfig;
    private final Model model;

    private CompactionConfig(Builder b) {
        this.triggerMessages = b.triggerMessages;
        this.triggerTokens = b.triggerTokens;
        this.reserved = b.reserved;
        this.keepMessages = b.keepMessages;
        this.keepTokens = b.keepTokens;
        this.keepTokensMin = b.keepTokensMin;
        this.keepTokensMax = b.keepTokensMax;
        this.keepTokensRatio = b.keepTokensRatio;
        this.summaryPrompt = b.summaryPrompt;
        this.flushBeforeCompact = b.flushBeforeCompact;
        this.offloadBeforeCompact = b.offloadBeforeCompact;
        this.truncateArgsConfig = b.truncateArgsConfig;
        this.pruneConfig = b.pruneConfig;
        this.model = b.model;
    }

    /** Message count above which compaction is triggered (0 = disabled). */
    /**
     * 触发压缩所需达到的消息条数阈值（0代表关闭该触发条件）。
     */
    public int getTriggerMessages() {
        return triggerMessages;
    }

    /**
     * Estimated token count above which compaction is triggered.
     * {@code 0} = dynamic mode (compute from model's context window minus {@link #getReserved()}).
     */
    /**
     * 触发压缩的预估token数量阈值。
     * {@code 0} 代表动态模式（基于模型上下文窗口减去 {@link #getReserved()} 进行计算）。
     */
    public int getTriggerTokens() {
        return triggerTokens;
    }

    /**
     * Token buffer reserved for the compaction process itself (summary prompt + output).
     * Only used in dynamic mode ({@code triggerTokens == 0}).
     */
    /**
     * 为压缩流程本身预留的token缓冲（摘要提示词+输出内容占用）。
     * 仅在动态模式下生效（{@code triggerTokens == 0}）。
     */
    public int getReserved() {
        return reserved;
    }

    /**
     * Number of recent <em>conversation</em> messages (non-SYSTEM) to preserve verbatim.
     * Used when {@link #getKeepTokens()} is 0 (static keep mode).
     */
    /**
     * 需要完整保留的近期对话消息（非系统消息）条数。
     * 在 {@link #getKeepTokens()} 为0时启用（静态保留模式）。
     */
    public int getKeepMessages() {
        return keepMessages;
    }

    /**
     * Token budget for the preserved tail.
     * <ul>
     *   <li>{@code > 0}: static budget — scan from end until this budget is exhausted</li>
     *   <li>{@code 0}: use {@link #getKeepMessages()} instead (message-count mode)</li>
     *   <li>{@code -1}: dynamic — compute as
     *       {@code min(keepTokensMax, max(keepTokensMin, usable * keepTokensRatio))}</li>
     * </ul>
     */
    /**
     * 尾部保留内容的token配额。
     * <ul>
     *   <li>{@code > 0}：固定配额——从末尾向前遍历消息，直至耗尽该配额</li>
     *   <li>{@code 0}：改用 {@link #getKeepMessages()}（按消息条数模式）</li>
     *   <li>{@code -1}：动态模式——计算公式为
     *       {@code min(keepTokensMax, max(keepTokensMin, usable * keepTokensRatio))}</li>
     * </ul>
     */
    public int getKeepTokens() {
        return keepTokens;
    }

    public int getKeepTokensMin() {
        return keepTokensMin;
    }

    public int getKeepTokensMax() {
        return keepTokensMax;
    }

    public double getKeepTokensRatio() {
        return keepTokensRatio;
    }

    /**
     * Prompt template used for the summarization LLM call. Must contain {@code {messages}}.
     */
    /**
     * 摘要大模型调用所使用的提示词模板。模板内必须包含 {@code {messages}} 占位符。
     */
    public String getSummaryPrompt() {
        return summaryPrompt;
    }

    /** Whether to flush long-term memories from the prefix before compaction. */
    /**
     * 是否在压缩执行前，将前文内容抽取为长期记忆进行持久化。
     */
    public boolean isFlushBeforeCompact() {
        return flushBeforeCompact;
    }

    /** Whether to offload raw messages to the session JSONL before compaction. */
    /**
     * 是否在压缩前将原始消息转存至会话JSONL文件。
     */
    public boolean isOffloadBeforeCompact() {
        return offloadBeforeCompact;
    }

    /**
     * Configuration for the lightweight pre-summarization argument truncation pass.
     * When {@code null}, argument truncation is disabled.
     */
    /**
     * 轻量级预摘要参数裁剪流程配置。
     * 若为 {@code null}，代表参数裁剪功能关闭。
     */
    public TruncateArgsConfig getTruncateArgsConfig() {
        return truncateArgsConfig;
    }

    /**
     * Configuration for aggregate tool-result pruning. When {@code null}, pruning is disabled.
     */
    /**
     * 工具返回结果聚合裁剪配置。为 {@code null} 时，裁剪功能关闭。
     */
    public PruneConfig getPruneConfig() {
        return pruneConfig;
    }

    /**
     * Optional model override for compaction (summarization). {@code null} means use
     * the agent's primary model.
     */
    /**
     * 压缩（摘要）流程可选独立模型配置。{@code null} 表示复用 Agent 主模型。
     */
    public Model getModel() {
        return model;
    }

    /**
     * Creates a resolved copy with effective trigger and keep values computed from a model's
     * context window. Used by {@code CompactionMiddleware} to resolve dynamic defaults.
     */
    /**
     * 创建一份已解析的配置副本，依据模型上下文窗口计算出生效的触发阈值与保留配额。
     * 由 {@code CompactionMiddleware} 调用，用于解析动态默认参数。
     */
    public CompactionConfig withEffective(int effectiveTriggerTokens, int effectiveKeepTokens) {
        Builder b = new Builder();
        b.triggerMessages = this.triggerMessages;
        b.triggerTokens = effectiveTriggerTokens;
        b.reserved = this.reserved;
        b.keepMessages = this.keepMessages;
        b.keepTokens = effectiveKeepTokens;
        b.keepTokensMin = this.keepTokensMin;
        b.keepTokensMax = this.keepTokensMax;
        b.keepTokensRatio = this.keepTokensRatio;
        b.summaryPrompt = this.summaryPrompt;
        b.flushBeforeCompact = this.flushBeforeCompact;
        b.offloadBeforeCompact = this.offloadBeforeCompact;
        b.truncateArgsConfig = this.truncateArgsConfig;
        b.pruneConfig = this.pruneConfig;
        b.model = this.model;
        return new CompactionConfig(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private int triggerMessages = 50;
        private int triggerTokens = 0;
        private int reserved = 20_000;
        private int keepMessages = 20;
        private int keepTokens = -1;
        private int keepTokensMin = 2_000;
        private int keepTokensMax = 8_000;
        private double keepTokensRatio = 0.25;
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private boolean flushBeforeCompact = true;
        private boolean offloadBeforeCompact = true;
        private TruncateArgsConfig truncateArgsConfig = null;
        private PruneConfig pruneConfig = PruneConfig.defaults();
        private Model model = null;

        /** Trigger compaction when conversation has at least this many messages (0 = disabled). */
        /**
         * 对话消息数量达到该值时触发压缩（0 代表关闭此触发条件）。
         */
        public Builder triggerMessages(int triggerMessages) {
            this.triggerMessages = triggerMessages;
            return this;
        }

        /**
         * Trigger compaction when estimated token count exceeds this value.
         * {@code 0} (default) = dynamic mode: compute from model's context window minus
         * {@link #reserved(int)}.
         */
        /**
         * 预估token数量超出该值时触发对话压缩。
         * {@code 0}（默认值）= 动态模式：依据模型上下文窗口减去 {@link #reserved(int)} 进行计算。
         */
        public Builder triggerTokens(int triggerTokens) {
            this.triggerTokens = triggerTokens;
            return this;
        }

        /**
         * Token buffer reserved for the compaction process (default 20 000).
         * Only used in dynamic mode ({@code triggerTokens == 0}).
         */
        /**
         * 为压缩流程预留的Token缓冲区（默认20000）。
         * 仅动态模式下生效（{@code triggerTokens == 0}）。
         */
        public Builder reserved(int reserved) {
            this.reserved = reserved;
            return this;
        }

        /** Number of recent messages to keep verbatim after compaction. */
        /**
         * 压缩完成后需要完整保留的近期消息条数。
         */
        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        /**
         * Token budget for the preserved tail.
         * {@code -1} (default) = dynamic mode; {@code 0} = use keepMessages; {@code > 0} = static.
         */
        /**
         * 尾部消息保留的Token配额。
         * {@code -1}（默认）= 动态模式；{@code 0} = 使用keepMessages配置；{@code > 0} = 固定配额模式。
         */
        public Builder keepTokens(int keepTokens) {
            this.keepTokens = keepTokens;
            return this;
        }

        public Builder keepTokensMin(int keepTokensMin) {
            this.keepTokensMin = keepTokensMin;
            return this;
        }

        public Builder keepTokensMax(int keepTokensMax) {
            this.keepTokensMax = keepTokensMax;
            return this;
        }

        public Builder keepTokensRatio(double keepTokensRatio) {
            this.keepTokensRatio = keepTokensRatio;
            return this;
        }

        /** Custom summary prompt. Must contain {@code {messages}} placeholder. */
        /**
         * 自定义摘要提示词。必须包含 {@code {messages}} 占位符。
         */
        public Builder summaryPrompt(String summaryPrompt) {
            this.summaryPrompt = summaryPrompt;
            return this;
        }

        /** Whether to flush long-term memories before compaction (default true). */
        /**
         * 是否在压缩前持久化长期记忆（默认开启）。
         */
        public Builder flushBeforeCompact(boolean flushBeforeCompact) {
            this.flushBeforeCompact = flushBeforeCompact;
            return this;
        }

        /** Whether to offload raw messages to session JSONL before compaction (default true). */
        /**
         * 是否在压缩前将原始消息转存至会话JSONL文件（默认开启）。
         */
        public Builder offloadBeforeCompact(boolean offloadBeforeCompact) {
            this.offloadBeforeCompact = offloadBeforeCompact;
            return this;
        }

        /**
         * Enables lightweight pre-summarization argument truncation.
         * Pass {@code null} to disable.
         */
        /**
         * 开启摘要前置轻量级参数裁剪。
         * 传入 {@code null} 则关闭该功能。
         */
        public Builder truncateArgs(TruncateArgsConfig config) {
            this.truncateArgsConfig = config;
            return this;
        }

        /**
         * Configures aggregate tool-result pruning. Defaults to {@link PruneConfig#defaults()}.
         * Pass {@code null} to disable.
         */
        /**
         * 工具返回结果聚合裁剪配置。默认使用 {@link PruneConfig#defaults()}。
         * 传入 {@code null} 将关闭该功能。
         */
        public Builder prune(PruneConfig config) {
            this.pruneConfig = config;
            return this;
        }

        /**
         * Sets a dedicated model for compaction (summarization), allowing a
         * lighter/cheaper model than the agent's primary reasoning model.
         */
        /**
         * 为压缩（摘要）指定独立模型，可选用比智能体主推理模型更轻量、成本更低的模型。
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Sets a dedicated model for compaction by model id string
         * (e.g. {@code "openai:gpt-4.1-mini"}).
         *
         * @see ModelRegistry#resolve(String)
         */
        /**
         * 通过模型标识字符串为压缩（摘要）设置独立模型
         *（例如 {@code "openai:gpt-4.1-mini"}）。
         *
         * @see ModelRegistry#resolve(String)
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        public CompactionConfig build() {
            return new CompactionConfig(this);
        }
    }

    // -------------------------------------------------------------------------
    //  TruncateArgsConfig
    // -------------------------------------------------------------------------

    /**
     * Configuration for the lightweight argument-truncation pass that runs before
     * summarization.
     *
     * <p>When triggered, large string arguments of {@code ToolUseBlock}s in older messages
     * (before the keep window) are clipped to {@link #getMaxArgLength()} characters.
     * This is a cheap, non-LLM operation that prevents context ballooning from verbose
     * tool invocations (e.g., {@code write_file}, {@code edit_file}).
     *
     * <p>Defaults (when enabled via {@link Builder#truncateArgs(TruncateArgsConfig)}):
     * <ul>
     *   <li>Trigger at 25 messages or 40 000 tokens</li>
     *   <li>Keep the 20 most recent messages untouched</li>
     *   <li>Max argument length: 2 000 characters</li>
     * </ul>
     */
    /**
     * 摘要执行前轻量级参数裁剪流程配置。
     *
     * <p>触发后，保留窗口之前旧消息内{@code ToolUseBlock}的超长字符串参数将被截断至{@link #getMaxArgLength()}字符。
     * 该操作无需调用大模型，开销较低，避免冗长工具调用（例如{@code write_file}、{@code edit_file}）造成上下文膨胀。
     *
     * <p>通过{@link Builder#truncateArgs(TruncateArgsConfig)}启用后的默认参数：
     * <ul>
     *   <li>触发条件：消息数量达到25条 或 Token总量超过40000</li>
     *   <li>最近20条消息不作处理，完整保留</li>
     *   <li>参数最大长度：2000字符</li>
     * </ul>
     */
    public static class TruncateArgsConfig {

        private final int triggerMessages;
        private final int triggerTokens;
        private final int keepMessages;
        private final int keepTokens;
        private final int maxArgLength;
        private final String truncationText;

        private TruncateArgsConfig(TruncateArgsBuilder b) {
            this.triggerMessages = b.triggerMessages;
            this.triggerTokens = b.triggerTokens;
            this.keepMessages = b.keepMessages;
            this.keepTokens = b.keepTokens;
            this.maxArgLength = b.maxArgLength;
            this.truncationText = b.truncationText;
        }

        public int getTriggerMessages() {
            return triggerMessages;
        }

        public int getTriggerTokens() {
            return triggerTokens;
        }

        public int getKeepMessages() {
            return keepMessages;
        }

        public int getKeepTokens() {
            return keepTokens;
        }

        /** Maximum character length of any single tool argument value (default 2 000). */
        /**
         * 单个工具参数内容最大字符长度（默认2000）。
         */
        public int getMaxArgLength() {
            return maxArgLength;
        }

        /** Suffix appended after the first 20 characters of a truncated argument. */
        /**
         * 参数截断时，在前20个字符之后追加的后缀文本。
         */
        public String getTruncationText() {
            return truncationText;
        }

        public static TruncateArgsBuilder builder() {
            return new TruncateArgsBuilder();
        }

        public static class TruncateArgsBuilder {

            private int triggerMessages = 25;
            private int triggerTokens = 40_000;
            private int keepMessages = 20;
            private int keepTokens = 0;
            private int maxArgLength = 2_000;
            private String truncationText = "...(argument truncated)";

            public TruncateArgsBuilder triggerMessages(int triggerMessages) {
                this.triggerMessages = triggerMessages;
                return this;
            }

            public TruncateArgsBuilder triggerTokens(int triggerTokens) {
                this.triggerTokens = triggerTokens;
                return this;
            }

            public TruncateArgsBuilder keepMessages(int keepMessages) {
                this.keepMessages = keepMessages;
                return this;
            }

            public TruncateArgsBuilder keepTokens(int keepTokens) {
                this.keepTokens = keepTokens;
                return this;
            }

            public TruncateArgsBuilder maxArgLength(int maxArgLength) {
                this.maxArgLength = maxArgLength;
                return this;
            }

            public TruncateArgsBuilder truncationText(String truncationText) {
                this.truncationText = truncationText;
                return this;
            }

            public TruncateArgsConfig build() {
                return new TruncateArgsConfig(this);
            }
        }
    }

    // -------------------------------------------------------------------------
    //  PruneConfig
    // -------------------------------------------------------------------------

    /**
     * Configuration for aggregate tool-result pruning.
     *
     * <p>Prune walks backward through tool-result messages, protecting the most recent
     * {@link #getProtectTokens()} tokens of tool output. Older tool results beyond that
     * protection window are replaced with a head+tail preview when the total prunable
     * amount exceeds {@link #getMinimumTokens()}.
     *
     * <p>This is a lightweight, non-LLM operation that runs inside
     * {@link ConversationCompactor#compactIfNeeded} before summarization.
     */
    /**
     * 工具执行结果聚合裁剪配置。
     *
     * <p>裁剪逻辑：从后向前遍历工具返回消息，保护最新 {@link #getProtectTokens()} Token 的工具输出内容。
     * 若可裁剪的旧工具结果总量超出 {@link #getMinimumTokens()}，超出保护窗口的历史工具返回内容将替换为首尾摘要预览文本。
     *
     * <p>该轻量化操作无需调用大模型，在 {@link ConversationCompactor#compactIfNeeded} 内部、摘要执行之前运行。
     */
    public static class PruneConfig {

        private final int protectTokens;
        private final int minimumTokens;
        private final int maxOutputChars;
        private final Set<String> excludedTools;

        private PruneConfig(PruneBuilder b) {
            this.protectTokens = b.protectTokens;
            this.minimumTokens = b.minimumTokens;
            this.maxOutputChars = b.maxOutputChars;
            this.excludedTools = Set.copyOf(b.excludedTools);
        }

        public static PruneConfig defaults() {
            return new PruneBuilder().build();
        }

        /** Token budget for recent tool outputs that are never pruned (default 40 000). */
        /**
         * 近期工具输出永久保留的Token配额（默认40000）。
         */
        public int getProtectTokens() {
            return protectTokens;
        }

        /** Minimum prunable token total before pruning actually executes (default 20 000). */
        /**
         * 触发裁剪所需的最小可删减Token总量（默认20000）。
         */
        public int getMinimumTokens() {
            return minimumTokens;
        }

        /** Max characters to keep per pruned tool result as head+tail preview (default 2 000). */
        /**
         * 被裁剪后的工具结果，首尾预览文本最大保留字符数（默认2000）。
         */
        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        /** Tool names excluded from pruning (e.g. read_file, memory tools). */
        /**
         * 豁免裁剪的工具名称列表（例如 read_file、记忆类工具）。
         */
        public Set<String> getExcludedTools() {
            return excludedTools;
        }

        public static PruneBuilder builder() {
            return new PruneBuilder();
        }

        public static class PruneBuilder {

            private int protectTokens = 40_000;
            private int minimumTokens = 20_000;
            private int maxOutputChars = 2_000;
            private Set<String> excludedTools =
                    Set.of("read_file", "memory_search", "memory_get", "session_search");

            public PruneBuilder protectTokens(int protectTokens) {
                this.protectTokens = protectTokens;
                return this;
            }

            public PruneBuilder minimumTokens(int minimumTokens) {
                this.minimumTokens = minimumTokens;
                return this;
            }

            public PruneBuilder maxOutputChars(int maxOutputChars) {
                this.maxOutputChars = maxOutputChars;
                return this;
            }

            public PruneBuilder excludedTools(Set<String> excludedTools) {
                this.excludedTools = excludedTools;
                return this;
            }

            public PruneConfig build() {
                return new PruneConfig(this);
            }
        }
    }
}
