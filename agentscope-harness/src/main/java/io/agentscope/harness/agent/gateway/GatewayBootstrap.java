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

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextResolver;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Multi-agent + channel routing bootstrap. The primary user-facing entry point for building a
 * gateway-managed agent system.
 *
 * <h2>Minimal usage — single agent + ChatUI</h2>
 *
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder()
 *     .model(model).name("assistant").sysPrompt("You are helpful.")
 *     .build();
 *
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel();
 * Msg reply = chat.send("hello").block();
 * }</pre>
 *
 * <h2>Multi-agent + binding routing</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("sales", salesAgent)
 *     .agent("support", supportAgent)
 *     .mainAgent("sales")
 *     .build();
 *
 * ChannelConfig config = ChannelConfig.builder("chatui")
 *     .dmScope(DmScope.PER_PEER)
 *     .binding(ChannelBinding.forPeer("direct:vip-user-1", "support"))
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel(config);
 * chat.send("vip-user-1", "help me").block();  // routes to support agent
 * chat.send("normal-user", "hi").block();      // routes to sales (default)
 * }</pre>
 *
 * <h2>External channels</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .channel(mySlackChannel)
 *     .build();
 *
 * gw.start();   // init + start all channels
 * gw.stop();    // stop all channels
 * }</pre>
 */
/**
 * 多智能体 + 通道路由引导类。构建网关托管智能体系统的主要用户入口。
 *
 * <h2>最小用法 —— 单智能体 + ChatUI</h2>
 *
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder()
 *     .model(model).name("assistant").sysPrompt("You are helpful.")
 *     .build();
 *
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel();
 * Msg reply = chat.send("hello").block();
 * }</pre>
 *
 * <h2>多智能体 + 绑定路由</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("sales", salesAgent)
 *     .agent("support", supportAgent)
 *     .mainAgent("sales")
 *     .build();
 *
 * ChannelConfig config = ChannelConfig.builder("chatui")
 *     .dmScope(DmScope.PER_PEER)
 *     .binding(ChannelBinding.forPeer("direct:vip-user-1", "support"))
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel(config);
 * chat.send("vip-user-1", "help me").block();  // 路由到 support 智能体
 * chat.send("normal-user", "hi").block();      // 路由到 sales（默认）
 * }</pre>
 *
 * <h2>外部通道</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .channel(mySlackChannel)
 *     .build();
 *
 * gw.start();   // 初始化并启动所有通道
 * gw.stop();    // 停止所有通道
 * }</pre>
 */
public final class GatewayBootstrap {

    private final HarnessGateway gateway;
    private final ChannelManager channelManager;
    private final Map<String, HarnessAgent> agents;
    private final String mainAgentId;

    private GatewayBootstrap(
            HarnessGateway gateway,
            ChannelManager channelManager,
            Map<String, HarnessAgent> agents,
            String mainAgentId) {
        this.gateway = gateway;
        this.channelManager = channelManager;
        this.agents = Collections.unmodifiableMap(agents);
        this.mainAgentId = mainAgentId;
    }

    /** Returns a new builder. */
    /** 返回一个新的构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** The underlying gateway (advanced usage). */
    /** 底层网关（高级用法）。 */
    public HarnessGateway gateway() {
        return gateway;
    }

    /** The channel manager (advanced usage). */
    /** 通道管理器（高级用法）。 */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /** The registered agents keyed by id. */
    /** 以 ID 为键的已注册智能体映射。 */
    public Map<String, HarnessAgent> agents() {
        return agents;
    }

    /** The main agent id. */
    /** 主智能体 ID。 */
    public String mainAgentId() {
        return mainAgentId;
    }

    /**
     * Returns a {@link SubagentGatewayBridge} that exposes subagents as user-addressable threads
     * within this gateway. Pass this to
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#setGatewayBridge} to enable
     * the {@code expose_to_user} parameter on {@code agent_spawn}.
     */
    /**
     * 返回一个 {@link SubagentGatewayBridge}，在本网关内把子智能体暴露为
     * 用户可寻址的话题。把它传给
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#setGatewayBridge}
     * 即可启用 {@code agent_spawn} 的 {@code expose_to_user} 参数。
     */
    public SubagentGatewayBridge gatewayBridge() {
        return (agentId, sessionId, agent, replyTo) -> {
            String subagentId = gateway.exposeSubagent(agentId, sessionId, agent, replyTo);
            return new SubagentGatewayBridge.ExposeResult(subagentId);
        };
    }

    /**
     * Returns a {@link ChatUiChannel} with default DmScope.MAIN config, pre-wired to this
     * gateway. All conversations share a single session.
     */
    /**
     * 返回使用默认 {@link DmScope#MAIN} 配置、已接入本网关的 {@link ChatUiChannel}。
     * 所有会话共享同一个会话。
     */
    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(gateway);
    }

    /**
     * Returns a {@link ChatUiChannel} with custom routing config, pre-wired to this gateway.
     * Use to configure DmScope, bindings, or default agent overrides.
     */
    /**
     * 返回使用自定义路由配置、已接入本网关的 {@link ChatUiChannel}。
     * 用于配置 DmScope、绑定规则或默认智能体覆盖。
     */
    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(gateway, config);
    }

    /**
     * Initializes and starts all pre-registered channels (injecting the gateway into each).
     * Call this after build() when using external channels (Slack, Telegram, etc.).
     */
    /**
     * 初始化并启动所有预注册的通道（把网关注入每个通道）。
     * 使用外部通道（Slack、Telegram 等）时在 build() 之后调用。
     */
    public GatewayBootstrap start() {
        channelManager.initAll(gateway);
        channelManager.startAll();
        return this;
    }

    /** Stops all channels and releases resources. */
    /** 停止所有通道并释放资源。 */
    public void stop() {
        channelManager.stopAll();
    }

    // =========================================================================
    //  Builder
    // =========================================================================

    public static final class Builder {

        private final LinkedHashMap<String, HarnessAgent> agents = new LinkedHashMap<>();
        private String mainAgentId;
        private final List<Channel> channels = new ArrayList<>();
        private Consumer<HarnessAgent.Builder> agentCustomizer;
        private DistributedStore distributedStore;
        private ChannelRuntimeContextResolver runtimeContextResolver;

        private Builder() {}

        /** Registers a pre-built agent under the given id. */
        /** 以指定 ID 注册一个已构建好的智能体。 */
        public Builder agent(String id, HarnessAgent agent) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(agent, "agent");
            agents.put(id, agent);
            return this;
        }

        /**
         * Registers an agent declared via a builder lambda. The lambda receives a
         * {@link HarnessAgent.Builder} pre-configured with any global customizer.
         */
        /**
         * 通过构建器 lambda 声明并注册智能体。lambda 接收一个已套用
         * 全局定制器（若有）的 {@link HarnessAgent.Builder}。
         */
        public Builder agent(String id, Consumer<HarnessAgent.Builder> configurator) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configurator, "configurator");
            HarnessAgent.Builder b = HarnessAgent.builder();
            if (agentCustomizer != null) {
                agentCustomizer.accept(b);
            }
            configurator.accept(b);
            agents.put(id, b.build());
            return this;
        }

        /**
         * Sets the main agent id (used as the routing fallback). If not called, the first
         * registered agent becomes the main agent.
         */
        /**
         * 设置主智能体 ID（作为路由回退）。未调用时，
         * 第一个注册的智能体成为主智能体。
         */
        public Builder mainAgent(String id) {
            this.mainAgentId = id;
            return this;
        }

        /** Registers one or more external channels for gateway management. */
        /** 注册一个或多个外部通道，交由网关统一管理生命周期。 */
        public Builder channel(Channel... channels) {
            for (Channel ch : channels) {
                this.channels.add(Objects.requireNonNull(ch, "channel"));
            }
            return this;
        }

        /**
         * Applies a customizer to every agent builder created via the lambda-based
         * {@link #agent(String, Consumer)} method. Useful for setting a shared model or workspace.
         */
        /**
         * 对所有通过 lambda 方式 {@link #agent(String, Consumer)} 创建的智能体构建器
         * 应用同一个定制器。适合统一设置共享模型或工作空间。
         */
        public Builder configureAllAgents(Consumer<HarnessAgent.Builder> customizer) {
            this.agentCustomizer = customizer;
            return this;
        }

        /**
         * Sets the distributed store used to build a durable
         * {@link SubagentRegistry}, making subagents exposed via {@code expose_to_user} resolvable
         * and re-materializable across nodes / restarts. When not set, the main agent's own
         * {@code distributedStore} (if any) is used as a fallback; otherwise exposure stays
         * in-process.
         */
        /**
         * 设置用于构建持久化 {@link SubagentRegistry} 的分布式存储，
         * 使通过 {@code expose_to_user} 暴露的子智能体可跨节点/跨重启解析与重建。
         * 未设置时，回退使用主智能体自身的 {@code distributedStore}（若有）；
         * 否则暴露仅保留在进程内。
         */
        public Builder distributedStore(DistributedStore store) {
            this.distributedStore = store;
            return this;
        }

        /**
         * Sets a {@link ChannelRuntimeContextResolver} that supplies / replaces caller {@code
         * RuntimeContext} on each Gateway turn before identity fields are applied. Useful for
         * attaching tenant / auth / tool dependencies from the surrounding request.
         */
        public Builder runtimeContextResolver(ChannelRuntimeContextResolver resolver) {
            this.runtimeContextResolver = resolver;
            return this;
        }

        /**
         * Builds the gateway bootstrap. At least one agent must be registered.
         *
         * @throws IllegalStateException if no agents are registered
         */
        /**
         * 构建网关引导对象。至少需要注册一个智能体。
         *
         * <p>装配流程：解析主智能体 ID（未指定取第一个）→ 创建 ChannelManager
         * 并注册外部通道 → 创建 HarnessGateway 并绑定/注册全部智能体 →
         * 装配暴露子智能体的恢复链路（组合式 Materializer + 可选持久化注册表）。
         *
         * @throws IllegalStateException 未注册任何智能体时抛出
         */
        public GatewayBootstrap build() {
            if (agents.isEmpty()) {
                throw new IllegalStateException(
                        "At least one agent must be registered via agent(...)");
            }

            String resolvedMainId = mainAgentId;
            if (resolvedMainId == null) {
                resolvedMainId = agents.keySet().iterator().next();
            }
            if (!agents.containsKey(resolvedMainId)) {
                throw new IllegalStateException(
                        "mainAgent('" + resolvedMainId + "') not found in registered agents");
            }

            ChannelManager cm = new ChannelManager();
            for (Channel ch : channels) {
                cm.register(ch);
            }

            HarnessGateway gw = HarnessGateway.create(cm);
            if (runtimeContextResolver != null) {
                gw.setRuntimeContextResolver(runtimeContextResolver);
            }

            // Register all agents, bind the main one
            // 注册所有智能体，并绑定主智能体
            HarnessAgent mainHa = agents.get(resolvedMainId);
            gw.bindMainAgent(mainHa);
            for (Map.Entry<String, HarnessAgent> entry : agents.entrySet()) {
                if (!entry.getKey().equals(resolvedMainId)) {
                    gw.registerAgent(entry.getKey(), entry.getValue());
                }
            }

            // ---- Exposed-subagent recovery wiring (cross-node / post-restart) ----
            // A composite materializer rebuilds a subagent on any node by trying each registered
            // agent's manager in turn; a durable registry (when a distributed store is present)
            // makes the subagentId resolvable beyond this process. Without these, exposure stays
            // in-process (legacy behaviour).
            // ---- 已暴露子智能体的恢复装配（跨节点/重启后）----
            // 组合式 Materializer 会依次尝试每个已注册智能体的管理器来在任意节点重建子智能体；
            // 持久化注册表（存在分布式存储时）使 subagentId 在本进程之外也可解析。
            // 缺少这些装配时，暴露仅保留在进程内（旧版行为）。
            List<DefaultAgentManager> managers =
                    agents.values().stream()
                            .map(HarnessAgent::getSubagentAgentManager)
                            .filter(Objects::nonNull)
                            .toList();
            if (!managers.isEmpty()) {
                gw.setSubagentMaterializer(
                        (agentId, rc) -> {
                            for (DefaultAgentManager m : managers) {
                                Optional<Agent> agent = m.createAgentIfPresent(agentId, rc);
                                if (agent.isPresent()) {
                                    return agent;
                                }
                            }
                            return Optional.empty();
                        });
            }
            DistributedStore store = distributedStore;
            if (store == null) {
                store = mainHa.getDistributedStore();
            }
            if (store != null) {
                gw.setSubagentRegistry(new StoreBackedSubagentRegistry(store.baseStore()));
            }

            return new GatewayBootstrap(gw, cm, new LinkedHashMap<>(agents), resolvedMainId);
        }
    }
}
