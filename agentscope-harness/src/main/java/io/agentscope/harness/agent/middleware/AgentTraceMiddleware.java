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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Observability middleware that logs the reasoning and execution trace of an agent.
 *
 * <p>At INFO level, logs concise summaries: agent name, model, tool names/IDs, and
 * message lengths. At DEBUG level, additionally logs tool call arguments, tool result
 * content, reasoning text, and input message details.
 */
/**
 * 可观测性中间件：把智能体的推理与执行轨迹以结构化日志的形式打印出来。
 *
 * <p>日志埋点覆盖 ReAct 循环的三个阶段，且全部走 Reactor 的 doOnNext/doOnComplete
 * 侧路观测，不改变事件流本身：
 * <ul>
 *   <li>{@code onAgent}：调用入口 PRE_CALL / 调用结束 POST_CALL / 异常 ERROR；</li>
 *   <li>{@code onReasoning}：推理前 PRE_REASONING（模型名、消息数）/ 推理后
 *       POST_REASONING（聚合的文本增量与工具调用清单）；</li>
 *   <li>{@code onActing}：执行前 PRE_ACTING（工具 id/名称）/ 执行后 POST_ACTING
 *       （结果长度与状态）。</li>
 * </ul>
 *
 * <p>INFO 级别只输出摘要（智能体名、模型、工具名/ID、消息长度）；
 * DEBUG 级别额外输出工具入参、工具结果内容、推理文本和输入消息详情。
 * 当日志级别低于 INFO 时直接透传 next，做到零开销。
 */
public class AgentTraceMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceMiddleware.class);

    /**
     * 调用级埋点：进入时打印 PRE_CALL（输入消息数，DEBUG 下含每条消息的角色与前 200 字符）；
     * 流完成时通过 logPostCall 打印 POST_CALL（最终回复摘要）；出错时打印 ERROR。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!log.isInfoEnabled()) {
            return next.apply(input);
        }
        String name = agent.getName();
        List<Msg> msgs = input.msgs();
        log.info("[{}] PRE_CALL  | {} input message(s)", name, msgs != null ? msgs.size() : 0);
        if (log.isDebugEnabled() && msgs != null) {
            for (Msg msg : msgs) {
                log.debug(
                        "[{}] PRE_CALL  |   [{}] {}",
                        name,
                        msg.getRole(),
                        truncate(msg.getTextContent(), 200));
            }
        }
        return next.apply(input)
                .doOnComplete(() -> logPostCall(agent, ctx))
                .doOnError(
                        e ->
                                log.info(
                                        "[{}] ERROR | {}: {}",
                                        name,
                                        e.getClass().getSimpleName(),
                                        e.getMessage()));
    }

    /**
     * 推理级埋点：进入时打印 PRE_REASONING（模型名、上下文消息数）；
     * 在事件流上侧路聚合文本增量（TextBlockDeltaEvent）与工具调用（ToolCallStartEvent），
     * 流完成时打印 POST_REASONING（推理文本摘要、工具调用清单或空完成警告）。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!log.isInfoEnabled()) {
            return next.apply(input);
        }
        String name = agent.getName();
        String modelName = resolveModelName(agent);
        int msgCount = input.messages() != null ? input.messages().size() : 0;
        log.info("[{}] PRE_REASONING  | model={}, messages={}", name, modelName, msgCount);
        if (log.isDebugEnabled() && input.messages() != null) {
            for (Msg msg : input.messages()) {
                log.debug(
                        "[{}] PRE_REASONING  |   [{}] len={}",
                        name,
                        msg.getRole(),
                        msg.getTextContent() != null ? msg.getTextContent().length() : 0);
            }
        }
        StringBuilder textBuf = new StringBuilder();
        List<ToolCallStartEvent> toolCalls = new ArrayList<>();
        return next.apply(input)
                .doOnNext(
                        ev -> {
                            if (ev instanceof TextBlockDeltaEvent tbd) {
                                if (tbd.getDelta() != null) {
                                    textBuf.append(tbd.getDelta());
                                }
                            } else if (ev instanceof ToolCallStartEvent tcs) {
                                toolCalls.add(tcs);
                            }
                        })
                .doOnComplete(
                        () -> {
                            String text = textBuf.toString();
                            boolean hasText = !text.isBlank();
                            // Always surface the model's text, even when it accompanies tool calls
                            // (a tool-call turn often carries a "thinking out loud" preamble that
                            // would otherwise be silently dropped).
                            // 只要模型产生了文本就打印出来，即使这一轮同时伴随工具调用
                            // （工具调用轮往往带有"自言自语"式的前导文本，
                            // 若不单独打印会被静默丢弃）。
                            if (hasText) {
                                log.info(
                                        "[{}] POST_REASONING | text: {}",
                                        name,
                                        truncate(text, 120));
                            }
                            if (toolCalls.isEmpty()) {
                                // No tool call ends the ReAct loop. If there was also no text, the
                                // model returned an empty completion — make that explicit instead
                                // of logging a misleading "<empty>" that looks like normal output.
                                // 没有工具调用就意味着 ReAct 循环结束。如果同时也没有文本，
                                // 说明模型返回了空完成——显式标注这一情况，
                                // 避免打印出看似正常输出的误导性 "<empty>"。
                                if (!hasText) {
                                    log.info(
                                            "[{}] POST_REASONING | empty completion (no text, no"
                                                    + " tool call) — ReAct loop will terminate",
                                            name);
                                }
                            } else {
                                for (ToolCallStartEvent tc : toolCalls) {
                                    log.info(
                                            "[{}] POST_REASONING | tool_call: id={}, name={}",
                                            name,
                                            tc.getToolCallId(),
                                            tc.getToolCallName());
                                }
                            }
                        });
    }

    /**
     * 执行级埋点：进入时打印 PRE_ACTING（每个工具调用的 id/名称，DEBUG 下含入参 JSON）；
     * POST_ACTING 通过观测流过的工具结果事件（Start/TextDelta/End）推导，
     * 用两个并发 Map 按 toolCallId 累积结果文本，结束时打印结果长度与状态。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (!log.isInfoEnabled()) {
            return next.apply(input);
        }
        String name = agent.getName();
        if (input.toolCalls() != null) {
            for (ToolUseBlock tu : input.toolCalls()) {
                log.info("[{}] PRE_ACTING  | id={}, name={}", name, tu.getId(), tu.getName());
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[{}] PRE_ACTING  |   args={}",
                            name,
                            truncate(mapToJson(tu.getInput()), 500));
                }
            }
        }
        // Derive POST_ACTING from the tool-result events flowing through the middleware stream
        // (ToolResultStart/TextDelta/End), rather than scanning the context tail on completion.
        // The context append happens after this stream completes, so the previous approach missed
        // successfully-executed tools; observing the events captures every tool deterministically
        // and keeps us off the deprecated hook path.
        // POST_ACTING 的数据来源是流经中间件事件流的工具结果事件
        // （ToolResultStart/TextDelta/End），而不是在完成时扫描上下文尾部。
        // 因为上下文追加发生在本流完成之后，旧方案会漏掉已成功执行的工具；
        // 观测事件则可以确定性地捕获每一个工具，且无需依赖已废弃的 hook 路径。
        Map<String, String> toolNames = new ConcurrentHashMap<>();
        Map<String, StringBuilder> toolText = new ConcurrentHashMap<>();
        return next.apply(input)
                .doOnNext(
                        ev -> {
                            if (ev instanceof ToolResultStartEvent start) {
                                toolNames.put(start.getToolCallId(), start.getToolCallName());
                                toolText.computeIfAbsent(
                                        start.getToolCallId(), k -> new StringBuilder());
                            } else if (ev instanceof ToolResultTextDeltaEvent delta) {
                                if (delta.getDelta() != null) {
                                    toolText.computeIfAbsent(
                                                    delta.getToolCallId(), k -> new StringBuilder())
                                            .append(delta.getDelta());
                                }
                            } else if (ev instanceof ToolResultEndEvent end) {
                                String id = end.getToolCallId();
                                String toolName = toolNames.getOrDefault(id, "<unknown>");
                                String text =
                                        toolText.getOrDefault(id, new StringBuilder()).toString();
                                log.info(
                                        "[{}] POST_ACTING | id={}, name={}, result_len={},"
                                                + " state={}",
                                        name,
                                        id,
                                        toolName,
                                        text.length(),
                                        end.getState());
                                if (log.isDebugEnabled()) {
                                    log.debug(
                                            "[{}] POST_ACTING |   result={}",
                                            name,
                                            truncate(text, 500));
                                }
                            }
                        });
    }

    /**
     * 调用结束埋点：从 AgentState 上下文中倒序找出最后一条助手消息作为"最终回复"。
     * 若该消息携带工具调用块，说明循环结束在工具调用轮（其后模型返回了空完成），
     * 此时不把前导文本当作正式回复打印，而是显式标注该情况。
     */
    private void logPostCall(Agent agent, RuntimeContext rc) {
        String name = agent.getName();
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            log.info("[{}] POST_CALL | response: <n/a>", name);
            return;
        }
        Msg lastAssistant = null;
        List<Msg> ctx = state.getContext();
        for (int i = ctx.size() - 1; i >= 0; i--) {
            if (ctx.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = ctx.get(i);
                break;
            }
        }
        if (lastAssistant == null) {
            log.info("[{}] POST_CALL | response: <n/a>", name);
            return;
        }
        String text = lastAssistant.getTextContent();
        // A turn carrying tool calls is never a clean final reply: if it is the last
        // assistant message, the loop ended on a tool-call turn (e.g., the model returned an
        // empty completion right after). Surfacing its preamble as the "response" is misleading,
        // so we flag the situation explicitly and show the preamble only as context.
        // 携带工具调用的一轮绝不可能是干净的最终回复：如果它是最后一条助手消息，
        // 说明循环结束在工具调用轮（例如模型紧接着返回了空完成）。
        // 把它的前导文本当作"回复"展示具有误导性，
        // 因此显式标注该情况，前导文本仅作为参考上下文打印。
        boolean endedOnToolCall = !lastAssistant.getContentBlocks(ToolUseBlock.class).isEmpty();
        if (endedOnToolCall) {
            log.info(
                    "[{}] POST_CALL | ended on a tool-call turn with no final text reply; last"
                            + " preamble: {}",
                    name,
                    truncate(text, 120));
        } else {
            log.info("[{}] POST_CALL | response: {}", name, truncate(text, 120));
        }
    }

    /** 从内层 ReActAgent 提取模型名称用于日志；无法解析时返回 "<unknown>"。 */
    private static String resolveModelName(Agent agent) {
        if (agent instanceof ReActAgent r && r.getModel() != null) {
            return r.getModel().getModelName();
        }
        return "<unknown>";
    }

    /** 日志截断工具：超过 max 字符只保留前缀并附加截断标记；空串显示 "<empty>"。 */
    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "<empty>";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated, limit=" + max + " chars]";
    }

    /** 把工具入参 Map 序列化为 JSON 字符串供 DEBUG 日志使用；序列化失败回退 toString。 */
    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(map);
        } catch (Exception e) {
            return map.toString();
        }
    }
}
