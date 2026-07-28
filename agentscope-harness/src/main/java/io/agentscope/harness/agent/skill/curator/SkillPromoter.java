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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Orchestrates the promotion pipeline: locate draft → run security scan → call gate →
 * physically move {@code _drafts/<name>/} to {@code skills/<name>/} → update sidecar.
 *
 * <p>Surfaced via {@code ReActAgent.promoteSkill(name, reviewerId)}; standalone here so it can
 * be unit tested without spinning up a full agent.
 */
/**
 * 调度晋升流水线：定位草稿技能 → 执行安全扫描 → 调用评审网关 →
 * 将 {@code _drafts/<name>/} 物理迁移至 {@code skills/<name>/} → 更新 Sidecar。
 *
 * <p>通过 {@code ReActAgent.promoteSkill(name, reviewerId)} 对外暴露；
 * 独立封装便于单元测试，无需启动完整代理实例。
 */
@SuppressWarnings("deprecation")
public class SkillPromoter {

    private static final Logger log = LoggerFactory.getLogger(SkillPromoter.class);

    private final WorkspaceSkillRepository draftsRepo;
    private final WorkspaceSkillRepository mainRepo;
    private final WorkspaceManager workspaceManager;
    private final SkillUsageStore usageStore;
    private final SkillPromotionGate gate;
    private final String draftsDir;
    private final String mainDir;
    private final SkillAuditLog auditLog;

    public SkillPromoter(
            WorkspaceSkillRepository draftsRepo,
            WorkspaceSkillRepository mainRepo,
            WorkspaceManager workspaceManager,
            SkillUsageStore usageStore,
            SkillPromotionGate gate,
            String draftsDir,
            String mainDir) {
        this(draftsRepo, mainRepo, workspaceManager, usageStore, gate, draftsDir, mainDir, null);
    }

    public SkillPromoter(
            WorkspaceSkillRepository draftsRepo,
            WorkspaceSkillRepository mainRepo,
            WorkspaceManager workspaceManager,
            SkillUsageStore usageStore,
            SkillPromotionGate gate,
            String draftsDir,
            String mainDir,
            SkillAuditLog auditLog) {
        this.draftsRepo = java.util.Objects.requireNonNull(draftsRepo, "draftsRepo");
        this.mainRepo = java.util.Objects.requireNonNull(mainRepo, "mainRepo");
        this.workspaceManager = workspaceManager;
        this.usageStore = usageStore;
        this.gate = gate != null ? gate : new RejectAllGate();
        this.draftsDir = draftsDir != null ? draftsDir : "skills/_drafts";
        this.mainDir = mainDir != null ? mainDir : "skills";
        this.auditLog = auditLog;
    }

    /**
     * Promote a draft. Returns a result describing what happened.
     *
     * @param name skill name (must exist under the drafts repo)
     * @param reviewerId tag stamped onto the sidecar's {@code promoted_by}
     * @param ctx runtime context (forwarded to gate / filesystem)
     */
    /**
     * 晋升一份草稿技能，并返回描述执行结果的对象。
     *
     * @param name 技能名称（草稿仓库下必须存在该技能）
     * @param reviewerId 将写入Sidecar {@code promoted_by} 字段的标识
     * @param ctx 运行时上下文（透传给评审网关与文件操作模块）
     */
    public Mono<PromotionResult> promote(String name, String reviewerId, RuntimeContext ctx) {
        if (name == null || name.isBlank()) {
            return Mono.just(PromotionResult.invalid("name is required"));
        }
        AgentSkill draft;
        try {
            draft = draftsRepo.getSkill(name);
        } catch (Exception e) {
            return Mono.just(PromotionResult.invalid("failed to load draft: " + e.getMessage()));
        }
        if (draft == null) {
            return Mono.just(PromotionResult.invalid("draft '" + name + "' not found"));
        }

        // 1. Security scan (always — even when SkillManageTool already scanned, this is the
        //    last gate before going live). Pull resources off disk because the repository's
        //    {@code getSkill(name)} only loads SKILL.md.
        // 1. 安全扫描（必执行，即便 SkillManageTool 已完成扫描，这也是上线前最后一道关卡）。
        //    直接从磁盘读取资源文件，仓库的 {@code getSkill(name)} 仅加载 SKILL.md。
        java.util.Map<String, String> resources = loadDraftResources(name);
        SkillSecurityScanner.ScanResult scan =
                SkillSecurityScanner.scan(name, mdOf(draft), resources);
        if (!SkillSecurityScanner.shouldAllow(
                SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
            return Mono.just(
                    PromotionResult.rejected(
                            "security scan blocked promotion (" + scan.verdict() + ")", scan));
        }

        // 2. Build candidate package and call the gate.
        // 2. 构建候选技能包并调用评审网关。
        SkillCandidate candidate = buildCandidate(draft, scan);
        return gate.review(candidate, ctx)
                .map(decision -> applyDecision(name, reviewerId, decision, scan));
    }

    private PromotionResult applyDecision(
            String name,
            String reviewerId,
            SkillPromotionGate.PromotionDecision decision,
            SkillSecurityScanner.ScanResult scan) {
        if (decision instanceof SkillPromotionGate.PromotionDecision.Reject reject) {
            return PromotionResult.rejected(
                    "gate rejected: " + reject.reason() + " (by " + reject.reviewerId() + ")",
                    scan);
        }
        if (decision instanceof SkillPromotionGate.PromotionDecision.Defer defer) {
            return PromotionResult.deferred(defer.reason(), defer.retryAfter());
        }
        if (!(decision instanceof SkillPromotionGate.PromotionDecision.Approve approve)) {
            return PromotionResult.invalid("unknown decision type");
        }

        // 3. Physically move _drafts/<name>/ → <mainDir>/<name>/
        // 3. 将目录物理迁移：_drafts/<name>/ → <mainDir>/<name>/
        String src = draftsDir + "/" + name;
        String dst = mainDir + "/" + name;
        if (workspaceManager == null) {
            return PromotionResult.invalid("workspaceManager is null; cannot move directory");
        }
        boolean moved = workspaceManager.moveSkill(RuntimeContext.empty(), src, dst);
        if (!moved) {
            return PromotionResult.invalid("failed to move draft directory");
        }

        // 4. Update sidecar with promoted state + reviewer info.
        // 4. 更新Sidecar，写入已晋升状态与审核人信息。
        if (usageStore != null) {
            try {
                List<String> envs = approve.targetEnvironments();
                if (envs == null || envs.isEmpty()) {
                    envs = List.of("prod");
                }
                usageStore.markAgentCreated(name, reviewerId, envs);
            } catch (Exception e) {
                log.warn("Failed to stamp sidecar after promote: {}", e.getMessage());
            }
        }

        PromotionResult ok =
                PromotionResult.approved(
                        approve.reviewerId(),
                        approve.targetEnvironments() != null
                                ? approve.targetEnvironments()
                                : List.of("prod"),
                        scan);
        if (auditLog != null) {
            auditLog.append(
                    SkillAuditLog.promoteEntry(
                            approve.reviewerId(),
                            name,
                            "Approve",
                            scan.verdict().name(),
                            ok.environments()));
        }
        return ok;
    }

    private SkillCandidate buildCandidate(AgentSkill skill, SkillSecurityScanner.ScanResult scan) {
        List<String> supportPaths = new ArrayList<>();
        List<SkillCandidate.ScriptFilePreview> scripts = new ArrayList<>();
        if (skill.getResources() != null) {
            for (var e : skill.getResources().entrySet()) {
                String path = e.getKey();
                String body = e.getValue();
                if (path == null || body == null) {
                    continue;
                }
                supportPaths.add(path);
                if (path.startsWith("scripts/")) {
                    scripts.add(buildScriptPreview(path, body));
                }
            }
        }
        SkillUsageRecord usage =
                usageStore != null ? usageStore.get(skill.getName()).orElse(null) : null;
        return new SkillCandidate(
                skill.getName(),
                skill.getDescription(),
                mdOf(skill),
                supportPaths,
                usage,
                scan,
                scripts);
    }

    private static SkillCandidate.ScriptFilePreview buildScriptPreview(String path, String body) {
        String[] lines = body.split("\n", -1);
        int n = Math.min(40, lines.length);
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < n; i++) {
            head.append(lines[i]).append(i + 1 < n ? "\n" : "");
        }
        String sha;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            sha = hex.toString();
        } catch (Exception e) {
            sha = "n/a";
        }
        return new SkillCandidate.ScriptFilePreview(path, head.toString(), lines.length, sha);
    }

    /**
     * Read every support file under the draft skill (scripts/, references/, templates/,
     * assets/) so the security scanner sees the full payload — the repository's
     * {@code getSkill} only deserialises SKILL.md.
     */
    /**
     * 读取草稿技能下所有配套文件（scripts/、references/、templates/、assets/），
     * 使安全扫描器能够获取完整负载；仓库提供的 {@code getSkill} 仅会反序列化 SKILL.md。
     */
    private java.util.Map<String, String> loadDraftResources(String skillName) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String sub : new String[] {"scripts", "references", "templates", "assets"}) {
            // Read each known support directory under <draftsDir>/<name>/<sub>/.
            // We can't directly enumerate via the read-only repo API, so we ask the underlying
            // filesystem for a glob match scoped to that subdirectory.
            // 读取 <draftsDir>/<name>/<sub>/ 下所有已知配套目录。
            // 无法通过只读仓库API直接枚举文件，因此调用底层文件系统，在该子目录范围内执行通配匹配。
            String relDir = draftsDir + "/" + skillName + "/" + sub;
            try {
                var glob =
                        draftsRepo
                                .filesystem()
                                .glob(io.agentscope.core.agent.RuntimeContext.empty(), "*", relDir);
                if (!glob.isSuccess() || glob.matches() == null) {
                    continue;
                }
                for (var fi : glob.matches()) {
                    String path = fi.path();
                    if (path == null) continue;
                    // Reduce to "<sub>/<filename>"; strip everything before "<sub>/".
                    // Normalize separator first — glob can return backslashes on Windows.
                    String pathSlash = path.replace('\\', '/');
                    int idx = pathSlash.indexOf("/" + sub + "/");
                    if (idx < 0) continue;
                    String relPath = pathSlash.substring(idx + 1);
                    var rr =
                            draftsRepo
                                    .filesystem()
                                    .read(
                                            io.agentscope.core.agent.RuntimeContext.empty(),
                                            path,
                                            0,
                                            0);
                    if (rr.isSuccess() && rr.fileData() != null) {
                        out.put(relPath, rr.fileData().content());
                    }
                }
            } catch (Exception e) {
                log.debug("loadDraftResources({}, {}) failed: {}", skillName, sub, e.getMessage());
            }
        }
        return out;
    }

    private static String mdOf(AgentSkill skill) {
        // Use the same reverse-serialization as WorkspaceSkillRepository.toMarkdown,
        // but called via reflection through the public skill content. For simplicity here we
        // just stitch frontmatter + content (good enough for the candidate payload that
        // reviewers see).
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.getName()).append('\n');
        sb.append("description: ").append(skill.getDescription()).append('\n');
        sb.append("---\n");
        if (skill.getSkillContent() != null) {
            sb.append(skill.getSkillContent());
        }
        return sb.toString();
    }

    // ---- result type ---------------------------------------------------

    public record PromotionResult(
            Status status,
            String message,
            List<String> environments,
            String reviewerId,
            SkillSecurityScanner.ScanResult scan) {

        public enum Status {
            APPROVED,
            DEFERRED,
            REJECTED,
            INVALID
        }

        public static PromotionResult approved(
                String reviewerId,
                List<String> environments,
                SkillSecurityScanner.ScanResult scan) {
            return new PromotionResult(Status.APPROVED, "approved", environments, reviewerId, scan);
        }

        public static PromotionResult deferred(String reason, java.time.Duration retryAfter) {
            return new PromotionResult(
                    Status.DEFERRED,
                    reason + " (retry after " + retryAfter + ")",
                    List.of(),
                    null,
                    null);
        }

        public static PromotionResult rejected(
                String reason, SkillSecurityScanner.ScanResult scan) {
            return new PromotionResult(Status.REJECTED, reason, List.of(), null, scan);
        }

        public static PromotionResult invalid(String reason) {
            return new PromotionResult(Status.INVALID, reason, List.of(), null, null);
        }
    }
}
