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
package io.agentscope.core.plan.model;

/**
 * State of a plan.
 * 计划的状态
 *
 * <p>Represents the overall status of a plan during its lifecycle.
 *    表示计划全生命周期的状态。
 */
public enum PlanState {
    /** Plan is created but not yet started. */
    /** 计划已创建但尚未开始 */
    TODO("todo"),

    /** Plan execution is in progress. */
    /** 计划执行正在进行中 */
    IN_PROGRESS("in_progress"),

    /** Plan has been completed successfully. */
    /** 计划已成功完成 */
    DONE("done"),

    /** Plan has been abandoned and will not be executed. */
    /** 计划已被放弃，将不会被执行 */
    ABANDONED("abandoned");

    private final String value;

    PlanState(String value) {
        this.value = value;
    }

    /**
     * Get the string representation of this state.
     *
     * @return The state value as a string
     */
    public String getValue() {
        return value;
    }
}
