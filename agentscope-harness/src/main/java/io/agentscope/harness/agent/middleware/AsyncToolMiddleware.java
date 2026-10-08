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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that offloads long-running tool calls to background execution.
 *
 * <p>When the acting phase exceeds the configured timeout:
 * <ol>
 *   <li>The underlying tool execution continues in the background (never cancelled)</li>
 *   <li>A placeholder {@link ToolResultBlock} is written to agent context so the LLM can
 *       continue reasoning</li>
 *   <li>When the background execution completes, the real result is pushed to the session's
 *       inbox as a {@code HintBlock} and a wakeup is enqueued</li>
 * </ol>
 *
 * <p>The {@link InboxMiddleware} drains the inbox on the next reasoning step, making the
 * real result available to the LLM.
 */
/**
 * 长耗时工具调用异步卸载中间件。
 *
 * <p>核心思想：工具执行一旦超过 offloadTimeout，不把智能体阻塞在这里，而是：
 * <ol>
 *   <li>底层工具执行转入后台继续跑（绝不取消）；</li>
 *   <li>向智能体上下文写入占位 {@link ToolResultBlock}，让 LLM 可以继续推理其他事情；</li>
 *   <li>后台执行完成后，把真实结果以 {@code HintBlock} 推入该会话的收件箱，
 *       并追加一条唤醒（wakeup）请求。</li>
 * </ol>
 *
 * <p>下一轮推理开始时 {@link InboxMiddleware} 会清空收件箱，把真实结果注入上下文，
 * LLM 因此"延迟"拿到工具结果——实现跨调用的异步结果回传。
 */
public class AsyncToolMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AsyncToolMiddleware.class);

    // 超时后写入上下文的占位结果模板：明确告知模型工具已转后台执行、
    // 禁止轮询等待，并给出两个选择（做其他独立任务 / 直接给文本回复）。
    private static final String PLACEHOLDER_TEMPLATE =
            "<system-reminder>Tool '%s' is running in background (id=%s) for over %ds. "
                    + "You will be notified automatically when it finishes, so DO NOT poll, "
                    + "query, or wait for the result yourself. You have two options:\n"
                    + "1. Continue with other independent tasks;\n"
                    + "2. If nothing else to do, give a text reply without calling any tool."
                    + "</system-reminder>";

    /** 消息总线：后台结果完成后推入会话收件箱，并排队唤醒该会话。 */
    private final MessageBus messageBus;

    /** 卸载超时阈值：acting 阶段超过该时长即转后台并写入占位结果。 */
    private final Duration offloadTimeout;

    /** 异步工具登记表（可选）：记录运行中/完成/失败的异步工具状态，供查询与超时兜底。 */
    private final AsyncToolRegistry asyncToolRegistry;

    public AsyncToolMiddleware(MessageBus messageBus, Duration offloadTimeout) {
        this(messageBus, offloadTimeout, null);
    }

    public AsyncToolMiddleware(
            MessageBus messageBus, Duration offloadTimeout, AsyncToolRegistry asyncToolRegistry) {
        this.messageBus = messageBus;
        this.offloadTimeout = offloadTimeout;
        this.asyncToolRegistry = asyncToolRegistry;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_ACTING);
    }

    /**
     * acting 阶段的超时卸载逻辑，本质是一个由两个原子标志驱动的状态机：
     * <ul>
     *   <li>未超时（timedOut=false）：下游事件原样转发给上游，正常结束即完成；</li>
     *   <li>超时后（timedOut=true）：上游已用占位结果完成，下游事件转入
     *       backgroundBuffer 缓冲；后台完成时再把缓冲的结果投递到收件箱。</li>
     * </ul>
     * 注意：无论上游是否取消，底层工具订阅 sub 都不会被 dispose（除非从未超时），
     * 保证后台执行不被中断。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        return Flux.create(
                sink -> {
                    // completed：下游工具执行是否已完成（CAS 保证完成回调只跑一次）
                    AtomicBoolean completed = new AtomicBoolean(false);
                    // timedOut：是否已触发超时卸载（CAS 保证占位逻辑只跑一次）
                    AtomicBoolean timedOut = new AtomicBoolean(false);
                    // 超时之后到达的后台事件缓冲，完成时从中提取真实结果文本
                    List<AgentEvent> backgroundBuffer = new CopyOnWriteArrayList<>();

                    Disposable sub =
                            next.apply(input)
                                    .subscribe(
                                            event -> {
                                                // 未超时：正常透传；已超时：转入后台缓冲
                                                if (!timedOut.get()) {
                                                    sink.next(event);
                                                } else {
                                                    backgroundBuffer.add(event);
                                                }
                                            },
                                            error -> {
                                                if (!timedOut.get()) {
                                                    sink.error(error);
                                                } else {
                                                    // 后台执行在超时后失败：记录日志，
                                                    // 并在登记表中把对应工具标记为失败
                                                    log.warn(
                                                            "Background tool execution failed"
                                                                    + " after timeout",
                                                            error);
                                                    if (asyncToolRegistry != null) {
                                                        String errMsg =
                                                                error.getMessage() != null
                                                                        ? error.getMessage()
                                                                        : error.getClass()
                                                                                .getSimpleName();
                                                        for (ToolUseBlock tc : input.toolCalls()) {
                                                            asyncToolRegistry
                                                                    .fail(tc.getId(), errMsg)
                                                                    .subscribe();
                                                        }
                                                    }
                                                }
                                            },
                                            () -> {
                                                if (completed.compareAndSet(false, true)) {
                                                    if (!timedOut.get()) {
                                                        // 超时前正常完成：直接结束上游流
                                                        sink.complete();
                                                    } else {
                                                        // 已超时：从后台缓冲提取真实结果，
                                                        // 推入收件箱并唤醒会话
                                                        deliverBackgroundResult(
                                                                backgroundBuffer,
                                                                input.toolCalls(),
                                                                ctx);
                                                    }
                                                }
                                            });

                    // 超时定时器：到期时若工具仍未完成，抢占 timedOut 标志，
                    // 写入占位结果并结束上游流（底层执行继续在后台跑）
                    Disposable timer =
                            Schedulers.parallel()
                                    .schedule(
                                            () -> {
                                                if (!completed.get()
                                                        && timedOut.compareAndSet(false, true)) {
                                                    log.info(
                                                            "Tool execution timed out after {}s,"
                                                                    + " offloading to background:"
                                                                    + " session={}",
                                                            offloadTimeout.getSeconds(),
                                                            ctx != null
                                                                    ? ctx.getSessionId()
                                                                    : "null");
                                                    emitPlaceholderAndComplete(
                                                            sink, input.toolCalls(), agent, ctx);
                                                }
                                            },
                                            offloadTimeout.toMillis(),
                                            TimeUnit.MILLISECONDS);

                    // 上游取消/销毁时的清理：定时器一定释放；
                    // 底层订阅仅在"尚未超时"时才释放——超时后必须让后台执行跑完
                    sink.onDispose(
                            () -> {
                                timer.dispose();
                                if (!timedOut.get()) {
                                    sub.dispose();
                                }
                            });
                },
                FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 超时卸载的执行动作：对每个超时的工具调用——
     * 1) 在异步工具登记表登记 RUNNING 记录（供后续查询与超时兜底）；
     * 2) 构造占位 ToolResultBlock 并写入 AgentState 上下文（让 ReAct 循环能继续）；
     * 3) 向事件流发射对应的 ToolResult Start/TextDelta/End 三事件（前端可见）；
     * 最后 complete 上游流。
     */
    private void emitPlaceholderAndComplete(
            FluxSink<AgentEvent> sink,
            List<ToolUseBlock> toolCalls,
            Agent agent,
            RuntimeContext ctx) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);

        String sessionId = ctx != null ? ctx.getSessionId() : null;
        for (ToolUseBlock toolCall : toolCalls) {
            if (asyncToolRegistry != null && sessionId != null) {
                asyncToolRegistry
                        .register(
                                new AsyncToolRecord(
                                        toolCall.getId(),
                                        sessionId,
                                        toolCall.getName(),
                                        toolCall.getId(),
                                        AsyncToolRecord.RUNNING,
                                        Instant.now()))
                        .subscribe();
            }

            String placeholderText =
                    String.format(
                            PLACEHOLDER_TEMPLATE,
                            toolCall.getName(),
                            toolCall.getId(),
                            offloadTimeout.getSeconds());

            ToolResultBlock placeholder =
                    ToolResultBlock.text(placeholderText)
                            .withIdAndName(toolCall.getId(), toolCall.getName());

            if (state != null) {
                Msg resultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                placeholder, toolCall, agent.getName());
                state.contextMutable().add(resultMsg);
            }

            sink.next(new ToolResultStartEvent(replyId, toolCall.getId(), toolCall.getName()));
            sink.next(
                    new ToolResultTextDeltaEvent(
                            replyId, toolCall.getId(), toolCall.getName(), placeholderText));
            sink.next(
                    new ToolResultEndEvent(
                            replyId,
                            toolCall.getId(),
                            toolCall.getName(),
                            ToolResultState.SUCCESS));
        }
        sink.complete();
    }

    /**
     * 后台执行完成后的结果投递：
     * 1) 从缓冲的后台事件中提取 ToolResultTextDelta 拼出真实结果文本；
     * 2) 包装成 system-notification 形式的 HintBlock 载荷；
     * 3) 在登记表中把对应工具标记为完成；
     * 4) 通过 messageBus 推入会话收件箱并排队一条唤醒，
     *    由 {@link InboxMiddleware} 在下一次推理时注入上下文。
     */
    private void deliverBackgroundResult(
            List<AgentEvent> backgroundBuffer, List<ToolUseBlock> toolCalls, RuntimeContext ctx) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null) {
            log.warn("Cannot deliver background tool result: no sessionId in RuntimeContext");
            return;
        }

        StringBuilder resultText = new StringBuilder();
        for (AgentEvent event : backgroundBuffer) {
            if (event instanceof ToolResultTextDeltaEvent delta) {
                resultText.append(delta.getDelta());
            }
        }

        String toolNames =
                toolCalls.stream()
                        .map(ToolUseBlock::getName)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("unknown");

        String hintContent =
                String.format(
                        "<system-notification>Tool '%s' running in background has completed.\n\n"
                                + "Result:\n\n%s</system-notification>",
                        toolNames, resultText.length() > 0 ? resultText.toString() : "(no output)");

        String hintId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> hintPayload =
                Map.of("type", "hint", "id", hintId, "hint", hintContent, "source", "tool_output");

        if (asyncToolRegistry != null) {
            String resultStr = resultText.length() > 0 ? resultText.toString() : "(no output)";
            for (ToolUseBlock tc : toolCalls) {
                asyncToolRegistry.complete(tc.getId(), resultStr).subscribe();
            }
        }

        messageBus.inboxPush(sessionId, hintPayload).subscribe();

        String agentId = ctx.get("agentId");
        String userId = ctx.getUserId();
        messageBus
                .enqueueWakeup(
                        userId != null ? userId : "", sessionId, agentId != null ? agentId : "")
                .subscribe(
                        unused -> {},
                        error ->
                                log.warn(
                                        "Failed to enqueue wakeup for session {}: {}",
                                        sessionId,
                                        error.getMessage()));

        log.info(
                "Background tool '{}' completed, pushed result to inbox: session={}",
                toolNames,
                sessionId);
    }
}
