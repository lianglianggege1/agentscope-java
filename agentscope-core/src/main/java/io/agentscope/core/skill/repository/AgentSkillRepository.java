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
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * Repository interface for AgentSkill persistence operations.
 * AgentSKill持久化操作的存储库接口。
 *
 * <p>This interface follows the Repository Pattern and Dependency Inversion Principle,
 * allowing different storage stores (filesystem, database, remote APIs, etc.) to be
 * used interchangeably.
 * 此接口遵循存储库模式和依赖反转原则，
 * 允许不同的存储后端（文件系统、数据库、远程API等）互换使用。
 *
 * <p>Example usage:
 * <pre>{@code
 * AgentSkillRepository repo = new GitHubSkillRepository("owner/repo");
 * AgentSkill skill = repo.getByName("calculate").orElseThrow();
 * }</pre>
 *
 */
public interface AgentSkillRepository extends AutoCloseable {

    /**
     * Gets a skill by its name.
     *
     * @param name The skill name
     * @return The skill matching the name
     * @throws IllegalArgumentException if name or version is invalid
     */
    /**
     * 根据技能名称获取技能。
     *
     * @param name 技能名称
     * @return 匹配该名称的技能
     * @throws IllegalArgumentException 名称或版本无效时抛出
     */
    AgentSkill getSkill(String name);

    /**
     * Lists all available skill IDs in the repository.
     *
     * <p>Each skill ID follows the format {@code name_version_source}.
     *
     * @return List of all skill IDs (never null, may be empty)
     */
    /**
     * 列出仓库中所有可用的技能ID。
     *
     * <p>每个技能ID遵循格式 {@code name_version_source}。
     *
     * @return 全部技能ID列表（永不返回null，允许为空）
     */
    List<String> getAllSkillNames();

    /**
     * Gets all skills from the repository.
     *
     * @return List of all skills (never null, may be empty)
     */
    /**
     * 从仓库中获取所有技能。
     *
     * @return 全部技能列表（永不返回null，允许为空）
     */
    List<AgentSkill> getAllSkills();

    /**
     * Saves or updates a skill in the repository.
     *
     * <p>If a skill with the same name exists, it will be updated.
     * Otherwise, a new skill will be created.
     * <p>If the skills list is empty, return false.
     *
     * @param skills The skills to save
     * @param force Whether to force save even if the skill already exists
     * @return {@code true} if save succeeded, {@code false} otherwise
     */
    /**
     * 在仓库中保存或更新技能。
     *
     * <p>若已存在同名技能，则执行更新操作；否则新建技能。
     * <p>传入技能列表为空时返回false。
     *
     * @param skills 待保存的技能集合
     * @param force 是否强制保存，即便技能已存在
     * @return 保存成功返回 {@code true}，否则返回 {@code false}
     */
    boolean save(List<AgentSkill> skills, boolean force);

    /**
     * Deletes a skill by its skill name.
     *
     * @param skillName The skill name (never null)
     * @return {@code true} if deletion succeeded, {@code false} if skill not found
     */
    /**
     * 根据技能名称删除技能。
     *
     * @param skillName 技能名称（永不为null）
     * @return 删除成功返回 {@code true}；未找到对应技能则返回 {@code false}
     */
    boolean delete(String skillName);

    /**
     * Checks if a skill exists in the repository.
     *
     * @param skillName The skill name (never null)
     * @return {@code true} if the skill exists, {@code false} otherwise
     */
    boolean skillExists(String skillName);

    /**
     * Gets metadata about this repository.
     *
     * <p>The information includes repository type, location, and other metadata.
     *
     * @return Repository information (never null)
     */
    /**
     * 获取当前仓库的元数据信息。
     *
     * <p>信息包含仓库类型、存储位置以及其他元数据。
     *
     * @return 仓库信息（永不返回null）
     */
    AgentSkillRepositoryInfo getRepositoryInfo();

    /**
     * Gets the source identifier of this repository.
     *
     * <p>The source follows the format {@code repositoryType_location}.
     *
     * @return The source identifier (never null)
     */
    /**
     * 获取该仓库的来源标识。
     *
     * <p>来源标识遵循格式 {@code repositoryType_location}。
     *
     * @return 来源标识（永不返回null）
     */
    String getSource();

    /**
     * Sets the writeable flag for this repository.
     *
     * @param writeable Whether the repository supports write operations
     */
    /**
     * 设置该仓库的可写标识。
     *
     * @param writeable 仓库是否支持写入操作
     */
    void setWriteable(boolean writeable);

    /**
     * Checks if the repository supports write operations.
     *
     * @return {@code true} if writable, {@code false} otherwise
     */
    /**
     * 检查该仓库是否支持写入操作。
     *
     * @return 支持写入返回 {@code true}，否则返回 {@code false}
     */
    boolean isWriteable();

    /**
     * Cleans up any resources used by this repository.
     *
     * <p>Implementations should override this method if they need to release resources
     * such as network connections, file handles, or caches.
     */
    /**
     * 释放当前仓库占用的所有资源。
     *
     * <p>若实现类需要释放网络连接、文件句柄、缓存等资源，应当重写此方法。
     */
    @Override
    default void close() {
        // Default implementation does nothing
    }
}
