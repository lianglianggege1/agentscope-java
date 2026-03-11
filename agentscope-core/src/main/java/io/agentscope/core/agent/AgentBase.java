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
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Abstract base class for all agents in the AgentScope framework.
 * scope智能体框架中所有智能体的抽象基类
 *
 * <p>This class provides common functionality for agents including basic hook integration,
 * MsgHub subscriber management, interrupt handling, tracing, and state management through StateModule.
 * It does NOT manage memory - that is the responsibility of specific agent implementations like
 * ReActAgent.
 * 这个类为智能体提供了包括基本的
 * 钩子集成、
 * MsgHub订阅者管理、
 * 中断处理、
 * 跟踪
 * 和通过StateModule进行状态管理的公共功能。
 * 它不管理内存-这是特定智能体实现（如ReActAgent）的职责。
 *
 * <p>Design Philosophy:
 * 设计理念
 * <ul>
 *   <li>AgentBase provides infrastructure (hooks, subscriptions, interrupt, state) but not domain
 *       logic</li>
 * AgentBase提供基础设施（钩子、订阅、中断、状态）但不提供领域逻辑
 *   <li>Memory management is delegated to concrete agents that need it (e.g., ReActAgent)</li>
 *  内存管理被需要它的具体智能体（如ReActAgent）委托
 *   <li>State management implements StateModule interface</li>
 *  状态管理实现StateModule接口
 *   <li>Interrupt mechanism uses reactive patterns: subclasses call checkInterruptedAsync()
 *       at appropriate checkpoints, which propagates InterruptedException through Mono chain</li>
 *  中断机制使用反应式模式：子类在适当的检查点调用checkInterruptedAsync()，它通过Mono链传播InterruptedException
 *   <li>Observe pattern: agents can receive messages without generating a reply</li>
 *   观察者模式：智能体可以接收消息而不生成回复
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 * Agent instances are NOT designed for concurrent execution. A single agent instance should not
 * be invoked concurrently from multiple threads (e.g., calling {@code call()} or {@code stream()}
 * simultaneously). The hooks list is mutable and modified during streaming operations without
 * synchronization, which is safe only under single-threaded execution per agent instance.
 * 线程安全：
 * 智能体实例不适用于并发执行。
 * 单个智能体实例不应同时从多个线程调用（例如，同时调用call()或stream()）。
 * 钩子列表是可变的，并且在没有同步的情况下在流操作期间修改，
 * 这仅在每个智能体实例的单线程执行下是安全的。
 *
 * <p><b>Interrupt Mechanism:</b>
 * 中断机制
 * <pre>{@code
 * // External call to interrupt
 * agent.interrupt(userMsg);
 *
 * // Inside agent's Mono chain, at checkpoints:
 * return checkInterruptedAsync()
 *     .then(doWork())
 *     .flatMap(result -> checkInterruptedAsync().thenReturn(result));
 *
 * // AgentBase.call() catches the exception:
 * .onErrorResume(error -> {
 *     if (error instanceof InterruptedException) {
 *         return handleInterrupt(context, msg);
 *     }
 *     ...
 * });
 * }</pre>
 */
public abstract class AgentBase implements StateModule, Agent {

    private final String agentId;
    private final String name;
    private final String description;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean checkRunning;
    private final List<Hook> hooks;
    private static final List<Hook> systemHooks = new CopyOnWriteArrayList<>();
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    // Interrupt state management (available to all agents)
    private final AtomicBoolean interruptFlag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userInterruptMessage = new AtomicReference<>(null);
    // Hook non-null
    private static final Comparator<Hook> HOOK_COMPARATOR = Comparator.comparingInt(Hook::priority);

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     */
    public AgentBase(String name) {
        this(name, null, true, List.of());
    }

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     * @param description Agent description
     */
    public AgentBase(String name, String description) {
        this(name, description, true, List.of());
    }

    /**
     * Constructor for AgentBase with hooks.
     *
     * @param name Agent name
     * @param description Agent description
     * @param checkRunning Whether to check running state
     * @param hooks List of hooks for monitoring/intercepting execution
     */
    public AgentBase(String name, String description, boolean checkRunning, List<Hook> hooks) {
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.checkRunning = checkRunning;
        this.hooks = new CopyOnWriteArrayList<>(hooks != null ? hooks : List.of());
        this.hooks.addAll(systemHooks);
        sortHooks();
    }

    @Override
    public final String getAgentId() {
        return agentId;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description != null ? description : Agent.super.getDescription();
    }

    /**
     * Process a list of input messages and generate a response with hook execution.
     * 处理一系列输入消息并生成带有钩子执行的响应
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     * 一旦启用遥测，跟踪数据将被捕获。
     *
     * @param msgs Input messages
     * 输入信息
     * @return Response message
     * 返回消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs) {
        return Mono.using(
                // 判断是否可以继续执行
                () -> {
                    // running.compareAndSet 原子级别的锁竞争，保证同一时间只有一个线程能成功将running从false设置为true
                    if (checkRunning && !running.compareAndSet(false, true)) {
                        throw new IllegalStateException(
                                "Agent is still running, please wait for it to finish");
                    }
                    resetInterruptFlag();
                    return this;
                },
                resource ->
                        TracerRegistry.get()
                                .callAgent(
                                        this,
                                        msgs,
                                        () ->   
                                               // 可自定义插入不同的模块
                                               // 前置处理
                                                notifyPreCall(msgs)
                                                         //核心流程
                                                        .flatMap(this::doCall)
                                                        // 后置处理
                                                        .flatMap(this::notifyPostCall)
                                                            // 错误处理
                                                        .onErrorResume(
                                                                createErrorHandler(
                                                                        msgs.toArray(new Msg[0])))),
                //执行完毕后重置状态为false                                                        
                resource -> running.set(false),
                true);
    }

    /**
     * Process multiple input messages and generate structured output with hook execution.
     * 处理多个输入消息并生成带有钩子执行的结构化输出
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     * 一旦启用遥测，跟踪数据将被捕获。
     *
     * @param msgs Input messages
     * 输入消息
     * @param structuredOutputClass Class defining the structure of the output
     * 结构化输出的类定义
     * @return Response message with structured data in metadata
     * 带有结构化数据的响应消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.using(
                // 判断是否可以继续执行
                // 返回 Agent 实例本身，传递给下一个 lambda 使用
                () -> {
                    if (checkRunning && !running.compareAndSet(false, true)) {
                        throw new IllegalStateException(
                                "Agent is still running, please wait for it to finish");
                    }
                    resetInterruptFlag();
                    return this;
                },
                resource ->
                        TracerRegistry.get()
                                .callAgent(
                                        this,
                                        msgs,
                                        () ->  
                                                // 前置处理
                                                notifyPreCall(msgs)
                                                        .flatMap(
                                                                m ->
                                                                        doCall(
                                                                                m,
                                                                                structuredOutputClass))
                                                            // 后置处理                        
                                                        .flatMap(this::notifyPostCall)
                                                                            
                                                        .onErrorResume(
                                                                createErrorHandler(
                                                                        msgs.toArray(new Msg[0])))),
                // 释放锁，允许下次调用
                resource -> running.set(false),
                true);
    }

    /**
     * Process multiple input messages and generate structured output with hook execution.
     * 处理多个输入消息并生成带有钩子执行的结构化输出
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     * 一旦启用遥测，跟踪数据将被捕获。
     *
     * @param msgs Input messages
     * 输入消息
     * @param schema com.fasterxml.jackson.databind.JsonNode instance defining the structure of the output
     * 输出结构定义的 com.fasterxml.jackson.databind.JsonNode 实例
     * @return Response message with structured data in metadata
     * 带有结构化数据的响应消息
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return Mono.using(
                () -> {
                    if (checkRunning && !running.compareAndSet(false, true)) {
                        throw new IllegalStateException(
                                "Agent is still running, please wait for it to finish");
                    }
                    resetInterruptFlag();
                    return this;
                },
                resource ->
                        TracerRegistry.get()
                                .callAgent(
                                        this,
                                        msgs,
                                        () ->
                                                notifyPreCall(msgs)
                                                        .flatMap(m -> doCall(m, schema))
                                                        .flatMap(this::notifyPostCall)
                                                        .onErrorResume(
                                                                createErrorHandler(
                                                                        msgs.toArray(new Msg[0])))),
                resource -> running.set(false),
                true);
    }

    /**
     * Internal implementation for processing multiple input messages.
     * 内部实现处理多个输入消息
     * Subclasses must implement their specific logic here.
     * 子类必须在这里实现他们的特定逻辑
     *
     * @param msgs Input messages
     * @return Response message
     */
    protected abstract Mono<Msg> doCall(List<Msg> msgs);

    /**
     * Internal implementation for processing multiple messages with structured output.
      * 支持结构化输出的子类必须重写此方法
     * Subclasses that support structured output must override this method.
     * 子类必须在这里实现他们的特定逻辑
     * Default implementation throws UnsupportedOperationException.
     * 默认实现抛出UnsupportedOperationException异常
     *
     * @param msgs Input messages
     * @param structuredOutputClass Class defining the structure
     * @return Response message with structured data in metadata
     */
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + getClass().getSimpleName()));
    }

    /**
     * Internal implementation for processing multiple messages with structured output.
     * 支持结构化输出的子类必须重写此方法
     * Subclasses that support structured output must override this method.
     * 子类必须在这里实现他们的特定逻辑
     * Default implementation throws UnsupportedOperationException.
     *
     * @param msgs Input messages
     * @param outputSchema com.fasterxml.jackson.databind.JsonNode instance defining the structure
     * @return Response message with structured data in metadata
     */
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + outputSchema.asText()));
    }
    
    public static void addSystemHook(Hook hook) {
        systemHooks.add(hook);
    }

    public static void removeSystemHook(Hook hook) {
        systemHooks.remove(hook);
    }

    /**
     * Interrupt the current agent execution.
     * 中断当前正在agent的执行
     * Sets an interrupt flag that will be checked by the agent at appropriate checkpoints.
     * 设置一个中断标志，代理将在执行过程中的适当检查点检查该标志。
     */
    @Override
    public void interrupt() {
        interruptFlag.set(true);
    }

    /**
     * Interrupt the current agent execution with a user message.
     * 中断当前agent的执行，此方法设置一个中断标志，并关联一个用户消息与中断。
     * Sets an interrupt flag and associates a user message with the interruption.
     * 设置一个中断标志，并关联一个用户消息与中断。
     *
     * @param msg User message associated with the interruption
     *           与中断相关的用户消息
     */
    @Override
    public void interrupt(Msg msg) {
        interruptFlag.set(true);
        if (msg != null) {
            userInterruptMessage.set(msg);
        }
    }

    /**
     * Check if the agent execution has been interrupted (reactive version).
     * Returns a Mono that completes normally if not interrupted, or errors with
     * InterruptedException if interrupted.
     * 如果智能体执行已被中断，返回一个正常完成的Mono；如果被中断，返回一个带有InterruptedException错误的Mono。
     *
     * <p>Subclasses should call this at appropriate checkpoints in their Mono chains.
     * For simple agents (like UserAgent), checkpoints may not be needed.
     * For complex agents (like ReActAgent), call this at:
     * 子类应该在他们的Mono链中的适当检查点调用这个方法。
     * 对于简单的智能体（如UserAgent），可能不需要检查点。
     * 对于复杂的智能体（如ReActAgent），请在以下位置调用：
     * <ul>
     *   <li>Start of each iteration</li>
     *   每个循环的开始
     *   <li>Before/after reasoning</li>
     *   推理之前/之后
     *   <li>Before/after each tool execution</li>
     *   在每次工具执行之前/之后
     *   <li>During streaming (each chunk)</li>
     *   在流式传输期间（每个块）
     * </ul>
     *
     * <p>Example usage:
     * <pre>{@code
     * return checkInterruptedAsync()
     *     .then(reasoning())
     *     .flatMap(result -> checkInterruptedAsync().thenReturn(result))
     *     .flatMap(result -> executeTools(result));
     * }</pre>
     *
     * @return Mono that completes if not interrupted, or errors if interrupted
     */
    protected Mono<Void> checkInterruptedAsync() {
        return Mono.defer(
                () ->
                        interruptFlag.get()
                                ? Mono.error(
                                        new InterruptedException("Agent execution interrupted"))
                                : Mono.empty());
    }

    /**
     * Reset the interrupt flag and associated state.
     * 重置中断标志和相关状态
     * This is called at the beginning of each call() to prepare for new execution.
     * 在每个call()的开始调用，以准备新的执行
     */
    protected void resetInterruptFlag() {
        interruptFlag.set(false);
        userInterruptMessage.set(null);
    }

    /**
     * Create interrupt context from current interrupt state.
     * 创建中断上下文从当前中断状态
     * Helper method to avoid code duplication.
     * 帮助方法避免代码重复
     *
     * @return InterruptContext with current user message
     */
    private InterruptContext createInterruptContext() {
        return InterruptContext.builder()
                .source(InterruptSource.USER)
                .userMessage(userInterruptMessage.get())
                .build();
    }

    /**
     * Create error handler for call() methods.
     * 为call()方法创建错误处理程序
     * Handles InterruptedException specially and delegates to handleInterrupt,
     * while notifying hooks for other errors.
     * 特殊处理InterruptedException并委托给handleInterrupt，同时通知钩子处理其他错误
     *
     * @param originalArgs Original arguments to pass to handleInterrupt
     * @return Function that handles errors appropriately
     */
    private Function<Throwable, Mono<Msg>> createErrorHandler(Msg... originalArgs) {
        return error -> {
            if (error instanceof InterruptedException
                    || (error.getCause() instanceof InterruptedException)) {
                return handleInterrupt(createInterruptContext(), originalArgs);
            }
            return notifyError(error).then(Mono.error(error));
        };
    }

    /**
     * Get the interrupt flag for access by subclasses.
     * 为子类提供访问的中断标志
     * Subclasses can use this flag to implement custom interrupt-checking logic
     * in addition to the standard checkInterruptedAsync() method.
     * 子类可以使用这个标志来实现自定义的中断检查逻辑，除了标准的checkInterruptedAsync()方法之外。
     *
     * @return The atomic boolean interrupt flag
     */
    protected AtomicBoolean getInterruptFlag() {
        return interruptFlag;
    }

    /**
     * Observe a message without generating a reply.
     * 观察一个信息而不需要回复
     * This allows agents to receive messages from other agents or the environment
     * without responding. It's commonly used in multi-agent collaboration scenarios.
     * 这允许智能体接收来自其他智能体或环境的消息而不需要响应。它通常用于多智能体协作场景。
     *
     * <p>Common implementation patterns:
     * 共同的实现模式
     * <ul>
     *   <li>Stateless agents: Empty implementation if observation is not needed</li>
     *   无状态agent: 如果不需要观察，则为空实现
     *   <li>Stateful agents: Store message in memory/context for use in future calls</li>
     *   有状态agent: 将消息存储在内存/上下文中以供将来调用使用
     *   <li>Collaborative agents: Update shared knowledge or trigger side effects</li>
     *   协作agent: 更新共享知识或触发副作用
     * </ul>
     *
     * @param msg The message to observe
     * 被观察的消息
     * @return Mono that completes when observation is done
     * 当观察完成时完成的Mono
     */
    protected Mono<Void> doObserve(Msg msg) {
        return Mono.empty();
    }

    /**
     * Handle an interruption that occurred during execution.
     * 处理执行过程中发生的中断
     * Subclasses must implement this to provide recovery logic based on the interrupt context.
     * 子类必须实现这个方法来根据中断上下文提供恢复逻辑
     *
     * <p>Implementation guidance:
     * 实现指南:
     * <ul>
     *   <li>Simple agents: Return a basic interrupt acknowledgment message</li>
     *   简单agent: 返回一个基本的中断确认消息
     *   <li>Complex agents: Generate a summary including any pending operations or partial results</li>
     *   复杂agent: 生成一个摘要，包括任何待处理的操作或部分结果
     *   <li>Stateful agents: Ensure state is saved appropriately before returning</li>
     *   有状态agent: 在返回之前确保状态得到适当保存
     * </ul>
     *
     * @param context The interrupt context containing metadata about the interruption
     * 中断上下文，包含关于中断的元数据
     * @param originalArgs The original arguments passed to the call() method (empty, single Msg,
     *     or List)
     * 原始参数，传递给call()方法（空、单个Msg或List）
     * @return Recovery message to return to the user
     * 返回给用户的恢复消息
     */
    protected abstract Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs);

    /**
     * Get the list of hooks for this agent.
     * 得到这个智能体的钩子列表
     * Protected to allow subclasses to access hooks for custom notification logic.
     * 受保护以允许子类访问钩子以进行自定义通知逻辑
     *
     * @return List of hooks
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    /**
     * Add a hook to this agent dynamically.
     * 动态地向这个智能体添加一个钩子
     *
     * <p>Hooks can be added during agent execution to provide temporary functionality.
     * 钩子可以在智能体执行期间添加以提供临时功能
     * This is commonly used for structured output handling or other short-lived behaviors.
     * 这通常用于结构化输出处理或其他短暂的行为
     *
     * @param hook The hook to add
     */
    protected void addHook(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
            sortHooks();
        }
    }

    //hook排序
    private void sortHooks() {
        this.hooks.sort(HOOK_COMPARATOR);
    }

    /**
     * Remove a hook from this agent dynamically.
     * 动态地从这个智能体移除一个钩子
     *
     * <p>Hooks should be removed when they are no longer needed to avoid memory leaks
     * and unintended side effects.
     * 当钩子不再需要时应该将其移除，以避免内存泄漏和意外的副作用
     *
     * @param hook The hook to remove
     */
    protected void removeHook(Hook hook) {
        if (hook != null) {
            hooks.remove(hook);
        }
    }

    /**
     * Get hooks sorted by priority (lower value = higher priority).
     * 根据优先级获取钩子（较低的值=较高的优先级）
     * Hooks with the same priority maintain registration order.
     * 具有相同优先级的钩子保持注册顺序
     *
     * @return Sorted list of hooks
     */
    protected List<Hook> getSortedHooks() {
        return hooks;
    }

    /**
     * Notify all hooks that agent is starting (preCall hook).
     * 钩子通知所有钩子智能体正在启动（preCall钩子）
     *
     * <p>Hooks may modify the input messages via {@link PreCallEvent#setInputMessages(List)}.
     * Hooks are executed sequentially, with each hook receiving the event modified by previous hooks.
     * 钩子可以通过{@link PreCallEvent#setInputMessages(List)}修改输入消息。钩子按顺序执行，每个钩子接收前一个钩子修改的事件
     *
     * @param msgs Input messages
     * 输入消息
     * @return Mono containing the messages after all hooks have processed them (may be modified)
     * 包含所有钩子处理后的消息的Mono（可能被修改）
     */
    private Mono<List<Msg>> notifyPreCall(List<Msg> msgs) {
        PreCallEvent event = new PreCallEvent(this, msgs);
        Mono<PreCallEvent> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        return result.map(PreCallEvent::getInputMessages);
    }

    /**
     * Notify all hooks about completion (postCall hook).
     * 钩子通知所有钩子关于完成（postCall钩子）
     * After hook notification, broadcasts the message to all subscribers.
     * 钩子通知后，将消息广播给所有订阅者
     *
     * @param finalMsg Final message
     * 最终消息
     * @return Mono containing potentially modified final message
     * 包含可能被修改的最终消息的Mono
     */
    private Mono<Msg> notifyPostCall(Msg finalMsg) {
        if (finalMsg == null) {
            return Mono.error(new IllegalStateException("Agent returned null message"));
        }
        PostCallEvent event = new PostCallEvent(this, finalMsg);
        Mono<PostCallEvent> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        // After hooks, broadcast to subscribers
        return result.map(PostCallEvent::getFinalMessage)
                .flatMap(msg -> broadcastToSubscribers(msg).thenReturn(msg));
    }

    /**
     * Notify all hooks about error.
     * 钩子通知所有钩子关于错误
     *
     * @param error The error
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyError(Throwable error) {
        ErrorEvent event = new ErrorEvent(this, error);
        return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
    }

    /**
     * Remove all subscribers for a specific MsgHub.
     * 移除特定MsgHub的所有订阅者
     * This method is typically called when a MsgHub is being destroyed or reset.
     * 这个方法通常在MsgHub被销毁或重置时调用
     * After calling this method, the agent will no longer receive messages from the specified hub.
     * 调用此方法后，智能体将不再接收来自指定hub的消息
     *
     * @param hubId MsgHub identifier
     */
    public void removeSubscribers(String hubId) {
        hubSubscribers.remove(hubId);
    }

    /**
     * Reset the subscriber list for a specific MsgHub.
     * 重置特定MsgHub的订阅者列表
     * This replaces any existing subscribers for the given hub with the new list.
     * 调用此方法后，给定hub的任何现有订阅者都将被新列表替换
     * Typically called by MsgHub when the subscription topology changes.
     * 通常由MsgHub在订阅拓扑发生变化时调用
     *
     * @param hubId MsgHub identifier
     * 参数 hubId MsgHub标识符
     * @param subscribers New list of subscribers (will be copied)
     * 参数 subscribers 新的订阅者列表（将被复制）
     */
    public void resetSubscribers(String hubId, List<AgentBase> subscribers) {
        hubSubscribers.put(hubId, new ArrayList<>(subscribers));
    }

    /**
     * Check if this agent has any subscribers.
     * 检查这个智能体是否有任何订阅者
     * Subscribers are agents that will receive messages published through MsgHub.
     * 订阅者是将通过MsgHub接收消息的智能体
     *
     * @return True if agent has one or more subscribers
     */
    public boolean hasSubscribers() {
        return !hubSubscribers.isEmpty()
                && hubSubscribers.values().stream().anyMatch(list -> !list.isEmpty());
    }

    /**
     * Get the total number of subscribers across all MsgHubs.
     * 获取所有MsgHub的订阅者总数
     * Subscribers are agents that will receive messages published through MsgHub.
     * 订阅者是将通过MsgHub接收消息的智能体
     *
     * @return Total count of subscribers
     */
    public int getSubscriberCount() {
        return hubSubscribers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Broadcast a message to all subscribers across all MsgHubs.
     * 将消息广播给所有MsgHub的订阅者
     * This method is called automatically after each agent call to implement
     * the MsgHub auto-broadcast functionality.
     * 这个方法在每次智能体调用后自动调用，以实现MsgHub自动广播功能
     *
     * @param msg Message to broadcast
     * @return Mono that completes when all subscribers have observed the message
     */
    private Mono<Void> broadcastToSubscribers(Msg msg) {
        if (hubSubscribers.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(hubSubscribers.values())
                .flatMap(Flux::fromIterable)
                .flatMap(subscriber -> subscriber.observe(msg))
                .then();
    }

    /**
     * Observe a single message without generating a reply.
     * 观察一个信息而不需要回复
     * This is the public API that delegates to doObserve implementation.
     * 这是公共API，委托给doObserve实现
     *
     * @param msg Message to observe
     * 被观察的消息
     * @return Mono that completes when observation is done
     * 当观察完成时完成的Mono
     */
    @Override
    public final Mono<Void> observe(Msg msg) {
        return doObserve(msg);
    }

    /**
     * Observe multiple messages without generating a reply.
     * 观察多个信息而不需要回复
     * This is the public API that delegates to doObserve implementation.
     * 这是公共API，委托给doObserve实现
     *
     * @param msgs Messages to observe
     * @return Mono that completes when all observations are done
     */
    @Override
    public final Mono<Void> observe(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(msgs).flatMap(this::doObserve).then();
    }

    /**
     * Stream with multiple input messages.
     * 多个输入消息的流式处理
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    @Override
    public final Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return createEventStream(options, () -> call(msgs));
    }

    /**
     * Stream with multiple input messages.
     * 多个输入消息的流式处理
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Optional class defining the structure
     * @return Flux of events emitted during execution
     */
    @Override
    public final Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return createEventStream(options, () -> call(msgs, structuredModel));
    }

    /**
     * Helper method to create an event stream with proper hook lifecycle management.
     * 使用适当的钩子生命周期管理创建事件流的帮助方法
     *
     * <p>This method handles the common logic for streaming events during agent execution,
     * 这个方法处理智能体执行期间流式事件的公共逻辑，
     * including:
     * 包含：
     * <ul>
     *   <li>Creating and registering a temporary StreamingHook</li>
     *   创建和注册一个临时的StreamingHook
     *   <li>Managing the hook lifecycle (add/remove from hooks list)</li>
     *   管理钩子生命周期（添加/从钩子列表中移除）
     *   <li>Optionally emitting the final agent result as an event</li>
     *   操作性地将最终智能体结果作为事件发出
     *   <li>Properly propagating errors and completion signals</li>
     *   正确传播错误和完成信号
     * </ul>
     *
     * @param options Stream configuration options
     * 流配置选项
     * @param callSupplier Supplier that executes the agent call (either single message or list)
     * 执行智能体调用的供应商（单个消息或列表）
     * @return Flux of events emitted during execution
     * 执行期间发出的事件流
     */
    private Flux<Event> createEventStream(StreamOptions options, Supplier<Mono<Msg>> callSupplier) {
        return Flux.deferContextual(
                ctxView ->
                        Flux.<Event>create(
                                        sink -> {
                                            // Create streaming hook with options
                                            StreamingHook streamingHook =
                                                    new StreamingHook(sink, options);

                                            // Add temporary hook
                                            addHook(streamingHook);

                                            // Use Mono.defer to ensure trace context propagation
                                            // while maintaining streaming hook functionality
                                            Mono.defer(() -> callSupplier.get())
                                                    .contextWrite(
                                                            context -> context.putAll(ctxView))
                                                    .doFinally(
                                                            signalType -> {
                                                                // Remove temporary hook
                                                                hooks.remove(streamingHook);
                                                            })
                                                    .subscribe(
                                                            finalMsg -> {
                                                                if (options.shouldStream(
                                                                        EventType.AGENT_RESULT)) {
                                                                    sink.next(
                                                                            new Event(
                                                                                    EventType
                                                                                            .AGENT_RESULT,
                                                                                    finalMsg,
                                                                                    true));
                                                                }

                                                                // Complete the stream
                                                                sink.complete();
                                                            },
                                                            sink::error);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                .publishOn(Schedulers.boundedElastic()));
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }
}
