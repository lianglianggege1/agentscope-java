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
package io.agentscope.core.rag;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Generic RAG Hook for automatic knowledge retrieval before reasoning.
 * 通用的 RAG Hook，用于自动知识检索。
 *
 * <p>This hook implements the Generic RAG mode, where knowledge is automatically retrieved
 * and injected into the prompt before each reasoning step. Unlike Agentic mode (where agents
 * decide when to retrieve), Generic mode always retrieves relevant knowledge for user queries.
 * 这个Hook 实现了通用 RAG 模式，
 * 在每个推理步骤之前，自动检索知识并注入到提示中。
 * 与智能体模式（智能体决定何时检索）不同，通用模式总是检索用户查询相关的知识。
 *
 * <p>This hook intercepts {@link PreReasoningEvent} and:
 *    这个hook 拦截了 PreReasoningEvent 事件。 执行推理之前，自动检索知识并注入到提示中。
 * <ol>
 *   <li>Extracts the query from user messages</li>
 *       提取用户消息中的查询
 *   <li>Retrieves relevant documents from the knowledge base</li>
 *   <li>Injects the retrieved knowledge as a system message</li>
 *   <li>Modifies the input messages to include the knowledge context</li>
 *       修改输入消息，使其包含知识上下文
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * KnowledgeBase knowledgeBase = new SimpleKnowledge(embeddingModel, vectorStore);
 * GenericRAGHook ragHook = new GenericRAGHook(knowledgeBase);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .hook(ragHook)
 *     .build();
 * }</pre>
 *
 * <p>Configuration options:
 * <ul>
 *   <li>{@code defaultConfig} - Retrieval configuration (limit, score threshold)</li>
 * </ul>
 */
public class GenericRAGHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(GenericRAGHook.class);

    private final Knowledge knowledge;
    private final RetrieveConfig defaultConfig;

    /**
     * Creates a GenericRAGHook with default configuration.
     * 创造一个通用 RAG Hook，使用默认配置。
     *
     * <p>Default configuration:
     *    默认配置：
     * <ul>
     *   <li>Limit: 5 documents</li>
     *   <li>Score threshold: 0.5</li>
     * </ul>
     *
     * @param knowledge the knowledge base to retrieve from
     * @throws IllegalArgumentException if knowledgeBase is null
     */
    public GenericRAGHook(Knowledge knowledge) {
        this(knowledge, RetrieveConfig.builder().build());
    }

    /**
     * Creates a GenericRAGHook with custom configuration.
     * 创造一个通用 RAG Hook，使用自定义配置。
     *
     * @param knowledge the knowledge base to retrieve from
     * @param defaultConfig the default retrieval configuration
     * @throws IllegalArgumentException if knowledgeBase is null
     */
    public GenericRAGHook(Knowledge knowledge, RetrieveConfig defaultConfig) {
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge base cannot be null");
        }
        if (defaultConfig == null) {
            throw new IllegalArgumentException("Default config cannot be null");
        }
        this.knowledge = knowledge;
        this.defaultConfig = defaultConfig;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
            return result;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // High priority to execute early in the hook chain
        // 高优先级，执行早于hook链中的其他钩子
        return 50;
    }

    /**
     * Handles PreCallEvent by retrieving knowledge and enhancing messages.
     * 处理 PreCallEvent，检索知识并增强消息。
     *
     * @param event the PreReasoningEvent
     * @return Mono containing the potentially modified event
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        List<Msg> inputMessages = event.getInputMessages();
        if (inputMessages == null || inputMessages.isEmpty()) {
            return Mono.just(event);
        }

        // Extract query text from messages (finds the last user message from back to front)
        // 从消息中提取查询文本（从后向前查找最后一个用户消息）
        String query = extractQueryFromMessages(inputMessages);
        if (query == null || query.trim().isEmpty()) {
            return Mono.just(event);
        }

        // Retrieve relevant documents
        // 检索相关的文档
        return knowledge
                .retrieve(query, defaultConfig)
                .flatMap(
                        retrievedDocs -> {
                            if (retrievedDocs == null || retrievedDocs.isEmpty()) {
                                return Mono.just(event);
                            }
                            List<Msg> enhancedMessages = new ArrayList<>();
                            // Build enhanced messages with knowledge context
                            // 构建带有知识上下文的增强消息
                            Msg enhancedMessage = createEnhancedMessages(retrievedDocs);
                            enhancedMessages.addAll(inputMessages);
                            enhancedMessages.add(enhancedMessage);
                            event.setInputMessages(enhancedMessages);
                            return Mono.just(event);
                        })
                .onErrorResume(
                        error -> {
                            // Log error but don't interrupt the flow
                            log.warn("Generic RAG retrieval failed: {}", error.getMessage(), error);
                            return Mono.just(event);
                        });
    }

    /**
     * Extracts query text from message list.
     * 从消息列表中提取查询文本。
     *
     * <p>Finds the last user message as the query source (not just the last message, which could be
     * ASSISTANT or TOOL in ReAct loops).
     * 寻找最后一个用户消息作为查询源（而不是仅最后一个消息，该消息可以是 ReAct 循环中的 ASSISTANT 或 TOOL）。
     *
     * @param messages the message list
     * @return the extracted query text, or empty string if no user message found
     */
    private String extractQueryFromMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // Find the last user message (not just the last message, which could be
        // ASSISTANT or TOOL in ReAct loops)
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return "";
    }

    /**
     * Creates enhanced message list with knowledge context injected.
     * 创建带有知识上下文注入的增强消息列表。
     *
     * <p>The knowledge is injected as a system message at the beginning of the message list.
     * 知识作为系统消息注入到消息列表的开头。
     *
     * @param retrievedDocs the retrieved documents
     * @return the enhanced message list with knowledge context
     */
    private Msg createEnhancedMessages(List<Document> retrievedDocs) {
        String knowledgeContent = buildKnowledgeContent(retrievedDocs);

        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(knowledgeContent).build())
                .build();
    }

    /**
     * Builds knowledge content string from retrieved documents.
     * 构建从检索的文档中生成的知识内容字符串。
     *
     * <p>Formats documents with scores and content for inclusion in the prompt.
     * 格式化文档以供提示包含
     *
     * @param documents the retrieved documents
     * @return the formatted knowledge content string
     */
    private String buildKnowledgeContent(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "<retrieved_knowledge>Use the following content from the knowledge base(s) if it is"
                        + " helpful:\n\n");
        for (Document doc : documents) {
            sb.append("- Score: ")
                    .append(String.format("%.3f", doc.getScore() != null ? doc.getScore() : 0.0))
                    .append(", ");
            sb.append("Content: ").append(doc.getMetadata().getContentText()).append("\n");
        }
        sb.append("</retrieved_knowledge>");

        return sb.toString();
    }

    /**
     * Gets the knowledge base used by this hook.
     * 获取此hook使用的知识库。
     *
     * @return the knowledge base
     */
    public Knowledge getKnowledgeBase() {
        return knowledge;
    }

    /**
     * Gets the default retrieval configuration.
     * 获取默认的检索配置。
     *
     * @return the default config
     */
    public RetrieveConfig getDefaultConfig() {
        return defaultConfig;
    }
}
