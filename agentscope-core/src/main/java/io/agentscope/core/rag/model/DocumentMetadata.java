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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Document metadata containing content and chunking information.
 * 文档元数据包含内容和分块信息。
 *
 * <p>This class stores metadata about a document chunk, including the content
 * (which can be text, image, video, etc.), document ID, chunk ID, and optional
 * custom payload fields.
 * 此类存储有关文档块的元数据，包括内容（可以是文本、图像、视频等）、文档ID、块ID和可选的自定义有效载荷字段。
 * 
 *
 * <p>The content field uses {@link ContentBlock} which is a sealed hierarchy
 * supporting different content types (TextBlock, ImageBlock, VideoBlock, etc.).
 * 内容字段使用{@link ContentBlock}，这是一个支持不同内容类型（文本块、图像块、视频块等）的密封层次结构。
 *
 * <p>The payload field allows storing custom metadata such as file name,
 * department, author, creation time, tags, and other business-specific fields.
 * These fields are stored as key-value pairs and will be persisted to vector
 * databases along with the document content.
 * 有效载荷字段允许存储自定义元数据，
 * 如文件名、部门、作者、创建时间、标签和其他特定于业务的字段。
 * 这些字段存储为键值对，并将与文档内容一起持久化到向量数据库中。
 *
 * <p>Example usage with payload:
 *    例子使用带有效负载：
 * <pre>{@code
 * Map<String, Object> payload = new HashMap<>();
 * payload.put("filename", "report.pdf");
 * payload.put("department", "Finance");
 * payload.put("author", "John Doe");
 * payload.put("created_at", "2024-01-15T10:30:00Z");
 * payload.put("tags", Arrays.asList("urgent", "quarterly"));
 *
 * TextBlock content = TextBlock.builder().text("Document content").build();
 * DocumentMetadata metadata = new DocumentMetadata(content, "doc-123", "chunk-0", payload);
 * }</pre>
 */
public class DocumentMetadata {

    private final ContentBlock content;
    private final String docId;
    private final String chunkId;
    private final Map<String, Object> payload;

    /**
     * Creates a new DocumentMetadata instance without custom payload.
     * 创造一个没有自定义有效负载的DocumentMetadata实例
     *
     * <p>This constructor is provided for backward compatibility. For new code,
     * consider using the constructor with payload parameter or the builder pattern
     * if you need to add custom metadata fields.
     * 提供此构造函数是为了向后兼容。
     * 对于新代码，如果需要添加自定义元数据字段，可以考虑使用带有payload参数的构造函数或构建器模式。
     *
     * @param content the content block (text, image, video, etc.) 
     * @param docId the document ID
     * @param chunkId the chunk ID within the document
     */
    public DocumentMetadata(ContentBlock content, String docId, String chunkId) {
        this(content, docId, chunkId, null);
    }

    /**
     * Creates a new DocumentMetadata instance with custom payload.
     * 创造一个带有自定义有效负载的DocumentMetadata实例
     *
     * <p>The payload map is copied to prevent external modifications. The returned
     * payload from {@link #getPayload()} will be an unmodifiable view.
     *
     * @param content the content block (text, image, video, etc.)
     * @param docId the document ID
     * @param chunkId the chunk ID within the document
     * @param payload the custom metadata fields (can be null or empty)
     * @throws IllegalArgumentException if content, docId, or chunkId is null
     */
    public DocumentMetadata(
            ContentBlock content, String docId, String chunkId, Map<String, Object> payload) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        if (docId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("Chunk ID cannot be null");
        }
        this.content = content;
        this.docId = docId;
        this.chunkId = chunkId;
        // Create an unmodifiable copy to prevent external modifications
        this.payload =
                payload != null && !payload.isEmpty()
                        ? Collections.unmodifiableMap(new HashMap<>(payload))
                        : Collections.emptyMap();
    }

    /**
     * Gets the content block.
     *
     * @return the content block
     */
    public ContentBlock getContent() {
        return content;
    }

    /**
     * Gets the document ID.
     *
     * @return the document ID
     */
    public String getDocId() {
        return docId;
    }

    /**
     * Gets the chunk ID.
     *
     * @return the chunk ID
     */
    public String getChunkId() {
        return chunkId;
    }

    /**
     * Gets the custom payload metadata.
     * 得到自定义有效负载元数据
     *
     * <p>Returns an unmodifiable map of custom metadata fields. This map contains
     * business-specific fields such as filename, department, author, tags, etc.
     * The map is never null but may be empty if no payload was provided.
     * 返回一个不可修改的元数据字段映射。这个映射包含业务特定的字段，如文件名、部门、作者、标签等。
     *
     * @return an unmodifiable map of custom metadata fields (never null) 
     * 返回不可修改的元数据字段映射（从不为null）
     */
    public Map<String, Object> getPayload() {
        return payload;
    }

    /**
     * Gets a specific payload value by key.
     * 得到一个特定有效负载值
     *
     * <p>This is a convenience method to retrieve individual payload values without
     * needing to access the entire payload map.
     * 这是是一个方便的方法，用于检索单个有效负载值，而无需访问整个有效负载映射。
     *
     * @param key the payload key
     * @return the payload value, or null if the key doesn't exist
     * @throws NullPointerException if key is null
     */
    public Object getPayloadValue(String key) {
        if (key == null) {
            throw new NullPointerException("Payload key cannot be null");
        }
        return payload.get(key);
    }

    /**
     * Checks if the payload contains a specific key.
     * 检查有效负载是否包含特定的键
     *
     * <p>Use this method to safely check for the existence of a payload field
     * before attempting to retrieve its value.
     * 使用此方法安全地检查有效负载字段的存不存在，
     *
     * @param key the payload key to check
     * @return true if the key exists in the payload, false otherwise
     * @throws NullPointerException if key is null
     */
    public boolean hasPayloadKey(String key) {
        if (key == null) {
            throw new NullPointerException("Payload key cannot be null");
        }
        return payload.containsKey(key);
    }

    /**
     * Gets the text content from the content block.
     * 获取内容块的文本内容
     *
     * <p>This is a convenience method that extracts text from the ContentBlock.
     * For TextBlock, it returns the text. For other block types, it returns their
     * string representation.
     * 这是一个方便的方法，从ContentBlock中提取文本。
     *
     * @return the text content, or empty string if not available 文本内容，如果不可用则返回空字符串
     */
    public String getContentText() {
        if (content instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        return content != null ? content.toString() : "";
    }

    /**
     * Creates a new builder for constructing DocumentMetadata instances.
     * 创造一个用于构建DocumentMetadata实例的构建器
     *
     * <p>The builder pattern provides a fluent API for creating DocumentMetadata
     * with optional payload fields. This is especially useful when you need to
     * add multiple custom metadata fields.
     * 构造器模式提供了一种流畅的API，用于创建带有可选有效负载字段的DocumentMetadata。
     *
     * <p>Example usage:
     * <pre>{@code
     * DocumentMetadata metadata = DocumentMetadata.builder()
     *     .content(TextBlock.builder().text("Content").build())
     *     .docId("doc-123")
     *     .chunkId("chunk-0")
     *     .addPayload("filename", "report.pdf")
     *     .addPayload("department", "Finance")
     *     .build();
     * }</pre>
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing DocumentMetadata instances with fluent API.
     * 用于构建具有流畅API的DocumentMetadata实例的生成器。
     *
     * <p>This builder provides a convenient way to create DocumentMetadata objects,
     * especially when working with custom payload fields. All fields can be set
     * independently, and the builder validates required fields when {@link #build()}
     * is called.
     * 此构建器提供了一种创建DocumentMetadata对象的方便方法，特别是在使用自定义有效负载字段时。
     * 所有字段都可以独立设置，构建器在调用build（）时验证所需字段。
     */
    public static class Builder {
        private ContentBlock content;
        private String docId;
        private String chunkId;
        private Map<String, Object> payload;

        private Builder() {}

        /**
         * Sets the content block.
         *
         * @param content the content block (text, image, video, etc.)
         * @return this builder for method chaining
         */
        public Builder content(ContentBlock content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the document ID.
         *
         * @param docId the document ID
         * @return this builder for method chaining
         */
        public Builder docId(String docId) {
            this.docId = docId;
            return this;
        }

        /**
         * Sets the chunk ID.
         *
         * @param chunkId the chunk ID within the document
         * @return this builder for method chaining
         */
        public Builder chunkId(String chunkId) {
            this.chunkId = chunkId;
            return this;
        }

        /**
         * Sets the entire payload map.、添加自定义元数据字段
         *
         * <p>This replaces any previously set payload. The map is copied to prevent
         * external modifications.
         * 这个方法替换了任何先前设置的有效负载。
         *
         * @param payload the custom metadata fields (can be null)
         * @return this builder for method chaining
         */
        public Builder payload(Map<String, Object> payload) {
            this.payload = payload != null ? new HashMap<>(payload) : null;
            return this;
        }

        /**
         * Adds a single payload field. 添加一个自定义元数据字段
         *
         * <p>This method allows adding payload fields one at a time. If the payload
         * map doesn't exist yet, it will be created automatically.
         * 这个方法允许一次添加单个有效负载字段。如果尚未创建有效负载映射，则将自动创建。
         *
         * @param key the payload key
         * @param value the payload value
         * @return this builder for method chaining
         * @throws NullPointerException if key is null
         */
        public Builder addPayload(String key, Object value) {
            if (key == null) {
                throw new NullPointerException("Payload key cannot be null");
            }
            if (this.payload == null) {
                this.payload = new HashMap<>();
            }
            this.payload.put(key, value);
            return this;
        }

        /**
         * Builds a new DocumentMetadata instance with the configured values.
         * 创建一个具有配置值的新的DocumentMetadata实例。
         *
         * @return a new DocumentMetadata instance 一个新的DocumentMetadata实例
         * @throws IllegalArgumentException if required fields (content, docId, chunkId) are null 如果缺少必需的字段（content, docId, chunkId）
         */
        public DocumentMetadata build() {
            return new DocumentMetadata(content, docId, chunkId, payload);
        }
    }
}
