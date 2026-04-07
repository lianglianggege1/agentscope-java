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
package io.agentscope.core.memory.mem0;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Long-term memory implementation using Mem0 as the backend.
 * 长久记忆实现，使用Mem0作为后端。
 *
 * <p>This implementation integrates with Mem0, a memory layer for AI applications that
 * provides persistent, searchable memory storage using vector embeddings and LLM-powered
 * memory extraction.
 * 这个实现与Mem0一起使用，一个用于AI应用程序的记忆层，使用向量嵌入和LLM-Powered记忆提取进行持久、可搜索记忆存储。
 *
 * <p><b>Key Features:</b>
 *       关键特性：
 * <ul>
 *   <li>Semantic memory search using vector embeddings 
 *       使用向量嵌入进行语义记忆搜索
 *   <li>LLM-powered memory extraction and inference
 *       LLM-Powered记忆提取和推理
 *   <li>Multi-tenant memory isolation (agent, user, run)
 *       多租户记忆隔离（agent，用户，运行）
 *   <li>Custom metadata support for tagging and filtering memories
 *       自定义元数据支持，用于标记和过滤记忆
 *   <li>Automatic fallback mechanisms to ensure reliable memory storage
 *       确保可靠记忆存储的自动回退机制
 *   <li>Reactive, non-blocking operations
 *       反应式，非阻塞操作
 * </ul>
 *
 * <p><b>Memory Isolation:</b>
 *       记忆隔离：
 * Memories are organized using three levels of metadata:
 * 记忆存储使用三个级别的元数据：
 * <ul>
 *   <li><b>agentId:</b> Identifies the agent (optional)</li>
 *          agentId: 标识代理（可选）
 *   <li><b>userId:</b> Identifies the user/workspace (optional)</li>
 *           userId: 标识用户/工作区（可选）
 *   <li><b>runId:</b> Identifies the session/run (optional)</li>
 *          runId: 标识会话/运行（可选）
 *   <li><b>metadata:</b> Custom key-value pairs for additional filtering (optional)</li>
 *          metadata: 自定义键值对，用于附加过滤（可选）
 * </ul>
 * At least one identifier is required. During retrieval, only memories with matching
 * metadata are returned.
 * 至少需要一个标识符。在检索过程中，只返回具有匹配元数据的内存。
 * 
 *
 * <p><b>Usage Example:</b>
 *       使用示例：
 * <pre>{@code
 * // Create memory instance with authentication
 * Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .apiKey(System.getenv("MEM0_API_KEY"))
 *     .build();
 *
 * // For local deployments without authentication
 * Mem0LongTermMemory localMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .build();
 *
 * // For self-hosted Mem0
 * Mem0LongTermMemory selfHostedMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .apiType(Mem0ApiType.SELF_HOSTED)  // Specify self-hosted API type
 *     .build();
 *
 * // With custom metadata for filtering
 * Map<String, Object> metadata = new HashMap<>();
 * metadata.put("category", "travel");
 * metadata.put("project_id", "proj_001");
 *
 * Mem0LongTermMemory memoryWithMetadata = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userId("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .metadata(metadata)  // Custom metadata for storage and filtering
 *     .build();
 *
 * // Record a message (metadata will be stored with the memory)
 * Msg msg = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content("I prefer homestays when traveling")
 *     .build();
 *
 * memory.record(List.of(msg)).block();
 *
 * // Retrieve relevant memories (metadata will be used as filter)
 * Msg query = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content("What are my travel preferences?")
 *     .build();
 *
 * String memories = memory.retrieve(query).block();
 * // Result: "User prefers homestays when traveling"
 * }</pre>
 *
 * @see LongTermMemory
 * @see Mem0Client
 */
public class Mem0LongTermMemory implements LongTermMemory {

    private final Mem0Client client;
    private final String agentId;
    private final String userId;
    private final String runId;

    /**
     * Custom metadata to be stored with memories and used for filtering during retrieval.
     *
     * <p>This metadata is:
     * <ul>
     *   <li>Included in the {@code metadata} field when recording memories via {@link #record(List)}</li>
     *   <li>Added to the {@code filters} field when retrieving memories via {@link #retrieve(Msg)}</li>
     * </ul>
     *
     * <p>Use cases include:
     *    使用示例：
     * <ul>
     *   <li>Tagging memories with custom labels (e.g., "category": "travel")</li>
     *       标记记忆使用自定义标签（例如："category": "travel")
     *   <li>Filtering memories by project, tenant, or other business attributes</li>
     *       过滤记忆 por 项目、租户或其他业务属性
     *   <li>Storing additional context that should be associated with all memories</li>
     *       存储应与所有记忆关联的附加上下文
     * </ul>
     */
    private final Map<String, Object> metadata;

    /**
     * Private constructor - use Builder instead.
     * 私有构造函数 - 请使用 Builder 代替。
     */
    private Mem0LongTermMemory(Builder builder) {
        Mem0ApiType apiType = builder.apiType != null ? builder.apiType : Mem0ApiType.PLATFORM;
        this.client = new Mem0Client(builder.apiBaseUrl, builder.apiKey, apiType, builder.timeout);
        this.agentId = builder.agentName;
        this.userId = builder.userId;
        this.runId = builder.runName;
        this.metadata = builder.metadata;

        // Validate that at least one identifier is provided
        if (agentId == null && userId == null && runId == null) {
            throw new IllegalArgumentException(
                    "At least one of agentName, userName, or runName must be provided");
        }
    }

    /**
     * Records messages to long-term memory.
     * 存储消息到长期记忆。
     *
     * <p>This method converts each message to a Mem0Message object, preserving the
     * conversation structure (role and content). The messages are sent to Mem0 API
     * which uses LLM inference to extract memorable information.
     * 这个方法将每个消息转换为 Mem0Message 对象，保留对话结构（角色和内容）。
     * 这个信息会被 LLM 推理提取出来。
     *
     * <p>Null messages and messages with empty text content are filtered out before
     * processing. Empty message lists are handled gracefully without error.
     * 空消息和文本内容为空的消息在处理前会被过滤掉。空消息列表处理得当，没有错误。
     *
     * @param msgs List of messages to record
     * @return A Mono that completes when recording is finished
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        // Convert Msg list to Mem0Message list
        List<Mem0Message> mem0Messages =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                msg ->
                                        msg.getTextContent() != null
                                                && !msg.getTextContent().isEmpty()
                                                && !msg.getTextContent()
                                                        .contains("<compressed_history>"))
                        .map(this::convertToMem0Message)
                        .collect(Collectors.toList());

        if (mem0Messages.isEmpty()) {
            return Mono.empty();
        }

        // Send messages to Mem0
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(mem0Messages)
                        .agentId(agentId)
                        .userId(userId)
                        .runId(runId)
                        .metadata(metadata)
                        .infer(true)
                        .build();

        return client.add(request).then();
    }

    /**
     * Converts a Msg to a Mem0Message.
     * 转换 Msg 为 Mem0Message。
     *
     * <p>Role mapping:
     * <ul>
     *   <li>USER -> "user"</li>
     *   <li>ASSISTANT -> "assistant"</li>
     *   <li>SYSTEM -> "user" (system messages as user context)</li>
     *   <li>TOOL -> "assistant" (tool results as assistant context)</li>
     * </ul>
     */
    private Mem0Message convertToMem0Message(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER, SYSTEM -> "user";
                    case ASSISTANT, TOOL -> "assistant";
                };

        return Mem0Message.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * Builds a search request with the given query.
     * 构建搜索请求，并使用给定的查询。
     *
     * <p>The search request includes:
     * <ul>
     *   <li>Standard filters: userId, agentId, runId (added by builder convenience methods)</li>
     *   <li>Custom metadata filters: merged into filters via builder.getFilters()</li>
     * </ul>
     *
     * @param query The search query string
     * @return A configured Mem0SearchRequest for v2 API
     */
    private Mem0SearchRequest buildSearchRequest(String query) {
        Mem0SearchRequest.Builder builder =
                Mem0SearchRequest.builder()
                        .query(query)
                        .userId(userId)
                        .agentId(agentId)
                        .runId(runId)
                        .topK(5);

        // Merge custom metadata into filters if present
        if (metadata != null && !metadata.isEmpty()) {
            builder.getFilters().putAll(metadata);
        }

        return builder.build();
    }

    /**
     * Retrieves relevant memories based on the input message.
     * 根据输入消息检索相关内存。
     *
     * <p>Uses semantic search to find memories relevant to the message content.
     * Returns memory text as a newline-separated string, or empty string if no
     * relevant memories are found.
     * 使用语义搜索来查找与消息内容相关的记忆。
     * 以换行符分隔的字符串返回内存文本，如果找不到相关内存，则返回空字符串。
     *
     * <p>Only memories with matching metadata (agentId, userId, runId) are returned.
     *    仅仅返回具有匹配元数据的记忆。
     *
     * @param msg The message to use as a search query
     * @return A Mono emitting the retrieved memory text (may be empty)
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }

        String query = msg.getTextContent();
        if (query == null || query.isEmpty()) {
            return Mono.just("");
        }

        return client.search(buildSearchRequest(query))
                .map(
                        response -> {
                            if (response.getResults() == null || response.getResults().isEmpty()) {
                                return "";
                            }

                            return response.getResults().stream()
                                    .map(Mem0SearchResult::getMemory)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.joining("\n"));
                        })
                .onErrorReturn("");
    }

    /**
     * Creates a new builder for Mem0LongTermMemory.
     * 创建 Mem0LongTermMemory 的新构建器。
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for Mem0LongTermMemory.
     * Mem0LongTermMemory 的构建器。
     */
    public static class Builder {
        private String agentName;
        private String userId;
        private String runName;
        private String apiBaseUrl;
        private String apiKey;
        private Mem0ApiType apiType;
        private Duration timeout = Duration.ofSeconds(60);
        private Map<String, Object> metadata;

        /**
         * Sets the agent name identifier.
         * 设置agent名标识符。
         *
         * @param agentName The agent's name
         * @return This builder
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * Sets the user id identifier.
         * 设置用户id标识符。
         *
         * @param userId The user's ID
         * @return This builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the run name identifier.
         *
         * @param runName The run/session name or ID
         * @return This builder
         */
        public Builder runName(String runName) {
            this.runName = runName;
            return this;
        }

        /**
         * Sets the Mem0 API base URL.
         *
         * @param apiBaseUrl The base URL (e.g., "http://localhost:8000")
         * @return This builder
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * Sets the Mem0 API key.
         *
         * @param apiKey The API key for authentication (optional for local deployments without
         *     authentication)
         * @return This builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the HTTP request timeout.
         *
         * @param timeout The timeout duration
         * @return This builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the Mem0 API type.
         *
         * @param apiType API type enum
         * @return This builder
         */
        public Builder apiType(Mem0ApiType apiType) {
            this.apiType = apiType;
            return this;
        }

        /**
         * Sets custom metadata to be stored with memories and used for filtering.
         *
         * <p>This metadata will be:
         * <ul>
         *   <li>Included in the request body when recording memories</li>
         *   <li>Added to the filters when searching/retrieving memories</li>
         * </ul>
         *
         * <p>Example usage:
         * <pre>{@code
         * Map<String, Object> metadata = new HashMap<>();
         * metadata.put("category", "travel");
         * metadata.put("priority", "high");
         *
         * Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
         *     .agentName("Assistant")
         *     .userId("user_123")
         *     .apiBaseUrl("http://localhost:8000")
         *     .metadata(metadata)
         *     .build();
         * }</pre>
         *
         * @param metadata Custom metadata map (can be null)
         * @return This builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds the Mem0LongTermMemory instance.
         *
         * @return A new Mem0LongTermMemory instance
         * @throws IllegalArgumentException If required fields are missing
         */
        public Mem0LongTermMemory build() {
            if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
                throw new IllegalArgumentException("apiBaseUrl is required");
            }

            return new Mem0LongTermMemory(this);
        }
    }
}
