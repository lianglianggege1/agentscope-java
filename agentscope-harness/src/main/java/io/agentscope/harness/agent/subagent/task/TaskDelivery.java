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

import java.time.Instant;

/**
 * Snapshot of a terminal-state subagent task awaiting (or undergoing) push delivery to the parent
 * agent's reasoning loop. Returned by {@link TaskRepository#findPendingDeliveries} so that
 * middleware can render it into a synthetic {@code <system-reminder>} message without re-querying
 * the underlying record.
 *
 * <p>Fields are denormalised from {@link TaskRecord} on purpose — the consumer
 * ({@code SubagentsMiddleware}) only needs the projection, and snapshotting up-front avoids racing
 * with a later status mutation.
 *
 * @param taskId        task identifier
 * @param agentId       which subagent type produced this result (may be {@code null} on legacy
 *                      records)
 * @param status        terminal status — one of COMPLETED / FAILED / CANCELLED
 * @param result        completion payload; {@code null} for FAILED / CANCELLED
 * @param errorMessage  failure message; {@code null} for COMPLETED / CANCELLED
 * @param completedAt   wall-clock instant the task reached its terminal status (best-effort —
 *                      typically {@link TaskRecord#getLastUpdatedAt()})
 */
/**
 * 已终止状态子任务快照，等待推送至父智能体推理循环（或正在推送中）。
 * 由 {@link TaskRepository#findPendingDeliveries} 返回，供中间件直接生成虚拟 {@code <system-reminder>} 系统提示消息，无需重复查询底层任务记录。
 *
 * <p>该快照字段对 {@link TaskRecord} 做了冗余展开设计：消费方（{@code SubagentsMiddleware}）仅需该投影数据；提前生成快照可避免与后续任务状态变更产生并发竞争。
 *
 * @param taskId        任务唯一标识
 * @param agentId       产出该结果的子智能体类型ID（旧版任务记录可能为 {@code null}）
 * @param status        终止状态，取值为 COMPLETED / FAILED / CANCELLED 三者之一
 * @param result        任务成功返回载荷；任务失败/取消时为 {@code null}
 * @param errorMessage  异常错误信息；任务成功/取消时为 {@code null}
 * @param completedAt   任务切换至终止状态的系统时间（尽力保证准确，通常取自 {@link TaskRecord#getLastUpdatedAt()}）
 */
public record TaskDelivery(
        String taskId,
        String agentId,
        TaskStatus status,
        String result,
        String errorMessage,
        Instant completedAt) {}
