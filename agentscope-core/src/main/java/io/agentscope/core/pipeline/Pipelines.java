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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Utility class providing functional-style pipeline operations. 提供函数式管道操作的实用工具类。
 *
 * This class provides static methods offering convenient ways to execute agent
 * pipelines without creating explicit pipeline objects.
 * 此类提供静态方法，无需创建显式管道对象即可便捷地执行代理管道。
 * These methods are stateless and suitable for one-time use, while the
 * class-based Pipeline implementations are better for reusable configurations.
 * 这些方法是无状态的，适合一次性使用；而基于类的管道实现更适合可重用的配置。
 */
public class Pipelines {

    private Pipelines() {
        // Utility class - prevent instantiation
    }

    /**
     * Execute agents in a sequential pipeline.
     * 按顺序管道执行代理。
     *
     * The output of each agent becomes the input of the next agent.
     * 每个代理的输出都成为下一个代理的输入。
     *
     * @param agents List of agents to execute sequentially 要按顺序执行的代理列表
     * @param input Initial input message  初始输入消息
     * @return Mono containing the final result 包含最终结果的 Mono
     */
    public static Mono<Msg> sequential(List<AgentBase> agents, Msg input) {
        return new SequentialPipeline(agents).execute(input);
    }

    /**
     * Execute agents in a sequential pipeline with no initial input.
     * 按照顺序管道执行代理，无需初始输入。
     *
     * @param agents List of agents to execute sequentially 要按顺序执行的代理列表
     * @return Mono containing the final result 包含最终结果的 Mono
     */
    public static Mono<Msg> sequential(List<AgentBase> agents) {
        return sequential(agents, (Msg) null);
    }

    /**
     * Execute agents in a sequential pipeline with structured output.
     * 按顺序管道执行代理，并输出结构化结果。
     *
     * @param agents List of agents to execute sequentially 要按顺序执行的代理列表
     * @param input Initial input message 初始输入消息
     * @param structuredOutputClass The class type for structured output 结构化输出的类类型
     * @return Mono containing the final result with structured output 包含最终结果的 Mono，输出结构化
     */
    public static Mono<Msg> sequential(
            List<AgentBase> agents, Msg input, Class<?> structuredOutputClass) {
        return new SequentialPipeline(agents).execute(input, structuredOutputClass);
    }

    /**
     * Execute agents in a sequential pipeline with structured output and no initial input.
     * 在具有结构化输出且无初始输入的顺序管道中执行代理。
     *
     * @param agents List of agents to execute sequentially
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing the final result with structured output
     */
    public static Mono<Msg> sequential(List<AgentBase> agents, Class<?> structuredOutputClass) {
        return sequential(agents, null, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution.
     * 在扇出管道中执行代理，并发执行。
     *
     * All agents receive the same input and execute concurrently.
     * 所有代理接收相同的输入并同时执行。
     *
     * @param agents List of agents to execute in parallel
     * @param input Input message to distribute to all agents
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanout(List<AgentBase> agents, Msg input) {
        return new FanoutPipeline(agents, true).execute(input);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution and no input.
     * 在扇出管道中执行代理，支持并发执行且无输入。
     *
     * @param agents List of agents to execute in parallel
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanout(List<AgentBase> agents) {
        return fanout(agents, (Msg) null);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution and structured output.
     * 在扇出管道中执行代理，实现并发执行和结构化输出。
     *
     * @param agents List of agents to execute in parallel
     * @param input Input message to distribute to all agents
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanout(
            List<AgentBase> agents, Msg input, Class<?> structuredOutputClass) {
        return new FanoutPipeline(agents, true).execute(input, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution, structured output, and no
     * input.
     *
     * @param agents List of agents to execute in parallel
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanout(List<AgentBase> agents, Class<?> structuredOutputClass) {
        return fanout(agents, null, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution.
     * 在扇出管道中按顺序执行代理。
     *
     * All agents receive the same input but execute one after another.
     * 所有代理接收相同的输入，但依次执行。
     *
     * @param agents List of agents to execute sequentially (but independently)
     * @param input Input message to distribute to all agents
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanoutSequential(List<AgentBase> agents, Msg input) {
        return new FanoutPipeline(agents, false).execute(input);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution and no input.
     * 在扇出管道中执行代理，顺序执行，无输入。
     *
     * @param agents List of agents to execute sequentially (but independently) 要按顺序（但独立）执行的代理列表
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanoutSequential(List<AgentBase> agents) {
        return fanoutSequential(agents, (Msg) null);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution and structured output.
     * 在扇出管道中执行代理，实现顺序执行和结构化输出。
     *
     * @param agents List of agents to execute sequentially (but independently) 要按顺序（但独立）执行的代理列表
     * @param input Input message to distribute to all agents 输入要分发给所有代理的消息
     * @param structuredOutputClass The class type for structured output 结构化输出的类类型
     * @return Mono containing list of all results with structured output 包含所有结果列表的 Mono，输出结构化
     */
    public static Mono<List<Msg>> fanoutSequential(
            List<AgentBase> agents, Msg input, Class<?> structuredOutputClass) {
        return new FanoutPipeline(agents, false).execute(input, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution, structured output, and no
     * input.
     * 在扇出管道中执行代理，执行顺序、结构化输出，且无输入。
     *
     * @param agents List of agents to execute sequentially (but independently)
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanoutSequential(
            List<AgentBase> agents, Class<?> structuredOutputClass) {
        return fanoutSequential(agents, null, structuredOutputClass);
    }

    /**
     * Create a reusable sequential pipeline.
     * 创建一个可重用的顺序流水线。
     *
     * @param agents List of agents for the pipeline
     * @return Sequential pipeline instance
     */
    public static SequentialPipeline createSequential(List<AgentBase> agents) {
        return new SequentialPipeline(agents);
    }

    /**
     * Create a reusable fanout pipeline with concurrent execution.
     * 创建一个可重用的扇出管道，支持并发执行。
     *
     * @param agents List of agents for the pipeline
     * @return Concurrent fanout pipeline instance
     */
    public static FanoutPipeline createFanout(List<AgentBase> agents) {
        return new FanoutPipeline(agents, true);
    }

    /**
     * Create a reusable fanout pipeline with sequential execution.
     * 创建一个可重用的扇出管道，并采用顺序执行方式。
     *
     * @param agents List of agents for the pipeline
     * @return Sequential fanout pipeline instance
     */
    public static FanoutPipeline createFanoutSequential(List<AgentBase> agents) {
        return new FanoutPipeline(agents, false);
    }

    /**
     * Compose two sequential pipelines into a single pipeline.
     * 将两个连续的流水线合并成一个流水线。
     *
     * @param first First pipeline to execute
     * @param second Second pipeline to execute with output from first
     * @return Composed pipeline
     */
    public static Pipeline<Msg> compose(SequentialPipeline first, SequentialPipeline second) {
        return new ComposedSequentialPipeline(first, second);
    }

    /**
     * Internal class for composing sequential pipelines.
     * 用于构建顺序管道的内部类。
     */
    private static class ComposedSequentialPipeline implements Pipeline<Msg> {
        private final SequentialPipeline first;
        private final SequentialPipeline second;

        ComposedSequentialPipeline(SequentialPipeline first, SequentialPipeline second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public Mono<Msg> execute(Msg input) {
            return first.execute(input).flatMap(second::execute);
        }

        @Override
        public Mono<Msg> execute(Msg input, Class<?> structuredOutputClass) {
            // Only the second pipeline uses structured output
            return first.execute(input).flatMap(msg -> second.execute(msg, structuredOutputClass));
        }

        @Override
        public String getDescription() {
            return String.format(
                    "Composed[%s -> %s]", first.getDescription(), second.getDescription());
        }
    }
}
