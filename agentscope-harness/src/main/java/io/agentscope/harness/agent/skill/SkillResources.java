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
package io.agentscope.harness.agent.skill;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Lazy resource accessor for a single skill.
 *
 * <p>Decouples "what resources exist" from "how to fetch their contents", so that repositories
 * backed by an {@code AbstractFilesystem} can answer {@code load_skill_through_path} calls
 * on demand without preloading every byte of every skill into memory at registration time.
 *
 * <p>All paths are relative to the skill root (e.g. {@code "references/guide.md"},
 * {@code "scripts/run.py"}). Absolute paths and path traversal sequences ({@code ".."}) are
 * rejected by implementations.
 */
/**
 * 单个技能的惰性资源访问器。
 *
 * <p>将“存在哪些资源”与“如何获取资源内容”进行解耦，
 * 使得基于 {@code AbstractFilesystem} 实现的仓库可以按需响应 {@code load_skill_through_path} 请求，
 * 无需在技能注册阶段将所有资源的全部字节预加载至内存。
 *
 * <p>所有路径均相对于技能根目录（例如 {@code "references/guide.md"}、{@code "scripts/run.py"}）。
 * 实现类需要拒绝绝对路径以及路径穿越序列（{@code ".."}）。
 */
public interface SkillResources {

    /**
     * Reads a text resource as UTF-8.
     *
     * @param relativePath path relative to the skill root
     * @return content, or empty if the resource does not exist or read fails
     */
    /**
     * 以UTF-8编码读取文本资源。
     *
     * @param relativePath 相对于技能根目录的路径
     * @return 资源内容；资源不存在或读取失败时返回空字符串
     */
    Optional<String> read(String relativePath);

    /**
     * Reads a binary resource.
     *
     * @param relativePath path relative to the skill root
     * @return content, or empty if the resource does not exist or read fails
     */
    /**
     * 读取二进制资源。
     *
     * @param relativePath 相对于技能根目录的路径
     * @return 资源内容；资源不存在或读取失败时返回空
     */
    Optional<byte[]> readBinary(String relativePath);

    /**
     * Lists all relative resource paths that this accessor can serve.
     *
     * <p>Used by {@code SkillLoadTool} to build a friendly "available resources" enumeration
     * when a requested path is not found.
     *
     * @return unmodifiable list of relative paths; never {@code null}
     */
    /**
     * 列出该访问器可提供服务的全部资源相对路径。
     *
     * <p>由 {@code SkillLoadTool} 使用，当请求路径未找到时，生成友好的“可用资源”清单。
     *
     * @return 不可修改的相对路径列表；永不返回 {@code null}
     */
    List<String> list();

    /**
     * Returns a no-op accessor that reports no resources.
     *
     * @return shared empty instance
     */
    /**
     * 返回一个无操作访问器，该访问器标识不存在任何资源。
     *
     * @return 共享的空实例
     */
    static SkillResources empty() {
        return EmptySkillResources.INSTANCE;
    }
}

final class EmptySkillResources implements SkillResources {

    static final EmptySkillResources INSTANCE = new EmptySkillResources();

    private EmptySkillResources() {}

    @Override
    public Optional<String> read(String relativePath) {
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> readBinary(String relativePath) {
        return Optional.empty();
    }

    @Override
    public List<String> list() {
        return Collections.emptyList();
    }
}
