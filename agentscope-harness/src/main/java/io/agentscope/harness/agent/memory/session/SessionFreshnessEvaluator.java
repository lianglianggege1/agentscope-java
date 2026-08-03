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
package io.agentscope.harness.agent.memory.session;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Evaluates whether a session is still "fresh" or should be reset.
 *
 * <p>Inspired by agentscope-claw's SessionFreshnessEvaluator. Supports two reset policies:
 * <ul>
 *   <li><b>Daily reset:</b> AgentStateStore resets after a configured hour each day</li>
 *   <li><b>Idle timeout:</b> AgentStateStore resets after inactivity exceeding a threshold</li>
 * </ul>
 */
/**
 * 评估会话是否仍处于“新鲜状态”，或是应当重置。
 *
 * <p>参考 agentscope-claw 的会话新鲜度评估器。支持两种重置策略：
 * <ul>
 *   <li><b>每日重置：</b>智能体状态存储在每日指定时点后执行重置</li>
 *   <li><b>空闲超时：</b>闲置时长超过阈值后重置智能体状态存储</li>
 * </ul>
 */
public class SessionFreshnessEvaluator {

    private final int dailyResetHour;
    private final Duration idleTimeout;
    private final ZoneId timezone;

    /**
     * Creates a freshness evaluator with default settings:
     * daily reset at 4 AM, idle timeout of 2 hours, system timezone.
     */
    /**
     * 使用默认配置创建新鲜度评估器：
     * 每日凌晨4点重置，空闲超时时长2小时，采用系统时区。
     */
    public SessionFreshnessEvaluator() {
        this(4, Duration.ofHours(2), ZoneId.systemDefault());
    }

    public SessionFreshnessEvaluator(int dailyResetHour, Duration idleTimeout, ZoneId timezone) {
        this.dailyResetHour = dailyResetHour;
        this.idleTimeout = idleTimeout;
        this.timezone = timezone;
    }

    /**
     * Determines if the session should be considered stale and reset.
     *
     * @param lastActivityAt the timestamp of the last activity in the session
     * @return true if the session should be reset
     */
    /**
     * 判断会话是否已失效，需要执行重置。
     *
     * @param lastActivityAt 会话最后一次活动的时间戳
     * @return 需要重置会话则返回 true
     */
    public boolean isStale(Instant lastActivityAt) {
        if (lastActivityAt == null) {
            return true;
        }

        Instant now = Instant.now();

        if (idleTimeout != null
                && Duration.between(lastActivityAt, now).compareTo(idleTimeout) > 0) {
            return true;
        }

        if (dailyResetHour >= 0) {
            ZonedDateTime lastActivity = lastActivityAt.atZone(timezone);
            ZonedDateTime nowZoned = now.atZone(timezone);

            LocalTime resetTime = LocalTime.of(dailyResetHour, 0);
            ZonedDateTime todayReset = nowZoned.toLocalDate().atTime(resetTime).atZone(timezone);

            if (nowZoned.isAfter(todayReset) && lastActivity.isBefore(todayReset)) {
                return true;
            }
        }

        return false;
    }

    public int getDailyResetHour() {
        return dailyResetHour;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }
}
