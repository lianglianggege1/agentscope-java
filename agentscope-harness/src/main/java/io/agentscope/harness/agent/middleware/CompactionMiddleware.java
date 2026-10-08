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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware that performs conversation compaction before each LLM reasoning call.
 *
 * <p>Fires on {@link #onReasoning}. When the compaction threshold is exceeded:
 * <ol>
 *   <li>Long-term memories are flushed from the prefix via {@link MemoryFlushManager}.</li>
 *   <li>The full conversation is offloaded to the session JSONL.</li>
 *   <li>The prefix is distilled into a structured summary via one LLM call.</li>
 *   <li>The agent's working {@link AgentState#contextMutable() context} is replaced with
 *       {@code [summaryMsg] + preservedTail}.</li>
 *   <li>The downstream {@link ReasoningInput} is rebuilt with
 *       {@code [systemMsg] + [summaryMsg] + preservedTail}.</li>
 * </ol>
 *
 * <p>When {@link CompactionConfig#getTriggerTokens()} is 0 (dynamic mode, the default), the
 * effective trigger threshold is computed as {@code model.getContextWindowSize() - reserved}.
 * If the model does not report its context window, falls back to
 * {@link CompactionConfig#FALLBACK_TRIGGER_TOKENS}.
 */
/**
 * 上下文压缩中间件：在每次 LLM 推理调用之前检查并执行会话压缩，
 * 防止上下文超出模型窗口。
 *
 * <p>触发于 {@link #onReasoning}。当 token 数超过触发阈值时依次执行：
 * <ol>
 *   <li>通过 {@link MemoryFlushManager} 把前缀中的长期记忆先落盘；</li>
 *   <li>把完整会话转储到会话 JSONL 文件（归档保底）；</li>
 *   <li>用一次 LLM 调用把前缀蒸馏为结构化摘要；</li>
 *   <li>用 {@code [摘要消息] + 保留尾部} 替换智能体的工作上下文
 *       （{@link AgentState#contextMutable()}）；</li>
 *   <li>用 {@code [系统消息] + [摘要消息] + 保留尾部} 重建下游 {@link ReasoningInput}。</li>
 * </ol>
 *
 * <p>动态阈值（默认）：当 {@link CompactionConfig#getTriggerTokens()} 为 0 时，
 * 有效触发阈值 = {@code 模型上下文窗口 - reserved}；
 * 模型未上报窗口大小时回退到 {@link CompactionConfig#FALLBACK_TRIGGER_TOKENS}。
 *
 * <p>容错：压缩过程出现任何异常都不会阻断主流程——记录警告后按原上下文继续推理。
 * 该实例同时被 HarnessAgent 用作"上下文溢出紧急压缩"恢复路径的开关。
 */
public class CompactionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(CompactionMiddleware.class);

    /** 工作空间管理器：压缩时把完整会话转储到会话 JSONL、长期记忆落盘都依赖它。 */
    private final WorkspaceManager workspaceManager;

    /** 主模型：既用于估算动态阈值（上下文窗口），也用于执行摘要蒸馏的 LLM 调用。 */
    private final Model model;

    /** 压缩配置（触发/保留 token、保留比例等），可能含待解析的动态默认值。 */
    private final CompactionConfig config;

    public CompactionMiddleware(
            WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    /**
     * 推理前压缩检查。用 Flux.defer 保证每次订阅都重新求值（读取最新输入消息）。
     * 流程：拆分系统消息与会话正文 → 解析有效配置 → 调 compactIfNeeded
     * → 未触发则原样放行；触发则把压缩结果同时应用到 AgentState 上下文
     * 与下游 ReasoningInput（重新拼回系统消息）。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return next.apply(input);
        }
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();

        return Flux.defer(
                () -> {
                    // 步骤 1：把首条系统消息剥离出来，剩余部分作为待压缩的会话正文
                    List<Msg> messages = input.messages();
                    Msg systemMsg = null;
                    List<Msg> conversation;
                    if (messages != null
                            && !messages.isEmpty()
                            && messages.get(0).getRole() == MsgRole.SYSTEM) {
                        systemMsg = messages.get(0);
                        conversation = new ArrayList<>(messages.subList(1, messages.size()));
                    } else {
                        conversation = messages != null ? new ArrayList<>(messages) : List.of();
                    }

                    String agentId = agent.getName();
                    String sessionId =
                            rc != null && rc.getSessionId() != null ? rc.getSessionId() : "default";

                    // 步骤 2：解析含动态默认值的有效配置（触发阈值/保留 token）
                    CompactionConfig effectiveConfig = resolveEffectiveConfig();

                    // 步骤 3：构建压缩器（记忆落盘器 + 会话压缩器），按需执行压缩
                    MemoryFlushManager flushManager =
                            new MemoryFlushManager(workspaceManager, model);
                    ConversationCompactor compactor =
                            new ConversationCompactor(model, flushManager);
                    final Msg sys = systemMsg;

                    // Only compaction may degrade; downstream reasoning errors must propagate.
                    // 容错兜底：压缩失败（如摘要 LLM 调用出错）不阻断主流程，
                    // 记录警告后按原始上下文继续推理；中断异常除外，需向上传播
                    return compactor
                            .compactIfNeeded(rc, conversation, effectiveConfig, agentId, sessionId)
                            .onErrorResume(
                                    error -> {
                                        if (ExceptionUtils.containsInterruptedException(error)) {
                                            return Mono.error(error);
                                        }
                                        log.warn(
                                                "Compaction failed, continuing without compaction:"
                                                        + " {}",
                                                error.getMessage());
                                        return Mono.just(Optional.empty());
                                    })
                            .flatMapMany(
                                    optResult -> {
                                        if (optResult.isEmpty()) {
                                            // 未达阈值：不做任何事，原样放行
                                            return next.apply(input);
                                        }
                                        // 步骤 4：把压缩结果应用到智能体工作上下文
                                        List<Msg> compacted = optResult.get();
                                        applyToContext(
                                                RuntimeContext.resolveAgentState(rc, reActAgent),
                                                compacted);
                                        log.debug(
                                                "Compacted to {} messages before reasoning",
                                                compacted.size());
                                        // 步骤 5：重建下游输入 = 系统消息 + 压缩后的会话
                                        List<Msg> newMessages = new ArrayList<>();
                                        if (sys != null) {
                                            newMessages.add(sys);
                                        }
                                        newMessages.addAll(compacted);
                                        return next.apply(
                                                new ReasoningInput(
                                                        newMessages,
                                                        input.tools(),
                                                        input.options()));
                                    });
                });
    }

    /**
     * Resolves dynamic defaults in the config using the model's context window.
     */
    /**
     * 用模型的上下文窗口解析配置中的动态默认值：
     * <ul>
     *   <li>triggerTokens == 0 → 动态触发阈值 = 窗口 - reserved
     *       （结果为负/零时钳制到窗口的一半，模型未报窗口则用回退常量）；</li>
     *   <li>keepTokens == -1 → 动态保留 token = clamp(窗口 - reserved 的比例值,
     *       keepTokensMin, keepTokensMax)。</li>
     * </ul>
     * 两者都非动态时直接原样返回配置，避免无谓计算。
     */
    private CompactionConfig resolveEffectiveConfig() {
        int configTrigger = config.getTriggerTokens();
        int configKeep = config.getKeepTokens();

        boolean needsDynamic = (configTrigger == 0) || (configKeep == -1);
        if (!needsDynamic) {
            return config;
        }

        int contextWindow = model.getContextWindowSize();

        int effectiveTrigger;
        if (configTrigger == 0) {
            if (contextWindow > 0) {
                effectiveTrigger = contextWindow - config.getReserved();
                if (effectiveTrigger <= 0) {
                    // reserved exceeds the model's context window; a negative or zero trigger
                    // would fire compaction on every call. Clamp to half the context window so
                    // compaction still activates at a sensible point without thrashing.
                    // reserved 超过了模型的上下文窗口；负数或零的触发阈值会导致每次调用
                    // 都触发压缩。钳制到窗口的一半，让压缩仍在合理的位置触发且不会反复抖动。
                    effectiveTrigger = Math.max(1, contextWindow / 2);
                    log.warn(
                            "Dynamic compaction trigger clamped: contextWindow={} <= reserved={}"
                                    + "; using proportional trigger={}. Consider reducing"
                                    + " reserved() for this model.",
                            contextWindow,
                            config.getReserved(),
                            effectiveTrigger);
                } else {
                    log.debug(
                            "Dynamic compaction trigger: contextWindow={} - reserved={} = {}",
                            contextWindow,
                            config.getReserved(),
                            effectiveTrigger);
                }
            } else {
                effectiveTrigger = CompactionConfig.FALLBACK_TRIGGER_TOKENS;
                log.debug(
                        "Model does not report context window, using fallback trigger: {}",
                        effectiveTrigger);
            }
        } else {
            effectiveTrigger = configTrigger;
        }

        int effectiveKeep;
        if (configKeep == -1) {
            if (contextWindow > 0) {
                int usable = contextWindow - config.getReserved();
                effectiveKeep =
                        Math.min(
                                config.getKeepTokensMax(),
                                Math.max(
                                        config.getKeepTokensMin(),
                                        (int) (usable * config.getKeepTokensRatio())));
                log.debug("Dynamic keep tokens: {}", effectiveKeep);
            } else {
                effectiveKeep = 0;
            }
        } else {
            effectiveKeep = configKeep;
        }

        return config.withEffective(effectiveTrigger, effectiveKeep);
    }

    /**
     * 把压缩后的消息列表整体替换进 AgentState 的工作上下文（clear + addAll），
     * 使后续轮次的记忆与持久化都基于压缩后的上下文。任何异常只记警告不抛出。
     */
    private static void applyToContext(AgentState state, List<Msg> compacted) {
        if (state == null) {
            log.warn("Cannot apply compacted messages: AgentState is null");
            return;
        }
        try {
            List<Msg> ctx = state.contextMutable();
            ctx.clear();
            ctx.addAll(compacted);
            log.debug("Applied compacted messages to state ({} messages)", compacted.size());
        } catch (Exception e) {
            log.warn("Failed to apply compacted messages to state: {}", e.getMessage());
        }
    }
}
