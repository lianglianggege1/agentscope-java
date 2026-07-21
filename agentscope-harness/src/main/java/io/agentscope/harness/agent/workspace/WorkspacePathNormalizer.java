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

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes file paths to workspace-relative form by stripping the active mode's workspace
 * prefix.
 *
 * <p>Only the prefix matching the current filesystem mode is registered, so there is no risk
 * of a sandbox prefix ({@code /workspace/}) accidentally matching a real host directory in
 * local mode, or vice versa.
 *
 * <p>Paths that don't match any registered prefix pass through unchanged, preserving the
 * ability to access non-workspace files in modes that allow it.
 */
/**
 * 去除当前运行模式的工作区路径前缀，将文件路径统一标准化为工作区相对路径格式。
 *
 * <p>仅注册与当前文件系统运行模式匹配的路径前缀，因此不会出现沙箱前缀{@code /workspace/}在本地模式下误匹配宿主机真实目录的问题，反之亦然。
 *
 * <p>不匹配任何已注册前缀的路径会原样返回，保证在允许访问外部文件的运行模式中仍可读取工作区以外的文件。
 */
public final class WorkspacePathNormalizer {

    private final List<String> prefixes;

    private WorkspacePathNormalizer(List<String> prefixes) {
        this.prefixes = List.copyOf(prefixes);
    }

    /**
     * Creates a normalizer that strips the given prefix.
     *
     * @param workspacePrefix the workspace root path for the active mode (e.g.
     *     {@code "/workspace"} for sandbox, or the host workspace absolute path for local)
     */
    /**
     * 创建路径标准化处理器，用于移除指定路径前缀。
     *
     * @param workspacePrefix 当前运行模式对应的工作区根路径（沙箱环境为 {@code "/workspace"}，本地模式为宿主机工作区绝对路径）
     */
    public static WorkspacePathNormalizer of(String workspacePrefix) {
        List<String> list = new ArrayList<>(1);
        String trimmed = trimTrailingSlash(workspacePrefix);
        if (trimmed != null && !trimmed.isEmpty()) {
            list.add(trimmed);
        }
        return new WorkspacePathNormalizer(list);
    }

    /**
     * Creates a normalizer that tries multiple prefixes in order. Use only when the active
     * mode has more than one valid prefix (e.g. local-with-shell where project dir and
     * workspace dir are both valid roots).
     */
    /**
     * 创建按顺序依次匹配多个前缀的路径标准化处理器。仅适用于当前运行模式存在多个合法根路径前缀的场景（例如带Shell的本地模式，项目目录与工作区目录均为有效根目录）。
     */
    public static WorkspacePathNormalizer of(String... workspacePrefixes) {
        List<String> list = new ArrayList<>(workspacePrefixes.length);
        for (String p : workspacePrefixes) {
            String trimmed = trimTrailingSlash(p);
            if (trimmed != null && !trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return new WorkspacePathNormalizer(list);
    }

    /**
     * Normalize a path to workspace-relative form by stripping the active mode's prefix.
     *
     * @param path the raw path (absolute or relative)
     * @return workspace-relative path, or the original path if no registered prefix matched
     */
    /**
     * 移除当前运行模式的路径前缀，将路径标准化为工作区相对路径。
     *
     * @param path 原始路径（可为绝对路径或相对路径）
     * @return 工作区相对路径；若无匹配的注册前缀，则返回原路径
     */
    public String normalize(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        for (String prefix : prefixes) {
            String stripped = tryStrip(path, prefix);
            if (stripped != null) {
                return stripped;
            }
        }
        return path;
    }

    private static String tryStrip(String path, String prefix) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedPrefix = prefix.replace('\\', '/');
        if (normalizedPath.startsWith(normalizedPrefix + "/")) {
            return normalizedPath.substring(normalizedPrefix.length() + 1);
        }
        if (normalizedPath.equals(normalizedPrefix)) {
            return ".";
        }
        return null;
    }

    private static String trimTrailingSlash(String s) {
        if (s != null && s.length() > 1 && s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
