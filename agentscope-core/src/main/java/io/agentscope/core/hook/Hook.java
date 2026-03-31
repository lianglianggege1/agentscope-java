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

import reactor.core.publisher.Mono;

/**
 * Hook interface for monitoring and intercepting agent execution.
 * 用于监控和拦截代理执行的钩子接口。
 *
 * <p>All agent execution events are delivered through a single {@link #onEvent(HookEvent)} method.
 * This unified event model provides a clean, type-safe way to intercept and modify agent behavior.
 * 所有的智能体执行事件是通过单个{@link #onEvent(HookEvent)}方法传递的。
 * 此统一事件模型提供了一种干净、类型安全的方式来拦截和修改代理行为。
 *
 * <p><b>Hook Priority:</b> Hooks are executed in priority order (lower value = higher priority).
 * Default priority is 100. Hooks with the same priority execute in registration order.
 * 钩子按优先级顺序执行（值越小，优先级越高）。 默认优先级为100。 同优先级钩子按注册顺序执行。
 *
 * <p><b>Event Modifiability:</b> Whether an event is modifiable is indicated by the presence of
 * setter methods:
 *      事件可修改特性： 事件是否可修改由以下setter方法决定：
 * <ul>
 *   <li>Events with setters (e.g., {@link PreReasoningEvent#setInputMessages}) allow
 *       modification</li>
 *       带setters的事(e.g., {@link PreReasoningEvent#setInputMessages})允许修改
 *   <li>Events without setters are notification-only</li>
 *       不带setter的事件仅作为通知
 * </ul>
 *
 * <p><b>Example Usage:</b>
 *
 * <pre>{@code
 * // Basic hook with default priority
 * Hook loggingHook = new Hook() {
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreReasoningEvent e -> {
 *                 System.out.println("Reasoning with model: " + e.getModelName());
 *                 yield Mono.just(e);
 *             }
 *             case ReasoningChunkEvent e -> {
 *                 // Display streaming output
 *                 System.out.print(extractText(e.getIncrementalChunk()));
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 *
 * // High priority hook (executes first)
 * Hook authHook = new Hook() {
 *     @Override
 *     public int priority() {
 *         return 10;  // High priority
 *     }
 *
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreActingEvent e -> {
 *                 // Inject auth token before any other hook
 *                 ToolUseBlock toolUse = e.getToolUse();
 *                 // ... add auth
 *                 e.setToolUse(toolUse);
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 *
 * // Modifying events
 * Hook hintInjector = new Hook() {
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreReasoningEvent e -> {
 *                 // Modify messages before LLM reasoning
 *                 // 修改输入消息在LLM推理之前
 *                 List<Msg> msgs = new ArrayList<>(e.getInputMessages());
 *                 msgs.add(0, Msg.builder()
 *                         .role(MsgRole.SYSTEM)
 *                         .content(new TextBlock("Think step by step"))
 *                         .build());
 *                 e.setInputMessages(msgs);
 *                 yield Mono.just(e);
 *             }
 *             case PostActingEvent e -> {
 *                 // Modify tool result
 *                 ToolResultBlock result = e.getToolResult();
 *                 // ... process result
 *                 e.setToolResult(result);
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 * }</pre>
 *
 * @see HookEvent
 * @see HookEventType
 */
public interface Hook {

    /**
     * Handle a hook event.
     * 处理钩子事件。
     *
     * <p>This method is called for all agent execution events. Use pattern matching to handle
     * specific event types. 这个方法被调用所有智能体执行事件。使用模式匹配处理特定事件类型。
     *
     * <p><b>Modifiable Events:</b> For events with setters, you can modify the context and the
     *       可修改事件：对于有setter的事件，你可以修改上下文和事件。
     * changes will affect agent execution:
     * <ul>
     *   <li>{@link PreReasoningEvent} - Modify messages before LLM reasoning在LLM推理之前修改消息</li>
     *   <li>{@link PostReasoningEvent} - Modify reasoning results 修改推理结果</li>
     *   <li>{@link PreActingEvent} - Modify tool parameters before execution 执行前修改tool参数</li>
     *   <li>{@link PostActingEvent} - Modify tool results 修改工具执行结果</li>
     *   <li>{@link PostCallEvent} - Modify final agent response 修改最终的智能体响应</li>
     * </ul>
     *
     * <p><b>Notification Events:</b> Events without setters are read-only:
     *       通知事件：事件没有setter是只读的：
     * <ul>
     *   <li>{@link PreCallEvent} - Notified when agent starts 当agent开始时通知</li>
     *   <li>{@link ReasoningChunkEvent} - Streaming reasoning chunks 流式推理块</li>
     *   <li>{@link ActingChunkEvent} - Streaming tool execution chunks 流式工具执行块</li>
     *   <li>{@link ErrorEvent} - Errors during execution 执行过程中出现错误</li>
     * </ul>
     *
     * @param event The hook event
     * @param <T> The concrete event type
     * @return Mono containing the potentially modified event
     */
    <T extends HookEvent> Mono<T> onEvent(T event);

    /**
     * The priority of this hook (lower value = higher priority).
     *
     * <p>Hooks are executed in ascending priority order. Hooks with the same priority execute in
     * their registration order.
     *
     * <p><b>Common Priority Ranges:</b>
     * <ul>
     *   <li>0-50: Critical system hooks (auth, security)关键系统Hook(身份验证、安全) </li>
     *   <li>51-100: High priority hooks (validation, preprocessing) 高优先级Hook(参数验证，执行之前的事件)</li>
     *   <li>101-500: Normal priority hooks (business logic) 一般优先级（业务逻辑）</li>
     *   <li>501-1000: Low priority hooks (logging, metrics) 低优先级hooks(日志，监控)</li>
     * </ul>
     *
     * @return The priority value (default: 100)
     */
    default int priority() {
        return 100;
    }
}
