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
package io.agentscope.harness.agent.bus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Live message transport for coordinating work across sessions and processes.
 *
 * <p>Three consumption modes are exposed:
 * <ul>
 *   <li><b>Drain queue</b> (Mode A) — single-consumer, ack-on-read. Each entry is returned at
 *       most once; storage drops it the moment it is read.</li>
 *   <li><b>Replay log</b> (Mode C) — multi-consumer, externally bounded. Each reader tracks its
 *       own cursor; entries persist until trimmed or capped by maxLen.</li>
 *   <li><b>Transient broadcast</b> (Mode D) — fire-and-forget pub/sub. Only currently-subscribed
 *       listeners receive a payload; no history is retained.</li>
 * </ul>
 *
 * <p>Domain helpers ({@link #inboxPush}, {@link #inboxDrain}, {@link #enqueueWakeup}) map
 * higher-level concepts onto these primitives with a fixed key-naming convention.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code WorkspaceMessageBus} — single-process, no external dependencies</li>
 * </ul>
 */
/**
 * 实时消息传输组件，用于跨会话、跨进程协同调度任务。
 *
 * <p>提供三种消费模式：
 * <ul>
 *   <li><b>消费队列（A模式）</b> — 单消费者、读取即确认。每条消息最多投递一次，读取后存储层直接删除该消息。</li>
 *   <li><b>回放日志（C模式）</b> — 多消费者、外部游标管控。每个消费者维护独立读取位点；消息持久留存，直至日志裁剪或达到最大长度限制。</li>
 *   <li><b>瞬时广播（D模式）</b> — 发后即忘的发布订阅。仅当前已订阅的监听器能收到消息，不保存历史数据。</li>
 * </ul>
 *
 * <p>领域封装方法（{@link #inboxPush}、{@link #inboxDrain}、{@link #enqueueWakeup}）基于固定键命名规范，将上层业务逻辑映射到底层原语能力。
 *
 * <p>现有实现类：
 * <ul>
 *   <li>{@code WorkspaceMessageBus} — 单进程实现，无外部中间件依赖</li>
 * </ul>
 */
public interface MessageBus extends AutoCloseable {

    // ---- Mode A: drain queue (single consumer, ack-on-read) ----

    /**
     * Append a payload to the drain queue at the given key.
     *
     * @param key     queue identifier (caller-defined naming convention)
     * @param payload JSON-serializable dict to enqueue
     * @return the transport-level entry id
     */
    /**
     * 将消息载荷追加至指定标识的消费队列。
     *
     * @param key 队列标识（由调用方自定义命名规范）
     * @param payload 待入队、可JSON序列化的数据对象
     * @return 传输层消息唯一ID
     */
    Mono<String> queuePush(String key, Map<String, Object> payload);

    /**
     * Drain up to {@code maxCount} entries from the queue at the given key. Returned entries are
     * removed from the queue atomically. A subsequent call returns only entries that arrived after
     * this one.
     *
     * @param key      queue identifier
     * @param maxCount maximum number of entries to return
     * @return entries in arrival order; empty list when the queue is empty
     */
    /**
     * 从指定队列中最多取出 {@code maxCount} 条消息。取出的消息会被原子性从队列删除，下次调用仅能获取本次操作之后新增的消息。
     *
     * @param key 队列标识
     * @param maxCount 最多返回消息条数
     * @return 按入队顺序排列的消息列表；队列为空时返回空列表
     */
    Mono<List<BusEntry>> queueDrain(String key, int maxCount);

    /**
     * Delete the drain queue at the given key and all of its entries. Idempotent.
     *
     * @param key queue identifier
     */
    /**
     * 删除指定标识对应的消费队列及其全部消息，接口幂等。
     *
     * @param key 队列标识
     */
    Mono<Void> queueDelete(String key);

    /**
     * Check whether the drain queue at the given key has any entries, without consuming them.
     *
     * @param key queue identifier
     * @return true if the queue has at least one entry
     */
    /**
     * 检测指定消费队列是否存在消息，不会消费队列数据。
     *
     * @param key 队列标识
     * @return 队列至少存在一条消息则返回true
     */
    Mono<Boolean> queuePeek(String key);

    // ---- Domain helpers: Inbox ----

    /**
     * Check whether a session's inbox has pending messages without consuming them.
     *
     * @param sessionId the session to check
     * @return true if the inbox has at least one message
     */
    /**
     * 检测指定会话收件箱是否存在待处理消息，不会消费消息。
     *
     * @param sessionId 待检测会话ID
     * @return 收件箱至少存在一条消息则返回true
     */
    default Mono<Boolean> inboxHasMessages(String sessionId) {
        return queuePeek("agentscope:inbox:" + sessionId);
    }

    // ---- Mode C: replay log (multi-consumer, externally bounded) ----

    /**
     * Append a payload to the replay log at the given key. Readers track their own cursor; entries
     * persist until trimmed or capped by {@code maxLen}.
     *
     * @param key     log identifier
     * @param payload JSON-serializable dict to append
     * @param maxLen  cap the log at approximately this many entries; older entries are trimmed when
     *                exceeded. Use {@code 0} or negative for no cap.
     * @return the transport-level entry id (usable as a cursor for subsequent reads)
     */
    /**
     * 将消息载荷追加至指定标识的回放日志。各消费者维护独立读取游标；消息持久存储，直至日志裁剪或达到最大条目上限。
     *
     * @param key 日志标识
     * @param payload 待追加、可JSON序列化的数据对象
     * @param maxLen 日志最大条目阈值，超出后自动裁剪旧消息；传0或负数代表不设上限
     * @return 传输层消息ID，可作为后续读取的游标
     */
    Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen);

    /**
     * Read up to {@code maxCount} entries from the replay log, starting after {@code since}.
     * Non-destructive: the same entries can be returned to any number of readers.
     *
     * @param key      log identifier
     * @param since    cursor — return entries strictly newer than this id. {@code null} reads from
     *                 the beginning.
     * @param maxCount maximum number of entries to return
     * @return entries in append order; empty list when no entries are newer than {@code since}
     */
    /**
     * 从回放日志中读取最多 {@code maxCount} 条消息，起始位置为游标 {@code since} 之后。
     * 读取操作不会删除数据，多条消费者可重复读取同批消息。
     *
     * @param key 日志标识
     * @param since 游标，仅返回该ID之后新增的消息；传null则从头读取
     * @param maxCount 最多返回消息条数
     * @return 按追加顺序排列的消息列表；不存在比{@code since}更新的消息时返回空列表
     */
    Mono<List<BusEntry>> logRead(String key, String since, int maxCount);

    /**
     * Delete the replay log at the given key and all of its entries. Idempotent.
     *
     * @param key log identifier
     */
    /**
     * 删除指定标识对应的回放日志及其全部消息，接口幂等。
     *
     * @param key 日志标识
     */
    Mono<Void> logTrim(String key);

    // ---- Mode D: transient broadcast (fire-and-forget) ----

    /**
     * Publish a payload on the broadcast channel. Only currently-subscribed listeners receive it.
     *
     * @param key     channel identifier
     * @param payload JSON-serializable dict
     */
    /**
     * 向广播通道发布消息载荷，仅当前已订阅的监听器可接收该消息。
     *
     * @param key 通道标识
     * @param payload 可JSON序列化的数据对象
     */
    Mono<Void> publish(String key, Map<String, Object> payload);

    /**
     * Subscribe to a broadcast channel. Yields payloads published after subscription is
     * established. The caller owns the subscription's lifetime — cancelling the Flux ends it.
     *
     * @param key channel identifier
     * @return stream of payloads
     */
    /**
     * 订阅指定广播通道，仅推送订阅建立之后发布的消息载荷。
     * 订阅生命周期由调用方管控，取消数据流即可终止订阅。
     *
     * @param key 通道标识
     * @return 消息载荷数据流
     */
    Flux<Map<String, Object>> subscribe(String key);

    // ---- Domain helpers: Inbox ----

    /**
     * Push a message into a session's inbox queue.
     *
     * @param sessionId the recipient session
     * @param msg       JSON-serializable payload (typically a serialized HintBlock)
     */
    /**
     * 向指定会话的收件队列推送消息。
     *
     * @param sessionId 接收方会话ID
     * @param msg 可JSON序列化的消息载荷（通常为序列化后的提示块）
     */
    default Mono<Void> inboxPush(String sessionId, Map<String, Object> msg) {
        return queuePush("agentscope:inbox:" + sessionId, msg).then();
    }

    /**
     * Drain pending inbox messages for a session.
     *
     * @param sessionId the session whose inbox to drain
     * @param maxCount  maximum entries to drain
     * @return entries in arrival order
     */
    /**
     * 消费指定会话收件箱内待处理消息。
     *
     * @param sessionId 待消费消息的会话ID
     * @param maxCount 最多消费消息条数
     * @return 按到达顺序排列的消息列表
     */
    default Mono<List<BusEntry>> inboxDrain(String sessionId, int maxCount) {
        return queueDrain("agentscope:inbox:" + sessionId, maxCount);
    }

    // ---- Domain helpers: Wakeup ----

    /**
     * Enqueue a wakeup request and signal dispatchers.
     *
     * @param userId    the owning user id (for multi-user isolation)
     * @param sessionId the session to wake
     * @param agentId   the agent that owns the session
     */
    /**
     * 入队唤醒请求并通知调度器。
     *
     * @param userId 所属用户ID（用于多用户隔离）
     * @param sessionId 待唤醒会话ID
     * @param agentId 该会话所属智能体ID
     */
    default Mono<Void> enqueueWakeup(String userId, String sessionId, String agentId) {
        return queuePush(
                        "agentscope:wakeups",
                        Map.of(
                                "userId", userId != null ? userId : "",
                                "sessionId", sessionId,
                                "agentId", agentId))
                .then(publish("agentscope:wakeup_signal", Map.of()));
    }

    /**
     * Convenience overload without userId. Delegates to the three-parameter form with an empty
     * userId.
     */
    /**
     * 便捷重载方法，无需传入用户ID。内部调用三参数重载，传入空用户ID。
     */
    default Mono<Void> enqueueWakeup(String sessionId, String agentId) {
        return enqueueWakeup("", sessionId, agentId);
    }

    /**
     * Subscribe to the shared wakeup signal channel.
     *
     * @return stream of signal payloads (each indicates "drain the wakeup queue now")
     */
    /**
     * 订阅共享唤醒信号通道。
     *
     * @return 信号载荷数据流，每条消息代表“立即消费唤醒队列”
     */
    default Flux<Map<String, Object>> subscribeWakeup() {
        return subscribe("agentscope:wakeup_signal");
    }

    // ---- Domain helpers: Session events ----

    String SESSION_EVENTS_KEY_PREFIX = "agentscope:session:events:";
    int SESSION_REPLAY_MAX_LEN = 1000;

    /**
     * Append a session event to the replay log and fan it out live. Late-joining subscribers can
     * replay from the log; already-connected subscribers receive it immediately via pub/sub.
     *
     * @param sessionId the session this event belongs to
     * @param event     JSON-serializable event payload
     * @return the replay-log entry id
     */
    /**
     * 将会话事件追加至回放日志并实时广播分发。后订阅的监听者可通过日志回放历史事件；已建立连接的订阅者会立刻收到该事件。
     *
     * @param sessionId 事件所属会话ID
     * @param event 可JSON序列化的事件载荷
     * @return 回放日志消息ID
     */
    default Mono<String> sessionPublishEvent(String sessionId, Map<String, Object> event) {
        String key = SESSION_EVENTS_KEY_PREFIX + sessionId;
        return logAppend(key, event, SESSION_REPLAY_MAX_LEN)
                .flatMap(
                        entryId -> {
                            Map<String, Object> live = new HashMap<>(event);
                            live.put("_entry_id", entryId);
                            return publish(key, live).thenReturn(entryId);
                        });
    }

    /**
     * Read events from a session's replay log for catch-up / reconnection.
     *
     * @param sessionId the session whose events to read
     * @param since     cursor — return entries strictly newer than this id; {@code null} reads all
     * @param maxCount  maximum events to return
     * @return entries in append order
     */
    /**
     * 读取指定会话回放日志中的事件，用于断线重连后补全历史数据。
     *
     * @param sessionId 待读取事件的会话ID
     * @param since 游标，仅返回该ID之后新增的消息；传null则读取全部历史事件
     * @param maxCount 最多返回事件条数
     * @return 按追加顺序排列的事件列表
     */
    default Mono<List<BusEntry>> sessionReadEvents(String sessionId, String since, int maxCount) {
        return logRead(SESSION_EVENTS_KEY_PREFIX + sessionId, since, maxCount);
    }

    /**
     * Live-subscribe to a session's published events. Yields only payloads delivered after the
     * subscription is established.
     *
     * @param sessionId the session to subscribe to
     * @return stream of event payloads
     */
    /**
     * 实时订阅指定会话发布的事件，仅推送订阅建立之后产生的事件载荷。
     *
     * @param sessionId 待订阅的会话ID
     * @return 事件载荷数据流
     */
    default Flux<Map<String, Object>> sessionSubscribeEvents(String sessionId) {
        return subscribe(SESSION_EVENTS_KEY_PREFIX + sessionId);
    }

    /**
     * Trim the session's event replay log. Called after a session run completes so late subscribers
     * don't replay stale events from a previous run.
     *
     * @param sessionId the session whose event log to clear
     */
    /**
     * 裁剪指定会话的事件回放日志。会话运行结束后调用，避免后续订阅者回放上一轮过期事件。
     *
     * @param sessionId 待清空事件日志的会话ID
     */
    default Mono<Void> sessionTrimEvents(String sessionId) {
        return logTrim(SESSION_EVENTS_KEY_PREFIX + sessionId);
    }

    /** Release underlying transport resources. Default is a no-op. */
    /** 释放底层传输组件资源，默认空实现。 */
    @Override
    default void close() {}
}
