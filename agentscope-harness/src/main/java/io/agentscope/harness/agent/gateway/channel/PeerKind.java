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

/**
 * Classification of the conversation peer, used for session key construction and DM-scope
 * resolution.
 *
 * <ul>
 *   <li>{@link #DIRECT} — one-to-one DM / private conversation
 *   <li>{@link #CHANNEL} — public or private channel / room
 *   <li>{@link #GROUP} — group chat (WhatsApp group, Telegram supergroup, etc.)
 *   <li>{@link #THREAD} — thread nested inside a {@link #CHANNEL} or {@link #GROUP} peer
 * </ul>
 */
/**
 * 会话对端的分类，用于会话键构造与 DM 作用域解析。
 *
 * <ul>
 *   <li>{@link #DIRECT} —— 一对一私聊
 *   <li>{@link #CHANNEL} —— 公开或私有频道/房间
 *   <li>{@link #GROUP} —— 群聊（WhatsApp 群组、Telegram 超级群组等）
 *   <li>{@link #THREAD} —— 嵌套在 {@link #CHANNEL} 或 {@link #GROUP} 对端内的话题
 * </ul>
 */
public enum PeerKind {
    DIRECT("direct"),
    CHANNEL("channel"),
    GROUP("group"),
    THREAD("thread");

    private final String value;

    PeerKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Whether this peer kind represents a direct (one-to-one) message. */
    /** 该对端类型是否表示一对一私聊消息。 */
    public boolean isDirect() {
        return this == DIRECT;
    }

    /** Whether this peer kind represents a thread nested inside another peer. */
    /** 该对端类型是否表示嵌套在其他对端内的话题。 */
    public boolean isThread() {
        return this == THREAD;
    }
}
