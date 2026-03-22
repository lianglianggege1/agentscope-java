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
package io.agentscope.core.plan.hint;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;

/**
 * Interface for generating contextual hints based on the current plan state.
 * 用于根据当前计划状态生成上下文提示的界面。
 *
 * <p>Implementations analyze the plan and its subtasks to generate appropriate guidance messages
 * that help the agent understand what actions to take next.
 * 实现分析计划及其子任务，以生成适当的指导消息，帮助代理了解接下来要采取的行动。
 */
public interface PlanToHint {

    /**
     * Generate a hint message based on the current plan state.
     * 根据当前计划状态生成提示消息。
     *
     * @param plan The current plan (can be null if no plan exists) 当前计划（如果不存在计划，则可以为空）
     * @return The generated hint message, or null if no hint is applicable 生成的提示消息，如果没有适用的提示，则为null
     */
    default String generateHint(Plan plan) {
        return generateHint(plan, PlanNotebook.builder().needUserConfirm(true).build());
    }

    /**
     * Generate a hint message based on the current plan state with planNoteBook control.
     * 使用planNoteBook控件根据当前计划状态生成提示消息。
     *
     * @param plan The current plan (can be null if no plan exists) 当前计划（如果不存在计划，则可以为空）
     * @param planNotebook related planNoteBook configuration 相关计划笔记本配置
     * @return The generated hint message, or null if no hint is applicable 生成的提示消息，如果没有适用的提示，则为null
     */
    String generateHint(Plan plan, PlanNotebook planNotebook);
}
