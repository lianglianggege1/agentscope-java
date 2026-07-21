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
package io.agentscope.harness.agent.workspace;

/**
 * Path-resolution policy mode for host-rooted filesystems
 * ({@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} and friends).
 *
 * <p>Controls what happens when the agent supplies an absolute path:
 *
 * <ul>
 *   <li>{@link #SANDBOXED} — anchor every path to the filesystem root, reject {@code ..} and
 *       absolute paths leaving the root. Equivalent to the legacy {@code virtualMode=true}.
 *   <li>{@link #ROOTED} — absolute paths are accepted only when they fall under one of the
 *       roots in the configured {@link PathPolicy} (project + workspace + additional roots).
 *       Relative paths still resolve against the filesystem root. This is the default for
 *       Local-mode agents and matches the Claude-Code-style "project + additional dirs"
 *       allow-list.
 *   <li>{@link #UNRESTRICTED} — absolute paths pass through unchanged. Equivalent to the legacy
 *       {@code virtualMode=false}. Escape hatch for tools or tests that need to read arbitrary
 *       host paths.
 * </ul>
 */
/**
 * 宿主机文件系统（{@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} 及其子类）的路径解析策略模式。
 *
 * <p>用于控制智能体传入绝对路径时的处理逻辑：
 *
 * <ul>
 *   <li>{@link #SANDBOXED} — 所有路径均限定在文件系统根目录内，拒绝使用 {@code ..} 跳出根目录的路径与外部绝对路径。等同于旧版 {@code virtualMode=true}。
 *   <li>{@link #ROOTED} — 仅允许落在 {@link PathPolicy} 配置的根目录列表（项目目录、工作区及附加目录）内的绝对路径；相对路径仍基于文件系统根目录解析。本地模式智能体默认采用该策略，对标 Claude-Code 风格的「项目目录+附加目录」白名单机制。
 *   <li>{@link #UNRESTRICTED} — 绝对路径直接透传不作拦截，等同于旧版 {@code virtualMode=false}。适用于需要读取宿主机任意路径的工具或测试场景，作为兜底放开选项。
 * </ul>
 */
public enum LocalFsMode {
    SANDBOXED,
    ROOTED,
    UNRESTRICTED
}
