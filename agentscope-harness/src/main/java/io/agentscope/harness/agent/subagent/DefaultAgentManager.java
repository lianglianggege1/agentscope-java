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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pure agent factory and invoker — knows how to create agents from registered factories and invoke
 * them with a prompt.
 *
 * <p>This is the <em>agent-internal</em> layer. It has <strong>no</strong> session registry, no lane
 * management, no run tracking. The
 * agent-internal {@link AgentSpawnTool} uses this directly for
 * lightweight subagent invocation.
 */
/**
 * 纯智能体工厂与调用器：负责通过已注册工厂创建智能体，并传入提示词执行调用。
 *
 * <p>该组件属于智能体内部底层能力层，不具备会话注册表、调度通道管理、任务运行追踪能力。
 * 内部工具 {@link AgentSpawnTool} 直接依赖本组件实现轻量子智能体调用。
 */
public final class DefaultAgentManager {

    private volatile Map<String, SubagentFactory> agentFactories;
    private volatile Map<String, SubagentDeclaration> declarations;
    private final WorkspaceManager workspaceManager;

    /**
     * Builds a manager from subagent entries (factories plus optional {@link SubagentDeclaration}
     * metadata for remote configuration).
     */
    /**
     * 根据子智能体条目构建管理器（条目包含工厂实例，以及可选的远程配置元数据 {@link SubagentDeclaration}）。
     */
    public DefaultAgentManager(List<SubagentEntry> entries, WorkspaceManager workspaceManager) {
        Map<String, SubagentFactory> factories = new HashMap<>();
        Map<String, SubagentDeclaration> decls = new HashMap<>();
        for (SubagentEntry e : entries) {
            factories.put(e.name(), e.factory());
            if (e.declaration() != null) {
                decls.put(e.name(), e.declaration());
            }
        }
        this.agentFactories = Map.copyOf(factories);
        this.declarations = Map.copyOf(decls);
        this.workspaceManager = workspaceManager;
    }

    /**
     * Replaces the current set of entries with a new snapshot. Called per-call from
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware} to reflect per-user subagent
     * configurations.
     */
    /**
     * 以原子方式替换当前的条目集为新快照。由 {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware}
     * 在每次调用时根据用户配置刷新子智能体配置。
     */
    public void refreshEntries(List<SubagentEntry> entries) {
        Map<String, SubagentFactory> factories = new HashMap<>();
        Map<String, SubagentDeclaration> decls = new HashMap<>();
        for (SubagentEntry e : entries) {
            factories.put(e.name(), e.factory());
            if (e.declaration() != null) {
                decls.put(e.name(), e.declaration());
            }
        }
        this.agentFactories = Map.copyOf(factories);
        this.declarations = Map.copyOf(decls);
    }

    /**
     * Atomic alias of {@link #refreshEntries(List)} used by
     * {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware} to swap the registered
     * subagent set on every reasoning step. The two volatile reference assignments below ensure
     * any concurrent reader observes either the previous snapshot or the new one fully — never a
     * partial state.
     */
    /**
     * {@link #refreshEntries(List)} 的原子别名方法，由
     * {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware} 调用，用于在每轮推理步骤替换已注册子智能体集合。
     * 下方两处 volatile 引用赋值保证所有并发读取线程只会读到完整旧快照或完整新快照，不会出现中间残缺状态。
     */
    public void replaceAgents(List<SubagentEntry> entries) {
        refreshEntries(entries);
    }

    /**
     * Race-safe lookup-and-create. Returns {@link Optional#empty()} when no factory is registered
     * for {@code agentId} at the moment of the volatile read, when the registered declaration is
     * {@link SubagentDeclaration.Mode#PRIMARY}-only (cannot be spawned as a subagent), otherwise
     * returns a freshly created agent. Preferred over the two-step {@link #hasAgent(String)} +
     * {@link #createAgent(String, RuntimeContext)} pair when the registry may be replaced
     * concurrently (e.g. dynamic reload between calls).
     *
     * <p>{@code parentRc} is forwarded to {@link SubagentFactory#create(RuntimeContext)} so child
     * agents can bucket their persisted state by parent identity. Pass
     * {@link RuntimeContext#empty()} when no parent context is available.
     */
    /**
     * 线程安全的查询并创建复合操作。
     * 若volatile读取时不存在对应agentId的工厂、或注册配置仅为顶层主智能体模式{@link SubagentDeclaration.Mode#PRIMARY}（不可作为子智能体派生），
     * 返回{@link Optional#empty()}；其余场景返回全新创建的智能体实例。
     * 当注册表存在并发替换场景（如调用间隙动态重载）时，优先使用本方法，而非分步组合 {@link #hasAgent(String)} + {@link #createAgent(String, RuntimeContext)}。
     *
     * <p>{@code parentRc} 会透传给 {@link SubagentFactory#create(RuntimeContext)}，使子智能体可依据父标识隔离持久化状态；无父上下文时传入 {@link RuntimeContext#empty()}。
     */
    public Optional<Agent> createAgentIfPresent(String agentId, RuntimeContext parentRc) {
        if (agentId == null) {
            return Optional.empty();
        }
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            return Optional.empty();
        }
        SubagentDeclaration decl = declarations.get(agentId);
        if (decl != null && decl.getMode() == SubagentDeclaration.Mode.PRIMARY) {
            return Optional.empty();
        }
        return Optional.of(factory.create(parentRc != null ? parentRc : RuntimeContext.empty()));
    }

    /**
     * Returns {@code true} when {@code agentId} is registered with a {@code PRIMARY}-only
     * declaration. Lets {@link io.agentscope.harness.agent.tool.AgentSpawnTool} produce a more
     * helpful error message ("PRIMARY-only, cannot be spawned") instead of the generic
     * "Unknown agent_id" when {@link #createAgentIfPresent} returns empty.
     */
    /**
     * 若传入agentId对应的注册配置仅为主智能体模式PRIMARY，则返回{@code true}。
     * 当{@link #createAgentIfPresent}返回空值时，供{@link io.agentscope.harness.agent.tool.AgentSpawnTool}输出精准错误提示（提示“仅为主智能体，无法派生创建”），而非笼统的“未知agent_id”报错。
     */
    public boolean isPrimaryOnly(String agentId) {
        if (agentId == null) return false;
        SubagentDeclaration decl = declarations.get(agentId);
        return decl != null && decl.getMode() == SubagentDeclaration.Mode.PRIMARY;
    }

    /** Whether a factory is registered for the given agent id. */
    /** 判断指定智能体ID是否已注册对应工厂实例。 */
    public boolean hasAgent(String agentId) {
        return agentId != null && agentFactories.containsKey(agentId);
    }

    /** Immutable view of registered subagent factories keyed by {@code agent_id}. */
    /** 以 {@code agent_id} 为键、存放已注册子智能体工厂的不可变视图。 */
    public Map<String, SubagentFactory> getAgentFactories() {
        return agentFactories;
    }

    /** Optional declaration metadata for the given {@code agent_id} (e.g. remote URL). */
    /** 可选的声明元数据，包含远程URL等信息。 */
    public Optional<SubagentDeclaration> getDeclaration(String agentId) {
        return Optional.ofNullable(declarations.get(agentId));
    }

    /**
     * Creates a new agent instance from the registered factory.
     *
     * @throws IllegalArgumentException if no factory is registered for the given id
     */
    /**
     * 通过已注册工厂创建全新智能体实例。
     *
     * @throws IllegalArgumentException 若指定ID未注册对应工厂
     */
    public Agent createAgent(String agentId, RuntimeContext parentRc) {
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown agent_id: " + agentId);
        }
        return factory.create(parentRc != null ? parentRc : RuntimeContext.empty());
    }

    /**
     * Invokes an agent with a user prompt. Handles both plain {@link Agent} and {@link
     * HarnessAgent} (injects {@link RuntimeContext} for the latter).
     *
     * <p>For {@link HarnessAgent} children, {@code userId} is propagated so that isolation-key
     * resolution (e.g. {@code USER}-scoped sandbox slots) works correctly. A fresh {@code
     * sessionId} is always assigned independently of the parent session.
     *
     * @param agent the agent to invoke
     * @param sessionId a new, child-specific session id
     * @param userId the parent's user-id (may be {@code null})
     * @param prompt the user message to send
     */
    /**
     * 传入用户提示词调用智能体。同时兼容普通 {@link Agent} 与 {@link HarnessAgent} 类型（后者会自动注入 {@link RuntimeContext}）。
     *
     * <p>针对 {@link HarnessAgent} 子实例，会透传 {@code userId}，保证隔离标识（如用户级沙箱资源）解析逻辑正常；
     * 子智能体始终分配独立全新的 {@code sessionId}，与父会话互不干扰。
     *
     * @param agent 待调用的智能体实例
     * @param sessionId 专属子会话的全新会话标识
     * @param userId 父级所属用户ID，允许传入 {@code null}
     * @param prompt 待发送的用户输入提示词
     */
    public Mono<Msg> invokeAgent(Agent agent, String sessionId, String userId, String prompt) {
        return invokeAgent(agent, sessionId, userId, prompt, null);
    }

    public Mono<Msg> invokeAgent(
            Agent agent, String sessionId, String userId, String prompt, RuntimeContext parentRc) {
        RuntimeContext ctx =
                parentRc != null
                        ? RuntimeContext.builder(parentRc)
                                .sessionId(sessionId)
                                .userId(userId)
                                .build()
                        : RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        if (agent instanceof ReActAgent react) {
            return react.call(List.of(userMessage(prompt)), ctx);
        }
        if (agent instanceof HarnessAgent harness) {
            return harness.call(userMessage(prompt), ctx);
        }
        return agent.call(List.of(userMessage(prompt)));
    }

    /**
     * Invokes an agent and returns its execution as a tagged {@link Flux} of {@link Event}s.
     *
     * <p>Every event in the returned flux carries an {@link EventSource} built from {@code source}
     * combined with the child's {@code agentId}/{@code sessionId}. This allows parent consumers
     * to identify which subagent emitted each event without out-of-band metadata.
     *
     * <p>The {@code parentSource} argument should be the {@link EventSource} already stored in the
     * parent's Reactor Context (if any). When the parent itself is a subagent, its path is used as
     * the prefix so the full call-hierarchy path is preserved across multiple nesting levels.
     *
     * @param agent the agent to invoke
     * @param sessionId a new, child-specific session id
     * @param userId the parent's user-id (may be {@code null})
     * @param prompt the user message to send
     * @param source the {@link EventSource} that will be stamped onto every emitted event
     * @param options stream configuration passed to the child agent
     * @return {@link Flux} of tagged events; never null
     */
    /**
     * 执行智能体调用，返回携带标记、由 {@link Event} 组成的响应流 {@link Flux}。
     *
     * <p>返回流中的每一条事件都会生成 {@link EventSource}：由入参 {@code source} 拼接子智能体的 {@code agentId}、{@code sessionId} 构成。
     * 上层消费方可直接通过该来源标识区分每条事件所属子智能体，无需额外传递元数据。
     *
     * <p>入参 {@code parentSource} 应取自父智能体 Reactor 上下文内已存储的 {@link EventSource}（如有）。
     * 若父智能体本身也是子智能体，则会将其父调用路径作为前缀拼接，多层嵌套场景下可完整保留全调用层级链路。
     *
     * @param agent 待执行调用的智能体实例
     * @param sessionId 专属子会话的全新会话标识
     * @param userId 父级用户ID，允许传 {@code null}
     * @param prompt 待下发的用户提示消息
     * @param source 用于标记所有输出事件的事件来源对象
     * @param options 传递给子智能体的流式配置参数
     * @return 带标记事件的响应流 {@link Flux}，永不为 null
     */
    public Flux<Event> invokeAgentStream(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            EventSource source,
            StreamOptions options) {
        return invokeAgentStream(agent, sessionId, userId, prompt, source, options, null);
    }

    public Flux<Event> invokeAgentStream(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            EventSource source,
            StreamOptions options,
            RuntimeContext parentRc) {
        Flux<Event> childFlux;
        StreamOptions effective = options != null ? options : StreamOptions.defaults();
        RuntimeContext ctx =
                parentRc != null
                        ? RuntimeContext.builder(parentRc)
                                .sessionId(sessionId)
                                .userId(userId)
                                .build()
                        : RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        if (agent instanceof ReActAgent react) {
            childFlux = react.stream(List.of(userMessage(prompt)), effective, ctx);
        } else if (agent instanceof HarnessAgent harness) {
            childFlux = harness.stream(List.of(userMessage(prompt)), effective, ctx);
        } else {
            childFlux = agent.stream(List.of(userMessage(prompt)), effective);
        }
        return childFlux.map(event -> event.withSource(source));
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    private static Msg userMessage(String prompt) {
        return Msg.builder().role(MsgRole.USER).textContent(prompt).build();
    }
}
