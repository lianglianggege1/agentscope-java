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

import java.util.Map;

/**
 * Constructs a {@link Channel} instance from the resolved routing config and provider-specific
 * properties.
 */
/**
 * 根据解析后的路由配置与提供方专属属性构造 {@link Channel} 实例。
 */
@FunctionalInterface
public interface ChannelFactory {

    /**
     * Build the channel.
     *
     * @param channelId the logical channel id
     * @param routing the parsed routing config (defaultAgentId, dmScope, bindings)
     * @param properties provider-specific properties; never null but may be empty
     * @return a fully constructed channel ready for {@code init(Gateway)} + {@code start()}
     */
    /**
     * 构建通道。
     *
     * @param channelId 逻辑通道 ID
     * @param routing 解析后的路由配置（defaultAgentId、dmScope、bindings）
     * @param properties 提供方专属属性；不会为 null，但可为空
     * @return 构建完毕、可直接 {@code init(Gateway)} + {@code start()} 的通道
     */
    Channel create(String channelId, ChannelConfig routing, Map<String, Object> properties);
}
