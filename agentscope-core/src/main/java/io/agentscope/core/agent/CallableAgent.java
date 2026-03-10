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
import reactor.core.publisher.Mono;

/**
 * Interface for agents that can be called to process messages.
 * 可用于调用agent处理消息的接口
 *
 * <p>This interface defines the core call capability of agents, including:
 * 这个接口定义了可调用agent的核心功能，包括：
 * <ul>
 *   <li>Basic message processing via {@link #call(List)}</li>
 *   通过{@link #call(List)}进行基本消息处理
 *   <li>Structured output generation via {@link #call(List, Class)} and {@link #call(List, JsonNode)}</li>
 *   通过{@link #call(List, Class)}和{@link #call(List, JsonNode)}进行结构化输出生成
 * </ul>
 *
 * <p>Default implementations are provided for convenience methods that delegate
 * to the core {@link #call(List)} method.
 * 提供了默认实现的便利方法，这些方法委托给核心{@link #call(List)}方法。
 */
public interface CallableAgent {

    /**
     * Continue generation based on current state without adding new input.
     * 继续生成基于当前状态而不添加新的输入
     * @return Response message
     */
    default Mono<Msg> call() {
        return call(List.of());
    }

    /**
     * Continue generation with JSON schema based on current state.
     * 继续生成基于当前状态的JSON schema
     * @param schema JSON schema defining the structure
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(JsonNode schema) {
        return call(List.of(), schema);
    }

    /**
     * Continue generation with structured model based on current state.
     * 继续生成基于当前状态的结构化模型
     *
     * @param structuredModel Class defining the structure
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(Class<?> structuredModel) {
        return call(List.of(), structuredModel);
    }

    /**
     * Process a single input message and generate a response.
     * 处理单个输入消息并生成响应
     *
     * @param msg Input message
     * @return Response message
     */
    default Mono<Msg> call(Msg msg) {
        return call(msg == null ? List.of() : List.of(msg));
    }

    /**
     * Process a single input message with structured model and generate a response.
     * 使用结构化模型处理单个输入消息并生成响应
     *
     * @param msg Input message
     * @param structuredModel Class defining the structure
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(Msg msg, Class<?> structuredModel) {
        return call(msg == null ? List.of() : List.of(msg), structuredModel);
    }

    /**
     * Process a single input message with JSON schema and generate a response.
     * 使用JSON schema处理单个输入消息并生成响应
     *
     * @param msg Input message
     * @param schema JSON schema defining the structure
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(Msg msg, JsonNode schema) {
        return call(msg == null ? List.of() : List.of(msg), schema);
    }

    /**
     * Process multiple input messages (varargs) and generate a response.
     * 处理多个输入消息（可变参数）并生成响应
     *
     * @param msgs Input messages (varargs)
     * @return Response message
     */
    default Mono<Msg> call(Msg... msgs) {
        return call(List.of(msgs));
    }

    /**
     * Process a list of input messages and generate a response.
     * 处理输入消息列表并生成响应
     *
     * @param msgs Input messages
     * @return Response message
     */
    Mono<Msg> call(List<Msg> msgs);

    /**
     * Process multiple input messages with structured model and generate a response.
     * 使用结构化模型处理多个输入消息并生成响应
     *
     * <p>The structured model parameter defines the expected structure of output data.
     * The structured data will be stored in the returned message's metadata field.
     * 结构化模型参数定义了输出数据的预期结构。结构化数据将存储在返回消息的metadata字段中。
     *
     * @param msgs Input messages
     * @param structuredModel Class defining the structure
     * @return Response message with structured data in metadata
     */
    Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel);

    /**
     * Process multiple input messages with JSON schema and generate a response.
     * 使用JSON schema处理多个输入消息并生成响应
     *
     * <p>The schema parameter defines the expected structure of output data.
     * The structured data will be stored in the returned message's metadata field.
     * schema参数定义了输出数据的预期结构。结构化数据将存储在返回消息的metadata字段中。
     *
     * @param msgs Input messages
     * @param schema JSON schema defining the structure
     * @return Response message with structured data in metadata
     */
    Mono<Msg> call(List<Msg> msgs, JsonNode schema);
}
