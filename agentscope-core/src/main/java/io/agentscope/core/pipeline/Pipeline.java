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
package io.agentscope.core.pipeline;

import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

/**
 * Base interface for pipeline execution in AgentScope.
 * 基础的流水线执行接口在AgentScope
 *
 * Pipelines provide orchestration of agents and operations in various patterns
 * such as sequential, parallel (fanout), or custom flows.
 * Pipelines可以以各种模式（例如顺序、并行（扇出）或自定义流程）来编排代理和操作。
 *
 * @param <T> Type of the pipeline result 管道结果类型
 */
public interface Pipeline<T> {

    /**
     * Execute the pipeline with the given input message.
     *
     * @param input Input message to process through the pipeline
     * @return Mono containing the pipeline result
     */
    Mono<T> execute(Msg input);

    /**
     * Execute the pipeline with the given input message and structured output.
     * 使用给定的输入消息和结构化输出执行管道。
     *
     * @param input Input message to process through the pipeline 要在管道中处理的输入消息
     * @param structuredOutputClass The class type for structured output 结构化输出的类类型
     * @return Mono containing the pipeline result with structured output
     */
    Mono<T> execute(Msg input, Class<?> structuredOutputClass);

    /**
     * Execute the pipeline with no input (for pipelines that don't need input).
     * 执行无输入管道（适用于不需要输入的管道）。
     *
     * @return Mono containing the pipeline result 包含管道结果的 Mono
     */
    default Mono<T> execute() {
        return execute((Msg) null);
    }

    /**
     * Execute the pipeline with no input but with structured output.
     * 执行管道操作，不输入数据，但输出结构化数据。
     *
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing the pipeline result with structured output
     */
    default Mono<T> execute(Class<?> structuredOutputClass) {
        return execute(null, structuredOutputClass);
    }

    /**
     * Get a description of this pipeline. 获取该管道的描述。
     *
     * @return Human-readable description of the pipeline 人类可以读懂的pipeline描述
     */
    default String getDescription() {
        return getClass().getSimpleName();
    }
}
