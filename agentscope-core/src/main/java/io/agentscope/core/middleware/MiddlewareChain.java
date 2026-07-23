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
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Builds an onion-style middleware chain for a given interception point.
 *
 * <p>The chain is constructed back-to-front: the last middleware wraps
 * the core logic, and the first middleware is the outermost wrapper.
 */
/**
 * 为指定拦截节点构建洋葱模型中间件调用链。
 *
 * <p>调用链采用逆序组装：最后一个中间件包裹核心逻辑，首个中间件作为最外层包装器。
 */
public final class MiddlewareChain {

    private MiddlewareChain() {}

    /**
     * Build a middleware chain that produces {@code Flux<AgentEvent>}.
     *
     * @param middlewares ordered list of middlewares (first = outermost)
     * @param agent      the agent instance passed to each middleware
     * @param ctx        per-call runtime context passed to each middleware
     * @param method     reference to the middleware hook method
     * @param core       the innermost logic to execute when all middlewares delegate
     * @param <I>        the input type for the interception point
     * @return a function that, when applied to an input, runs the full chain
     */
    /**
     * 构建输出类型为 {@code Flux<AgentEvent>} 的中间件调用链。
     *
     * @param middlewares 有序中间件列表（首个为最外层）
     * @param agent 传递给各个中间件的智能体实例
     * @param ctx 传递给各个中间件的单次调用运行时上下文
     * @param method 中间件钩子方法引用
     * @param core 所有中间件逐层转发后最终执行的内层核心逻辑
     * @param <I> 拦截节点对应的输入类型
     * @return 函数对象，传入输入参数即可执行完整中间件链路
     */
    public static <I> Function<I, Flux<AgentEvent>> build(
            List<? extends MiddlewareBase> middlewares,
            Agent agent,
            RuntimeContext ctx,
            MiddlewareMethod<I> method,
            Function<I, Flux<AgentEvent>> core) {
        if (middlewares == null || middlewares.isEmpty()) {
            return core;
        }
        Function<I, Flux<AgentEvent>> chain = core;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            MiddlewareBase mw = middlewares.get(i);
            Function<I, Flux<AgentEvent>> next = chain;
            chain = input -> method.apply(mw, agent, ctx, input, next);
        }
        return chain;
    }

    /**
     * Functional interface representing one of the onion-pattern middleware hooks.
     *
     * @param <I> the input type
     */
    /**
     * 函数式接口，用于定义洋葱模型中间件钩子。
     *
     * @param <I> 输入参数类型
     */
    @FunctionalInterface
    public interface MiddlewareMethod<I> {
        Flux<AgentEvent> apply(
                MiddlewareBase mw,
                Agent agent,
                RuntimeContext ctx,
                I input,
                Function<I, Flux<AgentEvent>> next);
    }
}
