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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/**
 * Knowledge retrieval tools for Agentic RAG mode.
 * 用于Agentic RAG mode的知识检索工具。
 *
 * <p>This class provides tool methods that can be registered with agents to enable
 * autonomous knowledge retrieval. Agents can call these tools to search the knowledge
 * base when they need information.
 * 此类提供了可以向Agent注册的工具方法，
 * 以实现自主知识检索。Agent可以在需要信息时调用这些工具来搜索知识库。
 *
 * <p>This is the Agentic mode implementation - agents decide when and how to retrieve
 * knowledge from the knowledge base.
 * 这是Agentic mode 实现——agents决定何时以及如何从知识库中检索知识。
 *
 * <p>Example usage:
 * <pre>{@code
 * KnowledgeBase knowledgeBase = new SimpleKnowledge(embeddingModel, vectorStore);
 * KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledgeBase);
 *
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(tools);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .toolkit(toolkit)
 *     .build();
 * }</pre>
 */
public class KnowledgeRetrievalTools {

    private final Knowledge knowledge;

    /**
     * Creates a new KnowledgeRetrievalTools instance.
     *
     * @param knowledge the knowledge base to retrieve from
     * @throws IllegalArgumentException if knowledgeBase is null
     */
    public KnowledgeRetrievalTools(Knowledge knowledge) {
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge base cannot be null");
        }
        this.knowledge = knowledge;
    }

    /**
     * Retrieves relevant documents from the knowledge base.
     * 检索知识库中的相关文档。
     *
     * <p>This tool method allows agents to search the knowledge base for information
     * relevant to a query. The agent can specify how many documents to retrieve.
     * 这个工具方法允许agents搜索知识库中的相关信息。agent可以指定要检索的文档数量。
     *
     * <p>If the agent has conversation history in memory, that history will be automatically
     * included in the retrieval configuration. Knowledge bases that support multi-turn
     * conversation context (like Bailian) can use this history to improve retrieval accuracy.
     * 如果agent有对话历史记录，那么该历史记录将自动包含在检索配置中。支持多轮会话上下文的知识库（如Bailian）
     *
     * <p>Use this tool when:
     *    何时使用这个工具
     * <ul>
     *   <li>The user asks questions about stored knowledge
     *       用户询问存储的知识内容
     *   <li>You need to find specific information from the knowledge base
     *       你需要从知识库中查找具体信息
     *   <li>You want to provide context-aware responses based on stored documents
     *       你希望基于存储的文档提供上下文感知的回复
     * </ul>
     *
     * @param query the search query to find relevant documents 查找相关文档的搜索查询
     * @param limit the maximum number of documents to retrieve (default: 5) 要检索的最大文档数（默认值：5）
     * @param agent the agent making the call (automatically injected by framework) 进行调用的代理（由框架自动注入）
     * @return a formatted string containing the retrieved documents and their scores 包含检索到的文档及其分数的格式化字符串
     */
    @Tool(
            name = "retrieve_knowledge",
            description =
                    "Retrieve relevant documents from knowledge base. Use this tool when you need"
                        + " to find specific information or when user asks questions about stored"
                        + " knowledge.")
    public String retrieveKnowledge(
            @ToolParam(
                            name = "query",
                            description =
                                    "The search query to find relevant documents in the knowledge"
                                            + " base")
                    String query,
            @ToolParam(
                            name = "limit",
                            description = "Maximum number of documents to retrieve (default: 5)",
                            required = false)
                    Integer limit,
            Agent agent) {

        // Set default value
        if (limit == null) {
            limit = 5;
        }

        // Extract conversation history from agent if available
        List<Msg> conversationHistory = null;
        if (agent instanceof ReActAgent reActAgent) {
            conversationHistory = reActAgent.getMemory().getMessages();
        }

        // Build retrieval config with conversation history
        RetrieveConfig config =
                RetrieveConfig.builder()
                        .limit(limit)
                        .scoreThreshold(0.5)
                        .conversationHistory(conversationHistory)
                        .build();

        return knowledge
                .retrieve(query, config)
                .map(this::formatDocumentsForTool)
                .onErrorReturn("Failed to retrieve knowledge for query: " + query)
                .block(); // Convert to synchronous call to match Tool interface
    }

    /**
     * Formats retrieved documents for tool return format.
     *
     * <p>Converts the list of documents into a human-readable string format that
     * can be used by the agent in its reasoning process.
     *
     * @param documents the list of retrieved documents
     * @return a formatted string representation
     */
    private String formatDocumentsForTool(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No relevant documents found in the knowledge base.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved ").append(documents.size()).append(" relevant document(s):\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            sb.append("Document ").append(i + 1);
            if (doc.getScore() != null) {
                sb.append(" (Score: ").append(String.format("%.3f", doc.getScore())).append(")");
            }
            sb.append(":\n");
            sb.append(doc.getMetadata().getContentText()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Gets the knowledge base used by this tool.
     *
     * @return the knowledge base
     */
    public Knowledge getKnowledgeBase() {
        return knowledge;
    }
}
