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
package io.agentscope.core.tool;

import io.agentscope.core.model.ExecutionConfig;
import java.util.concurrent.ExecutorService;

/**
 * Configuration for Toolkit execution behavior.
 * 工具包执行行为的配置。
 *
 * <p>This class defines how tools are executed:
 *    此类定义了工具的执行方式：
 * - Parallel vs Sequential execution 并行执行与顺序执行
 * - Custom ExecutorService (optional) 自定义执行器服务（可选）
 * - Execution configuration for timeout and retry 超时和重试的执行配置
 *
 * <p>By default, all tool execution is asynchronous using Reactor's Schedulers.
 *    默认情况下，所有工具的执行都是异步的，使用 Reactor 的调度器。
 * The default execution config provides 5-minute timeout with no retry (1 attempt).
 *    默认执行配置提供 5 分钟超时，不重试（1 次尝试）。
 */
public class ToolkitConfig {

    private final boolean parallel;
    private final ExecutorService executorService;
    private final ExecutionConfig executionConfig;
    private final boolean allowToolDeletion;
    private final ToolExecutionContext defaultContext;

    private ToolkitConfig(Builder builder) {
        this.parallel = builder.parallel;
        this.executorService = builder.executorService;
        this.executionConfig = builder.executionConfig;
        this.allowToolDeletion = builder.allowToolDeletion;
        this.defaultContext = builder.defaultContext;
    }

    /**
     * Whether tools should be executed in parallel.
     * 工具是否应该并行执行。
     *
     * @return true if parallel execution is enabled
     */
    public boolean isParallel() {
        return parallel;
    }

    /**
     * Get the custom executor service if provided.
     * 如果提供，请获取自定义执行人服务。
     *
     * @return ExecutorService or null if using default Reactor schedulers
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Check if a custom executor service was provided.
     * 检查是否提供了自定义执行服务。
     *
     * @return true if custom executor is configured
     */
    public boolean hasCustomExecutor() {
        return executorService != null;
    }

    /**
     * Get the execution configuration for timeout and retry.
     * 获取超时和重试的执行配置。
     *
     * @return ExecutionConfig or null if not configured (defaults will be applied)
     */
    public ExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    /**
     * Check if tool deletion is allowed.
     * 检查是否允许删除工具。
     * 
     * @return true if tool deletion is allowed (default), false otherwise
     */
    public boolean isAllowToolDeletion() {
        return allowToolDeletion;
    }

    /**
     * Get the default tool execution context.
     * 获取默认工具执行上下文。
     *
     * <p>This context is used as the base for all tool calls and can be overridden by
     * agent-level or call-level contexts.
     *
     * @return The default context, or null if not configured
     */
    public ToolExecutionContext getDefaultContext() {
        return defaultContext;
    }

    /**
     * Create a new builder for ToolkitConfig.
     * 为 ToolkitConfig 创建一个新的构建器。
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the default configuration (sequential execution using Reactor).
     * 获取默认配置（使用 Reactor 进行顺序执行）。
     *
     * @return Default ToolkitConfig
     */
    public static ToolkitConfig defaultConfig() {
        return builder().build();
    }

    /**
     * Builder for ToolkitConfig.
     */
    public static class Builder {
        private boolean parallel = false;
        private ExecutorService executorService;
        private ExecutionConfig executionConfig;
        private boolean allowToolDeletion = true;
        private ToolExecutionContext defaultContext;

        private Builder() {}

        /**
         * Set whether to execute tools in parallel.
         *
         * @param parallel true for parallel execution, false for sequential
         * @return this builder
         */
        public Builder parallel(boolean parallel) {
            this.parallel = parallel;
            return this;
        }

        /**
         * Set a custom executor service for tool execution.
         * If not set, Reactor's Schedulers.boundedElastic() will be used.
         *
         * @param executorService Custom executor service
         * @return this builder
         */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Set the execution configuration for timeout and retry behavior.
         * If not set, tool execution defaults will be applied (5 minutes timeout, no retry).
         *
         * @param executionConfig Execution configuration for tools
         * @return this builder
         */
        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.executionConfig = executionConfig;
            return this;
        }

        /**
         * Set whether tool deletion is allowed.
         * When set to false, calls to removeTool(), removeToolGroups(), and
         * updateToolGroups(groups, false) will be ignored.
         *
         * @param allowToolDeletion true to allow deletion (default), false to prevent
         * @return this builder
         */
        public Builder allowToolDeletion(boolean allowToolDeletion) {
            this.allowToolDeletion = allowToolDeletion;
            return this;
        }

        /**
         * Set the default tool execution context for all tools.
         *
         * <p>This context will be used as the base for all tool executions and can be
         * overridden by agent-level or call-level contexts through the merge mechanism.
         *
         * @param defaultContext The default execution context
         * @return this builder
         */
        public Builder defaultContext(ToolExecutionContext defaultContext) {
            this.defaultContext = defaultContext;
            return this;
        }

        /**
         * Build the ToolkitConfig.
         *
         * @return Configured ToolkitConfig instance
         */
        public ToolkitConfig build() {
            return new ToolkitConfig(this);
        }
    }
}
