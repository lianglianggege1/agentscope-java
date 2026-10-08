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
package io.agentscope.harness.agent.gateway;

/**
 * Per-key mutual exclusion for gateway turns (user/channel inbound runs and subagent notification).
 *
 * <p>Implementations include {@link LocalSessionTurnGate} (process-local fair semaphore) and
 * control-plane hosted locks for cross-node coordination. When using a distributed turn gate,
 * {@link io.agentscope.core.ReActAgent} {@code conflictPolicy} should be set to {@code FAIL} so
 * concurrent state writes surface as errors rather than silent overwrites.
 */
/**
 * 网关回合的按键公平互斥（用户/通道入站执行与子智能体通知）。
 */
public interface SessionTurnGate {

    /**
     * Acquires the turn slot for the given key, blocking until available or throwing when busy.
     *
     * @param key canonical gate key (typically {@link MsgContext#canonicalKey()})
     * @return a lease that must be closed to release the slot
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws TurnBusyException if the slot is held and the implementation uses a short acquire
     *     timeout (distributed gates)
     */
    TurnLease acquire(String key) throws InterruptedException, TurnBusyException;

    /**
     * Non-blocking check: returns {@code true} when a turn is currently held for the given key.
     * Used by {@link WakeupDispatcher} to skip sessions that are already active.
     *
     * @param key canonical gate key
     * @return {@code true} when a turn is in progress for the key
     */
    /**
     * 非阻塞检查：当指定键当前持有回合锁（即有执行正在进行）时返回 {@code true}。
     * 供 {@link WakeupDispatcher} 跳过已活跃的会话——它们当前的执行
     * 会自然地把收件箱（inbox）排空。
     */
    boolean isRunning(String key);
}
