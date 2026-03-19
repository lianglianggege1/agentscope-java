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

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for knowledge bases.
 * 最基本的知识库接口
 *
 * <p>This interface provides a unified API for storing and retrieving documents
 * in a knowledge base. Knowledge bases are used in RAG (Retrieval-Augmented Generation)
 * systems to provide context to language models.
 * 该接口提供了一个统一的API，
 * 用于在知识库中存储和检索文档。
 * 知识库用于RAG（检索增强生成）系统，为语言模型提供上下文。
 */
public interface Knowledge {

    /**
     * Adds documents to the knowledge base.
     * 添加文档到知识库中
     *
     * <p>Documents are embedded and stored in the vector database for later retrieval.
     *  文档类是embedded后并保存在向量数据库中，以便稍后检索。
     *
     * @param documents the list of documents to add 要被添加的文档列表
     * @return a Mono that completes when all documents have been added 一个Mono，当所有文档添加完成后完成
     */
    Mono<Void> addDocuments(List<Document> documents);

    /**
     * Retrieves relevant documents based on a query.
     * 根据查询检索相关的文档。
     *
     * <p>The query is embedded and used to search for similar documents in the
     * knowledge base. Results are filtered by the score threshold and limited
     * by the configured limit.
     * 这个查询被embedded并用于在知识库中搜索相似的文档。结果根据得分阈值和配置的limit进行过滤。
     *
     * @param query the search query text 查询文本
     * @param config the retrieval configuration (limit, score threshold, etc.) 检索配置（limit，得分阈值等）
     * @return a Mono that emits a list of relevant Document objects, sorted by relevance 一个Mono，发出一个相关的Document对象列表，按相关性排序
     */
    Mono<List<Document>> retrieve(String query, RetrieveConfig config);
}
