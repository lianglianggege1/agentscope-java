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
package io.agentscope.harness.agent.gateway.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-channel routing configuration: default agent fallback, DM session scope, and ordered binding
 * rules evaluated by {@link ChannelRouter}.
 *
 * <p>Used by {@link Channel} implementations to parameterize routing without hard-coding agent ids
 * or session shapes in adapter code.
 *
 * @param channelId the logical channel identifier this config applies to (must match {@link
 *     Channel#channelId()})
 * @param defaultAgentId fallback agent id when no binding matches; if null the global default
 *     agent (registered via {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent}) is
 *     used
 * @param dmScope controls how DM ({@link PeerKind#DIRECT}) sessions are keyed; defaults to {@link
 *     DmScope#MAIN}
 * @param bindings ordered list of binding rules; evaluated in list order within each priority tier
 */
/**
 * 通道路由配置：默认智能体回退、DM 会话作用域，以及供
 * {@link ChannelRouter} 评估的有序绑定规则。
 *
 * <p>供 {@link Channel} 实现用于参数化路由，避免在适配器代码中
 * 硬编码智能体 ID 或会话形态。
 *
 * @param channelId 本配置适用的逻辑通道标识（必须与 {@link Channel#channelId()} 一致）
 * @param defaultAgentId 无绑定命中时的回退智能体 ID；为 null 时使用全局默认智能体
 *     （通过 {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent} 注册）
 * @param dmScope 控制 DM（{@link PeerKind#DIRECT}）会话的键粒度；默认 {@link DmScope#MAIN}
 * @param bindings 有序的绑定规则列表；在每个优先级层级内按列表顺序评估
 */
public record ChannelConfig(
        String channelId, String defaultAgentId, DmScope dmScope, List<ChannelBinding> bindings) {

    public ChannelConfig {
        Objects.requireNonNull(channelId, "channelId");
        dmScope = dmScope != null ? dmScope : DmScope.defaultScope();
        bindings = bindings != null ? List.copyOf(bindings) : List.of();
    }

    /** Minimal config: channel id only, using global agent default and {@link DmScope#MAIN}. */
    /** 最小配置：仅通道 ID，使用全局默认智能体与 {@link DmScope#MAIN}。 */
    public static ChannelConfig of(String channelId) {
        return new ChannelConfig(channelId, null, DmScope.MAIN, List.of());
    }

    /** Config with explicit default agent (no binding rules). */
    /** 带显式默认智能体的配置（无绑定规则）。 */
    public static ChannelConfig of(String channelId, String defaultAgentId) {
        return new ChannelConfig(channelId, defaultAgentId, DmScope.MAIN, List.of());
    }

    /** Returns a builder for constructing channel configs fluently. */
    /** 返回用于流式构建通道配置的构建器。 */
    public static Builder builder(String channelId) {
        return new Builder(channelId);
    }

    /** 通道配置的流式构建器。 */
    public static final class Builder {
        private final String channelId;
        private String defaultAgentId;
        private DmScope dmScope = DmScope.MAIN;
        private final List<ChannelBinding> bindings = new ArrayList<>();

        private Builder(String channelId) {
            this.channelId = Objects.requireNonNull(channelId, "channelId");
        }

        public Builder defaultAgentId(String defaultAgentId) {
            this.defaultAgentId = defaultAgentId;
            return this;
        }

        public Builder dmScope(DmScope dmScope) {
            this.dmScope = dmScope;
            return this;
        }

        public Builder binding(ChannelBinding binding) {
            this.bindings.add(Objects.requireNonNull(binding, "binding"));
            return this;
        }

        public Builder bindings(List<ChannelBinding> bindings) {
            if (bindings != null) {
                this.bindings.addAll(bindings);
            }
            return this;
        }

        public ChannelConfig build() {
            return new ChannelConfig(channelId, defaultAgentId, dmScope, bindings);
        }
    }
}
