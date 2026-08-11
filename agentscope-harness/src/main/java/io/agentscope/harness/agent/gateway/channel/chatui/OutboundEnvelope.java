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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;

/**
 * A buffered proactive outbound message delivered to {@link ChatUiChannel} by the gateway. Callers
 * retrieve these via {@link ChatUiChannel#pollOutbound()}.
 *
 * @param address the delivery target
 * @param messages the outbound messages
 * @param timestampMs when the envelope was created
 */
/**
 * 由网关投递到 {@link ChatUiChannel} 的缓冲式主动出站消息信封。
 * 调用方通过 {@link ChatUiChannel#pollOutbound()} 拉取这些消息。
 *
 * @param address 投递目标地址
 * @param messages 出站消息列表
 * @param timestampMs 信封创建时间（毫秒时间戳）
 */
public record OutboundEnvelope(OutboundAddress address, List<Msg> messages, long timestampMs) {

    /** 便捷构造器：自动取当前时间戳，并对消息列表做防御性拷贝。 */
    public OutboundEnvelope(OutboundAddress address, List<Msg> messages) {
        this(address, List.copyOf(messages), System.currentTimeMillis());
    }
}
