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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reasoning context that manages all state and content accumulation for a single reasoning round.
 * 推理上下文，管理单个推理轮的所有状态和内容积累。
 * <p>Responsibilities:
 *    职责：
 *
 * <ul>
 *   <li>Accumulate various content types (text, thinking, tool calls) from streaming responses
 *       从流媒体响应中积累各种内容类型（文本、思维、工具调用）
 *   <li>Generate real-time streaming messages (for Hook notifications)
 *       生成实时流消息（用于Hook通知）
 *   <li>Build final aggregated message (for saving to memory)
 *       构建最终聚合消息（用于保存到内存中）
 * </ul>
 * @hidden
 */
public class ReasoningContext {

    private final String agentName;
    private String messageId;

    private final TextAccumulator textAcc = new TextAccumulator(); //文本累加器
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator(); // 思考块累加器
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator(); // 工具调用累加器

    private final List<Msg> allStreamedChunks = new ArrayList<>(); // 全部的流媒体块

    // ChatUsage
    private int inputTokens = 0; // 输入的token数
    private int outputTokens = 0; // 输出的token数
    private double time = 0; // 时间

    public ReasoningContext(String agentName) {
        this.agentName = agentName;
    }

    /**
     * Process a response chunk and return messages that can be sent immediately.
     * 处理响应块并返回可以立即发送的消息。
     *
     * <p>Strategy:
     *     策略：
     *
     * <ul>
     *   <li>TextBlock/ThinkingBlock: Emit immediately for real-time display
     *       文本块/思考块：立即发射以进行实时显示
     *   <li>ToolUseBlock: Accumulate and emit immediately for real-time streaming
     *       工具调用块：立即累积和发射以进行实时流式传输
     * </ul>
     *
     * @hidden
     * @param chunk Response chunk from the model 来自模型的响应块
     * @return List of messages that can be sent immediately 可立即发送的消息列表
     */
    public List<Msg> processChunk(ChatResponse chunk) { // 所以一般最重要的就四个块  文本块 思考块 工具调用块 工具结果块 多模态的块暂不考虑
        this.messageId = chunk.getId();

        // Accumulate ChatUsage
        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens = usage.getInputTokens();
            outputTokens = usage.getOutputTokens();
            time = usage.getTime();
        }

        List<Msg> streamingMsgs = new ArrayList<>();

        for (ContentBlock block : chunk.getContent()) {
            if (block instanceof TextBlock tb) {
                textAcc.add(tb);

                // Emit text block immediately
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);
                allStreamedChunks.add(msg);

            } else if (block instanceof ThinkingBlock tb) {
                thinkingAcc.add(tb);

                // Emit thinking block immediately
                Msg msg = buildChunkMsg(tb);
                streamingMsgs.add(msg);
                allStreamedChunks.add(msg);

            } else if (block instanceof ToolUseBlock tub) {
                // Accumulate tool calls and emit immediately for real-time streaming
                toolCallsAcc.add(tub);

                // Emit ToolUseBlock chunk immediately for real-time display
                // Each tool call chunk is emitted separately, supporting multiple parallel tool
                // calls
                // For fragments (placeholder names like "__fragment__"), we need to include
                // the correct tool call ID so users can properly concatenate the chunks
                ToolUseBlock outputBlock = enrichToolUseBlockWithId(tub);
                Msg msg = buildChunkMsg(outputBlock);
                streamingMsgs.add(msg);
                allStreamedChunks.add(msg);
            }
        }

        return streamingMsgs;
    }

    /**
     * Build the final reasoning message with all content blocks.
     * 使用所有内容块构建最终推理消息。
     * This includes text, thinking, AND tool calls in ONE message.
     * 这包括一条消息中的文本、思维和工具调用。
     *
     * <p>This method ensures that a single reasoning round produces one message
     * that may contain multiple content blocks.
     * 此方法可确保单轮推理产生一条可能包含多个内容块的消息。
     *
     * <p>Strategy:
     * 战略：
     *
     * <ol>
     *   <li>Add text content if present
     *       添加文本内容（如果存在）
     *   <li>Add thinking content if present
     *       添加思考内容（如果存在）
     *   <li>Add all tool calls
     *       添加所有工具调用
     * </ol>
     *
     * @hidden
     * @return The complete reasoning message with all blocks, or null if no content
     *         包含所有块的完整推理消息，如果没有内容，则为null
     */
    public Msg buildFinalMessage() {
        List<ContentBlock> blocks = new ArrayList<>();

        // Add thinking content if present
        if (thinkingAcc.hasContent()) {
            blocks.add(thinkingAcc.buildAggregated());
        }

        // Add text content if present
        if (textAcc.hasContent()) {
            blocks.add(textAcc.buildAggregated());
        }

        // Add all tool calls
        List<ToolUseBlock> toolCalls = toolCallsAcc.buildAllToolCalls();
        blocks.addAll(toolCalls);

        // If no content at all, return null
        if (blocks.isEmpty()) {
            return null;
        }

        // Build metadata with accumulated ChatUsage
        Map<String, Object> metadata = new HashMap<>();
        if (inputTokens > 0 || outputTokens > 0 || time > 0) {
            ChatUsage chatUsage =
                    ChatUsage.builder()
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .time(time)
                            .build();
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        return Msg.builder()
                .id(messageId)
                .name(agentName)
                .role(MsgRole.ASSISTANT)
                .content(blocks)
                .metadata(metadata)
                .build();
    }

    /**
     * Build a chunk message from a content block.
     * 从内容块构建块消息。
     * @hidden
     */
    private Msg buildChunkMsg(ContentBlock block) {
        return Msg.builder()
                .id(messageId)
                .name(agentName)
                .role(MsgRole.ASSISTANT)
                .content(block)
                .build();
    }

    /**
     * Enrich a ToolUseBlock with the correct tool call ID.
     * 使用正确的工具调用ID丰富ToolUseBlock。
     *
     * <p>For fragments (placeholder names like "__fragment__"), the original block may not have
     * the correct ID. This method retrieves the ID from the accumulator and creates a new block
     * with the correct ID, allowing users to properly concatenate chunks.
     * 对于片段（占位符名称，如“fragment”），原始块可能没有正确的ID。
     * 此方法从累加器中检索ID，并创建具有正确ID的新块，允许用户正确连接块。
     *
     * @param block The original ToolUseBlock 原始工具调用块
     * @return A ToolUseBlock with the correct ID 带有正确ID的ToolUseBlock
     */
    private ToolUseBlock enrichToolUseBlockWithId(ToolUseBlock block) {
        // If the block already has an ID, return it as-is
        if (block.getId() != null && !block.getId().isEmpty()) {
            return block;
        }

        // Get the current tool call ID from the accumulator
        String currentId = toolCallsAcc.getCurrentToolCallId();
        if (currentId == null || currentId.isEmpty()) {
            return block;
        }

        // Create a new block with the correct ID
        return ToolUseBlock.builder()
                .id(currentId)
                .name(block.getName())
                .input(block.getInput())
                .content(block.getContent())
                .metadata(block.getMetadata())
                .build();
    }

    /**
     * Get the accumulated text content.
     * 获取累计文本内容。
     *
     * @hidden
     * @return accumulated text as string
     */
    public String getAccumulatedText() {
        return textAcc.getAccumulated();
    }

    /**
     * Get the accumulated thinking content.
     * 获取累计思考内容。
     *
     * @hidden
     * @return accumulated thinking as string
     */
    public String getAccumulatedThinking() {
        return thinkingAcc.getAccumulated();
    }

    /**
     * Get accumulated tool call by ID.
     * 按ID获取累积的工具调用。
     *
     * <p>If the ID is null or empty, or if no builder is found for the given ID,
     * this method falls back to using the last tool call.
     * 如果ID为null或为空，或者找不到给定ID的构建器，
     * 则此方法将退回使用lastToolCallKey。
     * 
     * @param id The tool call ID to look up 要查找的工具调用ID
     * @return The accumulated ToolUseBlock, or null if not found 累积的ToolUseBlock，如果找不到，则为null
     */
    public ToolUseBlock getAccumulatedToolCall(String id) {
        return toolCallsAcc.getAccumulatedToolCall(id);
    }

    /**
     * Get all accumulated tool calls.
     * 获取所有累积的工具调用。
     *
     * @return List of all accumulated ToolUseBlocks
     */
    public List<ToolUseBlock> getAllAccumulatedToolCalls() {
        return toolCallsAcc.getAllAccumulatedToolCalls();
    }
}
