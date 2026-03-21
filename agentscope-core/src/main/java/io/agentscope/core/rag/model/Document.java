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

import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Document class representing a document chunk in the RAG system.
 * 表示RAG系统中文档块的文档类。
 *
 * <p>This is the core data structure for RAG operations. Each document contains
 * metadata, an optional embedding vector, and an optional similarity score.
 * 这是RAG操作的核心数据结构。每个文档都包含元数据、可选的嵌入向量和可选的相似性得分。
 *
 * <p>The document ID is automatically generated as a deterministic UUID based on
 * the document metadata (doc_id, chunk_id, and content), ensuring consistent IDs
 * for the same content across different runs.
 * 文档ID是基于文档元数据（doc_ID、chunk_ID和内容）自动生成的确定性UUID，
 * 确保不同运行中相同内容的ID一致。
 */
public class Document {

    private final String id;
    private final DocumentMetadata metadata;
    private double[] embedding;
    private Double score;
    private String vectorName;

    /**
     * Creates a new Document instance.
     * 创建新的Document实例。
     *
     * <p>The document ID is automatically generated as a deterministic UUID based on
     * the metadata (doc_id, chunk_id, and content).
     * 文档ID是基于元数据（doc_ID、chunk_ID和内容）自动生成的确定性UUID。
     *
     * @param metadata the document metadata 文档元数据
     */
    public Document(DocumentMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
        this.metadata = metadata;
        this.id = generateDocumentId(metadata);
    }

    /**
     * Gets the document ID. 获取文档ID
     *
     * @return the document ID (UUID string)
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the document metadata.  获取文档元数据
     *
     * @return the document metadata
     */
    public DocumentMetadata getMetadata() {
        return metadata;
    }

    /**
     * Gets the embedding vector. 获取嵌入向量。
     *
     * @return the embedding vector, or null if not set
     */
    public double[] getEmbedding() {
        return embedding;
    }

    /**
     * Sets the embedding vector. 设置嵌入向量。
     *
     * @param embedding the embedding vector
     */
    public void setEmbedding(double[] embedding) {
        this.embedding = embedding;
    }

    /**
     * Gets the similarity score. 获取相似性得分。
     *
     * @return the similarity score, or null if not set 相似性得分，如果未设置，则为空
     */
    public Double getScore() {
        return score;
    }

    /**
     * Sets the similarity score. 设置相似性得分。
     *
     * @param score the similarity score
     */
    public void setScore(Double score) {
        this.score = score;
    }

    /**
     * Gets the custom payload from document metadata.
     * 从文档元数据中获取自定义有效负载。
     *
     * <p>This is a convenience method that delegates to the metadata's getPayload().
     * The payload contains business-specific fields such as filename, department,
     * author, tags, and other custom metadata.
     * 这是一种方便的方法，它委托给元数据的getPayload（）。
     * 有效负载包含特定于业务的字段，如文件名、部门、作者、标签和其他自定义元数据。
     *
     * @return an unmodifiable map of custom metadata fields (never null) 自定义元数据字段的不可修改映射（从不为空）
     */
    public Map<String, Object> getPayload() {
        return metadata.getPayload();
    }

    /**
     * Gets a specific payload value by key.
     * 按键获取特定的有效负载值。
     *
     * <p>This is a convenience method that delegates to the metadata's getPayloadValue().
     * Use this to retrieve individual payload values without accessing the entire map.
     * 这是一种方便的方法，它委托给元数据的getPayloadValue（）。使用此功能可检索单个有效载荷值，而无需访问整个映射。
     *
     * @param key the payload key 有效载荷密钥
     * @return the payload value, or null if the key doesn't exist 有效载荷值，如果密钥不存在，则为null
     * @throws NullPointerException if key is null
     */
    public Object getPayloadValue(String key) {
        return metadata.getPayloadValue(key);
    }

    /**
     * Gets a specific payload value by key and converts it to the specified type.
     * 按键获取特定的有效负载值，并将其转换为指定类型。
     *
     * <p>This method is useful when the payload contains complex objects (like custom POJOs)
     * that were serialized to Map during storage. It uses Jackson's ObjectMapper to convert
     * the Map back to the original type.
     * 当有效负载包含在存储期间序列化为Map的复杂对象（如自定义POJO）时，
     * 此方法非常有用。它使用Jackson的ObjectMapper将Map转换回原始类型。
     *
     * @param <T> the target type 目标对象
     * @param key the payload key 有效载荷密钥
     * @param targetClass the target class to convert to 目标类
     * @return the payload value converted to the specified type, or null if the key doesn't exist 键不存在
     * @throws IllegalArgumentException if the value cannot be converted to the target type 转换的值不能转换为目标类型
     * @throws NullPointerException if key or clazz is null 键或clazz为空
     */
    public <T> T getPayloadValueAs(String key, Class<T> targetClass) {
        Object value = getPayloadValue(key);
        if (value == null) {
            return null;
        }
        try {
            return JsonUtils.getJsonCodec().convertValue(value, targetClass);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to convert payload value for key '%s' to type %s",
                            key, targetClass.getName()),
                    e);
        }
    }

    /**
     * Checks if the document payload contains a specific key.
     * 检查文档负载是否包含特定密钥。
     *
     * <p>This is a convenience method that delegates to the metadata's hasPayloadKey().
     * Use this to safely check for the existence of a payload field before retrieving it.
     * 这是一种方便的方法，它委托给元数据的hasPayloadKey（）。
     * 在检索之前，使用此功能安全地检查有效载荷字段的存在。
     *
     * @param key the payload key to check 要检查的有效载荷密钥
     * @return true if the key exists in the payload, false otherwise 如果密钥存在于有效载荷中，则为true，否则为false
     * @throws NullPointerException if key is null
     */
    public boolean hasPayloadKey(String key) {
        return metadata.hasPayloadKey(key);
    }

    /**
     * Gets the document vector name. 获取文档向量名称。
     *
     * @return the document name, or null if not set 文档名称，如果未设置，则为null
     */
    public String getVectorName() {
        return vectorName;
    }

    /**
     * Sets the document vector name. 设置文档向量名称。
     *
     * @param vectorName the document name 文档名称
     */
    public void setVectorName(String vectorName) {
        this.vectorName = vectorName;
    }

    /**
     * Generates a deterministic document ID based on metadata.
     * 基于元数据生成确定性文档ID。
     *
     * <p>This method creates a UUID v3 (name-based with MD5) from a JSON representation
     * of the document's key fields (doc_id, chunk_id, content). This ensures that the
     * same document content always generates the same ID, which is compatible with the
     * Python implementation's _map_text_to_uuid function.
     * 此方法根据文档关键字段（doc_id、chunk_id、content）的JSON表示创建UUID v3（基于MD5的名称）。
     * 这确保了相同的文档内容始终生成相同的ID，这与Python实现的_map_text_to_uid函数兼容。
     *
     * @param metadata the document metadata 文档元数据
     * @return a deterministic UUID string 确定性UUID字符串
     */
    private static String generateDocumentId(DocumentMetadata metadata) {
        // Create a map with doc_id, chunk_id, and content (matching Python implementation)
        Map<String, Object> keyMap = new LinkedHashMap<>();
        keyMap.put("doc_id", metadata.getDocId());
        keyMap.put("chunk_id", metadata.getChunkId());
        keyMap.put("content", metadata.getContent());

        // Serialize to JSON (ensure_ascii=False in Python, so we use default UTF-8)
        String jsonKey = JsonUtils.getJsonCodec().toJson(keyMap);

        // Generate UUID v3 (name-based with MD5) from the JSON string
        return UUID.nameUUIDFromBytes(jsonKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String toString() {
        return String.format(
                "Document(id=%s, name=%s, score=%s, content=%s)",
                id,
                vectorName != null ? vectorName : "null",
                score != null ? String.format("%.3f", score) : "null",
                metadata.getContentText());
    }
}
