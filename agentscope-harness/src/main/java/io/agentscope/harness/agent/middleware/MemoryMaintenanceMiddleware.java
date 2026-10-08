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
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that performs periodic memory maintenance after each agent call.
 *
 * <p>Fires on the agent invocation completion (via {@code onAgent doOnComplete}, after
 * {@link MemoryFlushMiddleware}) and is throttled by a configurable minimum gap so it
 * does not run on every single call. The maintenance is <em>fire-and-forget</em>: the agent
 * stream completes immediately while the maintenance runs on a background scheduler.
 *
 * <p>Maintenance steps executed in order:
 * <ol>
 *   <li>Expire daily memory files older than {@code dailyFileRetentionDays} by moving
 *       them to {@code memory/archive/}.</li>
 *   <li>Run LLM-based consolidation ({@link MemoryConsolidator#consolidate}) if a
 *       consolidator is configured.</li>
 *   <li>Prune session log files older than {@code sessionRetentionDays}.</li>
 * </ol>
 *
 * <p>The throttle window is tracked per <em>isolation key</em>, which matches the memory data
 * isolation in use:
 * <ul>
 *   <li>{@link IsolationScope#USER} (default) — one window per {@code userId}.</li>
 *   <li>{@link IsolationScope#SESSION} — one window per {@code sessionId}.</li>
 *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} — one shared window for
 *       the whole agent instance (prevents concurrent maintenance races on shared memory files).</li>
 * </ul>
 */
/**
 * 中间件，每次智能体调用结束后执行周期性内存维护工作。
 *
 * <p>在智能体调用完成时触发（借助onAgent串联机制，执行时机晚于{@link MemoryFlushMiddleware}），
 * 同时受可配置的最小执行间隔限流，不会在每次调用后都执行维护操作。
 *
 * <p>维护步骤按以下顺序执行：
 * <ol>
 *   <li>将超过日常文件留存天数的过期日常记忆文件迁移至memory/archive/归档目录。</li>
 *   <li>若已配置整合器，则执行基于大模型的记忆整合（{@link MemoryConsolidator#consolidate}）。</li>
 *   <li>清理超过会话日志留存天数的会话日志文件。</li>
 * </ol>
 *
 * <p>限流窗口以隔离键为单位单独记录，与当前内存数据隔离规则保持一致：
 * <ul>
 *   <li>{@link IsolationScope#USER}（默认）：每个用户ID独立对应一个限流窗口。</li>
 *   <li>{@link IsolationScope#SESSION}：每个会话ID独立对应一个限流窗口。</li>
 *   <li>{@link IsolationScope#AGENT}、{@link IsolationScope#GLOBAL}：整个智能体实例共用一个限流窗口，
 *       避免共享记忆文件在多任务维护时产生竞争冲突。</li>
 * </ul>
 */
public class MemoryMaintenanceMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceMiddleware.class);

    /** Default minimum gap between two maintenance runs. */
    /** 两次维护执行之间的默认最小间隔（30 分钟），即默认限流窗口。 */
    public static final Duration DEFAULT_MIN_GAP = Duration.ofMinutes(30);

    /** 工作区管理器，用于获取智能体使用的文件系统（读写记忆文件、会话日志等）。 */
    private final WorkspaceManager workspaceManager;

    /** 基于大模型的记忆整合器，可为 null（为 null 时跳过记忆整合步骤）。 */
    private final MemoryConsolidator consolidator;

    /** 日常记忆文件（按日期命名的 .md 文件）的保留天数，超过则归档。 */
    private final int dailyFileRetentionDays;

    /** 会话日志文件的保留天数，超过则删除。 */
    private final int sessionRetentionDays;

    /** 两次维护执行之间的最小间隔，用于限流，避免每次智能体调用都触发维护。 */
    private final Duration minGap;

    /** 隔离范围，决定限流窗口按用户、会话还是整个智能体实例划分。 */
    private final IsolationScope isolationScope;

    private final PeriodicGate periodicGate;

    /**
     * 构造维护中间件，隔离范围默认为 {@link IsolationScope#USER}（按用户限流）。
     *
     * @param workspaceManager 工作区管理器，提供文件系统访问能力
     * @param consolidator 记忆整合器，可为 null
     * @param dailyFileRetentionDays 日常记忆文件保留天数
     * @param sessionRetentionDays 会话日志保留天数
     * @param minGap 两次维护之间的最小间隔，为 null 时使用 {@link #DEFAULT_MIN_GAP}
     */
    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                sessionRetentionDays,
                minGap,
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    /**
     * 完整构造器，可显式指定隔离范围。
     *
     * @param isolationScope 隔离范围，为 null 时默认 {@link IsolationScope#USER}
     */
    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap,
            IsolationScope isolationScope) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                sessionRetentionDays,
                minGap,
                isolationScope,
                new LocalPeriodicGate());
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap,
            IsolationScope isolationScope,
            PeriodicGate periodicGate) {
        this.workspaceManager = workspaceManager;
        this.consolidator = consolidator;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.sessionRetentionDays = sessionRetentionDays;
        this.minGap = minGap != null ? minGap : DEFAULT_MIN_GAP;
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
    }

    /**
     * 简化构造器，使用默认保留策略：日常记忆文件保留 90 天、会话日志保留 180 天，
     * 限流间隔使用默认值 {@link #DEFAULT_MIN_GAP}。
     */
    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager, MemoryConsolidator consolidator) {
        this(workspaceManager, consolidator, 90, 180, DEFAULT_MIN_GAP);
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    /**
     * 中间件钩子：包裹智能体调用链。
     *
     * <p>通过 {@code doOnComplete} 在智能体正常事件流（{@code next.apply(input)}）<b>结束后</b>
     * 追加一次维护动作，因此不影响智能体本身的输出事件顺序。维护逻辑：
     * <ul>
     *   <li>维护体（含限流抢占）在 {@code boundedElastic} 调度器上执行，避免阻塞事件流线程；
     *       调度前先登记 {@link MemoryBackgroundTasks}，保证静默检查不会漏看在途任务。</li>
     *   <li>订阅时兜底错误处理，维护失败只记录警告日志，不会让智能体调用报错。</li>
     * </ul>
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        // The maintenance body — including the gate claim, which is remote I/O under a
        // store-backed gate — runs on the background scheduler. Only the in-flight counter is
        // updated synchronously, so a quiescence check can never observe an empty in-flight
        // set before the task is counted.
        return next.apply(input)
                .doOnComplete(
                        () -> {
                            MemoryBackgroundTasks.begin();
                            Mono.defer(() -> doMaintenance(rc))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doFinally(signal -> MemoryBackgroundTasks.end())
                                    .subscribe(
                                            null,
                                            e ->
                                                    log.warn(
                                                            "Memory maintenance failed: {}",
                                                            e.getMessage()));
                        });
    }

    /** 限流判断 + 触发维护。 */
    private Mono<Void> doMaintenance(RuntimeContext rc) {
        // 距上次维护不足 minGap 或 CAS 抢占失败（已有其他线程触发维护），本次跳过
        if (!periodicGate.tryClaim(compositeTimerKey(rc), minGap)) {
            // Throttled out; the in-flight slot acquired at dispatch is released when this
            // Mono completes.
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> runMaintenance(rc));
    }

    /**
     * Builds a composite key from {@link IsolationScope} name and the per-call identity returned
     * by {@link #timerKeyFor(RuntimeContext)}. The operation prefix keeps maintenance independent
     * of flush when both use the same {@link PeriodicGate}. The scope prefix keeps different
     * isolation dimensions from sharing a slot.
     */
    private String compositeTimerKey(RuntimeContext rc) {
        return "memory-maintenance:" + isolationScope.name() + ":" + timerKeyFor(rc);
    }

    /**
     * Derives the per-call identity portion of the composite timer key from the configured
     * {@link IsolationScope} and the {@link RuntimeContext}, mirroring the memory data
     * namespace. See {@link MemoryFlushMiddleware#timerKeyFor(RuntimeContext)} for the
     * identical logic.
     */
    /**
     * 根据配置的 {@link IsolationScope} 和每次调用的 {@link RuntimeContext} 派生限流键，
     * 与记忆数据的命名空间保持镜像一致。相同逻辑参见
     * {@link MemoryFlushMiddleware#timerKeyFor(RuntimeContext)}。
     *
     * <p>取值规则：USER 用 userId、SESSION 用 sessionId、AGENT/GLOBAL 共用空键；
     * 上下文缺失或字段为空时回退为空键（全局共享窗口）。
     */
    String timerKeyFor(RuntimeContext rc) {
        return switch (isolationScope) {
            case USER -> MemoryFlushMiddleware.blankToEmpty(rc != null ? rc.getUserId() : null);
            case SESSION ->
                    MemoryFlushMiddleware.blankToEmpty(rc != null ? rc.getSessionId() : null);
            case AGENT, GLOBAL -> "";
        };
    }

    /**
     * 执行一次完整维护，按固定顺序完成三步：
     * 归档过期日常记忆文件 → LLM 记忆整合 → 清理过期会话日志。
     */
    private void runMaintenance(RuntimeContext rc) {
        log.debug("Running memory maintenance...");
        expireDailyFiles(rc);
        consolidateMemory(rc);
        pruneOldSessions(rc);
        log.debug("Memory maintenance completed");
    }

    /**
     * 第一步：归档过期的日常记忆文件。
     *
     * <p>扫描记忆目录下的 {@code *.md} 文件，日常文件以日期命名（如 {@code 2026-01-01.md}）。
     * 将文件名解析为日期，早于"今天 - 保留天数"截止线的文件移动到
     * {@code memory/archive/} 归档目录。跳过目录、隐藏文件及非日期命名的文件。
     */
    private void expireDailyFiles(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.md", WorkspaceConstants.MEMORY_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        // 归档截止线：早于该日期的文件视为过期
        LocalDate cutoff = LocalDate.now().minusDays(dailyFileRetentionDays);
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String fileName = fileName(fi.path());
            if (fileName.startsWith(".")) {
                continue; // 隐藏文件，不处理
            }
            // 去掉 .md 后缀，剩余部分应为一个日期字符串
            String baseName =
                    fileName.endsWith(".md")
                            ? fileName.substring(0, fileName.length() - 3)
                            : fileName;
            try {
                LocalDate fileDate = LocalDate.parse(baseName);
                if (fileDate.isBefore(cutoff)) {
                    String fromPath = WorkspaceConstants.MEMORY_DIR + "/" + fileName;
                    String toPath = WorkspaceConstants.MEMORY_DIR + "/archive/" + fileName;
                    fs.move(rc, fromPath, toPath);
                    log.debug("Archived expired daily file: {}", fileName);
                }
            } catch (Exception e) {
                // not a date-named file, skip
                // 文件名不是日期格式（如 MEMORY.md 等常驻文件），跳过
            }
        }
    }

    /**
     * 第二步：基于大模型的记忆整合。
     *
     * <p>调用 {@link MemoryConsolidator#consolidate} 将零散的日常记忆归纳整合进长期记忆。
     * 未配置整合器时直接跳过；整合失败仅记录警告日志，不中断后续清理步骤。
     */
    private void consolidateMemory(RuntimeContext rc) {
        if (consolidator == null) {
            return;
        }
        try {
            // 维护整体在 boundedElastic 线程上同步执行，因此这里可以安全阻塞等待
            consolidator.consolidate(rc).block();
        } catch (Exception e) {
            log.warn("Memory consolidation failed: {}", e.getMessage());
        }
    }

    /**
     * 第三步：清理过期的会话日志文件。
     *
     * <p>扫描智能体目录下的 {@code *.log.jsonl} 会话日志，按文件的最后修改时间判断：
     * 早于"当前时间 - 保留天数"的文件直接删除（与日常文件不同，会话日志不做归档）。
     */
    private void pruneOldSessions(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.log.jsonl", WorkspaceConstants.AGENTS_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(sessionRetentionDays));
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String modifiedAt = fi.modifiedAt();
            if (modifiedAt == null || modifiedAt.isEmpty()) {
                continue; // 无修改时间信息，无法判断，跳过
            }
            try {
                Instant modified = Instant.parse(modifiedAt);
                if (modified.isBefore(cutoff)) {
                    fs.delete(rc, fi.path());
                    log.debug("Pruned old session file: {}", fi.path());
                }
            } catch (Exception e) {
                log.warn("Failed to check/prune {}: {}", fi.path(), e.getMessage());
            }
        }
    }

    /**
     * 工具方法：从完整路径中提取文件名（最后一个 {@code /} 之后的部分），
     * 路径为 null 时返回空字符串。
     */
    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
