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
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware that drains the message bus inbox before each reasoning step and injects the messages
 * as {@link HintBlock}s into the agent's context.
 *
 * <p>Producers (e.g. {@link AsyncToolMiddleware}, team tools, schedulers) push {@link HintBlock}
 * payloads into the per-session inbox via {@link MessageBus#inboxPush}. This middleware drains
 * them at the start of each reasoning step, appends them to the agent's context, and emits a
 * {@link HintBlockEvent} for each so the front-end event stream can render them in real time.
 */
/**
 * 收件箱中间件：在每一轮推理开始前清空消息总线收件箱，
 * 把其中的消息以 {@link HintBlock} 形式注入智能体上下文。
 *
 * <p>生产者（如 {@link AsyncToolMiddleware} 的后台工具结果、团队协作工具、调度器）
 * 通过 {@link MessageBus#inboxPush} 向按会话隔离的收件箱推送 {@link HintBlock} 载荷。
 * 本中间件在推理开始时把它们取出来追加进上下文，并为每条发射一个
 * {@link HintBlockEvent}，让前端事件流可以实时渲染。
 *
 * <p>额外兜底：若配置了 {@link AsyncToolRegistry}，还会找出运行时间超过 staleTtl
 * 仍未完成的异步工具（进程崩溃或超时场景），标记为超时并注入提示，
 * 避免智能体永远等不到结果。
 */
public class InboxMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(InboxMiddleware.class);
    private static final Duration DEFAULT_STALE_TTL = Duration.ofMinutes(10);

    /** 消息总线：从按会话隔离的收件箱中批量取出待注入的消息。 */
    private final MessageBus messageBus;

    /** 单次推理最多清空的收件箱条数，防止一次性注入过多内容撑爆上下文。 */
    private final int maxDrainCount;

    /** 异步工具登记表（可选）：用于检测长期未完成的"陈旧"异步工具。 */
    private final AsyncToolRegistry asyncToolRegistry;

    /** 陈旧判定阈值：异步工具运行超过该时长仍未完成即视为超时（默认 10 分钟）。 */
    private final Duration staleTtl;

    public InboxMiddleware(MessageBus messageBus) {
        this(messageBus, 100, null, DEFAULT_STALE_TTL);
    }

    public InboxMiddleware(MessageBus messageBus, int maxDrainCount) {
        this(messageBus, maxDrainCount, null, DEFAULT_STALE_TTL);
    }

    public InboxMiddleware(
            MessageBus messageBus,
            int maxDrainCount,
            AsyncToolRegistry asyncToolRegistry,
            Duration staleTtl) {
        this.messageBus = messageBus;
        this.maxDrainCount = maxDrainCount;
        this.asyncToolRegistry = asyncToolRegistry;
        this.staleTtl = staleTtl != null ? staleTtl : DEFAULT_STALE_TTL;
    }

    /**
     * 推理前清空收件箱并注入提示。两路数据并行拉取后合并：
     * 1) 收件箱中的 HintBlock 载荷（异步工具完成通知、团队消息等）；
     * 2) 登记表中运行超时的陈旧异步工具（标记超时并生成通知）。
     * 合并后注入上下文，先发射 HintBlockEvent 让前端可见，再放行下游推理。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {

        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null) {
            // 无会话上下文时无法定位收件箱，直接放行
            return next.apply(input);
        }

        // 第一路：清空收件箱（最多 maxDrainCount 条），反序列化为 HintBlock
        Mono<List<HintBlock>> inboxHints =
                messageBus
                        .inboxDrain(sessionId, maxDrainCount)
                        .map(
                                entries -> {
                                    List<HintBlock> hints = new ArrayList<>();
                                    for (BusEntry entry : entries) {
                                        HintBlock h = deserializeHintBlock(entry.payload());
                                        if (h != null) {
                                            hints.add(h);
                                        }
                                    }
                                    return hints;
                                });

        // 第二路：找出运行超过 staleTtl 仍未完成的异步工具（进程崩溃/超时兜底），
        // 逐条标记为超时并生成"未完成，可重试或继续"的系统通知
        Mono<List<HintBlock>> staleHints =
                asyncToolRegistry != null
                        ? asyncToolRegistry
                                .findStale(sessionId, staleTtl)
                                .flatMap(
                                        staleRecords -> {
                                            List<HintBlock> hints = new ArrayList<>();
                                            for (AsyncToolRecord rec : staleRecords) {
                                                asyncToolRegistry.markTimeout(rec.id()).subscribe();
                                                hints.add(
                                                        new HintBlock(
                                                                UUID.randomUUID()
                                                                        .toString()
                                                                        .replace("-", ""),
                                                                "<system-notification>Tool '"
                                                                        + rec.toolName()
                                                                        + "' (id="
                                                                        + rec.toolCallId()
                                                                        + ") did not complete"
                                                                        + " (process crash or"
                                                                        + " timeout). You may"
                                                                        + " retry or proceed"
                                                                        + " without the result."
                                                                        + "</system-notification>",
                                                                "system"));
                                            }
                                            return Mono.just(hints);
                                        })
                        : Mono.just(List.of());

        // 两路合并：都为空则零开销放行；否则注入上下文并先发射 HintBlockEvent
        return Mono.zip(inboxHints, staleHints)
                .flatMapMany(
                        tuple -> {
                            List<HintBlock> allHints = new ArrayList<>(tuple.getT1());
                            allHints.addAll(tuple.getT2());

                            if (allHints.isEmpty()) {
                                return next.apply(input);
                            }

                            log.debug(
                                    "InboxMiddleware: injecting {} HintBlock(s) into context"
                                            + " for session {} ({} from inbox, {} stale async"
                                            + " tools)",
                                    allHints.size(),
                                    sessionId,
                                    tuple.getT1().size(),
                                    tuple.getT2().size());

                            // 注入智能体上下文，使模型在本轮推理中就能看到这些提示
                            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                            if (state != null) {
                                injectHintsToContext(state, allHints, agent.getName());
                            }

                            String replyId =
                                    state != null
                                            ? state.getReplyId()
                                            : UUID.randomUUID().toString().replace("-", "");

                            // 为每条提示发射 HintBlockEvent（前端实时渲染），
                            // 之后再拼接下游推理流
                            Flux<AgentEvent> hintEvents =
                                    Flux.fromIterable(allHints)
                                            .map(
                                                    h ->
                                                            new HintBlockEvent(
                                                                    replyId,
                                                                    h.getId(),
                                                                    h.getSource(),
                                                                    h.getHint()));

                            return hintEvents.concatWith(next.apply(input));
                        });
    }

    /**
     * Inject hint blocks into the agent's context. Appends to the last assistant message's content
     * list if present; otherwise creates a new assistant message.
     */
    /**
     * 把提示块注入智能体上下文：若上下文末尾是本智能体的助手消息，
     * 直接把提示追加到该消息的内容列表（保持消息连续性）；
     * 否则新建一条仅含提示的助手消息追加到末尾。
     */
    static void injectHintsToContext(AgentState state, List<HintBlock> hints, String agentName) {
        List<Msg> context = state.contextMutable();
        if (!context.isEmpty()) {
            Msg last = context.get(context.size() - 1);
            if (last.getRole() == MsgRole.ASSISTANT
                    && agentName != null
                    && agentName.equals(last.getName())) {
                List<ContentBlock> extended = new ArrayList<>(last.getContent());
                extended.addAll(hints);
                Msg updatedMsg =
                        Msg.builder()
                                .id(last.getId())
                                .name(last.getName())
                                .role(MsgRole.ASSISTANT)
                                .content(extended)
                                .build();
                context.set(context.size() - 1, updatedMsg);
                return;
            }
        }
        Msg hintMsg =
                AssistantMessage.builder().name(agentName).content(new ArrayList<>(hints)).build();
        context.add(hintMsg);
    }

    /** 把收件箱载荷（Map）反序列化为 HintBlock；缺少 hint 字段返回 null（跳过该条）。 */
    static HintBlock deserializeHintBlock(Map<String, Object> payload) {
        Object id = payload.get("id");
        Object hint = payload.get("hint");
        Object source = payload.get("source");
        if (hint == null) {
            return null;
        }
        return new HintBlock(
                id != null ? id.toString() : UUID.randomUUID().toString().replace("-", ""),
                hint.toString(),
                source != null ? source.toString() : null);
    }
}
