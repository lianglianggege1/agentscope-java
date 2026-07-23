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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * Marker for middleware installed by harness runtime wiring.
 *
 * <p>These instances are framework-owned and should not be copied by {@code fromAgent(...)}.
 */
/**
 * 由运行时装配器自动安装的中间件标记接口。
 *
 * <p>该类实例归属框架管理，禁止通过 {@code fromAgent(...)} 进行拷贝。
 */
public interface HarnessRuntimeMiddleware extends MiddlewareBase {}
