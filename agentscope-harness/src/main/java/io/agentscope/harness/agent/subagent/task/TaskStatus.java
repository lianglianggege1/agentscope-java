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

/**
 * Lifecycle status of a background subagent task. Terminal statuses ({@link #isTerminal()}) will
 * never change, so status checks can be skipped for finished tasks.
 */
/**
 * 后台子智能体任务的生命周期状态。终态（{@link #isTerminal()}）一旦达成就不会再变更，
 * 因此已结束任务可无需重复校验状态。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * Whether this status represents a final state that will never change. Useful for skipping
     * redundant status polling on tasks that are already done.
     */
    /**
     * 判断当前状态是否为永不变更的最终终态。适用于已完成任务，可跳过重复轮询状态以减少冗余操作。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
