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

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP client for interacting with the Mem0 API.
 * 用于与 Mem0 API 交互的 HTTP 客户端。
 *
 * <p>Supports both Platform Mem0 and self-hosted Mem0 deployments:
 *    同时支持平台 Mem0 和自托管 Mem0 部署：
 * <ul>
 *   <li><b>Platform Mem0:</b> Uses endpoints /v1/memories/ and /v2/memories/search/</li>
 *          平台 Mem0：</b> 使用端点 /v1/memories/ 和 /v2/memories/search/
 *   <li><b>Self-hosted Mem0:</b> Uses endpoints /memories and /memories/search</li>
 *          自托管 Mem0：</b> 使用端点 /memories 和 /memories/search
 * </ul>
 *
 * <p>By default, the client uses Platform Mem0 endpoints. To use self-hosted Mem0,
 * specify "self-hosted" as the apiType parameter.
 */
public class Mem0Client {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // Platform Mem0 endpoints
    private static final String PLATFORM_MEMORIES_ENDPOINT = "/v1/memories/";
    private static final String PLATFORM_SEARCH_ENDPOINT = "/v2/memories/search/";

    // Self-hosted Mem0 endpoints
    private static final String SELF_HOSTED_MEMORIES_ENDPOINT = "/memories";
    private static final String SELF_HOSTED_SEARCH_ENDPOINT = "/search";

    private final OkHttpClient httpClient;
    private final String apiBaseUrl;
    private final String apiKey;
    private final Mem0ApiType apiType;
    private final JsonCodec jsonCodec;
    private final String addEndpoint;
    private final String searchEndpoint;

    /**
     * Creates a new Mem0Client with specified configuration (defaults to Platform Mem0).
     *
     * @param apiBaseUrl The base URL of the Mem0 API (e.g., "http://localhost:8000")
     * @param apiKey The API key for authentication (can be null for local deployments without
     *     authentication)
     */
    public Mem0Client(String apiBaseUrl, String apiKey) {
        this(apiBaseUrl, apiKey, Duration.ofSeconds(60));
    }

    /**
     * Creates a new Mem0Client with custom timeout (defaults to Platform Mem0).
     *
     * @param apiBaseUrl The base URL of the Mem0 API
     * @param apiKey The API key for authentication (can be null for local deployments without
     *     authentication)
     * @param timeout HTTP request timeout duration
     */
    public Mem0Client(String apiBaseUrl, String apiKey, Duration timeout) {
        this(apiBaseUrl, apiKey, Mem0ApiType.PLATFORM, timeout);
    }

    /**
     * Creates a new Mem0Client with API type specification.
     *
     * @param apiBaseUrl The base URL of the Mem0 API
     * @param apiKey The API key for authentication (can be null for local deployments without
     *     authentication)
     * @param apiType API type enum
     * @param timeout HTTP request timeout duration
     */
    public Mem0Client(String apiBaseUrl, String apiKey, Mem0ApiType apiType, Duration timeout) {
        this.apiBaseUrl =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        this.apiKey = apiKey;
        this.apiType = apiType != null ? apiType : Mem0ApiType.PLATFORM;
        this.jsonCodec = JsonUtils.getJsonCodec();
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(timeout)
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();

        // Select endpoints based on API type
        if (this.apiType == Mem0ApiType.SELF_HOSTED) {
            this.addEndpoint = SELF_HOSTED_MEMORIES_ENDPOINT;
            this.searchEndpoint = SELF_HOSTED_SEARCH_ENDPOINT;
        } else {
            // Default to platform endpoints
            this.addEndpoint = PLATFORM_MEMORIES_ENDPOINT;
            this.searchEndpoint = PLATFORM_SEARCH_ENDPOINT;
        }
    }

    /**
     * Executes a POST request and returns the raw response body as a string.
     * 执行 POST 请求并将原始响应正文作为字符串返回。
     *
     * <p>This is a low-level method that handles HTTP communication, JSON serialization,
     * and error handling. The response parsing is left to the caller.
     * 这是一个底层方法，负责处理 HTTP 通信、JSON 序列化和错误处理。响应解析由调用者负责。
     *
     * @param endpoint The API endpoint path (e.g., "/v1/memories")  API 端点路径（例如，“/v1/memories”）
     * @param request The request object to serialize as JSON        要序列化为 JSON 的请求对象
     * @param operationName A human-readable name for the operation (for error messages) 操作的易读名称（用于错误消息）
     * @param <T> The request type 请求类型
     * @return A Mono emitting the raw response body as a string  一个 Mono 对象，以字符串形式发出原始响应体。
     * @throws IOException If the HTTP request fails  如果 HTTP 请求失败
     */
    private <T> Mono<String> executePostRaw(String endpoint, T request, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            // Serialize request to JSON
                            String json = jsonCodec.toJson(request);

                            // Build HTTP request
                            Request.Builder requestBuilder =
                                    new Request.Builder()
                                            .url(apiBaseUrl + endpoint)
                                            .addHeader("Content-Type", "application/json")
                                            .post(RequestBody.create(json, JSON));

                            // Add Authorization header only if apiKey is provided
                            if (apiKey != null && !apiKey.isEmpty()) {
                                if (apiType == Mem0ApiType.SELF_HOSTED) {
                                    requestBuilder.addHeader("X-API-Key", apiKey);
                                } else {
                                    requestBuilder.addHeader("Authorization", "Token " + apiKey);
                                }
                            }

                            Request httpRequest = requestBuilder.build();

                            // Execute request
                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    String errorBody =
                                            response.body() != null
                                                    ? response.body().string()
                                                    : "No error details";
                                    throw new IOException(
                                            "Mem0 API "
                                                    + operationName
                                                    + " failed with status "
                                                    + response.code()
                                                    + ": "
                                                    + errorBody);
                                }

                                // Return raw response body
                                return response.body().string();
                            } catch (IOException e) {
                                // Re-throw IOException as-is (it already contains status code info)
                                throw e;
                            } catch (Exception e) {
                                // Wrap other exceptions
                                throw new IOException("Mem0 API " + operationName + " failed", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Executes a POST request to the Mem0 API and parses the response.
     * 向 Mem0 API 执行 POST 请求并解析响应。
     *
     * <p>This is a generic method that handles HTTP communication, JSON serialization,
     * error handling, and response parsing for all POST endpoints.
     * 这是一个通用方法，用于处理所有 POST 端点的 HTTP 通信、JSON 序列化、错误处理和响应解析。
     *
     * @param endpoint The API endpoint path (e.g., "/v1/memories")
     * @param request The request object to serialize as JSON
     * @param responseType The class of the response type
     * @param operationName A human-readable name for the operation (for error messages)
     * @param <T> The request type
     * @param <R> The response type
     * @return A Mono emitting the parsed response
     * @throws IOException If the HTTP request fails or response cannot be parsed
     */
    private <T, R> Mono<R> executePost(
            String endpoint, T request, Class<R> responseType, String operationName) {
        return executePostRaw(endpoint, request, operationName)
                .map(responseBody -> jsonCodec.fromJson(responseBody, responseType));
    }

    /**
     * Adds memories to Mem0 by sending messages for processing.
     * 通过发送消息进行处理，将内存添加到 Mem0。
     *
     * <p>This method calls the {@code POST /v1/memories} endpoint. Mem0 will process
     * the messages and extract memorable information using LLM inference (unless
     * {@code infer} is set to false in the request).
     * 此方法调用 POST /v1/memories 端点。
     * Mem0 将处理消息并使用 LLM 推理提取记忆信息（除非请求中将 infer 设置为 false）。
     *
     * <p>The operation is performed asynchronously on the bounded elastic scheduler
     * to avoid blocking the caller thread.
     * 该操作在有界弹性调度器上异步执行，以避免阻塞调用线程。
     *
     * @param request The add request containing messages and metadata
     * @return A Mono emitting the response with extracted memories
     */
    public Mono<Mem0AddResponse> add(Mem0AddRequest request) {
        return executePost(addEndpoint, request, Mem0AddResponse.class, "add request");
    }

    /**
     * Searches memories in Mem0 using semantic similarity.
     * 使用语义相似性在 Mem0 中搜索内存。
     *
     * <p>This method calls the {@code POST /v2/memories/search/} endpoint to find
     * memories relevant to the query string. Results are ordered by relevance score
     * (highest first).
     * 此方法调用 POST /v2/memories/search/ 端点，查找与查询字符串相关的记忆。
     * 结果按相关性得分排序（得分最高排在最前面）。
     *
     * <p>Automatically compatible with two Mem0 API response formats:
     * 自动兼容两种 Mem0 API 响应格式：
     * <ul>
     *   <li><b>format v1.1</b> — response is a JSON object with a {@code results} field
     *       (e.g. {@code {"results": [...]}}), deserialized directly into
     *       {@link Mem0SearchResponse}.</li>
     *   <li><b>format v1.0</b> — response is a direct JSON array (e.g. {@code [...]}),
     *       parsed as a list of results and wrapped into a {@link Mem0SearchResponse}.</li>
     * </ul>
     *
     * <p>The metadata filters (agent_id, user_id, run_id) in the request ensure
     * that only memories from the specified context are returned.
     *
     * @param request The search request containing query and filters
     * @return A Mono emitting the search response with relevant memories
     */
    public Mono<Mem0SearchResponse> search(Mem0SearchRequest request) {
        return executePostRaw(searchEndpoint, request, "search request")
                .map(
                        responseBody -> {
                            // Support both response formats: direct array or object with results
                            String trimmed = responseBody != null ? responseBody.trim() : "";
                            if (trimmed.startsWith("[")) {
                                // Response is a JSON array: parse as list and wrap in results
                                List<Mem0SearchResult> results =
                                        jsonCodec.fromJson(
                                                responseBody,
                                                new TypeReference<List<Mem0SearchResult>>() {});
                                Mem0SearchResponse searchResponse = new Mem0SearchResponse();
                                searchResponse.setResults(results);
                                return searchResponse;
                            }
                            // Response is an object (e.g. {"results": [...]})
                            return jsonCodec.fromJson(responseBody, Mem0SearchResponse.class);
                        });
    }

    /**
     * Shuts down the HTTP client and releases resources.
     * 关闭 HTTP 客户端并释放资源。
     *
     * <p>This method should be called when the client is no longer needed.
     * After calling this method, the client should not be used for further requests.
     * 当不再需要客户端时，应调用此方法。调用此方法后，不应再使用该客户端处理任何请求。
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
