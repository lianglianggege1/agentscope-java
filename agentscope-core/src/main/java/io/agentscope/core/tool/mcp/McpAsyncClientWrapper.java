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
package io.agentscope.core.tool.mcp;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Wrapper for asynchronous MCP clients using Project Reactor.
 * 使用Project Reactor的异步MCP客户端的包装器。
 * This implementation delegates to {@link McpAsyncClient} and provides
 * reactive operations that return Mono types.
 * 此实现委托给{@link McpAsyncClient}，并提供返回Mono类型的响应式操作。
 *
 * <p>Example usage:
 * <pre>{@code
 * McpAsyncClient client = ... // created via McpClient.async()
 * McpAsyncClientWrapper wrapper = new McpAsyncClientWrapper("my-mcp", client);
 * wrapper.initialize()
 *     .then(wrapper.callTool("tool_name", Map.of("arg1", "value1")))
 *     .subscribe(result -> System.out.println(result));
 * }</pre>
 */
public class McpAsyncClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(McpAsyncClientWrapper.class);

    private final McpAsyncClient client;

    /**
     * Constructs a new asynchronous MCP client wrapper.
     *
     * @param name unique identifier for this client
     * @param client the underlying async MCP client
     */
    public McpAsyncClientWrapper(String name, McpAsyncClient client) {
        super(name);
        this.client = client;
    }

    /**
     * Initializes the async MCP client connection and caches available tools.
     *
     * <p>This method connects to the MCP server, discovers available tools, and caches them for
     * later use. If already initialized, this method returns immediately without re-initializing.
     *
     *  初始化异步MCP客户端连接并缓存可用工具。
     * 此方法连接到MCP服务器，发现可用工具，并缓存它们以供以后使用。如果已经初始化，此方法将立即返回，而无需重新初始化。
     * @return a Mono that completes when initialization is finished
     */
    @Override
    public Mono<Void> initialize() {
        if (initialized) {
            return Mono.empty();
        }

        logger.info("Initializing MCP async client: {}", name);

        return client.initialize()
                .doOnSuccess(
                        result ->
                                logger.debug(
                                        "MCP client '{}' initialized with server: {}",
                                        name,
                                        result.serverInfo().name()))
                .then(client.listTools())
                .doOnNext(
                        result -> {
                            logger.debug(
                                    "MCP client '{}' discovered {} tools",
                                    name,
                                    result.tools().size());
                            // Cache all tools
                            result.tools().forEach(tool -> cachedTools.put(tool.name(), tool));
                        })
                .doOnSuccess(v -> initialized = true)
                .doOnError(e -> logger.error("Failed to initialize MCP client: {}", name, e))
                .then();
    }

    /**
     * Lists all tools available from the MCP server.
     *
     * <p>This method queries the MCP server for its current list of tools. The client must be
     * initialized before calling this method.
     * 列出MCP服务器上可用的所有工具。
     * 此方法向MCP服务器查询其当前的工具列表。在调用此方法之前，必须初始化客户端。
     * 重写：McpClientWrapper中的listTools（）
     * @return a Mono emitting the list of available tools
     * @throws IllegalStateException if the client is not initialized
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        return client.listTools().map(McpSchema.ListToolsResult::tools);
    }

    /**
     * Invokes a tool on the MCP server asynchronously.
     * 异步调用MCP服务器上的工具。
     *
     * <p>This method sends a tool call request to the MCP server and returns the result
     * asynchronously. The client must be initialized before calling this method.
     * 此方法向MCP服务器发送工具调用请求，并异步返回结果。在调用此方法之前，必须初始化客户端。
     *
     * @param toolName the name of the tool to call
     * @param arguments the arguments to pass to the tool
     * @return a Mono emitting the tool call result (may contain error information)
     * @throws IllegalStateException if the client is not initialized
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        logger.debug("Calling MCP tool '{}' on client '{}'", toolName, name);

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);

        return client.callTool(request)
                .doOnSuccess(
                        result -> {
                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "MCP tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("MCP tool '{}' completed successfully", toolName);
                            }
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to call MCP tool '{}': {}",
                                        toolName,
                                        e.getMessage()));
    }

    /**
     * Closes the MCP client connection and releases all resources.
     * 关闭MCP客户端连接并释放所有资源。
     *
     * <p>This method attempts to close the client gracefully, falling back to forceful closure if
     * graceful closure fails. This method is idempotent and can be called multiple times safely.
     * 此方法尝试优雅地关闭客户端，如果优雅关闭失败，则退回到强制关闭。此方法是幂等的，可以安全地多次调用。
     */
    @Override
    public void close() {
        if (client != null) {
            logger.info("Closing MCP async client: {}", name);
            try {
                client.closeGracefully()
                        .doOnSuccess(v -> logger.debug("MCP client '{}' closed", name))
                        .doOnError(e -> logger.error("Error closing MCP client '{}'", name, e))
                        .block();
            } catch (Exception e) {
                logger.error("Exception during MCP client close", e);
                client.close();
            }
        }
        initialized = false;
        cachedTools.clear();
    }
}
