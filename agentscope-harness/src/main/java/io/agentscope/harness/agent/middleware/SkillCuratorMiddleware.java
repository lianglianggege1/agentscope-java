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
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Schedules {@link SkillCurator} runs after the main {@code call()} completes. Gates on the
 * curator's interval + {@code PeriodicGate}, and runs on a single-thread daemon executor so the
 * agent loop is never blocked.
 */
/**
 * 在主 {@code call()} 完成后调度 {@link SkillCurator}（技能策展器）运行。
 * 行为与 {@code MemoryMaintenanceMiddleware} 类似：以空闲时长 + 执行间隔作为触发门槛，
 * 并在单线程守护线程执行器上运行，确保不会阻塞智能体主循环。
 */
public class SkillCuratorMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SkillCuratorMiddleware.class);

    /** 技能策展器，负责技能生命周期治理（如草稿转正、过期清理等）。 */
    private final SkillCurator curator;

    /** 单线程守护调度执行器，后台运行策展任务，不阻塞智能体主循环。 */
    private final ScheduledExecutorService executor;

    /** 关闭标记，置位后不再接受新的后台策展任务。 */
    private volatile boolean shutdown = false;

    /**
     * @param curator 技能策展器，不允许为 null；内部创建单线程守护调度执行器
     */
    public SkillCuratorMiddleware(SkillCurator curator) {
        this.curator = java.util.Objects.requireNonNull(curator, "curator");
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "skill-curator-mw");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    /**
     * 中间件钩子：在智能体事件流完成时（doOnComplete）记录调用结束时间，
     * 并尝试触发一次策展运行。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(this::maybeRunCurator);
    }

    /**
     * Called from the {@code onAgent} doOnComplete: if the curator gate accepts, dispatch a run
     * to the daemon executor.
     */
    /**
     * 由 {@code onAgent} 的 doOnComplete 调用：若触发门槛通过，则把一次策展运行
     * 提交到守护执行器异步执行。当 {@code minIdleHours} == 0 时相当于立即执行，
     * 与方案文本中的默认行为一致。
     */
    private void maybeRunCurator() {
        if (shutdown) {
            return;
        }
        Instant now = Instant.now();
        // 由策展器内部判断空闲时长与执行间隔是否满足触发条件
        if (!curator.shouldRunNow(now)) {
            return;
        }
        executor.submit(
                () -> {
                    try {
                        SkillCurator.CuratorRunReport report = curator.runOnce(Instant.now());
                        log.info(
                                "skill-curator ran: transitions={} report={} duration_ms={}",
                                report.transitions(),
                                report.dryRunReportPath(),
                                report.durationMs());
                    } catch (Exception e) {
                        log.warn("skill-curator run failed: {}", e.getMessage(), e);
                    }
                });
    }

    /** Stop accepting new background work; idempotent. */
    /** 停止接受新的后台任务；可重复调用（幂等）。 */
    public void close() {
        shutdown = true;
        executor.shutdownNow();
    }

    /** Direct access to the underlying curator (for {@code agent.runCuratorOnce}). */
    /** 直接访问底层策展器（供 {@code agent.runCuratorOnce} 手动触发使用）。 */
    public SkillCurator curator() {
        return curator;
    }
}
