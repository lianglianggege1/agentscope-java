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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.util.ExceptionUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Unified executor for tool execution with infrastructure concerns.
 * 针对基础设施问题的工具执行的统一执行器。
 *
 * <p>This class consolidates all tool execution logic including:
 *    此类整合了所有工具执行逻辑，包括：
 * <ul>
 *   <li>Single and batch tool execution 单次和批量工具执行</li>
 *   <li>Parallel/sequential execution control 并行/顺序执行控制</li>
 *   <li>Timeout and retry handling 超时和重试处理</li>
 *   <li>Thread scheduling 线程调度</li>
 *   <li>Schema validation before execution 执行前的架构验证</li>
 * </ul>
 *
 * <p>Execution modes:
 * <ul>
 *   <li>Default: Uses Reactor's Schedulers.boundedElastic() for async I/O operations</li>
 *                使用Reactor的Scheduler.boundedElastic（）进行异步I/O操作
 *   <li>Custom: Uses user-provided ExecutorService</li>
 *               使用用户提供的ExecutorService
 * </ul>
 */
class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    private final Toolkit toolkit;
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolkitConfig config;
    private final ExecutorService executorService;
    private BiConsumer<ToolUseBlock, ToolResultBlock> userChunkCallback;
    private BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback;

    /**
     * Create a tool executor with Reactor Schedulers (recommended).
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config) {
        this(toolkit, toolRegistry, groupManager, config, null);
    }

    /**
     * Create a tool executor with custom executor service.
     */
    ToolExecutor(
            Toolkit toolkit,
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolkitConfig config,
            ExecutorService executorService) {
        this.toolkit = toolkit;
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.config = config;
        this.executorService = executorService;
    }

    /**
     * Set the user-defined chunk callback for streaming tool responses.
     */
    void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.userChunkCallback = callback;
    }

    /**
     * Set the framework-internal chunk callback used by ReActAgent hooks.
     */
    void setInternalChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        this.internalChunkCallback = callback;
    }

    /**
     * Get the user-defined chunk callback.
     * Used by Toolkit.copy() to preserve user callbacks during deep copy.
     */
    BiConsumer<ToolUseBlock, ToolResultBlock> getChunkCallback() {
        return this.userChunkCallback;
    }

    /**
     * Combine the user-defined and internal chunk callbacks.
     */
    private BiConsumer<ToolUseBlock, ToolResultBlock> getEffectiveChunkCallback() {
        if (internalChunkCallback == null) {
            return userChunkCallback != null
                    ? (toolUse, chunk) ->
                            invokeChunkCallback("user", userChunkCallback, toolUse, chunk)
                    : null;
        }
        if (userChunkCallback == null) {
            return (toolUse, chunk) ->
                    invokeChunkCallback("internal", internalChunkCallback, toolUse, chunk);
        }
        return (toolUse, chunk) -> {
            invokeChunkCallback("internal", internalChunkCallback, toolUse, chunk);
            invokeChunkCallback("user", userChunkCallback, toolUse, chunk);
        };
    }

    /**
     * Invoke a chunk callback without allowing it to block other callbacks.
     */
    private void invokeChunkCallback(
            String callbackType,
            BiConsumer<ToolUseBlock, ToolResultBlock> callback,
            ToolUseBlock toolUse,
            ToolResultBlock chunk) {
        try {
            callback.accept(toolUse, chunk);
        } catch (Exception e) {
            logger.warn(
                    "Chunk callback '{}' failed for tool '{}': {}",
                    callbackType,
                    toolUse.getName(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    e);
        }
    }

    // ==================== Single Tool Execution ====================

    /**
     * Execute a single tool call with full infrastructure support.
     *
     * @param param Tool call parameters
     * @return Mono containing execution result
     */
    Mono<ToolResultBlock> execute(ToolCallParam param) {
        return TracerRegistry.get().callTool(this.toolkit, param, () -> executeCore(param));
    }

    /**
     * Core tool execution logic.
     * 核心工具执行逻辑。
     *
     * <p>This method handles:
     *    此方法处理：
     * <ul>
     *   <li>Tool lookup and validation 工具查找和验证</li>
     *   <li>Group activation check 组激活检查</li>
     *   <li>Parameter merging (preset + input) 参数合并（预设+输入）</li>
     *   <li>Context merging 上下文合并</li>
     *   <li>Schema validation 模式验证</li>
     *   <li>Actual tool invocation 实际工具调用</li>
     * </ul>
     */
    private Mono<ToolResultBlock> executeCore(ToolCallParam param) {
        ToolUseBlock toolCall = param.getToolUseBlock();
        AgentTool tool = toolRegistry.getTool(toolCall.getName());

        if (tool == null) {
            return Mono.just(ToolResultBlock.error("Tool not found: " + toolCall.getName()));
        }

        // Check tool activation
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolCall.getName());
        if (registered != null && !groupManager.isActiveTool(toolCall.getName())) {
            String errorMsg =
                    String.format(
                            "Unauthorized tool call: '%s' is not available", toolCall.getName());
            logger.warn(errorMsg);
            return Mono.just(ToolResultBlock.error(errorMsg));
        }

        // Validate input against schema
        String validationError =
                ToolValidator.validateInput(toolCall.getContent(), tool.getParameters());
        if (validationError != null) {
            String errorMsg =
                    String.format(
                            "Parameter validation failed for tool '%s': %s\n"
                                    + "Please correct the parameters and try again.",
                            toolCall.getName(), validationError);
            logger.debug(errorMsg);
            return Mono.just(ToolResultBlock.error(errorMsg));
        }

        // Merge context
        ToolExecutionContext toolkitContext = config.getDefaultContext();
        ToolExecutionContext finalContext =
                ToolExecutionContext.merge(param.getContext(), toolkitContext);

        // Create emitter for streaming
        ToolEmitter toolEmitter = new DefaultToolEmitter(toolCall, getEffectiveChunkCallback());

        // Merge preset parameters with input
        Map<String, Object> mergedInput = new HashMap<>();
        if (registered != null) {
            mergedInput.putAll(registered.getPresetParameters());
        }
        if (param.getInput() != null && !param.getInput().isEmpty()) {
            mergedInput.putAll(param.getInput());
        } else if (toolCall.getInput() != null) {
            mergedInput.putAll(toolCall.getInput());
        }

        // Build final execution param
        ToolCallParam executionParam =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .input(mergedInput)
                        .agent(param.getAgent())
                        .context(finalContext)
                        .emitter(toolEmitter)
                        .build();

        return tool.callAsync(executionParam)
                .onErrorResume(
                        ToolSuspendException.class,
                        e -> {
                            // Convert ToolSuspendException to suspended result
                            logger.debug(
                                    "Tool '{}' suspended: {}",
                                    toolCall.getName(),
                                    e.getReason() != null ? e.getReason() : "no reason");
                            return Mono.just(ToolResultBlock.suspended(toolCall, e));
                        })
                .onErrorResume(
                        e -> {
                            String errorMsg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        });
    }

    // ==================== Batch Tool Execution ====================

    /**
     * Execute multiple tool calls with concurrency control, timeout, and retry.
     * 执行多个具有并发控制、超时和重试的工具调用。
     *
     * @param toolCalls List of tool calls to execute
     * @param parallel Whether to execute in parallel
     * @param executionConfig Execution configuration
     * @param agent The agent making the calls (may be null)
     * @param agentContext The agent-level context (may be null)
     * @return Mono containing list of results
     */
    Mono<List<ToolResultBlock>> executeAll(
            List<ToolUseBlock> toolCalls,
            boolean parallel,
            ExecutionConfig executionConfig,
            Agent agent,
            ToolExecutionContext agentContext) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }

        logger.debug("Executing {} tool calls (parallel={})", toolCalls.size(), parallel);

        // Map each tool call to an execution Mono
        List<Mono<ToolResultBlock>> monos =
                toolCalls.stream()
                        .map(
                                toolCall ->
                                        executeWithInfrastructure(
                                                toolCall, executionConfig, agent, agentContext))
                        .toList();

        // Parallel or sequential execution
        if (parallel) {
            return Flux.mergeSequential(monos).collectList();
        }
        return Flux.concat(monos).collectList();
    }

    /**
     * Execute a single tool call with infrastructure (scheduling, timeout, retry).
     * 使用基础架构执行单个工具调用（调度、超时、重试）。
     */
    private Mono<ToolResultBlock> executeWithInfrastructure(
            ToolUseBlock toolCall,
            ExecutionConfig executionConfig,
            Agent agent,
            ToolExecutionContext agentContext) {
        // Build tool call parameter
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolCall)
                        .agent(agent)
                        .context(agentContext)
                        .build();

        // Get core execution
        Mono<ToolResultBlock> execution = execute(param);

        // Apply infrastructure layers
        execution = applyScheduling(execution);
        execution = applyTimeout(execution, executionConfig, toolCall);
        execution = applyRetry(execution, executionConfig, toolCall);

        // Add tool metadata and error handling
        return execution
                .map(result -> result.withIdAndName(toolCall.getId(), toolCall.getName()))
                .onErrorResume(
                        e -> {
                            logger.warn("Tool call failed: {}", toolCall.getName(), e);
                            String errorMsg = ExceptionUtils.getErrorMessage(e);
                            return Mono.just(
                                    ToolResultBlock.error("Tool execution failed: " + errorMsg));
                        });
    }

    // ==================== Infrastructure Methods ====================

    private Mono<ToolResultBlock> applyScheduling(Mono<ToolResultBlock> execution) {
        if (executorService == null) {
            return execution.subscribeOn(Schedulers.boundedElastic());
        }
        return execution.subscribeOn(Schedulers.fromExecutor(executorService));
    }

    private Mono<ToolResultBlock> applyTimeout(
            Mono<ToolResultBlock> execution, ExecutionConfig config, ToolUseBlock toolCall) {
        if (config == null || config.getTimeout() == null) {
            return execution;
        }

        Duration timeout = config.getTimeout();
        logger.debug("Applied timeout: {} for tool: {}", timeout, toolCall.getName());

        return execution.timeout(
                timeout,
                Mono.error(new RuntimeException("Tool execution timeout after " + timeout)));
    }

    private Mono<ToolResultBlock> applyRetry(
            Mono<ToolResultBlock> execution, ExecutionConfig config, ToolUseBlock toolCall) {
        if (config == null || config.getMaxAttempts() == null || config.getMaxAttempts() <= 1) {
            return execution;
        }

        Integer maxAttempts = config.getMaxAttempts();
        Duration initialBackoff =
                config.getInitialBackoff() != null
                        ? config.getInitialBackoff()
                        : Duration.ofSeconds(1);
        Duration maxBackoff =
                config.getMaxBackoff() != null ? config.getMaxBackoff() : Duration.ofSeconds(10);
        Predicate<Throwable> retryOn =
                config.getRetryOn() != null ? config.getRetryOn() : error -> true;

        Retry retrySpec =
                Retry.backoff(maxAttempts - 1, initialBackoff)
                        .maxBackoff(maxBackoff)
                        .jitter(0.5)
                        .filter(retryOn)
                        .doBeforeRetry(
                                signal ->
                                        logger.warn(
                                                "Retrying tool call (attempt {}/{}) due to: {}",
                                                signal.totalRetriesInARow() + 1,
                                                maxAttempts - 1,
                                                signal.failure().getMessage(),
                                                signal.failure()));

        logger.debug(
                "Applied retry config: maxAttempts={} for tool: {}",
                maxAttempts,
                toolCall.getName());

        return execution.retryWhen(retrySpec);
    }
}
