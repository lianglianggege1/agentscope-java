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
package io.agentscope.harness.agent.subagent.task;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Discriminated union describing how a background subagent task should execute.
 *
 * <p>{@link LocalTaskRunSpec} runs the supplier on a local executor. {@link RemoteTaskRunSpec}
 * delegates to an AgentScope task HTTP API (see {@code agentscope-extensions-agent-protocol}).
 * {@link AdoptedTaskRunSpec} wraps an already-running {@link CompletableFuture} (used when a sync
 * execution is promoted to async on timeout).
 */
/**
 * 区分联合类型，用于描述后台子智能体任务的执行方式。
 *
 * <p>{@link LocalTaskRunSpec}：在本地线程池执行任务工厂；
 * {@link RemoteTaskRunSpec}：将任务转发至 AgentScope 任务HTTP接口执行（依赖 agentscope-extensions-agent-protocol 扩展包）。
 */
public sealed interface TaskRunSpec {

    /** In-process execution via {@link Supplier}. */
    /** 通过 {@link Supplier} 实现进程内本地执行。 */
    record LocalTaskRunSpec(Supplier<String> execution) implements TaskRunSpec {}

    /**
     * Remote HTTP task execution. The {@code taskId} is chosen by the client and used as the
     * remote task key end-to-end. {@code context} carries submission-time metadata (streaming
     * preference, propagated deny rules, parent identity); defaults to
     * {@link RemoteSubmitContext#empty()} for callers that predate this field.
     */
    /** 远端HTTP任务执行模式。taskId由客户端指定，并全程作为远端任务唯一标识。 */
    record RemoteTaskRunSpec(
            String baseUrl,
            Map<String, String> headers,
            String agentId,
            String input,
            RemoteSubmitContext context)
            implements TaskRunSpec {

        public RemoteTaskRunSpec(
                String baseUrl, Map<String, String> headers, String agentId, String input) {
            this(baseUrl, headers, agentId, input, RemoteSubmitContext.empty());
        }
    }

    /**
     * Adopts an already-running {@link CompletableFuture} as a tracked background task. Used when
     * a sync execution exceeds its timeout and is promoted to async: the future is still in
     * progress and only needs status-tracking callbacks, not a new executor submission.
     */
    /**
     * 接管已在运行的 {@link CompletableFuture} 作为托管后台任务。适用于同步执行超时后转为异步的场景：
     * 该异步任务仍在执行中，仅需挂载状态跟踪回调，无需重新提交至执行器。
     */
    record AdoptedTaskRunSpec(CompletableFuture<String> future) implements TaskRunSpec {}
}
