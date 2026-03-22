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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Interface for agents that support streaming events during execution.
 * 支持在执行过程中流式事件的智能体接口
 *
 * <p>This interface enables real-time streaming of execution events as the agent
 * processes input. Events can include reasoning steps, tool results, and final output.
 * 该接口使得在智能体处理输入时能够实时流式传输执行事件。事件可以包括推理步骤、工具结果和最终输出。
 *
 * <p>Streaming is useful for:
 * <ul>
 *   <li>Displaying incremental progress to users</li>
 *   向用户显示增量进度
 *   <li>Monitoring agent reasoning in real-time</li>
 *   监控智能体的实时推理
 *   <li>Building interactive chat interfaces</li>
 *   构建交互式聊天界面
 * </ul>
 */
public interface StreamableAgent {

    /**
     * Stream execution events based on current state without adding new input.
     * 基于当前状态流式执行事件而不添加新的输入
     *
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(StreamOptions options) {
        return stream(List.of(), options);
    }

    /**
     * Stream execution events with structured output support based on current state.
     * 基于当前状态流式执行事件并支持结构化输出
     *
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Class<?> structuredModel) {
        return stream(List.of(), StreamOptions.defaults(), structuredModel);
    }

    /**
     * Stream execution events with structured output support based on current state.
     * 基于当前状态流式执行事件并支持结构化输出
     *
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(), options, structuredModel);
    }

    /**
     * Stream execution events for a single message with default options.
     * 使用默认选项为单个消息流式执行事件
     *
     * @param msg Input message
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg) {
        return stream(msg, StreamOptions.defaults());
    }

    /**
     * Stream execution events for a single message.
     * 为单个消息流式执行事件
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg, StreamOptions options) {
        return stream(List.of(msg), options);
    }

    /**
     * Stream execution events for a single message with structured output support.
     * 为单个消息流式执行事件并支持结构化输出
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg, StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(msg), options, structuredModel);
    }

    /**
     * Stream execution events for a single message with JSON schema support.
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @param schema JSON schema defining the structure
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg, StreamOptions options, JsonNode schema) {
        return stream(List.of(msg), options, schema);
    }

    /**
     * Stream execution events for multiple messages with default options.
     * 为多个消息使用默认选项流式执行事件
     *
     * @param msgs Input messages
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(List<Msg> msgs) {
        return stream(msgs, StreamOptions.defaults());
    }

    /**
     * Stream execution events in real-time as the agent processes the input.
     * 在智能体处理输入时实时流式执行事件
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options);

    /**
     * Stream execution events with structured output support.
     * 支持结构化输出的流式执行事件
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel);

    /**
     * Stream execution events with JSON schema support.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param schema JSON schema defining the structure
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema);
}
