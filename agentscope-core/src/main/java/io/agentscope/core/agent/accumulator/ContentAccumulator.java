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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;

/**
 * Content accumulator interface for accumulating streaming content blocks.
 * 内容累积器接口，用于累积流媒体内容块。
 *
 * <p>This interface defines the contract for accumulating content blocks from streaming responses.
 * Different content types (text, thinking, tool calls) have different accumulation strategies.
 * 此接口定义了从流响应中累积内容块的契约。
 * 不同的内容类型（文本、思维、工具调用）有不同的积累策略。类型参数：<T>要累积的内容块的类型
 * 
 * @hidden
 * @param <T> The type of content block to accumulate  要累积的内容块类型
 */
public interface ContentAccumulator<T extends ContentBlock> {

    /**
     * Add a content block chunk to the accumulator.
     * 添加内容块块块到累积器。
     *
     * @hidden
     * @param block The content block chunk to add
     *              要添加的内容块
     */
    void add(T block);

    /**
     * Check if the accumulator has any accumulated content.
     * 检查累积器是否有任何累积内容。
     *
     * @hidden
     * @return true if there is accumulated content, false otherwise
     *         如果有累积内容，则为true，否则为false
     */
    boolean hasContent();

    /**
     * Build the aggregated content block from all accumulated chunks.
     * 构建从所有累积块中聚合的内容块。
     *
     * @hidden
     * @return The aggregated content block, or null if no content
     *         聚合内容块，如果没有内容，则为null
     */
    ContentBlock buildAggregated();

    /**
     * @hidden
     * Reset the accumulator state, clearing all accumulated content.
     * 重置累积器状态，清除所有累积内容。
     */
    void reset();
}
