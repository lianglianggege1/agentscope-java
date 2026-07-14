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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;

/**
 * Marks a filesystem that can have its backing {@link Sandbox} injected at runtime.
 *
 * <p>Implemented by {@link SandboxBackedFilesystem} so {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} can set the active sandbox for each
 * call and clear it afterward.
 */
/**
 * 标记可在运行时注入底层 {@link Sandbox} 实例的文件系统接口。
 *
 * <p>由 {@link SandboxBackedFilesystem} 实现，供
 * {@link io.agentscope.harness.agent.hook.SandboxLifecycleHook}
 * 在每次调用时绑定当前沙箱，调用结束后清空绑定。
 */
public interface SandboxAware {

    void setSandbox(Sandbox sandbox);

    Sandbox getSandbox();
}
