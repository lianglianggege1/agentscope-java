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

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry and lifecycle manager for {@link Channel} adapters. Provides outbound dispatch so the
 * gateway can deliver proactive messages (e.g. subagent announces) through the correct channel.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #register(Channel)} — add a channel to the registry
 *   <li>{@link #initAll(Gateway)} — inject the gateway into all registered channels
 *   <li>{@link #startAll()} — start all channels (connect to external transports)
 *   <li>{@link #stopAll()} — stop all channels and release resources
 * </ol>
 *
 * <h2>Outbound delivery</h2>
 *
 * {@link #deliver(OutboundAddress, List)} looks up the target channel by {@link
 * OutboundAddress#channelId()} and delegates to {@link Channel#deliver(OutboundAddress, List)}.
 */
/**
 * {@link Channel} 适配器的注册表与生命周期管理器。提供出站分发能力，
 * 使网关能通过正确的通道投递主动消息（例如子智能体公告）。
 *
 * <h2>生命周期</h2>
 *
 * <ol>
 *   <li>{@link #register(Channel)} —— 把通道加入注册表
 *   <li>{@link #initAll(Gateway)} —— 向所有已注册通道注入网关
 *   <li>{@link #startAll()} —— 启动所有通道（连接外部传输层）
 *   <li>{@link #stopAll()} —— 停止所有通道并释放资源
 * </ol>
 *
 * <h2>出站投递</h2>
 *
 * {@link #deliver(OutboundAddress, List)} 按 {@link OutboundAddress#channelId()}
 * 查找目标通道，并委托给 {@link Channel#deliver(OutboundAddress, List)}。
 */
public final class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public ChannelManager() {}

    /**
     * Registers a channel adapter. If a channel with the same {@link Channel#channelId()} is
     * already registered, it is replaced.
     */
    /**
     * 注册一个通道适配器。若已存在相同 {@link Channel#channelId()} 的通道，
     * 旧通道将被替换。
     */
    public void register(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        channels.put(channel.channelId(), channel);
    }

    /**
     * Stops and removes the channel registered under {@code channelId}, if any. Returns
     * {@code true} when a channel was removed, {@code false} when no channel was registered under
     * that id.
     */
    /**
     * 停止并移除以 {@code channelId} 注册的通道（若存在）。
     * 有通道被移除时返回 {@code true}，该 ID 下无通道时返回 {@code false}。
     */
    public boolean unregister(String channelId) {
        if (channelId == null) {
            return false;
        }
        Channel removed = channels.remove(channelId);
        if (removed == null) {
            return false;
        }
        try {
            removed.stop();
            log.info("Channel unregistered and stopped: {}", channelId);
        } catch (Exception e) {
            log.warn(
                    "Error stopping channel '{}' during unregister: {}",
                    channelId,
                    e.getMessage(),
                    e);
        }
        return true;
    }

    /** Returns the channel registered under the given id, if any. */
    /** 返回以指定 ID 注册的通道（若存在）。 */
    public Optional<Channel> getChannel(String channelId) {
        if (channelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(channels.get(channelId));
    }

    /** Returns an unmodifiable snapshot of all registered channel ids. */
    /** 返回所有已注册通道 ID 的不可变快照。 */
    public List<String> channelIds() {
        return List.copyOf(channels.keySet());
    }

    /** Returns an unmodifiable snapshot of all registered channels. */
    /** 返回所有已注册通道的不可变快照。 */
    public Collection<Channel> getAllChannels() {
        return List.copyOf(channels.values());
    }

    /**
     * Injects the gateway into all registered channels via {@link Channel#init(Gateway)}. Called
     * once during bootstrap before {@link #startAll()}.
     */
    /**
     * 通过 {@link Channel#init(Gateway)} 向所有已注册通道注入网关。
     * 在引导阶段、{@link #startAll()} 之前调用一次。
     */
    public void initAll(Gateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        for (Channel ch : channels.values()) {
            try {
                ch.init(gateway);
            } catch (Exception e) {
                log.error("Failed to init channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
    }

    /** Starts all registered channels. Channels that fail to start are logged but do not abort. */
    /** 启动所有已注册通道。启动失败的通道仅记录日志，不会中止整体流程。 */
    public void startAll() {
        for (Channel ch : channels.values()) {
            try {
                ch.start();
                log.info("Channel started: {}", ch.channelId());
            } catch (Exception e) {
                log.error("Failed to start channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
        started = true;
    }

    /** Stops all registered channels and clears the registry. */
    /** 停止所有已注册通道。 */
    public void stopAll() {
        started = false;
        for (Channel ch : channels.values()) {
            try {
                ch.stop();
                log.info("Channel stopped: {}", ch.channelId());
            } catch (Exception e) {
                log.warn("Error stopping channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
    }

    /** Whether {@link #startAll()} has been called. */
    /** {@link #startAll()} 是否已被调用。 */
    public boolean isStarted() {
        return started;
    }

    /**
     * Delivers proactive outbound messages through the channel identified by {@link
     * OutboundAddress#channelId()}. If the target channel is not registered, the messages are
     * dropped with a warning log.
     *
     * @param address the delivery target
     * @param messages the messages to deliver
     */
    /**
     * 通过 {@link OutboundAddress#channelId()} 标识的通道投递主动出站消息。
     * 目标通道未注册时，消息被丢弃并记录告警日志。
     *
     * @param address 投递目标
     * @param messages 要投递的消息
     */
    public void deliver(OutboundAddress address, List<Msg> messages) {
        Objects.requireNonNull(address, "address");
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Channel ch = channels.get(address.channelId());
        if (ch == null) {
            log.warn(
                    "Outbound delivery skipped: no channel registered for '{}'",
                    address.channelId());
            return;
        }
        try {
            ch.deliver(address, messages);
        } catch (Exception e) {
            log.error(
                    "Outbound delivery failed: channel='{}', to='{}': {}",
                    address.channelId(),
                    address.to(),
                    e.getMessage(),
                    e);
        }
    }
}
