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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AgentTool implementation that wraps a sub-agent for multi-turn conversation.
 * AgentTool 实现，封装了一个用于多轮对话的子代理。
 *
 * <p>This tool allows an agent to be called as a tool by other agents, supporting multi-turn
 * conversation with session management. Each session maintains its own agent instance and state.
 * 该工具允许其他代理将某个代理作为工具调用，支持多轮对话和会话管理。每个会话都维护着自己的代理实例和状态。
 *
 * <p>Thread safety is ensured by using {@link SubAgentProvider} to create a fresh agent instance
 * for each new session.
 * 通过使用 {@link SubAgentProvider} 为每个新会话创建一个新的代理实例，从而确保线程安全。
 *
 * <p>The tool exposes two parameters:
 *
 * <ul>
 *   <li>{@code session_id} - Optional. Omit to start a new session, provide to continue an
 *       existing one. 可选。省略此参数将开始新会话，或继续现有会话。
 *   <li>{@code message} - Required. The message to send to the agent. 必填项。要发送给代理人的消息。
 * </ul>
 */
public class SubAgentTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);

    /** Parameter name for session ID. */
    private static final String PARAM_SESSION_ID = "session_id";

    /** Parameter name for message. */
    private static final String PARAM_MESSAGE = "message";

    private final String name;
    private final String description;
    private final SubAgentProvider<?> agentProvider;
    private final SubAgentConfig config;

    /**
     * Creates a new SubAgentTool.
     *
     * @param agentProvider Provider for creating agent instances 用于创建代理实例的提供程序
     * @param config Configuration for the tool 工具配置
     */
    public SubAgentTool(SubAgentProvider<?> agentProvider, SubAgentConfig config) {
        // Create a sample agent to derive name and description
        Agent sampleAgent = agentProvider.provide();

        this.agentProvider = agentProvider;
        this.config = config != null ? config : SubAgentConfig.defaults();
        this.name = resolveToolName(sampleAgent, this.config);
        this.description = resolveDescription(sampleAgent, this.config);

        logger.debug("Created SubAgentTool: name={}, description={}", name, description);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return buildSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return executeConversation(param);
    }

    /**
     * Executes a conversation with the sub-agent, managing session lifecycle.
     * 与子代理执行对话，管理会话生命周期。
     *
     * <p>This method handles:
     *
     * <ul>
     *   <li>Session ID generation for new conversations 为新对话生成会话 ID
     *   <li>Agent state loading for continued sessions 代理状态加载以继续会话
     *   <li>Message execution (streaming or non-streaming based on config) 消息执行（根据配置选择流式或非流式）
     *   <li>Agent state persistence after execution 执行后代理状态的持久性
     * </ul>
     *
     * @param param The tool call parameters containing input and emitter
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeConversation(ToolCallParam param) {
        return Mono.deferContextual(
                (ctxView) -> {
                    try {
                        Map<String, Object> input = param.getInput();

                        // Get or create session ID
                        String sessionId = (String) input.get(PARAM_SESSION_ID);
                        boolean isNewSession = sessionId == null;
                        if (isNewSession) {
                            sessionId = UUID.randomUUID().toString();
                        }

                        // Get message
                        String message = (String) input.get(PARAM_MESSAGE);
                        if (message == null || message.isEmpty()) {
                            return Mono.just(ToolResultBlock.error("Message is required"));
                        }

                        // Create agent for this session
                        final String finalSessionId = sessionId;
                        Agent agent = agentProvider.provide();

                        // Load existing state if continuing session
                        if (!isNewSession && agent instanceof StateModule) {
                            loadAgentState(finalSessionId, (StateModule) agent);
                        }

                        // Build user message
                        Msg userMsg =
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text(message).build())
                                        .build();

                        logger.debug(
                                "Session {} with agent '{}': {}",
                                isNewSession ? "started" : "continued",
                                agent.getName(),
                                message.substring(0, Math.min(50, message.length())));

                        // Get emitter for event forwarding
                        ToolEmitter emitter = param.getEmitter();

                        // Execute and save state after completion
                        Mono<ToolResultBlock> result;
                        if (config.isForwardEvents()) {
                            result = executeWithStreaming(agent, userMsg, finalSessionId, emitter);
                        } else {
                            result = executeWithoutStreaming(agent, userMsg, finalSessionId);
                        }

                        // Save state after execution
                        return result.doOnSuccess(
                                r -> {
                                    if (agent instanceof StateModule) {
                                        saveAgentState(finalSessionId, (StateModule) agent);
                                    }
                                });
                    } catch (Exception e) {
                        logger.error("Error in session setup: {}", e.getMessage(), e);
                        return Mono.just(
                                ToolResultBlock.error("Session setup failed: " + e.getMessage()));
                    }
                });
    }

    /**
     * Loads agent state from the session storage.
     *
     * <p>If the session exists, the agent's state is restored. Any errors during loading are logged
     * but do not interrupt execution.
     *
     * @param sessionId The session ID to load state from
     * @param agent The state module to restore state into
     */
    private void loadAgentState(String sessionId, StateModule agent) {
        Session session = config.getSession();
        try {
            agent.loadIfExists(session, sessionId);
            logger.debug("Loaded state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn("Failed to load state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Saves agent state to the session storage.
     * 将代理状态保存到会话存储中。
     *
     * <p>Persists the agent's current state. Any errors during saving are logged but do not
     * interrupt execution.
     * 保存代理的当前状态。保存过程中出现的任何错误都会被记录，但不会中断执行。
     *
     * @param sessionId The session ID to save state under
     * @param agent The state module to save state from
     */
    private void saveAgentState(String sessionId, StateModule agent) {
        Session session = config.getSession();
        try {
            agent.saveTo(session, sessionId);
            logger.debug("Saved state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn("Failed to save state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Executes agent call with streaming, forwarding events to the emitter.
     * 执行流式代理调用，将事件转发给发射器。
     *
     * <p>Uses the agent's streaming API and forwards each event to the provided emitter as JSON.
     * The final response is extracted from the last event.
     * 使用代理的流式 API，并将每个事件以 JSON 格式转发到指定的发射器。最终响应从最后一个事件中提取。
     *
     * @param agent The agent to execute
     * @param userMsg The user message to send
     * @param sessionId The session ID for result building
     * @param emitter The emitter to forward events to
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeWithStreaming(
            Agent agent, Msg userMsg, String sessionId, ToolEmitter emitter) {

        StreamOptions streamOptions =
                config.getStreamOptions() != null
                        ? config.getStreamOptions()
                        : StreamOptions.defaults();

        return Mono.deferContextual(
                ctxView ->
                        agent.stream(List.of(userMsg), streamOptions)
                                .doOnNext(event -> forwardEvent(event, emitter, agent, sessionId))
                                .filter(Event::isLast)
                                .last()
                                .map(
                                        lastEvent -> {
                                            Msg response = lastEvent.getMessage();
                                            return buildResult(response, sessionId);
                                        })
                                .contextWrite(context -> context.putAll(ctxView))
                                .onErrorResume(
                                        e -> {
                                            logger.error(
                                                    "Error in streaming execution:" + " {}",
                                                    e.getMessage(),
                                                    e);
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            "Execution error: " + e.getMessage()));
                                        }));
    }

    /**
     * Executes agent call without streaming.
     * 执行代理呼叫，无需流式传输。
     *
     * <p>Uses the agent's standard call API. No events are forwarded to the emitter.
     *
     * @param agent The agent to execute
     * @param userMsg The user message to send
     * @param sessionId The session ID for result building
     * @return A Mono emitting the tool result block
     */
    private Mono<ToolResultBlock> executeWithoutStreaming(
            Agent agent, Msg userMsg, String sessionId) {

        return Mono.deferContextual(
                ctxView ->
                        agent.call(List.of(userMsg))
                                .map(response -> buildResult(response, sessionId))
                                .onErrorResume(
                                        e -> {
                                            logger.error(
                                                    "Error in execution: {}", e.getMessage(), e);
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            "Execution error: " + e.getMessage()));
                                        })
                                .contextWrite(context -> context.putAll(ctxView)));
    }

    /**
     * Forwards an event to the emitter as serialized JSON.
     * 将事件以序列化 JSON 的形式转发给发射器。
     *
     * <p>Serializes the event using JsonCodec and emits it as a text block. Serialization
     * failures are logged but do not interrupt execution.
     *
     * @param event The event to forward
     * @param emitter The emitter to send the event to
     * @param agent The agent
     * @param sessionId Current session ID
     */
    private void forwardEvent(Event event, ToolEmitter emitter, Agent agent, String sessionId) {
        try {
            String json = JsonUtils.getJsonCodec().toJson(event);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subagent_event", event == null ? "" : event);
            metadata.put("subagent_name", agent.getName() == null ? "" : agent.getName());
            metadata.put("subagent_id", agent.getAgentId() == null ? "" : agent.getAgentId());
            metadata.put("subagent_session_id", sessionId == null ? "" : sessionId);
            emitter.emit(
                    new ToolResultBlock(
                            null, null, List.of(TextBlock.builder().text(json).build()), metadata));
        } catch (Exception e) {
            logger.warn("Failed to serialize event to JSON: {}", e.getMessage());
        }
    }

    /**
     * Builds the final tool result with session context.
     * 根据会话上下文构建最终工具结果。
     *
     * <p>Formats the response to include the session ID, allowing callers to continue the
     * conversation by passing the session ID in subsequent calls.
     * 将响应格式化为包含会话 ID，允许呼叫者通过在后续呼叫中传递会话 ID 来继续对话。
     *
     * @param response The agent's response message
     * @param sessionId The session ID to include in the result
     * @return A tool result block containing the formatted response
     */
    private ToolResultBlock buildResult(Msg response, String sessionId) {
        String textContent = response.getTextContent();

        // Return response with session context
        return ToolResultBlock.text(
                String.format(
                        "session_id: %s\n\n%s",
                        sessionId, textContent != null ? textContent : "(No response)"));
    }

    /**
     * Builds the JSON schema for tool parameters.
     * 构建工具参数的 JSON 模式。
     *
     * <p>Creates a schema with two properties:
     *
     * <ul>
     *   <li>{@code session_id} - Optional string for continuing existing conversations
     *   <li>{@code message} - Required string containing the message to send
     * </ul>
     *
     * @return A map representing the JSON schema for tool parameters 表示工具参数 JSON 模式的映射
     */
    private Map<String, Object> buildSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // Session ID (optional)
        Map<String, Object> sessionIdProp = new HashMap<>();
        sessionIdProp.put("type", "string");
        sessionIdProp.put(
                "description",
                "Session ID for multi-turn dialogue. Omit to start a NEW session."
                        + " To CONTINUE an existing session and retain memory, you MUST extract"
                        + " the session_id from the previous response and pass it here.");
        properties.put(PARAM_SESSION_ID, sessionIdProp);

        // Message (required)
        Map<String, Object> messageProp = new HashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description", "Message to send to the agent");
        properties.put(PARAM_MESSAGE, messageProp);

        schema.put("properties", properties);
        schema.put("required", List.of(PARAM_MESSAGE));

        return schema;
    }

    /**
     * Resolves the tool name from config or derives it from the agent.
     * 根据配置解析工具名称，或从代理中推导出工具名称。
     *
     * <p>Priority: config.toolName > derived from agent name. When deriving from agent name, the
     * name is converted to lowercase and prefixed with "call_" (e.g., "ResearchAgent" becomes
     * "call_researchagent").
     *
     * @param agent The agent to derive name from if not configured
     * @param config The configuration that may override the name
     * @return The resolved tool name
     */
    private String resolveToolName(Agent agent, SubAgentConfig config) {
        if (config.getToolName() != null && !config.getToolName().isEmpty()) {
            return config.getToolName();
        }
        // Generate from agent name: "ResearchAgent" -> "call_researchagent"
        String agentName = agent.getName();
        if (agentName == null || agentName.isEmpty()) {
            return "call_agent";
        }
        return "call_" + agentName.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    /**
     * Resolves the tool description from config or derives it from the agent.
     * 根据配置解析工具描述，或从代理中推导出工具描述。
     *
     * <p>Priority: config.description > agent.description > default. The default description is
     * generated as "Call {agentName} to complete tasks".
     *
     * @param agent The agent to derive description from if not configured
     * @param config The configuration that may override the description
     * @return The resolved description
     */
    private String resolveDescription(Agent agent, SubAgentConfig config) {
        if (config.getDescription() != null && !config.getDescription().isEmpty()) {
            return config.getDescription();
        }
        // Use agent description if available
        String agentDesc = agent.getDescription();
        if (agentDesc != null && !agentDesc.isEmpty()) {
            return agentDesc;
        }
        // Generate default description
        return "Call " + agent.getName() + " to complete tasks";
    }
}
