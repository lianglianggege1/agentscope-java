/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill;

/**
 * Filter for deciding whether a skill resource should be uploaded.
 * 用于决定是否上传技能资源的过滤器。
 */
@FunctionalInterface
public interface SkillFileFilter {

    /**
     * Determines whether a resource path should be accepted for upload.
     * 确定是否应接受资源路径进行上载。
     *
     * @param resourcePath The resource path (relative to the skill root)
     * @return true if the resource should be uploaded, false otherwise
     */
    boolean accept(String resourcePath);

    /**
     * Returns a filter that accepts all files.
     * 返回一个接受所有文件的筛选器。
     *
     * @return A filter that accepts all files
     */
    static SkillFileFilter acceptAll() {
        return path -> true;
    }
}
