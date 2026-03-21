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
package io.agentscope.core.rag.model;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Configuration for document retrieval operations.
 * 配置文档检索操作
 *
 * <p>This class uses the builder pattern to configure retrieval parameters such as
 * the maximum number of results, the minimum similarity score threshold, and optional
 * conversation history for context-aware retrieval.
 * 此类使用构建器模式配置检索参数，
 * 如最大结果数、最小相似性得分阈值和可选的上下文感知检索对话历史。
 */
public class RetrieveConfig {

    private final int limit;
    private final double scoreThreshold;
    private final String vectorName;
    private final List<Msg> conversationHistory;

    private RetrieveConfig(Builder builder) {
        this.limit = builder.limit;
        this.scoreThreshold = builder.scoreThreshold;
        this.vectorName = builder.vectorName;
        this.conversationHistory = builder.conversationHistory;
    }

    /**
     * Gets the maximum number of documents to retrieve.
     *
     * @return the limit
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Gets the minimum similarity score threshold.
     *
     * @return the score threshold
     */
    public double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * Gets the vector name for retrieval.
     * 得到向量名称
     *
     * @return the vector name, or null if not set
     */
    public String getVectorName() {
        return vectorName;
    }

    /**
     * Gets the conversation history for context-aware retrieval.
     * 获取用于上下文感知检索的对话历史记录。
     *
     * <p>This is an optional field used by knowledge bases that support multi-turn
     * conversation context (e.g., Bailian Knowledge Base with query rewriting).
     * 这是支持多回合对话上下文的知识库使用的可选字段（例如，具有查询重写功能的百联知识库）。
     *
     * @return the conversation history, or null if not set
     */
    public List<Msg> getConversationHistory() {
        return conversationHistory;
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for RetrieveConfig.
     * 构建RetrieveConfig实例
     */
    public static class Builder {

        private int limit = 5;
        private double scoreThreshold = 0.5;
        private String vectorName;
        private List<Msg> conversationHistory;

        /**
         * Sets the maximum number of documents to retrieve.
         *
         * @param limit the limit (must be positive)
         * @return this builder for chaining
         */
        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("Limit must be positive");
            }
            this.limit = limit;
            return this;
        }

        /**
         * Sets the minimum similarity score threshold.
         * 设置相似性得分阈值
         *
         * @param scoreThreshold the score threshold (must be between 0.0 and 1.0)
         * @return this builder for chaining
         */
        public Builder scoreThreshold(double scoreThreshold) {
            if (scoreThreshold < 0.0 || scoreThreshold > 1.0) {
                throw new IllegalArgumentException("Score threshold must be between 0.0 and 1.0");
            }
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * Sets the vector name for retrieval.
         * 设置向量名称
         *
         * @param vectorName the vector name (can be null)
         * @return this builder for chaining
         */
        public Builder vectorName(String vectorName) {
            this.vectorName = vectorName;
            return this;
        }

        /**
         * Sets the conversation history for context-aware retrieval.
         * 设置用于上下文感知检索的会话历史记录。
         *
         * <p>This is an optional parameter used by knowledge bases that support
         * multi-turn conversation context. When provided, the knowledge base may use
         * this history to rewrite or contextualize the query for better retrieval results.
         * 这是支持多回合对话上下文的知识库使用的可选参数。
         * 当提供时，知识库可以使用此历史来重写或上下文化查询，以获得更好的检索结果
         *
         * @param conversationHistory the conversation history (can be null)
         * @return this builder for chaining
         */
        public Builder conversationHistory(List<Msg> conversationHistory) {
            this.conversationHistory = conversationHistory;
            return this;
        }

        /**
         * Builds the RetrieveConfig instance.
         * 构建RetrieveConfig实例
         *
         * @return the configured RetrieveConfig
         */
        public RetrieveConfig build() {
            return new RetrieveConfig(this);
        }
    }
}
