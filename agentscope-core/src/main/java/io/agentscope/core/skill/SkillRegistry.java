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
package io.agentscope.core.skill;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing skill registration and activation state.
 * 用于管理技能注册和激活状态的注册表。
 *
 * <p>This class provides basic storage and retrieval operations for skills.
 *    本课程提供技能的基本存储和检索操作。
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve skills
 *       存储和检索技能
 *   <li>Track skill metadata and activation state
 *       跟踪技能元数据和激活状态
 * </ul>
 *
 * <p><b>Design principle:</b>
 * This is a pure storage layer. All parameters are assumed to be non-null
 * unless explicitly documented. Parameter validation should be performed
 * at the Toolkit layer.
 * 设计原则：这是一个纯存储层。除非另有明确说明，所有参数均假定为非空值。参数验证应在工具包层执行。
 */
class SkillRegistry {
    // 技能就存在线程安全的集合中
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();
    // 已经注册的技能也存在线程安全的集合中
    private final Map<String, RegisteredSkill> registeredSkills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill with its metadata.
     * 注册技能及其元数据。
     *
     * <p>If the skill is already registered, it will be replaced.
     * 如果该技能已被注册，则会被替换。
     *
     * @param skillId The unique skill identifier (must not be null) 唯一技能标识符（不能为空）
     * @param skill The skill implementation (must not be null) 技能实现（不能为空）
     * @param registered The registered skill wrapper containing metadata (must not be null) 已注册的技能包装器包含元数据（不能为空）
     */
    void registerSkill(String skillId, AgentSkill skill, RegisteredSkill registered) {
        skills.put(skillId, skill);
        registeredSkills.put(skillId, registered);
    }

    // ==================== Activation Management ====================

    /**
     * Sets the activation state of a skill.
     * 设置技能的激活状态。
     *
     * @param skillId The skill ID (must not be null) 技能 ID（不能为空）
     * @param active Whether to activate the skill 是否激活该技能
     */
    void setSkillActive(String skillId, boolean active) {
        RegisteredSkill registered = registeredSkills.get(skillId);
        if (registered != null) {
            registered.setActive(active);
        }
    }

    /**
     * Sets the activation state of all skills.
     * 设置所有技能的激活状态。
     *
     * @param active Whether to activate all skills
     */
    void setAllSkillsActive(boolean active) {
        registeredSkills.values().forEach(r -> r.setActive(active));
    }

    // ==================== Query Operations ====================

    /**
     * Gets a skill by ID.
     *
     * @param skillId The skill ID (must not be null)
     * @return The skill instance, or null if not found
     */
    AgentSkill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * Gets a registered skill by ID.
     *
     * @param skillId The skill ID (must not be null)
     * @return The registered skill, or null if not found
     */
    RegisteredSkill getRegisteredSkill(String skillId) {
        return registeredSkills.get(skillId);
    }

    /**
     * Gets all skill IDs.
     *
     * @return Set of skill IDs (never null, may be empty)
     */
    Set<String> getSkillIds() {
        return new HashSet<>(skills.keySet());
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillId The skill ID (must not be null)
     * @return true if the skill exists, false otherwise
     */
    boolean exists(String skillId) {
        return skills.containsKey(skillId);
    }

    /**
     * Gets all registered skills.
     *
     * @return Map of skill IDs to registered skills (never null, may be empty)
     */
    Map<String, RegisteredSkill> getAllRegisteredSkills() {
        return new ConcurrentHashMap<>(registeredSkills);
    }

    // ==================== Removal Operations ====================

    /**
     * Removes a skill completely.
     *
     * @param skillId The skill ID (must not be null)
     */
    void removeSkill(String skillId) {
        skills.remove(skillId);
        registeredSkills.remove(skillId);
    }
}
