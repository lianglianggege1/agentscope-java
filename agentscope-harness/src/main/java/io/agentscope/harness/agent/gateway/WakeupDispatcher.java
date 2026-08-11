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

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Per-process dispatcher that wakes idle sessions when background work completes.
 *
 * <p>Subscribes to the shared wakeup signal channel on {@link MessageBus#subscribeWakeup()} and
 * drains the durable wakeup queue on each signal. For each queued entry whose target session is
 * idle, triggers a new reasoning round via {@link WakeupTarget#runWakeup(String)}.
 *
 * It serves as the universal activator for all cross-session communication:
 *
 * <ul>
 *   <li>Background subagent task completion
 *   <li>Async tool results ({@link io.agentscope.harness.agent.middleware.AsyncToolMiddleware})
 *   <li>Team messages (future Agent Teams support)
 *   <li>Scheduled task triggers
 * </ul>
 *
 * <p>Lifecycle: call {@link #start()} after the gateway is fully configured. Call {@link #close()}
 * on shutdown. Typically managed by the application bootstrap or gateway factory.
 */
/**
 * 进程级分发器：在后台任务完成时唤醒空闲会话。
 *
 * <p>订阅 {@link MessageBus#subscribeWakeup()} 上的共享唤醒信号通道，
 * 每收到一次信号就排空持久化唤醒队列。对每个目标会话处于空闲状态的队列条目，
 * 通过 {@link WakeupTarget#runWakeup(String)} 触发新一轮推理。
 *
 * 它是所有跨会话通信的统一激活器：
 *
 * <ul>
 *   <li>后台子智能体任务完成
 *   <li>异步工具结果（{@link io.agentscope.harness.agent.middleware.AsyncToolMiddleware}）
 *   <li>团队消息（未来 Agent Teams 支持）
 *   <li>定时任务触发
 * </ul>
 *
 * <p>生命周期：在网关完全配置好后调用 {@link #start()}；关闭时调用 {@link #close()}。
 * 通常由应用引导或网关工厂管理。
 */
public class WakeupDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WakeupDispatcher.class);

    /** 单次信号最多消费的唤醒队列条目数。 */
    private static final int MAX_DRAIN_COUNT = 64;

    private final MessageBus messageBus;
    private final WakeupTarget target;
    private volatile Disposable subscription;

    /**
     * Thin interface for the two gateway operations the dispatcher needs. {@link HarnessGateway}
     * implements this directly; tests can supply a stub.
     */
    /**
     * 分发器所需的两个网关操作的细粒度接口。{@link HarnessGateway} 直接实现；
     * 测试可提供桩实现。
     */
    public interface WakeupTarget {
        /** 指定会话当前是否有执行在进行。 */
        boolean isSessionRunning(String sessionId);

        /** 对空闲会话发起一次唤醒驱动的推理执行。 */
        Mono<Msg> runWakeup(String sessionId);
    }

    public WakeupDispatcher(MessageBus messageBus, WakeupTarget target) {
        this.messageBus = messageBus;
        this.target = target;
    }

    /**
     * Starts the dispatcher: performs an initial drain of any queued wakeups (to pick up signals
     * produced while this process was down), then subscribes to the live signal channel.
     */
    /**
     * 启动分发器：先执行一次初始排空（拾取本进程停机期间产生的唤醒信号），
     * 然后订阅实时信号通道。
     */
    public void start() {
        drainAndDispatch();
        subscription =
                messageBus
                        .subscribeWakeup()
                        .subscribe(
                                signal -> drainAndDispatch(),
                                err ->
                                        log.error(
                                                "WakeupDispatcher subscription error; "
                                                        + "dispatcher is dead",
                                                err));
        log.info("WakeupDispatcher started");
    }

    /** 停止分发器：释放信号通道订阅。 */
    @Override
    public void close() {
        Disposable d = subscription;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        subscription = null;
        log.info("WakeupDispatcher stopped");
    }

    /**
     * 从唤醒队列（键 {@code "agentscope:wakeups"}）阻塞式消费最多
     * {@link #MAX_DRAIN_COUNT} 条条目，逐条分发；整体异常仅告警不抛出。
     */
    private void drainAndDispatch() {
        try {
            List<BusEntry> entries =
                    messageBus.queueDrain("agentscope:wakeups", MAX_DRAIN_COUNT).block();
            if (entries == null || entries.isEmpty()) {
                return;
            }

            for (BusEntry entry : entries) {
                dispatch(entry.payload());
            }
        } catch (Exception e) {
            log.warn("WakeupDispatcher: drainAndDispatch failed", e);
        }
    }

    /**
     * 分发单个唤醒条目：解析 sessionId/agentId；缺少 sessionId 跳过；
     * 目标会话正在运行也跳过（其当前执行会自然排空收件箱）；
     * 否则异步触发 {@link WakeupTarget#runWakeup}。
     */
    private void dispatch(Map<String, Object> payload) {
        String sessionId = getString(payload, "sessionId");
        String agentId = getString(payload, "agentId");

        if (sessionId == null || sessionId.isBlank()) {
            log.debug("WakeupDispatcher: skipping entry with no sessionId");
            return;
        }

        if (target.isSessionRunning(sessionId)) {
            log.debug(
                    "WakeupDispatcher: session {} is running, skipping (current run will drain"
                            + " inbox)",
                    sessionId);
            return;
        }

        log.info("WakeupDispatcher: waking idle session {}, agentId={}", sessionId, agentId);
        target.runWakeup(sessionId)
                .subscribe(
                        msg ->
                                log.debug(
                                        "WakeupDispatcher: wakeup run completed for session {}",
                                        sessionId),
                        err ->
                                log.warn(
                                        "WakeupDispatcher: wakeup run failed for session {}",
                                        sessionId,
                                        err));
    }

    /** 从 Map 中安全取出字符串值，类型不符或缺失时返回 null。 */
    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
