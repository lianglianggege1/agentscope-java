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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Fair per-key mutual exclusion for gateway turns (user/channel inbound runs and subagent
 * notification).
 */
/**
 * 网关回合的按键公平互斥（用户/通道入站执行与子智能体通知）。
 */
public final class SessionTurnGate {

    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    /** 阻塞获取指定键的回合锁；信号量为公平模式，先等待者先获得。 */
    public void acquire(String key) throws InterruptedException {
        gates.computeIfAbsent(key, k -> new Semaphore(1, true)).acquire();
    }

    /** 释放指定键的回合锁；键从未被使用过时不执行任何操作。 */
    public void release(String key) {
        Semaphore s = gates.get(key);
        if (s != null) {
            s.release();
        }
    }

    /**
     * Non-blocking check: returns {@code true} when a turn is currently held for the given key
     * (i.e. a run is in progress). Used by {@link WakeupDispatcher} to skip sessions that are
     * already active — their current run will drain the inbox naturally.
     */
    /**
     * 非阻塞检查：当指定键当前持有回合锁（即有执行正在进行）时返回 {@code true}。
     * 供 {@link WakeupDispatcher} 跳过已活跃的会话——它们当前的执行
     * 会自然地把收件箱（inbox）排空。
     */
    public boolean isRunning(String key) {
        Semaphore s = gates.get(key);
        return s != null && s.availablePermits() == 0;
    }
}
