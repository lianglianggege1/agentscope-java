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
package io.agentscope.harness.agent.filesystem.remote.store;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;

/**
 * Factory that produces a namespace tuple for {@link BaseStore} operations at call time.
 *
 * <p>Unlike a static namespace, a {@code NamespaceFactory} is invoked on <em>every</em> store
 * operation (read, write, ls, etc.), allowing the namespace to vary based on the per-call {@link
 * RuntimeContext} (user id, session id) rather than mutable shared state on the agent instance.
 *
 * <p>Example:
 *
 * <pre>{@code
 * NamespaceFactory factory = rc ->
 *         List.of("sessions", rc.getSessionId(), "filesystem");
 * RemoteFilesystem fs = new RemoteFilesystem(store, factory);
 * }</pre>
 */
/**
 * 工厂类，每次执行 {@link BaseStore} 存储操作时生成命名空间元组。
 *
 * <p>与静态命名空间不同，{@code NamespaceFactory} 在每一次存储操作（读、写、列举文件等）都会执行，
 * 支持基于单次调用的 {@link RuntimeContext}（用户ID、会话ID）动态变更命名空间，
 * 而非依赖智能体实例上可变的共享状态。
 *
 * <p>示例：
 *
 * <pre>{@code
 * NamespaceFactory factory = rc ->
 *         List.of("sessions", rc.getSessionId(), "filesystem");
 * RemoteFilesystem fs = new RemoteFilesystem(store, factory);
 * }</pre>
 */
@FunctionalInterface
public interface NamespaceFactory {

    /**
     * Returns the namespace tuple for the current operation context.
     *
     * @param runtimeContext per-call runtime context; never {@code null} (callers without a real RC
     *     must pass {@link RuntimeContext#empty()})
     * @return non-null, non-empty list of namespace segments
     */
    /**
     * 获取当前操作上下文对应的命名空间分段元组。
     *
     * @param runtimeContext 单次调用运行时上下文，不会为 {@code null}；无实际上下文的调用方需传入 {@link RuntimeContext#empty()}
     * @return 非空、元素不为空的命名空间分段列表
     */
    List<String> getNamespace(RuntimeContext runtimeContext);
}
