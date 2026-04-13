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
 * State of a subtask in a plan.
 * 在一个计划里面的子任务状态
 *
 * <p>Represents the current status of a subtask during plan execution.
 *    表示计划执行期间子任务的当前状态。
 */
public enum SubTaskState {
    /** Subtask is planned but not yet started. */
    /** 子任务已计划，但尚未开始。 */
    TODO("todo"),

    /** Subtask is currently being executed. */
    /** 子任务当前正在执行中。 */
    IN_PROGRESS("in_progress"),

    /** Subtask has been completed successfully. */
    /** 子任务已成功完成。 */
    DONE("done"),

    /** Subtask has been abandoned and will not be executed. */
    /** 子任务已被放弃，将不会执行。 */
    ABANDONED("abandoned");

    private final String value;

    SubTaskState(String value) {
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
