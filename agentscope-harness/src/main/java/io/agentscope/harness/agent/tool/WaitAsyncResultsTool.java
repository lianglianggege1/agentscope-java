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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool that blocks until async results arrive in the session's inbox, or until a timeout is
 * reached. This gives the LLM the option to wait for background results within a single
 * {@code call()} invocation instead of returning and relying on a wakeup.
 *
 * <p>After this tool returns, the next reasoning step's {@code InboxMiddleware} will drain the
 * inbox and inject the results into context.
 */
/**
 * 阻塞工具，持续等待会话收件箱接收异步结果或达到超时阈值。
 * 允许大模型在单次 {@code call()} 调用内等待后台任务返回结果，
 * 无需先退出调用、依靠唤醒机制重新轮询。
 *
 * <p>该工具返回后，下一轮推理的 {@code InboxMiddleware} 将消费收件箱消息，
 * 并将结果注入运行时上下文。
 */
public class WaitAsyncResultsTool {

    private static final Logger log = LoggerFactory.getLogger(WaitAsyncResultsTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final long POLL_INTERVAL_MS = 3000;

    private final MessageBus messageBus;

    public WaitAsyncResultsTool(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    /*
    @Tool(
            name = "wait_async_results",
            description =
                    "等待后台异步工具或子智能体执行结果返回。"
                            + "当你发起异步任务且需要等待任务完成，而非直接回复用户时调用。"
                            + "该工具成功返回后可继续推理，执行结果会自动注入上下文。",
            readOnly = true)
    public String waitForResults(
            @ToolParam(
                            name = "timeout_seconds",
                            description = "最长等待秒数，默认60。")
                    Integer timeoutSeconds,
            RuntimeContext runtimeContext)
            throws InterruptedException {}
     */
    @Tool(
            name = "wait_async_results",
            description =
                    "Wait for background async tool or subagent results to arrive. "
                            + "Call this when you have launched async tasks and want to wait for "
                            + "their completion instead of returning to the user. After this tool "
                            + "returns successfully, continue reasoning — the results will be "
                            + "automatically injected into your context.",
            readOnly = true)
    public String waitForResults(
            @ToolParam(
                            name = "timeout_seconds",
                            description = "Maximum seconds to wait. Default 60.")
                    Integer timeoutSeconds,
            RuntimeContext runtimeContext)
            throws InterruptedException {
        int timeout =
                timeoutSeconds != null && timeoutSeconds > 0
                        ? timeoutSeconds
                        : DEFAULT_TIMEOUT_SECONDS;
        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;

        if (sessionId == null) {
            return "Cannot wait: no session context available.";
        }

        log.info(
                "wait_async_results: waiting up to {}s for inbox messages, session={}",
                timeout,
                sessionId);

        long deadlineMs = System.currentTimeMillis() + (timeout * 1000L);

        while (true) {
            long remainingMs = deadlineMs - System.currentTimeMillis();
            if (remainingMs <= 0) {
                break;
            }
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info("wait_async_results: inbox has messages, session={}", sessionId);
                return "Async results have arrived. Continue reasoning — "
                        + "the results will be injected into your context automatically.";
            }
            // Cap sleep to the remaining budget so the tool never overshoots the caller's timeout.
            Thread.sleep(Math.min(POLL_INTERVAL_MS, remainingMs));
        }

        log.info("wait_async_results: timeout after {}s, session={}", timeout, sessionId);
        return "Timeout after "
                + timeout
                + "s. No async results yet. "
                + "You may continue with other work or try waiting again.";
    }
}
