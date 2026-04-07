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
package io.agentscope.core;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.session.Session;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.state.AgentMetaState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.state.ToolkitState;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ReAct (Reasoning and Acting) Agent implementation.
 * ReAct (推理与行动) 智能体实现。
 *
 * <p>ReAct is an agent design pattern that combines reasoning (thinking and planning) with acting
 * (tool execution) in an iterative loop. The agent alternates between these two phases until it
 * either completes the task or reaches the maximum iteration limit.
 * ReAct 是一种智能体设计模式，
 * 将推理（思考和规划）与行动（工具执行）结合在一个迭代循环中。
 * 智能体在这两个阶段之间交替，直到完成任务或达到最大迭代限制。
 *
 * <p><b>Key Features:</b>
 * 主要特性：
 * <ul>
 *   <li><b>Reactive Streaming:</b> Uses Project Reactor for non-blocking execution
 *   响应式流：使用 Project Reactor 实现非阻塞执行
 *   <li><b>Hook System:</b> Extensible hooks for monitoring and intercepting agent execution
 *   钩子系统：可扩展的钩子，用于监控和拦截智能体执行
 *   <li><b>HITL Support:</b> Human-in-the-loop via stopAgent() in PostReasoningEvent/PostActingEvent
 *   人机协作支持：通过 PostReasoningEvent/PostActingEvent 中的 stopAgent() 实现人机协作
 *   <li><b>Structured Output:</b> StructuredOutputCapableAgent provides type-safe output generation
 *   结构化输出：StructuredOutputCapableAgent 提供类型安全的输出生成
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create a model
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen-plus")
 *     .build();
 *
 * // Create a toolkit with tools
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(new MyToolClass());
 *
 * // Build the agent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .sysPrompt("You are a helpful assistant.")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .memory(new InMemoryMemory())
 *     .maxIters(10)
 *     .build();
 *
 * // Use the agent
 * Msg response = agent.call(Msg.builder()
 *     .name("user")
 *     .role(MsgRole.USER)
 *     .content(TextBlock.builder().text("What's the weather?").build())
 *     .build()).block();
 * }</pre>
 *
 * @see StructuredOutputCapableAgent
 */
public class ReActAgent extends StructuredOutputCapableAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    // ==================== Core Dependencies ====================
    // 核心依赖
    // 记忆
    private final Memory memory;
    // 系统提示词
    private final String sysPrompt;
    // 模型
    private final Model model;
    // 最大迭代次数
    private final int maxIters;
    // 模型执行配置
    private final ExecutionConfig modelExecutionConfig;
    // 工具执行配置
    private final ExecutionConfig toolExecutionConfig;
    // 生成选项
    private final GenerateOptions generateOptions;
    // 笔记
    private final PlanNotebook planNotebook;
    // 工具执行上下文
    private final ToolExecutionContext toolExecutionContext;
    // 状态持久化
    private final StatePersistence statePersistence;

    // ==================== Constructor ====================

    private ReActAgent(Builder builder, Toolkit agentToolkit) {
        super(
                builder.name,
                builder.description,
                builder.checkRunning,
                new ArrayList<>(builder.hooks),
                agentToolkit,
                builder.structuredOutputReminder);

        this.memory = builder.memory;
        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.generateOptions = builder.generateOptions;
        this.planNotebook = builder.planNotebook;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.statePersistence =
                builder.statePersistence != null
                        ? builder.statePersistence
                        : StatePersistence.all();
    }

    // ==================== New StateModule API ====================

    /**
     * Save agent state to the session using the new API.
     * 使用一个新API保存智能体状态到会话中
     *
     * <p>This method saves the state of all managed components according to the StatePersistence
     * configuration:
     * 此方法根据StatePersistence配置保存所有受管组件的状态
     *
     * <ul>
     *   <li>Agent metadata (always saved)
     *       智能体元数据（总是保存）
     *   <li>Memory messages (if memoryManaged is true)
     *       内存消息（如果memoryManaged为true）
     *   <li>Toolkit activeGroups (if toolkitManaged is true)
     *       工具包活动组（如果toolkitManaged为true）
     *   <li>PlanNotebook state (if planNotebookManaged is true)
     *       笔记状态（如果planNotebookManaged为true）
     * </ul>
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        // Save agent metadata
        // 保存智能体元数据
        session.save(
                sessionKey,
                "agent_meta",
                new AgentMetaState(getAgentId(), getName(), getDescription(), sysPrompt));

        // Save memory if managed
        // 如果管理保存到记忆中
        if (statePersistence.memoryManaged()) {
            memory.saveTo(session, sessionKey);
        }

        // Save toolkit activeGroups if managed
        // 如果管理保存到工具包活动组中
        if (statePersistence.toolkitManaged() && toolkit != null) {
            session.save(
                    sessionKey,
                    "toolkit_activeGroups",
                    new ToolkitState(toolkit.getActiveGroups()));
        }

        // Save PlanNotebook if managed
        // 如果管理保存到笔记中
        if (statePersistence.planNotebookManaged() && planNotebook != null) {
            planNotebook.saveTo(session, sessionKey);
        }
    }

    /**
     * Load agent state from the session using the new API.
     * 使用新API从会话中加载智能体状态
     *
     * <p>This method loads the state of all managed components according to the StatePersistence
     * configuration.
     * 此方法根据 StatePersistence 配置加载所有受管组件的状态。
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     */
    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        // Load memory if managed
        // 如果管理从记忆中加载
        if (statePersistence.memoryManaged()) {
            memory.loadFrom(session, sessionKey);
        }

        // Load toolkit activeGroups if managed
        // 如果管理从工具包活动组中加载
        if (statePersistence.toolkitManaged() && toolkit != null) {
            session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class)
                    .ifPresent(state -> toolkit.setActiveGroups(state.activeGroups()));
        }

        // Load PlanNotebook if managed
        // 如果管理从笔记中加载
        if (statePersistence.planNotebookManaged() && planNotebook != null) {
            planNotebook.loadFrom(session, sessionKey);
        }
    }

    // ==================== Protected API ====================

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        Set<String> pendingIds = getPendingToolUseIds();

        // No pending tools -> normal processing
        if (pendingIds.isEmpty()) {
            addToMemory(msgs);
            return executeIteration(0);
        }

        // Has pending tools -> validate and add tool results
        validateAndAddToolResults(msgs, pendingIds);
        // 有待处理的工具？执行工具 ： 继续迭代执行
        return hasPendingToolUse() ? acting(0) : executeIteration(0);
    }

    /**
     * Find the last assistant message in memory.
     * 寻找记忆中最后一个助手消息。
     *
     * @return The last assistant message, or null if not found
     *         最后的助手消息，如果没有找到则为null
     */
    private Msg findLastAssistantMsg() {
        List<Msg> memoryMsgs = memory.getMessages();
        for (int i = memoryMsgs.size() - 1; i >= 0; i--) {
            Msg msg = memoryMsgs.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Check if there are pending tool calls without corresponding results.
     * 检查是否有未处理的工具调用，没有对应的结果。
     *
     * @return true if there are pending tool calls
     */
    private boolean hasPendingToolUse() {
        return !getPendingToolUseIds().isEmpty();
    }

    /**
     * Get the set of pending tool use IDs from the last assistant message.
     * 从最后一个助手消息中获取待处理的工具调用ID集合。
     *
     * @return Set of tool use IDs that have no corresponding results in memory
     */
    private Set<String> getPendingToolUseIds() {
        Msg lastAssistant = findLastAssistantMsg();
        if (lastAssistant == null || !lastAssistant.hasContentBlocks(ToolUseBlock.class)) {
            return Set.of();
        }

        Set<String> existingResultIds =
                memory.getMessages().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getId)
                .filter(id -> !existingResultIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Validate input messages when there are pending tool calls, then add to memory.
     * 当有待处理的工具调用时，验证输入消息，然后将其添加到内存中。
     *
     * <p>Validation rules:
     *    验证规则：
     * <ul>
     *   <li>Empty input: no-op (will proceed to acting)</li>
     *       空输入：无操作（将继续执行）
     *   <li>No tool results: throw error</li>
     *       没有工具结果：抛出错误
     *   <li>Has tool results: validate IDs match pending, no duplicates</li>
     *       存在工具结果：验证ID匹配待处理，没有重复
     *   <li>Partial results + text content: throw error (text only allowed when all tools
     *       completed)</li>
     *       部分结果 + 文本内容：抛出错误（仅在所有工具完成时允许文本））
     * </ul>
     *
     * @param msgs The input messages to validate
     * @param pendingIds The set of pending tool use IDs
     * @throws IllegalStateException if validation fails
     */
    private void validateAndAddToolResults(List<Msg> msgs, Set<String> pendingIds) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }

        List<ToolResultBlock> results =
                msgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();

        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot add messages without tool results when pending tool calls exist. "
                            + "Pending IDs: "
                            + pendingIds);
        }

        // Check for duplicate IDs
        // 检查是否有重复的ID
        Set<String> providedIds = new HashSet<>();
        for (ToolResultBlock r : results) {
            if (!providedIds.add(r.getId())) {
                throw new IllegalStateException("Duplicate tool result ID: " + r.getId());
            }
        }

        // Check all provided IDs match pending IDs
        // 检查所有提供的ID是否与待处理的ID匹配
        Set<String> invalidIds =
                providedIds.stream()
                        .filter(id -> !pendingIds.contains(id))
                        .collect(Collectors.toSet());
        if (!invalidIds.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid tool result IDs: " + invalidIds + ". Expected: " + pendingIds);
        }

        // Check for non-ToolResultBlock content
        // 检查是否有非ToolResultBlock的内容
        boolean hasTextContent =
                msgs.stream()
                        .flatMap(m -> m.getContent().stream())
                        .anyMatch(block -> !(block instanceof ToolResultBlock));

        // If only partial results provided, text content is not allowed
        // 仅提供部分结果时，不允许包含文本内容
        boolean isPartialResults = !providedIds.containsAll(pendingIds);
        if (isPartialResults && hasTextContent) {
            throw new IllegalStateException(
                    "Cannot include text content when providing partial tool results. "
                            + "Provided: "
                            + providedIds
                            + ", Pending: "
                            + pendingIds);
        }

        msgs.forEach(memory::addMessage);
    }

    /**
     * Add messages to memory if not null.
     * 如果不为null，则将消息添加到内存中。
     *
     * @param msgs The messages to add
     */
    private void addToMemory(List<Msg> msgs) {
        if (msgs != null) {
            msgs.forEach(memory::addMessage);
        }
    }

    // ==================== Core ReAct Loop ====================
    // 推理-行动循环核心

    // 没有工具时的执行
    private Mono<Msg> executeIteration(int iter) {
        // 推理
        return reasoning(iter, false);
    }

    /**
     * Execute the reasoning phase.
     * 执行推理阶段。
     * 有太多不清楚的地方了
     *
     * <p>This method streams from the model, accumulates chunks, notifies hooks, and
     * decides whether to continue to acting or return early (HITL stop, gotoReasoning, or finished).
     * 此方法从模型流式传输数据，累积数据块，通知钩子，并决定是继续执行还是提前返回（HITL 停止、gotoReasoning 或完成）。
     *
     * @param iter Current iteration number 当前迭代数
     * @param ignoreMaxIters If true, skip maxIters check (for gotoReasoning) 如果为true，则跳过maxIters检查（用于gotoReasoning）
     * @return Mono containing the final result message 含最终结果消息的Mono
     */
    private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
        // Check maxIters unless ignoreMaxIters is set
        // 检查maxIters，除非ignoreMaxIters设置为true
        if (!ignoreMaxIters && iter >= maxIters) {
            // 到了最大迭代次数，执行
            return summarizing(); //当达到最大迭代次数时生成总结。
        }

        // TODO 暂时还不知道这里是什么意思  待分析 思考上下文
        ReasoningContext context = new ReasoningContext(getName());

        return checkInterruptedAsync() // 检查是否被中断
                .then(notifyPreReasoningEvent(prepareMessages())) // 通知预推理事件
                .flatMapMany(
                        event -> {
                            GenerateOptions options =
                                    event.getEffectiveGenerateOptions() != null
                                            ? event.getEffectiveGenerateOptions() // 使用事件中的生成选项
                                            : buildGenerateOptions(); // 使用默认的生成选项
                            return model.stream(
                                            event.getInputMessages(), // 输入消息
                                            toolkit.getToolSchemas(), // 工具模式
                                            options) 
                                    .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk));
                        })
                .doOnNext(
                        chunk -> {
                            List<Msg> chunkMsgs = context.processChunk(chunk); // 处理数据块
                            // Notify streaming hooks for each chunk message
                            // 从每一个块消息通知流式钩子
                            for (Msg msg : chunkMsgs) {
                                // 通知推理块消息
                                notifyReasoningChunk(msg, context).subscribe();
                            }
                        })
                .then(Mono.defer(() -> Mono.justOrEmpty(context.buildFinalMessage()))) // 构建最终消息
                .onErrorResume(
                        InterruptedException.class,
                        error -> {
                            // Save accumulated message before propagating interrupt
                            // 在传播中断之前保存累积的消息
                            Msg msg = context.buildFinalMessage();
                            if (msg != null) {
                                memory.addMessage(msg);
                            }
                            return Mono.error(error);
                        })
                .flatMap(this::notifyPostReasoning) // 通知后推理事件
                .flatMap(
                        event -> {
                            Msg msg = event.getReasoningMessage();
                            if (msg != null) {
                                memory.addMessage(msg);
                            }

                            // HITL stop
                            if (event.isStopRequested()) {
                                return Mono.just(
                                        msg.withGenerateReason(
                                                GenerateReason.REASONING_STOP_REQUESTED));
                            }

                            // gotoReasoning requested (e.g., by StructuredOutputHook)
                            if (event.isGotoReasoningRequested()) {
                                // Validation already done in PostReasoningEvent.gotoReasoning()
                                List<Msg> gotoMsgs = event.getGotoReasoningMsgs();
                                if (gotoMsgs != null) {
                                    gotoMsgs.forEach(memory::addMessage);
                                }
                                // Continue to next iteration, ignoring maxIters for this entry
                                // 继续进行下一次迭代，忽略此条目的 maxIters 值
                                return reasoning(iter + 1, true);
                            }

                            // Check finish conditions
                            // 检查完成条件
                            if (isFinished(msg)) {
                                return Mono.just(msg);
                            }

                            // Continue to acting
                            // 继续进行执行
                            return checkInterruptedAsync().then(acting(iter));
                        })
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    // No message was produced
                                    // 没有生成任何消息
                                    return Mono.justOrEmpty((Msg) null);
                                }));
    }

    /**
     * Execute the acting phase.
     * 执行执行阶段
     *
     * <p>This method executes only pending tools (those without results in memory),
     * notifies hooks for successful tool results, and decides whether to continue iteration
     * or return (HITL stop, suspended tools, or structured output).
     * 此方法仅执行待处理工具（内存中没有结果的工具），通知Hook工具成功的结果，并决定是继续迭代还是返回（HITL停止、暂停工具或结构化输出）。
     *
     * <p>For tools that throw {@link io.agentscope.core.tool.ToolSuspendException}:
     *    对于抛出{@link io.agentscope.core.tool.ToolSuspendException}的工具：
     * <ul>
     *   <li>The exception is caught by Toolkit and converted to a pending ToolResultBlock</li>
     *       Toolkit 捕获到异常并将其转换为待处理的 ToolResultBlock
     *   <li>Successful results are stored in memory, pending results are not</li>
     *       成功的结果存储在内存中，未决的结果则不存储
     *   <li>Returns Msg with {@link GenerateReason#TOOL_SUSPENDED} containing suspended ToolUseBlocks</li>
     *       返回包含已暂停ToolUseBlocks的{@link GenerateReason#TOOL_SUSPENDED}消息
     * </ul>
     *
     * @param iter Current iteration number
     * @return Mono containing the final result message
     */
    private Mono<Msg> acting(int iter) {
        // Extract only pending tool calls (those without results in memory)
        // 仅提取待处理的工具调用（即内存中尚未有结果的调用）
        List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();

        if (pendingToolCalls.isEmpty()) {
            // No pending tools have been executed, continue to next iteration
            // 没有执行任何待处理工具，继续进行下一次迭代
            return executeIteration(iter + 1);
        }

        // Forward tool chunks into ActingChunkEvent hooks without overwriting user callbacks.
        // 将工具块转发到ActingChunkEvent挂钩中，同时不覆盖用户回调。
        toolkit.setInternalChunkCallback(
                (toolUse, chunk) -> notifyActingChunk(toolUse, chunk).subscribe());

        // Execute only pending tools (those without results in memory)
        // 仅执行待处理的工具（即内存中尚未有结果的调用）
        return notifyPreActingHooks(pendingToolCalls)
                .flatMap(this::executeToolCalls) // 执行工具调用并返回配对结果。
                .flatMap(
                        results -> {
                            // Separate success and pending results
                            // 分离成功和待处理的结果
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> successPairs =
                                    results.stream()
                                            .filter(e -> !e.getValue().isSuspended())
                                            .toList();
                            List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs =
                                    results.stream()
                                            .filter(e -> e.getValue().isSuspended())
                                            .toList();

                            // If no success results to process
                            // 如果没有要处理的成功结果
                            if (successPairs.isEmpty()) {
                                if (!pendingPairs.isEmpty()) {
                                    // 构建一条包含已暂停工具调用以供用户执行的消息。
                                    return Mono.just(buildSuspendedMsg(pendingPairs));
                                }
                                // 执行下一轮迭代
                                return executeIteration(iter + 1);
                            }

                            // Process success results through hooks and add to memory
                            // 通过挂钩机制获取进程成功结果，并将其添加到内存中
                            return Flux.fromIterable(successPairs)
                                    .concatMap(this::notifyPostActingHook) // 通知PostActingEvent挂钩以获取单个工具结果，构建消息并将其添加到内存中。
                                    .last()
                                    .flatMap(
                                            event -> {
                                                // HITL stop (also triggered by
                                                // StructuredOutputHook when completed)
                                                if (event.isStopRequested()) {
                                                    return Mono.just(
                                                            event.getToolResultMsg()
                                                                    .withGenerateReason(
                                                                            GenerateReason
                                                                                    .ACTING_STOP_REQUESTED));
                                                }

                                                // If there are pending results, build suspended Msg
                                                if (!pendingPairs.isEmpty()) {
                                                    return Mono.just(
                                                        // 构建一条包含已暂停工具调用以供用户执行的消息。
                                                            buildSuspendedMsg(pendingPairs));
                                                }

                                                // Continue next iteration
                                                // 继续进行下一轮迭代
                                                return executeIteration(iter + 1);
                                            });
                        });
    }

    /**
     * Build a message containing suspended tool calls for user execution.
     * 构建一条包含已暂停工具调用以供用户执行的消息。
     *
     * <p>The message contains both the ToolUseBlocks and corresponding pending ToolResultBlocks
     * for the suspended tools.
     * 该消息既包含ToolUseBlocks，也包含已挂起工具对应的待处理ToolResultBlocks。
     *
     * @param pendingPairs List of (ToolUseBlock, pending ToolResultBlock) pairs
     * @return Msg with GenerateReason.TOOL_SUSPENDED
     */
    private Msg buildSuspendedMsg(List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs) {
        List<ContentBlock> content = new ArrayList<>();
        for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : pendingPairs) {
            content.add(pair.getKey());
            content.add(pair.getValue());
        }
        return Msg.builder()
                .name(getName())
                .role(MsgRole.ASSISTANT)
                .content(content)
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
    }

    /**
     * Execute tool calls and return paired results.
     * 执行工具调用并返回配对结果。
     *
     * @param toolCalls The list of tool calls (potentially modified by PreActingEvent hooks)
     *                  工具调用列表（可能被PreActingEvent挂钩修改）
     * @return Mono containing list of (ToolUseBlock, ToolResultBlock) pairs
     *         包含(ToolUseBlock, ToolResultBlock)对列表的单例
     */
    private Mono<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> executeToolCalls(
            List<ToolUseBlock> toolCalls) {
        return toolkit.callTools(toolCalls, toolExecutionConfig, this, toolExecutionContext)
                .map(
                        results ->
                                IntStream.range(0, toolCalls.size())
                                        .mapToObj(i -> Map.entry(toolCalls.get(i), results.get(i)))
                                        .toList());
    }

    /**
     * Notify PostActingEvent hook for a single tool result, build message and add to memory.
     * 通知PostActingEvent挂钩以获取单个工具结果，构建消息并将其添加到内存中。
     */
    private Mono<PostActingEvent> notifyPostActingHook(
            Map.Entry<ToolUseBlock, ToolResultBlock> entry) {
        // 获取工具调用
        ToolUseBlock toolUse = entry.getKey();
        // 获取工具结果
        ToolResultBlock result = entry.getValue();

        // Build tool result message first so hooks can access it
        // 先构建工具结果消息，以便挂钩可以访问它
        Msg toolMsg = ToolResultMessageBuilder.buildToolResultMsg(result, toolUse, getName());

        // Create event with toolResultMsg already set
        // 创建已设置toolResultMsg的事件
        PostActingEvent event = new PostActingEvent(this, toolkit, toolUse, result);
        event.setToolResultMsg(toolMsg);

        // Notify hooks and add to memory
        // 通知挂钩并添加到内存中
        return notifyHooks(event).doOnNext(e -> memory.addMessage(e.getToolResultMsg()));
    }

    /**
     * Generate summary when max iterations reached.
     * 当达到最大迭代次数时生成总结。
     */
    protected Mono<Msg> summarizing() {
        log.debug("Maximum iterations reached. Generating summary...");

        //准备总结的信息
        List<Msg> messageList = prepareSummaryMessages();
        // 构建生成选项
        // todo 总结到了一半 明天来了继续总结，
        // 本部分收获：了解了Reasoning - acting loop 里面执行的具体细节
        // 1.当有工具调用和无工具调用的处理流程
        // 2.当达到最大迭代次数时的总结
        // 3.agent runtime过程中如何与其他模块进行调度和信息传播
        GenerateOptions generateOptions = buildGenerateOptions();

        return notifyPreSummaryHook(messageList, generateOptions) // 摘要之前进行汇总
                .flatMap(
                        preSummaryEvent -> {
                            List<Msg> effectiveMessages = preSummaryEvent.getInputMessages();
                            GenerateOptions effectiveOptions =
                                    preSummaryEvent.getEffectiveGenerateOptions();

                            return streamAndAccumulateSummary(effectiveMessages, effectiveOptions)
                                    .flatMap(
                                            msg ->
                                                    notifyPostSummaryHook(msg, effectiveOptions)
                                                            .map(
                                                                    postEvent -> {
                                                                        Msg finalMsg =
                                                                                postEvent
                                                                                        .getSummaryMessage()
                                                                                        .withGenerateReason(
                                                                                                GenerateReason
                                                                                                        .MAX_ITERATIONS);
                                                                        memory.addMessage(finalMsg);
                                                                        return finalMsg;
                                                                    }));
                        })
                .onErrorResume(this::handleSummaryError);
    }

    private Mono<Msg> streamAndAccumulateSummary(
            List<Msg> messages, GenerateOptions generateOptions) {
        return model.stream(messages, null, generateOptions)
                .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk))
                .reduce(
                        new ReasoningContext(getName()),
                        (ctx, chunk) -> {
                            List<Msg> streamedMessages = ctx.processChunk(chunk);
                            for (Msg streamedMessage : streamedMessages) {
                                notifySummaryChunk(streamedMessage, ctx, generateOptions)
                                        .subscribe();
                            }
                            return ctx;
                        })
                .map(ReasoningContext::buildFinalMessage);
    }

    private List<Msg> prepareSummaryMessages() {
        List<Msg> messageList = prepareMessages();
        messageList.add(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "You have failed to generate response within the"
                                                    + " maximum iterations. Now respond directly by"
                                                    + " summarizing the current situation.")
                                        .build())
                        .build());
        return messageList;
    }

    private Mono<Msg> handleSummaryError(Throwable error) {
        if (error instanceof InterruptedException) {
            return Mono.error(error);
        }
        log.error("Error generating summary", error);
        Msg errorMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                String.format(
                                                        "Maximum iterations (%d) reached."
                                                                + " Error generating summary: %s",
                                                        maxIters, error.getMessage()))
                                        .build())
                        .build();
        memory.addMessage(errorMsg);
        return Mono.just(errorMsg);
    }

    // ==================== Helper Methods ====================

    /**
     * Prepare messages for model input.
     */
    private List<Msg> prepareMessages() {
        List<Msg> messages = new ArrayList<>();
        if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
            messages.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(sysPrompt).build())
                            .build());
        }
        messages.addAll(memory.getMessages());
        return messages;
    }

    /**
     * Check if the ReAct loop should terminate.
     *
     * <p>Note: Structured output retry is now handled by StructuredOutputHook via gotoReasoning().
     *
     * @param msg The reasoning message
     * @return true if should finish, false if should continue to acting
     */
    private boolean isFinished(Msg msg) {
        if (msg == null) {
            return true;
        }

        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);

        // No tool calls - finished
        // If there are tool calls (even non-existent ones), continue to acting phase
        // where ToolExecutor will return "Tool not found" error for the model to see
        return toolCalls.isEmpty();
    }

    /**
     * Extract tool calls from the most recent assistant message.
     */
    private List<ToolUseBlock> extractRecentToolCalls() {
        return MessageUtils.extractRecentToolCalls(memory.getMessages(), getName());
    }

    /**
     * Extract only pending tool calls (those without results in memory) from the most recent
     * assistant message.
     *
     * <p>This method filters out tool calls that already have corresponding results in memory,
     * preventing duplicate execution when resuming from HITL or partial tool result scenarios.
     *
     * @return List of tool use blocks that don't have results yet, or empty list if all tools
     *     have been executed
     */
    private List<ToolUseBlock> extractPendingToolCalls() {
        List<ToolUseBlock> allToolCalls = extractRecentToolCalls();
        if (allToolCalls.isEmpty()) {
            return List.of();
        }

        Set<String> pendingIds = getPendingToolUseIds();
        return allToolCalls.stream()
                .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                .toList();
    }

    @Override
    protected GenerateOptions buildGenerateOptions() {
        // Start with user-configured generateOptions if available
        GenerateOptions baseOptions = generateOptions;

        // If modelExecutionConfig is set, merge it into the options
        if (modelExecutionConfig != null) {
            GenerateOptions execConfigOptions =
                    GenerateOptions.builder().executionConfig(modelExecutionConfig).build();
            baseOptions = GenerateOptions.mergeOptions(execConfigOptions, baseOptions);
        }

        return baseOptions != null ? baseOptions : GenerateOptions.builder().build();
    }

    // ==================== Hook Notification Methods ====================

    /**
     * Generic hook notification method.
     */
    private <T extends HookEvent> Mono<T> notifyHooks(T event) {
        Mono<T> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        return result;
    }

    private Mono<PreReasoningEvent> notifyPreReasoningEvent(List<Msg> msgs) {
        return notifyHooks(new PreReasoningEvent(this, model.getModelName(), null, msgs));
    }

    private Mono<PostReasoningEvent> notifyPostReasoning(Msg msg) {
        return notifyHooks(new PostReasoningEvent(this, model.getModelName(), null, msg));
    }

    private Mono<List<ToolUseBlock>> notifyPreActingHooks(List<ToolUseBlock> toolCalls) {
        return Flux.fromIterable(toolCalls)
                .concatMap(tool -> notifyHooks(new PreActingEvent(this, toolkit, tool)))
                .map(PreActingEvent::getToolUse)
                .collectList();
    }

    private Mono<Void> notifyActingChunk(ToolUseBlock toolUse, ToolResultBlock chunk) {
        ActingChunkEvent event =
                new ActingChunkEvent(
                        this,
                        toolkit,
                        toolUse,
                        chunk.withIdAndName(toolUse.getId(), toolUse.getName()));
        return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
    }

    private Mono<Void> notifyReasoningChunk(Msg chunkMsg, ReasoningContext context) {
        ContentBlock content = chunkMsg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().thinking(context.getAccumulatedThinking()).build();
        } else if (content instanceof ToolUseBlock tub) {
            // Support streaming ToolUseBlock events
            ToolUseBlock accumulated = context.getAccumulatedToolCall(tub.getId());
            if (accumulated != null) {
                accumulatedContent = accumulated;
            } else {
                // If no accumulated data, use the current chunk directly
                accumulatedContent = tub;
            }
        }

        if (accumulatedContent != null) {
            Msg accumulated =
                    Msg.builder()
                            .id(chunkMsg.getId())
                            .name(chunkMsg.getName())
                            .role(chunkMsg.getRole())
                            .content(accumulatedContent)
                            .build();
            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            this, model.getModelName(), null, chunkMsg, accumulated);
            return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
        }

        return Mono.empty();
    }

    // ==================== Summary Hook Notification Methods ====================

    private Mono<PreSummaryEvent> notifyPreSummaryHook(
            List<Msg> msgs, GenerateOptions generateOptions) {
        return notifyHooks(
                new PreSummaryEvent(
                        this, model.getModelName(), generateOptions, msgs, maxIters, maxIters));
    }

    private Mono<PostSummaryEvent> notifyPostSummaryHook(Msg msg, GenerateOptions generateOptions) {
        return notifyHooks(new PostSummaryEvent(this, model.getModelName(), generateOptions, msg));
    }

    private Mono<Void> notifySummaryChunk(
            Msg chunkMsg, ReasoningContext context, GenerateOptions generateOptions) {
        ContentBlock content = chunkMsg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().thinking(context.getAccumulatedThinking()).build();
        }

        if (accumulatedContent != null) {
            Msg accumulated =
                    Msg.builder()
                            .id(chunkMsg.getId())
                            .name(chunkMsg.getName())
                            .role(chunkMsg.getRole())
                            .content(accumulatedContent)
                            .build();
            SummaryChunkEvent event =
                    new SummaryChunkEvent(
                            this, model.getModelName(), generateOptions, chunkMsg, accumulated);
            return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
        }

        return Mono.empty();
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        String recoveryText = "I noticed that you have interrupted me. What can I do for you?";

        Msg recoveryMsg =
                Msg.builder()
                        .name(getName())
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(recoveryText).build())
                        .build();

        memory.addMessage(recoveryMsg);
        return Mono.just(recoveryMsg);
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null) {
            memory.addMessage(msg);
        }
        return Mono.empty();
    }

    // ==================== Getters ====================

    @Override
    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        throw new UnsupportedOperationException(
                "Memory cannot be replaced after agent construction. "
                        + "Create a new agent instance if you need different memory.");
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public Model getModel() {
        return model;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public PlanNotebook getPlanNotebook() {
        return planNotebook;
    }

    /**
     * Gets the configured generation options for this agent.
     *
     * @return The generation options, or null if not configured
     */
    public GenerateOptions getGenerateOptions() {
        return generateOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== Builder ====================

    public static class Builder {
        private String name;
        private String description;
        private String sysPrompt;
        private boolean checkRunning = true;
        private Model model;
        private Toolkit toolkit = new Toolkit();
        private Memory memory = new InMemoryMemory();
        private int maxIters = 10;
        private ExecutionConfig modelExecutionConfig;
        private ExecutionConfig toolExecutionConfig;
        private GenerateOptions generateOptions;
        private final Set<Hook> hooks = new LinkedHashSet<>();
        private boolean enableMetaTool = false;
        private StructuredOutputReminder structuredOutputReminder =
                StructuredOutputReminder.TOOL_CHOICE;
        private PlanNotebook planNotebook;
        private SkillBox skillBox;
        private ToolExecutionContext toolExecutionContext;

        // Long-term memory configuration
        private LongTermMemory longTermMemory;
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;

        // State persistence configuration
        private StatePersistence statePersistence;

        // RAG configuration
        private final Set<Knowledge> knowledgeBases = new LinkedHashSet<>();
        private RAGMode ragMode = RAGMode.GENERIC;
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        private Builder() {}

        /**
         * Sets the name for this agent.
         *
         * @param name The agent name, must not be null
         * @return This builder instance for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            return this;
        }

        /**
         * Sets the system prompt for this agent.
         *
         * @param sysPrompt The system prompt, can be null or empty
         * @return This builder instance for method chaining
         */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * Sets the language model for this agent.
         *
         * @param model The language model to use for reasoning, must not be null
         * @return This builder instance for method chaining
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the toolkit containing available tools for this agent.
         *
         * @param toolkit The toolkit with available tools, must not be null
         * @return This builder instance for method chaining
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Sets the memory for storing conversation history.
         *
         * @param memory The memory implementation, can be null (defaults to InMemoryMemory)
         * @return This builder instance for method chaining
         */
        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * Sets the maximum number of reasoning-acting iterations.
         *
         * @param maxIters Maximum iterations, must be positive
         * @return This builder instance for method chaining
         */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * Adds a hook for monitoring and intercepting agent execution events.
         *
         * <p>Hooks can observe or modify events during reasoning, acting, and other phases.
         * Multiple hooks can be added and will be executed in priority order (lower priority
         * values execute first).
         *
         * @param hook The hook to add, must not be null
         * @return This builder instance for method chaining
         * @see Hook
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * Adds multiple hooks for monitoring and intercepting agent execution events.
         *
         * <p>Hooks can observe or modify events during reasoning, acting, and other phases.
         * All hooks will be executed in priority order (lower priority values execute first).
         *
         * @param hooks The list of hooks to add, must not be null
         * @return This builder instance for method chaining
         * @see Hook
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * Enables or disables the meta-tool functionality.
         * 启用或禁用元工具功能。
         *
         * <p>When enabled, the toolkit will automatically register a meta-tool that provides
         * information about available tools to the agent. This can help the agent understand
         * what tools are available without relying solely on the system prompt.
         *
         * @param enableMetaTool true to enable meta-tool, false to disable
         * @return This builder instance for method chaining
         */
        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        /**
         * Sets the execution configuration for model API calls.
         * 设置模型 API 调用的执行配置。
         *
         * <p>This configuration controls timeout, retry behavior, and backoff strategy for
         * model requests during the reasoning phase. If not set, the agent will use the
         * model's default execution configuration.
         * 这个配置控制了推理阶段模型请求的超时、重试行为和退避策略。如果未设置，智能体将使用模型的默认执行配置。
         *
         * @param modelExecutionConfig The execution configuration for model calls, can be null
         * @return This builder instance for method chaining
         * @see ExecutionConfig
         */
        public Builder modelExecutionConfig(ExecutionConfig modelExecutionConfig) {
            this.modelExecutionConfig = modelExecutionConfig;
            return this;
        }

        /**
         * Sets the execution configuration for tool executions.
         * 设置工具执行的执行配置。
         *
         * <p>This configuration controls timeout, retry behavior, and backoff strategy for
         * tool calls during the acting phase. If not set, the toolkit will use its default
         * execution configuration.
         * 这个配置控制了行动阶段工具调用的超时、重试行为和退避策略。如果未设置，工具包将使用其默认执行配置。
         *
         * @param toolExecutionConfig The execution configuration for tool calls, can be null
         * @return This builder instance for method chaining
         * @see ExecutionConfig
         */
        public Builder toolExecutionConfig(ExecutionConfig toolExecutionConfig) {
            this.toolExecutionConfig = toolExecutionConfig;
            return this;
        }

        /**
         * Sets the generation options for model API calls.
         *
         * <p>This configuration controls LLM generation parameters such as temperature, topP,
         * maxTokens, frequencyPenalty, presencePenalty, etc. These options are passed to the
         * model during the reasoning phase.
         *
         * <p><b>Example usage:</b>
         * <pre>{@code
         * ReActAgent agent = ReActAgent.builder()
         *     .name("assistant")
         *     .model(model)
         *     .generateOptions(GenerateOptions.builder()
         *         .temperature(0.7)
         *         .topP(0.9)
         *         .maxTokens(1000)
         *         .build())
         *     .build();
         * }</pre>
         *
         * <p><b>Note:</b> If both generateOptions and modelExecutionConfig are set,
         * the modelExecutionConfig's executionConfig will be merged into the generateOptions,
         * with modelExecutionConfig taking precedence for execution settings.
         *
         * @param generateOptions The generation options for model calls, can be null
         * @return This builder instance for method chaining
         * @see GenerateOptions
         */
        public Builder generateOptions(GenerateOptions generateOptions) {
            this.generateOptions = generateOptions;
            return this;
        }

        /**
         * Sets the structured output enforcement mode.
         * 设置结构化输出强制模式。
         *
         * @param reminder The structured output reminder mode, must not be null
         * @return This builder instance for method chaining
         */
        public Builder structuredOutputReminder(StructuredOutputReminder reminder) {
            this.structuredOutputReminder = reminder;
            return this;
        }

        /**
         * Sets the PlanNotebook for plan-based task execution.
         * 设置计划笔记本以支持基于计划的任务执行。
         *
         * <p>When provided, the PlanNotebook will be integrated into the agent:
         * 当提供时，计划笔记本将被集成到智能体中：
         * <ul>
         *   <li>Plan management tools will be automatically registered to the toolkit
         *   计划管理工具将被自动注册到工具包中
         *   <li>A hook will be added to inject plan hints before each reasoning step
         *    在每次推理步骤之前将添加一个钩子来注入计划提示
         * </ul>
         *
         * @param planNotebook The configured PlanNotebook instance, can be null
         * @return This builder instance for method chaining
         */
        public Builder planNotebook(PlanNotebook planNotebook) {
            this.planNotebook = planNotebook;
            return this;
        }

        /**
         * Sets the skill box for this agent.
         * 设置此智能体的技能箱。
         *
         * <p>The skill box is used to manage the skills for this agent. It will be used to register the skills to the toolkit.
         * 技能箱用于管理此智能体的技能。它将被用来将技能注册到工具包中。
         * <ul>
         *   <li>Skill loader tools will be automatically registered to the toolkit</li>
         *   技能加载器工具将被自动注册到工具包中
         *   <li>A skill hook will be added to inject skill prompts and manage skill activation</li>
         *   技能钩子将被添加以注入技能提示并管理技能激活
         * </ul>
         * @param skillBox The skill box to use for this agent
         * @return This builder instance for method chaining
         */
        public Builder skillBox(SkillBox skillBox) {
            this.skillBox = skillBox;
            return this;
        }

        /**
         * Sets the long-term memory for this agent.
         * 设置此智能体的长期记忆。
         *
         * <p>Long-term memory enables the agent to remember information across sessions.
         * It can be used in combination with {@link #longTermMemoryMode(LongTermMemoryMode)}
         * to control whether memory management is automatic, agent-controlled, or both.
         * 长期记忆使智能体能够跨会话记住信息。
         * 它可以与 {@link #longTermMemoryMode(LongTermMemoryMode)} 结合使用，
         * 以控制记忆管理是自动的、由智能体控制的还是两者兼有。
         *
         * @param longTermMemory The long-term memory implementation
         * @return This builder instance for method chaining
         * @see LongTermMemoryMode
         */
        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /**
         * Sets the long-term memory mode.
         * 设置长期记忆模式。
         *
         * <p>This determines how long-term memory is integrated with the agent:
         * 翻译：这决定了长期记忆如何与智能体集成：
         * <ul>
         *   <li><b>AGENT_CONTROL:</b> Memory tools are registered for agent to call</li>
         *   <li><b>AGENT_CONTROL:</b> 注册内存工具供智能体调用</li>
         *   <li><b>STATIC_CONTROL:</b> Framework automatically retrieves/records memory</li>
         *   <li><b>STATIC_CONTROL:</b> 框架自动检索/记录记忆</li>
         *   <li><b>BOTH:</b> Combines both approaches (default)</li>
         *   <li><b>BOTH:</b> 结合两种方法（默认）</li>
         * </ul>
         *
         * @param mode The long-term memory mode
         * @return This builder instance for method chaining
         * @see LongTermMemoryMode
         */
        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            this.longTermMemoryMode = mode;
            return this;
        }

        /**
         * Sets the state persistence configuration.
         * 设置状态持久化配置。
         *
         * <p>Use this to control which components' state is managed by the agent during
         * saveTo/loadFrom operations. By default, all components are managed.
         * 使用此选项来控制在 saveTo/loadFrom 操作期间由智能体管理哪些组件的状态。
         * 默认情况下，所有组件都由智能体管理。
         *
         * <p>Example usage:
         *
         * <pre>{@code
         * ReActAgent agent = ReActAgent.builder()
         *     .name("assistant")
         *     .model(model)
         *     .statePersistence(StatePersistence.builder()
         *         .planNotebookManaged(false)  // Let user manage PlanNotebook separately
         *         .build())
         *     .build();
         * }</pre>
         *
         * @param statePersistence The state persistence configuration
         * @return This builder instance for method chaining
         * @see StatePersistence
         */
        public Builder statePersistence(StatePersistence statePersistence) {
            this.statePersistence = statePersistence;
            return this;
        }

        /**
         * Enables plan functionality with default configuration.
         * 启用具有默认配置的计划功能。
         *
         * <p>This is a convenience method equivalent to:
         * <pre>{@code
         * planNotebook(PlanNotebook.builder().build())
         * }</pre>
         *
         * @return This builder instance for method chaining
         */
        public Builder enablePlan() {
            this.planNotebook = PlanNotebook.builder().build();
            return this;
        }

        /**
         * Adds a knowledge base for RAG (Retrieval-Augmented Generation).
         * 为 RAG（检索增强生成）添加知识库。
         *
         * @param knowledge The knowledge base to add
         * @return This builder instance for method chaining
         */
        public Builder knowledge(Knowledge knowledge) {
            if (knowledge != null) {
                this.knowledgeBases.add(knowledge);
            }
            return this;
        }

        /**
         * Adds multiple knowledge bases for RAG.
         * 为 RAG 添加多个知识库。
         *
         * @param knowledges The list of knowledge bases to add
         * @return This builder instance for method chaining
         */
        public Builder knowledges(List<Knowledge> knowledges) {
            if (knowledges != null) {
                this.knowledgeBases.addAll(knowledges);
            }
            return this;
        }

        /**
         * Sets the RAG mode.
         * 设置 RAG 模式。
         *
         * @param mode The RAG mode (GENERIC, AGENTIC, or NONE)
         * @return This builder instance for method chaining
         */
        public Builder ragMode(RAGMode mode) {
            if (mode != null) {
                this.ragMode = mode;
            }
            return this;
        }

        /**
         * Sets the retrieve configuration for RAG.
         * 设置 RAG 的检索配置。
         *
         * @param config The retrieve configuration
         * @return This builder instance for method chaining
         */
        public Builder retrieveConfig(RetrieveConfig config) {
            if (config != null) {
                this.retrieveConfig = config;
            }
            return this;
        }

        /**
         * Sets the tool execution context for this agent.
         * 设置此智能体的工具执行上下文。
         *
         * <p>This context will be passed to all tools invoked by this agent and can include
         * user identity, session information, permissions, and other metadata. The context
         * from this agent level will override toolkit-level context but can be overridden by
         * call-level context.
         * 这个上下文将被传递给此智能体调用的所有工具，
         * 并且可以包含用户身份、会话信息、权限和其他元数据。
         * 这个智能体级别的上下文将覆盖工具包级别的上下文，
         * 但可以被调用级别的上下文覆盖。
         *
         * @param toolExecutionContext The tool execution context
         * @return This builder instance for method chaining
         */
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        /**
         * Builds and returns a new ReActAgent instance with the configured settings.
         * 构建并返回一个具有配置设置的新 ReActAgent 实例。
         *
         * @return A new ReActAgent instance
         * @throws IllegalArgumentException if required parameters are missing or invalid
         */
        public ReActAgent build() {
            // Deep copy toolkit to avoid state interference between agents
            Toolkit agentToolkit = this.toolkit.copy();

            if (enableMetaTool) {
                agentToolkit.registerMetaTool();
            }

            // Configure long-term memory if provided
            if (longTermMemory != null) {
                configureLongTermMemory(agentToolkit);
            }

            // Configure RAG if knowledge bases are provided
            if (!knowledgeBases.isEmpty()) {
                configureRAG(agentToolkit);
            }

            // Configure PlanNotebook if provided
            if (planNotebook != null) {
                configurePlan(agentToolkit);
            }

            // Configure SkillBox if provided
            if (skillBox != null) {
                configureSkillBox(agentToolkit);
            }

            return new ReActAgent(this, agentToolkit);
        }

        /**
         * Configures long-term memory based on the selected mode.
         * 根据选择的模式配置长期记忆。
         *
         * <p>This method sets up long-term memory integration:
         * 这个方法设置长期记忆集成：
         * <ul>
         *   <li>AGENT_CONTROL: Registers memory tools for agent to call</li>
         *  <li>AGENT_CONTROL: 注册内存工具供智能体调用</li>
         *   <li>STATIC_CONTROL: Registers StaticLongTermMemoryHook for automatic retrieval/recording</li>
         *   <li>STATIC_CONTROL: 注册 StaticLongTermMemoryHook 以实现自动检索/记录</li>
         * <li>BOTH: Combines both approaches (registers tools + hook)</li>
         *  <li>BOTH: 结合两种方法（注册工具 + 钩子）</li>
         * </ul>
         */
        private void configureLongTermMemory(Toolkit agentToolkit) {
            // If agent control is enabled, register memory tools via adapter
            if (longTermMemoryMode == LongTermMemoryMode.AGENT_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                agentToolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }

            // If static control is enabled, register the hook for automatic memory management
            if (longTermMemoryMode == LongTermMemoryMode.STATIC_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                StaticLongTermMemoryHook hook =
                        new StaticLongTermMemoryHook(longTermMemory, memory);
                hooks.add(hook);
            }
        }

        /**
         * Configures RAG (Retrieval-Augmented Generation) based on the selected mode.
         *
         * <p>This method automatically sets up the appropriate hooks or tools based on the RAG mode:
         * <ul>
         *   <li>GENERIC: Adds a GenericRAGHook to automatically inject knowledge</li>
         *   <li>AGENTIC: Registers KnowledgeRetrievalTools for agent-controlled retrieval</li>
         *   <li>NONE: Does nothing</li>
         * </ul>
         */
        private void configureRAG(Toolkit agentToolkit) {
            // Aggregate knowledge bases if multiple are provided
            Knowledge aggregatedKnowledge;
            if (knowledgeBases.size() == 1) {
                aggregatedKnowledge = knowledgeBases.iterator().next();
            } else {
                aggregatedKnowledge = buildAggregatedKnowledge();
            }

            // Configure based on mode
            switch (ragMode) {
                case GENERIC -> {
                    // Create and add GenericRAGHook
                    GenericRAGHook ragHook =
                            new GenericRAGHook(aggregatedKnowledge, retrieveConfig);
                    hooks.add(ragHook);
                }
                case AGENTIC -> {
                    // Register knowledge retrieval tools
                    KnowledgeRetrievalTools tools =
                            new KnowledgeRetrievalTools(aggregatedKnowledge, retrieveConfig);
                    agentToolkit.registerTool(tools);
                }
                case NONE -> {
                    // Do nothing
                }
            }
        }

        private Knowledge buildAggregatedKnowledge() {
            return new Knowledge() {
                @Override
                public Mono<Void> addDocuments(List<Document> documents) {
                    return Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.addDocuments(documents))
                            .then();
                }

                @Override
                public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                    return Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.retrieve(query, config))
                            .collectList()
                            .map(this::mergeAndSortResults);
                }

                private List<Document> mergeAndSortResults(List<List<Document>> allResults) {
                    return allResults.stream()
                            .flatMap(List::stream)
                            .collect(
                                    Collectors.toMap(
                                            Document::getId,
                                            doc -> doc,
                                            (doc1, doc2) ->
                                                    doc1.getScore() != null
                                                                    && doc2.getScore() != null
                                                                    && doc1.getScore()
                                                                            > doc2.getScore()
                                                            ? doc1
                                                            : doc2))
                            .values()
                            .stream()
                            .sorted(
                                    Comparator.comparing(
                                            Document::getScore,
                                            Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(retrieveConfig.getLimit())
                            .toList();
                }
            };
        }

        /**
         * Configures PlanNotebook integration.
         *
         * <p>This method automatically:
         * <ul>
         *   <li>Registers plan management tools to the toolkit
         *   <li>Adds a hook to inject plan hints before each reasoning step
         * </ul>
         */
        private void configurePlan(Toolkit agentToolkit) {
            // Register plan tools to toolkit
            agentToolkit.registerTool(planNotebook);

            // Add plan hint hook
            Hook planHintHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PreReasoningEvent) {
                                PreReasoningEvent e = (PreReasoningEvent) event;
                                return planNotebook
                                        .getCurrentHint()
                                        .map(
                                                hintMsg -> {
                                                    List<Msg> modifiedMsgs =
                                                            new ArrayList<>(e.getInputMessages());
                                                    modifiedMsgs.add(hintMsg);
                                                    e.setInputMessages(modifiedMsgs);
                                                    return (T) e;
                                                })
                                        .defaultIfEmpty(event);
                            }
                            return Mono.just(event);
                        }
                    };

            hooks.add(planHintHook);
        }

        /**
         * Configures SkillBox integration.
         *
         * <p>This method automatically:
         * <ul>
         *   <li>Registers skill load tool to the toolkit
         *   <li>Adds the skill hook to inject skill prompts and manage skill activation
         *   <li>Uploads skill files to the upload directory if auto upload is enabled
         * </ul>
         */
        private void configureSkillBox(Toolkit agentToolkit) {
            skillBox.bindToolkit(agentToolkit);
            // Register skill loader tools to toolkit
            skillBox.registerSkillLoadTool();

            // If auto upload is enabled, upload skill files
            if (skillBox.isAutoUploadSkill()) {
                skillBox.uploadSkillFiles();
            }

            hooks.add(new SkillHook(skillBox));
        }
    }
}
