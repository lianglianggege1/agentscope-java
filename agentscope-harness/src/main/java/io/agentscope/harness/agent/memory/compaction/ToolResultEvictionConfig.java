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

import java.util.Set;

/**
 * Configuration for per-tool-result eviction of oversized outputs.
 *
 * <p>When a tool produces a result whose text content exceeds {@link #getMaxResultChars()}, the
 * full output is written to the workspace filesystem abstraction at a deterministic path under
 * {@link #getEvictionPath()}, and the in-context {@link io.agentscope.core.message.ToolResultBlock}
 * is replaced with a compact placeholder that includes a head+tail preview and an instruction to
 * use {@code readFile} for the full content.
 *
 * <p>This mechanism is <b>orthogonal</b> to conversation summarization ({@link CompactionConfig}):
 * <ul>
 *   <li><b>Eviction</b> addresses context <em>width</em> — individual messages that are too large.</li>
 *   <li><b>Compaction</b> addresses context <em>depth</em> — too many accumulated messages.</li>
 * </ul>
 * Both operate independently on different trigger conditions and different lifecycle events.
 *
 * <ul>
 *   <li>Trigger at 80,000 characters (~20 K tokens at 4 chars/token)</li>
 *   <li>Preview: first + last 2,000 characters of the original output</li>
 *   <li>Eviction path prefix: {@code large_tool_results} (relative to the workspace)</li>
 *   <li>Excluded tools: filesystem read/write/edit + memory tools (small or self-paginating)</li>
 * </ul>
 */
/**
 * 单工具结果超大输出逐出机制配置。
 *
 * <p>当工具输出文本内容超出 {@link #getMaxResultChars()} 时，完整输出将写入工作区文件系统，
 * 存储路径基于 {@link #getEvictionPath()} 生成；上下文内的 {@link io.agentscope.core.message.ToolResultBlock}
 * 会被精简占位符替代，占位符包含内容首尾预览片段，并提示模型调用 {@code readFile} 获取完整内容。
 *
 * <p>该机制与对话压缩（{@link CompactionConfig}）相互独立：
 * <ul>
 *   <li><b>逐出机制</b> 解决上下文宽度问题——单条消息体量过大。</li>
 *   <li><b>压缩机制</b> 解决上下文深度问题——消息累积数量过多。</li>
 * </ul>
 * 二者触发条件、生命周期互不干扰，独立运行。
 *
 * <ul>
 *   <li>触发阈值：80000字符（按每4字符1Token估算，约20000 Token）</li>
 *   <li>预览策略：截取原始输出首尾各2000字符</li>
 *   <li>逐出文件根路径：{@code /large_tool_results}</li>
 *   <li>豁免工具：文件读写、编辑、列举工具以及记忆工具（输出体量较小或自带分页能力）</li>
 * </ul>
 */
public class ToolResultEvictionConfig {

    /** ~20 K tokens × 4 chars/token — default eviction threshold. */
    /** 约20000 Token，按4字符/Token折算 —— 默认逐出阈值。 */
    public static final int DEFAULT_MAX_RESULT_CHARS = 80_000;

    /** Characters to show at head and tail in the eviction placeholder preview. */
    /** 逐出占位预览中首尾展示的字符数量。 */
    public static final int DEFAULT_PREVIEW_CHARS = 2_000;

    /** Workspace-relative path prefix under which evicted results are stored. */
    /** 被逐出结果的存储根路径前缀。 */
    public static final String DEFAULT_EVICTION_PATH = "large_tool_results";

    /**
     * Tools excluded from eviction by default.
     *
     * <ul>
     *   <li>{@code read_file} — evicting would cause re-read loops; pagination handles size</li>
     *   <li>{@code write_file}, {@code edit_file} — return tiny success messages</li>
     *   <li>Search/list tools have bounded previews and remain eligible for eviction.</li>
     *   <li>{@code memory_search}, {@code memory_get}, {@code session_search} — small/paginated results</li>
     * </ul>
     *
     * Shell ({@code execute}) is intentionally NOT excluded: command output can be very large.
     */
    /**
     * 默认豁免逐出机制的工具列表。
     *
     * <ul>
     *   <li>{@code read_file} — 逐出会引发重复读取循环；依靠分页控制内容大小</li>
     *   <li>{@code write_file}、{@code edit_file} — 仅返回简短成功信息</li>
     *   <li>{@code grep_files}、{@code glob_files}、{@code list_files} — 输出自带容量限制</li>
     *   <li>{@code memory_search}、{@code memory_get}、{@code session_search} — 结果体量较小或支持分页</li>
     * </ul>
     *
     * Shell命令（{@code execute}）不加入豁免列表：命令输出可能体量巨大。
     */
    public static final Set<String> DEFAULT_EXCLUDED_TOOLS =
            Set.of(
                    "read_file",
                    "write_file",
                    "edit_file",
                    "memory_search",
                    "memory_get",
                    "session_search");

    private final int maxResultChars;
    private final int previewChars;
    private final String evictionPath;
    private final Set<String> excludedToolNames;

    private ToolResultEvictionConfig(Builder builder) {
        this.maxResultChars = builder.maxResultChars;
        this.previewChars = builder.previewChars;
        this.evictionPath = builder.evictionPath;
        this.excludedToolNames = builder.excludedToolNames;
    }

    /** Creates a config with all defaults applied. */
    /** 创建一份使用全部默认参数的配置实例。 */
    public static ToolResultEvictionConfig defaults() {
        return new Builder().build();
    }

    /** Maximum text length (chars) before eviction fires. */
    /** 触发逐出机制的文本最大长度（字符数）。 */
    public int getMaxResultChars() {
        return maxResultChars;
    }

    /** Characters to show in the head and tail preview. */
    /** 首尾预览展示的字符数量。 */
    public int getPreviewChars() {
        return previewChars;
    }

    /** Root path under which evicted files are written (e.g. {@code large_tool_results}). */
    /** Root path under which evicted files are written (e.g. {@code /large_tool_results}). */
    /** 逐出文件的存储根路径（例如 {@code /large_tool_results}）。 */
    public String getEvictionPath() {
        return evictionPath;
    }

    /** Tool names that will never be evicted regardless of result size. */
    /** 无论结果大小，永不执行逐出操作的工具名称集合。 */
    public Set<String> getExcludedToolNames() {
        return excludedToolNames;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ToolResultEvictionConfig}. */
    /** {@link ToolResultEvictionConfig} 的构建器。 */
    public static class Builder {

        private int maxResultChars = DEFAULT_MAX_RESULT_CHARS;
        private int previewChars = DEFAULT_PREVIEW_CHARS;
        private String evictionPath = DEFAULT_EVICTION_PATH;
        private Set<String> excludedToolNames = DEFAULT_EXCLUDED_TOOLS;

        /** Sets the character threshold above which eviction is triggered. */
        /** 设置触发逐出机制的字符阈值。 */
        public Builder maxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
            return this;
        }

        /** Sets how many characters to include in the head/tail preview. */
        /** 设置首尾预览所截取的字符数量。 */
        public Builder previewChars(int previewChars) {
            this.previewChars = previewChars;
            return this;
        }

        /** Sets the root filesystem path prefix for evicted files. */
        /** 设置逐出文件存储的文件系统根路径前缀。 */
        public Builder evictionPath(String evictionPath) {
            this.evictionPath = evictionPath;
            return this;
        }

        /** Replaces the default set of excluded tool names. */
        /** 替换默认的豁免工具名称集合。 */
        public Builder excludedToolNames(Set<String> excludedToolNames) {
            this.excludedToolNames = Set.copyOf(excludedToolNames);
            return this;
        }

        public ToolResultEvictionConfig build() {
            return new ToolResultEvictionConfig(this);
        }
    }
}
