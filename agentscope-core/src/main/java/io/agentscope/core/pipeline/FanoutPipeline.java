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
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.exception.CompositeAgentException;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Fanout pipeline implementation for parallel agent execution.
 * 实现用于并行代理执行的扇出管道。
 *
 * This pipeline distributes the same input to multiple agents and executes
 * them either concurrently or sequentially, collecting all results.
 * 该管道将​​相同的输入分发给多个代理，并让它们并发或顺序执行，收集所有结果。
 *
 * Execution flow:
 * Input -> [Agent1, Agent2, ..., AgentN] -> [Output1, Output2, ..., OutputN]
 *
 * Features:
 * - Fan-out pattern execution (one input, multiple outputs) 扇出模式执行（一个输入，多个输出）
 * - Configurable concurrent vs sequential execution 可配置的并发执行与顺序执行
 * - Input isolation (each agent gets a copy of the input) 输入隔离（每个代理都获得一份输入副本）
 * - Result aggregation into a list 将结果汇总成列表
 * - Enhanced error handling with detailed agent failure information 增强了错误处理能力，提供了详细的代理故障信息
 * - Composite exception collection for multiple agent failures 针对多个Agent故障的复合异常集合
 * - Individual agent error isolation without affecting others 隔离单个Agent错误而不影响其他代理
 */
public class FanoutPipeline implements Pipeline<List<Msg>> {

    private final List<AgentBase> agents;
    private final boolean enableConcurrent;
    private final String description;
    private final Scheduler scheduler;

    /**
     * Create a fanout pipeline with the specified agents and execution mode.
     * 创建具有指定智能体和执行模式的扇出管道。
     * Uses boundedElastic scheduler by default for concurrent execution.
     * 默认情况下使用 boundedElastic 调度器进行并发执行。
     *
     * @param agents List of agents to execute in parallel 要并行执行的智能体列表
     * @param enableConcurrent True for concurrent execution, false for sequential 并发执行时为真，顺序执行时为假。
     */
    public FanoutPipeline(List<AgentBase> agents, boolean enableConcurrent) {
        this.agents = List.copyOf(agents != null ? agents : List.of());
        this.enableConcurrent = enableConcurrent;
        this.scheduler = Schedulers.boundedElastic();
        this.description =
                String.format(
                        "FanoutPipeline[%d agents, %s]",
                        this.agents.size(), enableConcurrent ? "concurrent" : "sequential");
    }

    /**
     * Create a fanout pipeline with the specified agents, execution mode and scheduler.
     * 创建具有指定代理、执行模式和调度程序的扇出管道。
     *
     * @param agents List of agents to execute in parallel 要并行执行的代理列表
     * @param enableConcurrent True for concurrent execution, false for sequential 并发执行时为真，顺序执行时为假。
     * @param scheduler Custom scheduler for execution 自定义执行调度器
     */
    public FanoutPipeline(List<AgentBase> agents, boolean enableConcurrent, Scheduler scheduler) {
        this.agents = List.copyOf(agents != null ? agents : List.of());
        this.enableConcurrent = enableConcurrent;
        this.scheduler = scheduler != null ? scheduler : Schedulers.boundedElastic();
        this.description =
                String.format(
                        "FanoutPipeline[%d agents, %s]",
                        this.agents.size(), enableConcurrent ? "concurrent" : "sequential");
    }

    /**
     * Create a fanout pipeline with concurrent execution enabled by default.
     * 创建默认启用并发执行的扇出管道。
     *
     * @param agents List of agents to execute in parallel 要并行执行的代理列表
     */
    public FanoutPipeline(List<AgentBase> agents) {
        this(agents, true);
    }

    @Override
    public Mono<List<Msg>> execute(Msg input) {
        return execute(input, null);
    }

    @Override
    public Mono<List<Msg>> execute(Msg input, Class<?> structuredOutputClass) {
        if (agents.isEmpty()) {
            return Mono.just(List.of());
        }
        return enableConcurrent
                ? executeConcurrent(input, structuredOutputClass)
                : executeSequential(input, structuredOutputClass);
    }

    /**
     * Execute agents concurrently using reactive merge with true parallelism.
     * 使用响应式合并实现真正的并行性，并发执行代理。
     * All agents are executed even if some fail, but the all error is propagated.
     * 即使部分代理失败，所有代理都会执行，但所有错误都会传播。
     *
     * <p>Implementation: Each agent's call is subscribed on a separate thread from the
     * configured scheduler, enabling true concurrent execution of HTTP requests to the LLM API.
     * By default, the {@link Schedulers#boundedElastic()} scheduler is used, which is optimal
     * for I/O-bound operations. A custom scheduler can also be provided via constructor.
     * 实现方式：每个代理的调用都订阅在与配置的调度器不同的线程上，从而实现对 LLM API 的 HTTP 请求的真正并发执行。
     * 默认情况下，使用 Schedulers.boundedElastic() 调度器，该调度器最适合 I/O 密集型操作。
     * 也可以通过构造函数提供自定义调度器。
     *
     * @param input Input message to distribute to all agents 输入要分发给所有代理的消息
     * @param structuredOutputClass The class type for structured output (optional) 结构化输出的类类型（可选）
     * @return Mono containing list of all agent results 包含所有代理结果列表的 Mono
     */
    private Mono<List<Msg>> executeConcurrent(Msg input, Class<?> structuredOutputClass) {
        // Collect all agent results and errors

        List<CompositeAgentException.AgentExceptionInfo> errors =
                Collections.synchronizedList(new ArrayList<>());

        List<Mono<Msg>> agentMonos =
                agents.stream()
                        .map(
                                agent -> {
                                    // Choose call method based on structured output parameter
                                    Mono<Msg> mono =
                                            structuredOutputClass != null
                                                    ? agent.call(input, structuredOutputClass)
                                                    : agent.call(input);

                                    return mono.subscribeOn(scheduler)
                                            .doOnError(
                                                    throwable ->
                                                            errors.add(
                                                                    new CompositeAgentException
                                                                            .AgentExceptionInfo(
                                                                            agent.getAgentId(),
                                                                            agent.getName(),
                                                                            throwable)))
                                            .onErrorResume(e -> Mono.empty());
                                })
                        .toList();

        return Flux.merge(agentMonos)
                .collectList()
                .flatMap(
                        results -> {
                            // If there was an error, propagate the first one
                            if (!errors.isEmpty()) {
                                return Mono.error(
                                        new CompositeAgentException(
                                                "Multiple agent execution failures occurred",
                                                errors));
                            }
                            return Mono.just(results);
                        });
    }

    /**
     * Execute agents sequentially with independent inputs.
     * 按顺序执行代理，每个代理使用独立的输入。
     *
     * @param input Input message to distribute to all agents 输入要分发给所有代理的消息
     * @param structuredOutputClass The class type for structured output (optional) 结构化输出的类类型（可选）
     * @return Mono containing list of all agent results 包含所有代理结果列表的 Mono
     */
    private Mono<List<Msg>> executeSequential(Msg input, Class<?> structuredOutputClass) {
        List<Mono<Msg>> chain = new ArrayList<>();
        for (AgentBase agent : agents) {
            // Choose call method based on structured output parameter
            Mono<Msg> mono =
                    structuredOutputClass != null
                            ? agent.call(input, structuredOutputClass)
                            : agent.call(input);
            chain.add(mono);
        }
        return Flux.concat(chain).collectList();
    }

    /**
     * Get the list of agents in this pipeline.
     * 获取此销售渠道中的代理商列表。
     *
     * @return Copy of the agents list
     */
    public List<AgentBase> getAgents() {
        return agents;
    }

    /**
     * Get the number of agents in this pipeline.
     * 获取此管道中的代理商数量。
     *
     * @return Number of agents
     */
    public int size() {
        return agents.size();
    }

    /**
     * Check if this pipeline is empty (has no agents).
     * 检查此管道是否为空（没有代理）。
     *
     * @return True if pipeline has no agents
     */
    public boolean isEmpty() {
        return agents.isEmpty();
    }

    /**
     * Check if concurrent execution is enabled.
     * 检查是否启用并发执行。
     *
     * @return True if agents execute concurrently
     */
    public boolean isConcurrentEnabled() {
        return enableConcurrent;
    }

    @Override
    public String getDescription() {
        return description;
    }

    // ==================== Streaming API ====================

    /**
     * Stream execution events from all agents with default options.
     * 使用默认选项从所有代理流式传输执行事件​​。
     *
     * <p>Events from multiple agents are merged (concurrent mode) or concatenated
     * (sequential mode) based on the pipeline configuration.
     * 根据管道配置，来自多个代理的事件将被合并（并发模式）或连接（顺序模式）。
     *
     * @param input Input message to distribute to all agents
     * @return Flux of events emitted during execution from all agents
     */
    public Flux<Event> stream(Msg input) {
        return stream(input, StreamOptions.defaults());
    }

    /**
     * Stream execution events from all agents with specified options.
     *
     * <p>Events from multiple agents are merged (concurrent mode) or concatenated
     * (sequential mode) based on the pipeline configuration.
     *
     * @param input Input message to distribute to all agents
     * @param options Stream configuration options
     * @return Flux of events emitted during execution from all agents
     */
    public Flux<Event> stream(Msg input, StreamOptions options) {
        return stream(input, options, null);
    }

    /**
     * Stream execution events from all agents with structured output support.
     *
     * <p>Events from multiple agents are merged (concurrent mode) or concatenated
     * (sequential mode) based on the pipeline configuration.
     *
     * @param input Input message to distribute to all agents
     * @param options Stream configuration options
     * @param structuredOutputClass The class type for structured output (optional)
     * @return Flux of events emitted during execution from all agents
     */
    public Flux<Event> stream(Msg input, StreamOptions options, Class<?> structuredOutputClass) {
        if (agents.isEmpty()) {
            return Flux.empty();
        }

        StreamOptions effectiveOptions = options != null ? options : StreamOptions.defaults();

        return enableConcurrent
                ? streamConcurrent(input, effectiveOptions, structuredOutputClass)
                : streamSequential(input, effectiveOptions, structuredOutputClass);
    }

    /**
     * Stream events from all agents concurrently.
     *
     * <p>All agents execute in parallel and their events are merged into a single stream.
     * Events may arrive interleaved from different agents.
     *
     * @param input Input message to distribute to all agents
     * @param options Stream configuration options
     * @param structuredOutputClass The class type for structured output (optional)
     * @return Flux of merged events from all agents
     */
    private Flux<Event> streamConcurrent(
            Msg input, StreamOptions options, Class<?> structuredOutputClass) {
        List<CompositeAgentException.AgentExceptionInfo> errors =
                Collections.synchronizedList(new ArrayList<>());

        List<Flux<Event>> agentFluxes =
                agents.stream()
                        .map(
                                agent -> {
                                    Flux<Event> flux =
                                            structuredOutputClass != null
                                                    ? agent.stream(
                                                            input, options, structuredOutputClass)
                                                    : agent.stream(input, options);

                                    return flux.subscribeOn(scheduler)
                                            .doOnError(
                                                    throwable ->
                                                            errors.add(
                                                                    new CompositeAgentException
                                                                            .AgentExceptionInfo(
                                                                            agent.getAgentId(),
                                                                            agent.getName(),
                                                                            throwable)))
                                            .onErrorResume(e -> Flux.empty());
                                })
                        .toList();

        return Flux.merge(agentFluxes)
                .doOnComplete(
                        () -> {
                            if (!errors.isEmpty()) {
                                throw new CompositeAgentException(
                                        "Multiple agent streaming failures occurred", errors);
                            }
                        });
    }

    /**
     * Stream events from all agents sequentially.
     *
     * <p>Agents execute one after another. Events from each agent are emitted
     * in order before the next agent starts.
     *
     * @param input Input message to distribute to all agents
     * @param options Stream configuration options
     * @param structuredOutputClass The class type for structured output (optional)
     * @return Flux of concatenated events from all agents
     */
    private Flux<Event> streamSequential(
            Msg input, StreamOptions options, Class<?> structuredOutputClass) {
        List<Flux<Event>> chain = new ArrayList<>();
        for (AgentBase agent : agents) {
            Flux<Event> flux =
                    structuredOutputClass != null
                            ? agent.stream(input, options, structuredOutputClass)
                            : agent.stream(input, options);
            chain.add(flux);
        }
        return Flux.concat(chain);
    }

    @Override
    public String toString() {
        return String.format(
                "%s{agents=%s, concurrent=%s}",
                getClass().getSimpleName(),
                agents.stream().map(AgentBase::getName).toList(),
                enableConcurrent);
    }

    /**
     * Create a builder for constructing fanout pipelines.
     *
     * @return New pipeline builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating fanout pipelines with fluent API.
     */
    public static class Builder {
        private final List<AgentBase> agents = new ArrayList<>();
        private boolean enableConcurrent = true;
        private Scheduler scheduler;

        /**
         * Add an agent to the pipeline.
         *
         * @param agent Agent to add
         * @return This builder for method chaining
         */
        public Builder addAgent(AgentBase agent) {
            if (agent != null) {
                agents.add(agent);
            }
            return this;
        }

        /**
         * Add multiple agents to the pipeline.
         *
         * @param agentList List of agents to add
         * @return This builder for method chaining
         */
        public Builder addAgents(List<AgentBase> agentList) {
            if (agentList != null) {
                agents.addAll(agentList);
            }
            return this;
        }

        /**
         * Set whether to enable concurrent execution.
         *
         * @param concurrent True for concurrent execution, false for sequential
         * @return This builder for method chaining
         */
        public Builder concurrent(boolean concurrent) {
            this.enableConcurrent = concurrent;
            return this;
        }

        /**
         *
         * @param scheduler
         * @return
         */
        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Enable concurrent execution (default).
         *
         * @return This builder for method chaining
         */
        public Builder concurrent() {
            return concurrent(true);
        }

        /**
         * Enable sequential execution.
         *
         * @return This builder for method chaining
         */
        public Builder sequential() {
            return concurrent(false);
        }

        /**
         * Build the fanout pipeline.
         *
         * @return Configured fanout pipeline
         */
        public FanoutPipeline build() {
            return new FanoutPipeline(agents, enableConcurrent, scheduler);
        }
    }
}
