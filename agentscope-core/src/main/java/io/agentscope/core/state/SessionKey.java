/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import io.agentscope.core.util.JsonUtils;

/**
 * Marker interface for session identifiers.
 * 会话标识符的标记接口。
 *
 * <p>Users can define custom session identifier structures for complex scenarios like multi-tenant
 * applications. The default implementation {@link SimpleSessionKey} uses a simple string.
 * 用户可以为多租户应用程序等复杂场景定义自定义会话标识符结构。
 * 默认实现SimpleSessionKey使用一个简单的字符串。
 *
 * <p>Custom Session implementations can interpret SessionKey structures to determine storage
 * strategies (e.g., multi-tenant database sharding).
 * 自定义会话实现可以解释SessionKey结构以确定存储策略（例如，多租户数据库分片）。
 *
 * <p>Example custom implementation:
 *
 * <pre>{@code
 * // Multi-tenant scenario
 * public record TenantSessionKey(
 *     String tenantId,
 *     String userId,
 *     String sessionId
 * ) implements SessionKey {}
 *
 * // Usage
 * session.save(new TenantSessionKey("tenant_001", "user_123", "session_456"), "agent_meta", state);
 * }</pre>
 *
 * @see SimpleSessionKey
 * @see io.agentscope.core.session.Session
 */
public interface SessionKey {

    /**
     * Returns a string identifier for this session key.
     * 返回此会话密钥的字符串标识符。
     *
     * <p>This method is used by Session implementations to convert the session key to a string
     * suitable for storage (e.g., as a directory name, database key, or Redis key prefix).
     * Session实现使用此方法将会话密钥转换为适合存储的字符串（例如，作为目录名、数据库密钥或Redis密钥前缀）。
     *
     * <p>The default implementation uses JSON serialization. Implementations like {@link
     * SimpleSessionKey} override this to return the session ID directly for better readability.
     * 默认实现使用JSON序列化。SimpleSessionKey等实现会覆盖此内容，直接返回会话ID，以提高可读性。
     *
     * @return a string identifier for this session key
     */
    default String toIdentifier() {
        return JsonUtils.getJsonCodec().toJson(this);
    }
}
