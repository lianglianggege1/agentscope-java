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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a subtask within a plan.
 * 代表一个子任务
 * 
 *
 * <p>
 * A subtask is a unit of work with a specific goal and expected outcome. It has
 * a state that
 * tracks its progress through the execution lifecycle.
 * 子任务是一个具有特定目标和预期结果的工作单元。
 * 它有一个状态，可以跟踪其在执行生命周期中的进度。
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>{@code
 * SubTask task = new SubTask(
 *         "Setup project",
 *         "Initialize project structure with proper directory layout",
 *         "Project scaffolding completed");
 *
 * task.setState(SubTaskState.IN_PROGRESS);
 * // ... execute task
 * task.finish("Project initialized with src/, test/, and docs/ directories");
 * }</pre>
 */
public class SubTask {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String name;
    private String description;
    private String expectedOutcome;

    @JsonIgnore private String outcome;

    private SubTaskState state = SubTaskState.TODO;
    private String createdAt;

    @JsonIgnore private String finishedAt;

    /** Default constructor for deserialization. */
    public SubTask() {
        this.createdAt = ZonedDateTime.now().format(FORMATTER);
    }

    /**
     * Create a new subtask.
     *
     * @param name            The subtask name (should be concise, not exceed 10
     *                        words)
     *                        子任务的名称（应该短，不超过10个单词）
     * @param description     The detailed description including constraints and
     *                        targets
     *                        包括约束和目标在内的详细描述
     * @param expectedOutcome The expected outcome, specific and measurable
     *                        具体和可衡量的预期成果
     */
    public SubTask(String name, String description, String expectedOutcome) {
        this();
        this.name = name;
        this.description = description;
        this.expectedOutcome = expectedOutcome;
    }

    /**
     * Mark the subtask as finished with the actual outcome.
     * 使用实际结果将子任务标记为已完成。
     *
     * @param outcome The actual outcome achieved
     *                实际取得的成果
     */
    public void finish(String outcome) {
        finish(SubTaskState.DONE, outcome);
    }

    /**
     * Mark the subtask as finished with a specific state and outcome.
     * 使用特定状态和结果将子任务标记为已完成。
     *
     * @param state   The final state (e.g., DONE or ABANDONED)
     *                这个最终的状态（例如： DONE 或 ABANDONED）
     * @param outcome The actual outcome or reason for abandoning
     *                 放弃的实际结果或原因
     * @throws IllegalArgumentException if the state is not a terminal state (DONE
     *                                  or ABANDONED)
     */
    public void finish(SubTaskState state, String outcome) {
        if (state != SubTaskState.DONE && state != SubTaskState.ABANDONED) {
            throw new IllegalArgumentException(
                    "SubTask can only be finished with DONE or ABANDONED state, but got: " + state);
        }
        this.state = state;
        this.outcome = outcome;
        this.finishedAt = ZonedDateTime.now().format(FORMATTER);
    }

    /**
     * Convert to one-line markdown representation.
     * 转换为单行Markdown表示。
     *
     * @return One-line markdown string
     */
    public String toOneLineMarkdown() {
        String statusPrefix =
                switch (state) {
                    case TODO -> "- [ ]";
                    case IN_PROGRESS -> "- [ ] [WIP]";
                    case DONE -> "- [x]";
                    case ABANDONED -> "- [ ] [Abandoned]";
                };

        String displayName = (name != null) ? name : "Unnamed Subtask";
        return statusPrefix + " " + displayName;
    }

    /**
     * Convert to markdown representation.
     * 转换为Markdown表示。
     *
     * @param detailed Whether to include detailed information
     *                 是否包含详细信息
     * @return Markdown string representation
     *         Markdown字符串表示
     */
    public String toMarkdown(boolean detailed) {
        if (!detailed) {
            return toOneLineMarkdown();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(toOneLineMarkdown()).append("\n");
        sb.append("\t- Created At: ").append(createdAt != null ? createdAt : "N/A").append("\n");
        sb.append("\t- Description: ")
                .append(description != null ? description : "N/A")
                .append("\n");
        sb.append("\t- Expected Outcome: ")
                .append(expectedOutcome != null ? expectedOutcome : "N/A")
                .append("\n");
        sb.append("\t- State: ").append(state.getValue());

        if (state == SubTaskState.DONE) {
            sb.append("\n");
            sb.append("\t- Finished At: ")
                    .append(finishedAt != null ? finishedAt : "N/A")
                    .append("\n");
            sb.append("\t- Actual Outcome: ").append(outcome != null ? outcome : "N/A");
        }

        return sb.toString();
    }

    // Getters and Setters

    /**
     * Gets the name of this subtask.
     *
     * @return The subtask name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this subtask.
     *
     * @param name The subtask name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the description of this subtask.
     *
     * @return The detailed description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of this subtask.
     *
     * @param description The detailed description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Gets the expected outcome for this subtask.
     *
     * @return The expected outcome
     */
    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    /**
     * Sets the expected outcome for this subtask.
     *
     * @param expectedOutcome The expected outcome
     */
    public void setExpectedOutcome(String expectedOutcome) {
        this.expectedOutcome = expectedOutcome;
    }

    /**
     * Gets the actual outcome achieved by this subtask.
     *
     * @return The actual outcome, or null if not finished
     */
    public String getOutcome() {
        return outcome;
    }

    /**
     * Sets the actual outcome of this subtask.
     *
     * @param outcome The actual outcome
     */
    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    /**
     * Gets the current state of this subtask.
     *
     * @return The subtask state (TODO, IN_PROGRESS, DONE, or ABANDONED)
     */
    public SubTaskState getState() {
        return state;
    }

    /**
     * Sets the state of this subtask.
     *
     * @param state The subtask state
     */
    public void setState(SubTaskState state) {
        this.state = state;
    }

    /**
     * Gets the creation timestamp of this subtask.
     *
     * @return The creation time (formatted as "yyyy-MM-dd HH:mm:ss")
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp of this subtask.
     *
     * @param createdAt The creation time
     */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Gets the timestamp when this subtask was finished.
     *
     * @return The finish time, or null if not finished yet
     */
    public String getFinishedAt() {
        return finishedAt;
    }

    /**
     * Sets the timestamp when this subtask was finished.
     *
     * @param finishedAt The finish time
     */
    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }
}
