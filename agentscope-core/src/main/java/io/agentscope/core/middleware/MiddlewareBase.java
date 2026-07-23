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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware provides interception mechanisms at 5 key execution points
 * in the Agent lifecycle.
 *
 * <p><b>Onion Pattern</b> (4 hooks — wrap execution with before/after logic):
 * <ul>
 *   <li>{@link #onAgent} — intercepts the entire agent invocation</li>
 *   <li>{@link #onReasoning} — intercepts the reasoning/model-call phase</li>
 *   <li>{@link #onActing} — intercepts individual tool-call execution</li>
 *   <li>{@link #onModelCall} — intercepts the raw model API call</li>
 * </ul>
 *
 * <p><b>Transformer/Pipeline Pattern</b> (1 hook — sequential transform):
 * <ul>
 *   <li>{@link #onSystemPrompt} — transforms the system prompt string</li>
 * </ul>
 *
 * <p>Each hook has a default implementation that delegates directly to
 * {@code next}, so subclasses only need to override the hooks they care about.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * MiddlewareBase logging = new MiddlewareBase() {
 *     @Override
 *     public Flux<AgentEvent> onReasoning(
 *             Agent agent, RuntimeContext ctx, ReasoningInput input,
 *             Function<ReasoningInput, Flux<AgentEvent>> next) {
 *         System.out.println("Before reasoning, session=" + ctx.getSessionId());
 *         return next.apply(input)
 *             .doOnComplete(() -> System.out.println("After reasoning"));
 *     }
 * };
 * }</pre>
 */
/**
 * 中间件，在智能体生命周期的5个关键执行节点提供拦截能力。
 *
 * <p><b>洋葱模型（Onion Pattern）</b>（4个钩子：通过前置/后置逻辑包裹执行流程）：
 * <ul>
 *   <li>{@link #onAgent} — 拦截整个智能体调用流程</li>
 *   <li>{@link #onReasoning} — 拦截推理/模型调用阶段</li>
 *   <li>{@link #onActing} — 拦截单次工具调用执行</li>
 *   <li>{@link #onModelCall} — 拦截原始模型API调用</li>
 * </ul>
 *
 * <p><b>转换器/管道模式（Transformer/Pipeline Pattern）</b>（1个钩子：串行转换处理）：
 * <ul>
 *   <li>{@link #onSystemPrompt} — 对系统提示词文本进行转换</li>
 * </ul>
 *
 * <p>每个钩子均提供默认实现，直接转发至 {@code next}；子类仅需重写需要自定义的钩子。
 *
 * <p><b>示例：</b>
 * <pre>{@code
 * MiddlewareBase logging = new MiddlewareBase() {
 *     @Override
 *     public Flux<AgentEvent> onReasoning(
 *             Agent agent, RuntimeContext ctx, ReasoningInput input,
 *             Function<ReasoningInput, Flux<AgentEvent>> next) {
 *         System.out.println("推理开始，会话=" + ctx.getSessionId());
 *         return next.apply(input)
 *             .doOnComplete(() -> System.out.println("推理结束"));
 *     }
 * };
 * }</pre>
 */
public interface MiddlewareBase {

    /**
     * Intercept the entire agent invocation.
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input agent input (messages)
     * @param next  calls the next middleware or the core agent logic
     * @return event stream from the agent invocation
     */
    /**
     * 拦截完整的智能体调用流程。
     *
     * @param agent 智能体实例
     * @param ctx 单次调用运行时上下文（会话、用户、属性信息）
     * @param input 智能体输入（消息列表）
     * @param next 调用下一层中间件或智能体核心逻辑
     * @return 智能体调用产生的事件数据流
     */
    default Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the reasoning phase (LLM call + streaming output parsing).
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input reasoning input (messages, tools, options)
     * @param next  calls the next middleware or the core reasoning logic
     * @return event stream from reasoning
     */
    /**
     * 拦截推理阶段（大模型调用 + 流式输出解析）。
     *
     * @param agent 智能体实例
     * @param ctx 单次调用运行时上下文（会话、用户、属性信息）
     * @param input 推理输入（消息、工具、配置项）
     * @param next 调用下一层中间件或核心推理逻辑
     * @return 推理产生的事件数据流
     */
    default Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the tool-call execution phase.
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input acting input (the tool calls)
     * @param next  calls the next middleware or the core acting logic
     * @return event stream from acting
     */
    /**
     * 拦截工具调用执行阶段。
     *
     * @param agent 智能体实例
     * @param ctx 单次调用运行时上下文（会话、用户、属性信息）
     * @param input 动作输入（工具调用集合）
     * @param next 调用下一层中间件或核心执行逻辑
     * @return 工具执行产生的事件数据流
     */
    default Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the raw model API call.
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input model-call input (messages, tools, options, model)
     * @param next  calls the next middleware or the actual model invocation
     * @return event stream from the model call
     */
    /**
     * 拦截原始模型API调用。
     *
     * @param agent 智能体实例
     * @param ctx 单次调用运行时上下文（会话、用户、属性信息）
     * @param input 模型调用入参（消息、工具、配置项、模型标识）
     * @param next 调用下一层中间件或发起真实模型请求
     * @return 模型调用产生的事件数据流
     */
    default Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Transform the system prompt string (pipeline pattern).
     *
     * <p>Multiple middlewares are applied sequentially; each receives the
     * output of the previous one.
     *
     * @param agent         the agent instance
     * @param ctx           per-call runtime context (session, user, attributes)
     * @param currentPrompt the current system prompt
     * @return the (possibly transformed) system prompt
     */
    /**
     * 转换系统提示词文本（管道模式）。
     *
     * <p>多个中间件按顺序执行；每个中间件接收上一个中间件处理后的提示词结果。
     *
     * @param agent 智能体实例
     * @param ctx 单次调用运行时上下文（会话、用户、属性信息）
     * @param currentPrompt 当前系统提示词内容
     * @return 经过转换（或原样返回）的系统提示词
     */
    default Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt);
    }
}
