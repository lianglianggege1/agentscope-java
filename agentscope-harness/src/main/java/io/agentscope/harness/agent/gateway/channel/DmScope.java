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
 * Controls how DM ({@link PeerKind#DIRECT}) session keys are scoped.
 *
 * <ul>
 *   <li>{@link #MAIN} — all DMs for a given agent share a single session. Suitable for single-user
 *       or shared assistant scenarios.
 *   <li>{@link #PER_PEER} — one session per peer id. Most common for multi-user deployments.
 *   <li>{@link #PER_CHANNEL_PEER} — like {@code PER_PEER} but the channel name is included in the
 *       key, disambiguating the same peer across channels.
 *   <li>{@link #PER_ACCOUNT_CHANNEL_PEER} — extends {@code PER_CHANNEL_PEER} with the account id,
 *       useful for multi-account (multi-bot) deployments on the same channel platform.
 * </ul>
 */
/**
 * 控制 DM（{@link PeerKind#DIRECT}）会话键的作用域。
 *
 * <ul>
 *   <li>{@link #MAIN} —— 某智能体的所有私聊共享一个会话。适合单用户或共享助手场景。
 *   <li>{@link #PER_PEER} —— 每个对端 ID 一个会话。多用户部署最常见。
 *   <li>{@link #PER_CHANNEL_PEER} —— 类似 {@code PER_PEER} 但键中包含通道名，
 *       用于区分同一对端在不同通道的会话。
 *   <li>{@link #PER_ACCOUNT_CHANNEL_PEER} —— 在 {@code PER_CHANNEL_PEER} 基础上
 *       增加账号 ID，适合同一通道平台上的多账号（多机器人）部署。
 * </ul>
 */
public enum DmScope {
    MAIN,
    PER_PEER,
    PER_CHANNEL_PEER,
    PER_ACCOUNT_CHANNEL_PEER;

    /** Default scope used when none is configured. */
    /** 未配置时使用的默认作用域。 */
    public static DmScope defaultScope() {
        return MAIN;
    }
}
