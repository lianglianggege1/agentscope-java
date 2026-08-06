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
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import io.agentscope.harness.agent.skill.runtime.SkillRuntime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Harness-native skill middleware. Replaces the legacy {@code DynamicSkillMiddleware} subclass
 * with a standalone implementation that owns a {@link SkillRuntime}.
 *
 * <p>Per {@code onSystemPrompt} pass:
 *
 * <ol>
 *   <li>Resolve the current {@link RuntimeContext} from the agent.
 *   <li>Iterate repositories low-to-high priority and merge by {@code AgentSkill.name} (later
 *       wins).
 *   <li>Apply the optional {@link SkillVisibilityFilter}.
 *   <li>Stage Layer-1/Layer-2 marketplace skills' resources into
 *       {@code <wsRoot>/.skills-cache/<source-ns>/<skill>/} via {@link MarketplaceStager}.
 *   <li>Build a {@link SkillCatalog} of {@link HarnessSkillEntry} (with lazy resources and
 *       resolved {@code filesRoot}).
 *   <li>Install the catalog into the {@link SkillRuntime}, which (idempotently) registers the
 *       {@code load_skill_through_path} tool on the agent's runtime toolkit.
 *   <li>Render the {@code <available_skills>} prompt block and append it to the current system
 *       prompt.
 * </ol>
 *
 * <p><b>Toolkit note:</b> the {@code toolkit} constructor parameter is accepted for API
 * compatibility but is <em>not</em> used for runtime tool registration. Instead,
 * {@link #onSystemPrompt} always installs into {@code agent.getToolkit()} so the tool is
 * registered on the toolkit that the running agent actually uses for reasoning. This matters
 * because {@link io.agentscope.harness.agent.HarnessAgent HarnessAgent} makes a deep copy of
 * the toolkit when building the inner {@link io.agentscope.core.ReActAgent ReActAgent}, so the
 * constructor-injected intermediate toolkit is not the same instance as the agent's live toolkit.
 */
/**
 * harness 原生的技能中间件。取代旧的 {@code DynamicSkillMiddleware} 子类方案，
 * 以独立实现持有一个 {@link SkillRuntime}。
 *
 * <p>每次 {@code onSystemPrompt} 执行的完整流水线：
 * <ol>
 *   <li>从智能体解析当前 {@link RuntimeContext}；</li>
 *   <li>按低→高优先级遍历技能仓库，按 {@code AgentSkill.name} 合并（后者覆盖前者）；</li>
 *   <li>应用可选的 {@link SkillVisibilityFilter}（灰度/白名单可见性过滤）；</li>
 *   <li>通过 {@link MarketplaceStager} 把 Layer-1/Layer-2 市场技能的资源暂存到
 *       {@code <wsRoot>/.skills-cache/<source-ns>/<skill>/}；</li>
 *   <li>构建 {@link HarnessSkillEntry} 组成的 {@link SkillCatalog}
 *       （携带懒加载资源与解析后的 {@code filesRoot}）；</li>
 *   <li>把目录安装进 {@link SkillRuntime}，由其（幂等地）向智能体运行时工具集
 *       注册 {@code load_skill_through_path} 工具；</li>
 *   <li>渲染 {@code <available_skills>} 提示块并追加到当前系统提示词。</li>
 * </ol>
 *
 * <p><b>工具集注意点：</b>构造参数 {@code toolkit} 仅为 API 兼容而保留，
 * <em>不用于</em>运行时工具注册。{@link #onSystemPrompt} 始终安装到
 * {@code agent.getToolkit()}——即运行中智能体实际用于推理的工具集。
 * 这很关键：{@link io.agentscope.harness.agent.HarnessAgent HarnessAgent} 在构建内层
 * {@link io.agentscope.core.ReActAgent ReActAgent} 时会对工具集做深拷贝，
 * 构造期注入的中间工具集并不是智能体实际使用的那个实例。
 */
@SuppressWarnings("deprecation")
public class HarnessSkillMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(HarnessSkillMiddleware.class);

    /** 技能仓库列表，按组合顺序排列（低优先级在前，高优先级在后）。 */
    private final List<AgentSkillRepository> repositories;

    /** 仅为 API 兼容保留，运行时注册实际使用 agent.getToolkit()（见类注释）。 */
    private final Toolkit toolkit;

    /** 构建期注入的技能过滤器（静态部分），会与请求级过滤器叠加。 */
    private final SkillFilter builderFilter;

    /** 可选的请求级可见性过滤器（灰度/白名单），可为 null。 */
    private final SkillVisibilityFilter visibilityFilter;

    /** 市场技能资源暂存器；为 null 时完全跳过资源暂存。 */
    private final MarketplaceStager stager;

    /** 按 shell 模式解析技能 {@code <files-root>} 的策略，永不为 null。 */
    private final ShellPathPolicy shellPathPolicy;

    /** 技能运行时：持有当前目录并幂等注册 load_skill_through_path 工具。 */
    private final SkillRuntime runtime;

    /** 构建期预解析的"仓库 → 来源命名空间"映射，供资源暂存按命名空间分目录。 */
    private final Map<AgentSkillRepository, String> sourceNamespaces;

    public HarnessSkillMiddleware(List<AgentSkillRepository> repositories, Toolkit toolkit) {
        this(repositories, toolkit, null, null, null, ShellPathPolicy.noShell());
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories, Toolkit toolkit, SkillFilter builderFilter) {
        this(repositories, toolkit, builderFilter, null, null, ShellPathPolicy.noShell());
    }

    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter) {
        this(
                repositories,
                toolkit,
                builderFilter,
                visibilityFilter,
                null,
                ShellPathPolicy.noShell());
    }

    /**
     * Full constructor.
     *
     * @param repositories     compose-ordered list (low-to-high priority)
     * @param toolkit          accepted for API compatibility; not used for runtime registration
     *                         (see class-level note on toolkit copy semantics)
     * @param builderFilter    skill filter passed at agent build time (may be {@code null})
     * @param visibilityFilter optional per-request filter (canary/allow-list)
     * @param stager           marketplace stager; {@code null} disables staging entirely
     * @param shellPathPolicy  policy for resolving {@code <files-root>} per shell mode; never
     *                         {@code null} — pass {@link ShellPathPolicy#noShell()} when no
     *                         shell tool is registered
     */
    /**
     * 全参构造器。
     *
     * @param repositories     按组合顺序（低→高优先级）排列的仓库列表
     * @param toolkit          仅为 API 兼容保留；不用于运行时注册
     *                         （见类级注释中关于工具集拷贝语义的说明）
     * @param builderFilter    智能体构建期传入的技能过滤器（可为 {@code null}）
     * @param visibilityFilter 可选的请求级过滤器（灰度/白名单）
     * @param stager           市场技能暂存器；{@code null} 表示完全跳过暂存
     * @param shellPathPolicy  按 shell 模式解析 {@code <files-root>} 的策略；永不为
     *                         {@code null}——未注册 shell 工具时传
     *                         {@link ShellPathPolicy#noShell()}
     */
    public HarnessSkillMiddleware(
            List<AgentSkillRepository> repositories,
            Toolkit toolkit,
            SkillFilter builderFilter,
            SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager,
            ShellPathPolicy shellPathPolicy) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.toolkit = toolkit;
        this.builderFilter = builderFilter != null ? builderFilter : SkillFilter.all();
        this.visibilityFilter = visibilityFilter;
        this.stager = stager;
        this.shellPathPolicy =
                shellPathPolicy != null ? shellPathPolicy : ShellPathPolicy.noShell();
        this.runtime = new SkillRuntime();
        // Pre-resolve source namespaces once at build time. The compose order is fixed for
        // the lifetime of the middleware, so this is safe and avoids repeated work per call.
        // 构建期一次性预解析来源命名空间。组合顺序在中间件生命周期内固定不变，
        // 因此预解析是安全的，也避免了每次调用重复计算。
        this.sourceNamespaces = MarketplaceStager.resolveSourceNamespaces(this.repositories);
    }

    /** Visible for tests / introspection. */
    /** 供测试与内省访问：返回技能运行时实例。 */
    public SkillRuntime runtime() {
        return runtime;
    }

    /**
     * 技能装配主流程（每轮系统提示词渲染时执行）：
     * 合并仓库 → 可见性过滤 → 市场资源暂存 → 组装 HarnessSkillEntry 目录
     * → 安装进运行时（注册加载工具）→ 渲染 <available_skills> 提示块追加到系统提示词。
     * 任一环节为空都会安装空目录并原样返回当前提示词。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            ctx = RuntimeContext.empty();
        }

        // 关键：始终取运行中智能体自己的工具集（而非构造期注入的中间工具集）
        Toolkit agentToolkit = agent != null ? agent.getToolkit() : null;

        // 步骤 1：按低→高优先级合并所有仓库的技能（同名后者覆盖前者）
        Map<String, RepoBound> merged = mergeRepositories(ctx);
        if (merged.isEmpty()) {
            runtime.install(SkillCatalog.empty(), agentToolkit);
            return Mono.just(currentPrompt);
        }

        // 步骤 2：应用请求级可见性过滤（灰度/白名单）
        List<RepoBound> visible = applyVisibility(merged.values(), ctx);
        if (visible.isEmpty()) {
            runtime.install(SkillCatalog.empty(), agentToolkit);
            return Mono.just(currentPrompt);
        }

        // 步骤 3：市场技能资源暂存到 .skills-cache（stager 为 null 时跳过）
        Map<String, StageResult> staged =
                stager != null ? stager.stage(visible, sourceNamespaces) : Map.of();

        // 步骤 4：为每个可见技能组装目录条目——
        // 懒加载资源（仓库支持时）+ 按 shell 策略解析的 filesRoot
        List<HarnessSkillEntry> entries = new ArrayList<>(visible.size());
        for (RepoBound bound : visible) {
            SkillResources lazy = null;
            if (bound.repo() instanceof LazyResourceCapable lrc) {
                try {
                    lazy = lrc.resourcesFor(bound.skill().getName(), ctx);
                } catch (Exception e) {
                    log.debug(
                            "resourcesFor({}) failed; continuing without lazy: {}",
                            bound.skill().getName(),
                            e.getMessage());
                }
            }
            StageResult stage = staged.getOrDefault(bound.skill().getName(), StageResult.NONE);
            String filesRoot = shellPathPolicy.resolve(bound.skill().getName(), stage);
            entries.add(new HarnessSkillEntry(bound.skill(), lazy, filesRoot));
        }

        // 步骤 5：安装目录进运行时——幂等注册 load_skill_through_path 工具
        SkillCatalog catalog = SkillCatalog.of(entries);
        runtime.install(catalog, agentToolkit);

        // 步骤 6：渲染 <available_skills> 提示块并追加到系统提示词。
        // 生效过滤器 = 构建期过滤器 overlay 请求级过滤器（RuntimeContext 携带）
        SkillFilter effective =
                builderFilter.overlay(ctx != null ? ctx.get(SkillFilter.class) : null);
        String append = runtime.renderPrompt(catalog, effective);
        if (append == null || append.isEmpty()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return Mono.just(base + separator + append);
    }

    // ---------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    //  内部实现
    // ---------------------------------------------------------------------

    /**
     * Merge skills from every repository, in compose order. Later entries with the same
     * {@code AgentSkill.name} win. Also remembers the source repository per winning skill so
     * subsequent steps (lazy resources, marketplace stage) can act on it.
     */
    /**
     * 按组合顺序合并所有仓库的技能：同名技能（{@code AgentSkill.name}）后者胜出。
     * 同时记录每个胜出技能的来源仓库（RepoBound），
     * 供后续步骤（懒加载资源、市场资源暂存）定位使用。
     * 单个仓库加载失败只记警告并跳过，不影响其他仓库。
     */
    private LinkedHashMap<String, RepoBound> mergeRepositories(RuntimeContext ctx) {
        LinkedHashMap<String, RepoBound> merged = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repositories) {
            List<AgentSkill> skills;
            try {
                skills = repo.getAllSkills();
            } catch (Exception e) {
                log.warn(
                        "Skill repository {} failed to load: {}",
                        repo.getClass().getSimpleName(),
                        e.getMessage());
                continue;
            }
            if (skills == null) {
                continue;
            }
            for (AgentSkill skill : skills) {
                if (skill == null || skill.getName() == null) {
                    continue;
                }
                merged.put(skill.getName(), new RepoBound(skill, repo));
            }
        }
        return merged;
    }

    /**
     * 应用请求级可见性过滤：用 IdentityHashMap 建立技能实例到 RepoBound 的反查，
     * 过滤后再映射回仓库绑定。过滤器抛异常或返回 null 时按全量通过处理（降级不阻断）。
     */
    private List<RepoBound> applyVisibility(
            java.util.Collection<RepoBound> input, RuntimeContext ctx) {
        if (visibilityFilter == null || input.isEmpty()) {
            return new ArrayList<>(input);
        }
        Map<AgentSkill, RepoBound> bySkill = new IdentityHashMap<>();
        List<AgentSkill> raw = new ArrayList<>(input.size());
        for (RepoBound rb : input) {
            bySkill.put(rb.skill(), rb);
            raw.add(rb.skill());
        }
        List<AgentSkill> filtered;
        try {
            filtered = visibilityFilter.filter(raw, ctx);
        } catch (Exception e) {
            log.warn(
                    "SkillVisibilityFilter {} failed; treating as pass-through: {}",
                    visibilityFilter.getClass().getSimpleName(),
                    e.getMessage());
            return new ArrayList<>(input);
        }
        if (filtered == null) {
            return new ArrayList<>(input);
        }
        List<RepoBound> out = new ArrayList<>(filtered.size());
        for (AgentSkill s : filtered) {
            RepoBound rb = bySkill.get(s);
            if (rb != null) {
                out.add(rb);
            }
        }
        return out;
    }
}
