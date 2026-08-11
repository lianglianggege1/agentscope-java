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

import java.util.Objects;

/**
 * Identifies the conversation peer: who or what is being messaged (a user DM, a channel, a group,
 * or a thread). Used by {@link ChannelRouter} for binding evaluation and session key construction.
 *
 * @param kind the peer classification
 * @param id provider-assigned peer identifier (user id, channel id, thread id, etc.)
 */
/**
 * 标识会话对端：消息的对象是谁/什么（用户私聊、频道、群组或话题）。
 * 供 {@link ChannelRouter} 用于绑定评估与会话键构造。
 *
 * @param kind 对端分类
 * @param id 提供方分配的对端标识（用户 ID、频道 ID、话题 ID 等）
 */
public record Peer(PeerKind kind, String id) {

    public Peer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** Returns the composite key {@code "<kind>:<id>"} used in binding matching. */
    /** 返回用于绑定匹配的复合键 {@code "<kind>:<id>"}。 */
    public String key() {
        return kind.value() + ":" + id;
    }

    /** Creates a direct / DM peer. */
    /** 创建私聊/DM 对端。 */
    public static Peer direct(String id) {
        return new Peer(PeerKind.DIRECT, id);
    }

    /** Creates a channel peer. */
    /** 创建频道对端。 */
    public static Peer channel(String id) {
        return new Peer(PeerKind.CHANNEL, id);
    }

    /** Creates a group peer. */
    /** 创建群组对端。 */
    public static Peer group(String id) {
        return new Peer(PeerKind.GROUP, id);
    }

    /** Creates a thread peer. */
    /** 创建话题对端。 */
    public static Peer thread(String id) {
        return new Peer(PeerKind.THREAD, id);
    }
}
