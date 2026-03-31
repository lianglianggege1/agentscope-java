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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import java.util.Objects;

/**
 * Event fired during reasoning streaming.
 * 推理流期间触发的事件。
 *
 * <p><b>Modifiable:</b> No (notification-only)
 *       不可修改： 仅作为通知
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getModelName()} - The model name</li>
 *   <li>{@link #getGenerateOptions()} - The generation options</li>
 *   <li>{@link #getIncrementalChunk()} - Only the new content in this chunk 此块中仅包含新内容</li>
 *   <li>{@link #getAccumulated()} - The full accumulated message so far 目前为止所有累积的信息</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Use {@link #getIncrementalChunk()} for incremental display (append-only) 使用 {@link #getIncrementalChunk()} 进行增量显示（仅追加）</li>
 *   <li>Use {@link #getAccumulated()} for full context display (replace entire text) 使用 {@link #getAccumulated()} 显示完整上下文（替换全部文本）</li>
 *   <li>Display streaming output in real-time 实时显示流媒体输出</li>
 *   <li>Monitor reasoning progress 监控推理进度</li>
 *   <li>Log streaming content 日志流内容</li>
 * </ul>
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * case ReasoningChunkEvent e -> {
 *     // Incremental mode: print only new content
 *     System.out.print(extractText(e.getIncrementalChunk()));
 *
 *     // OR Cumulative mode: update entire display
 *     ui.setText(extractText(e.getAccumulated()));
 *
 *     yield Mono.just(e);
 * }
 * }</pre>
 */
public final class ReasoningChunkEvent extends ReasoningEvent {

    private final Msg incrementalChunk;
    private final Msg accumulated;

    /**
     * Constructor for ReasoningChunkEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param incrementalChunk Only the new content generated in this streaming event (must not be
     *     null)
     * @param accumulated The full accumulated message containing all content generated so far (must
     *     not be null)
     * @throws NullPointerException if agent, modelName, incrementalChunk, or accumulated is null
     */
    public ReasoningChunkEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            Msg incrementalChunk,
            Msg accumulated) {
        super(HookEventType.REASONING_CHUNK, agent, modelName, generateOptions);
        this.incrementalChunk =
                Objects.requireNonNull(incrementalChunk, "incrementalChunk cannot be null");
        this.accumulated = Objects.requireNonNull(accumulated, "accumulated cannot be null");
    }

    /**
     * Get only the new content generated in this streaming event.
     *
     * @return The incremental chunk
     */
    public Msg getIncrementalChunk() {
        return incrementalChunk;
    }

    /**
     * Get the full accumulated message containing all content generated so far.
     *
     * @return The accumulated message
     */
    public Msg getAccumulated() {
        return accumulated;
    }
}
