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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;

/**
 * Interface for emitting streaming responses during tool execution.
 * 用于在工具执行期间发出流式响应的接口。
 *
 * <p>Tool methods can declare a ToolEmitter parameter to send intermediate progress updates and
 * messages during execution. These streaming chunks are delivered to registered hooks via
 * {@code onActingChunk()} events but are NOT sent to the LLM. Only the final return value of the
 * tool method is sent to the LLM as the tool result.
 * 工具方法可以声明ToolEmitter参数，以便在执行过程中发送中间进度更新和消息。
 * 这些流块通过onAgingChunk（）事件传递到已注册的钩子，但不会发送到LLM。
 * 只有工具方法的最终返回值作为工具结果发送给LLM。
 *
 * <p><b>Key Characteristics:</b>
 * 主要特征：
 * <ul>
 *   <li>ToolEmitter is auto-injected by the framework - no {@link ToolParam} annotation needed</li>
 *   ToolEmitter由框架自动注入-不需要{@link ToolParam}注释
 *   <li>Emitted chunks go to hooks (for monitoring/logging), not to the LLM</li>
 *    Emitted的块会进入钩子（用于监控/记录），而不是LLM
 *   <li>Useful for long-running tools to provide progress feedback</li>
 *    对于长时间运行的工具提供进度反馈很有用
 *   <li>Does not affect the tool schema visible to the LLM</li>
 *    不影响LLM可见的工具架构
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * @Tool(name = "long_task", description = "Execute a long running task")
 * public ToolResultBlock execute(
 *     @ToolParam(name = "input") String input,
 *     ToolEmitter emitter  // Automatically injected by framework
 * ) {
 *     emitter.emit(ToolResultBlock.text("Starting task..."));
 *
 *     // Step 1
 *     processStep1(input);
 *     emitter.emit(ToolResultBlock.text("Step 1 completed"));
 *
 *     // Step 2
 *     processStep2(input);
 *     emitter.emit(ToolResultBlock.text("Step 2 completed"));
 *
 *     // Final result (this is what the LLM sees)
 *     return ToolResultBlock.text("Task completed successfully");
 * }
 * }</pre>
 */
public interface ToolEmitter {

    /**
     * Emit a streaming response chunk during tool execution.
     * 在工具执行期间发出流式响应块。
     *
     * <p>This method sends intermediate messages to registered hooks via {@code onToolChunk()}.
     * The emitted chunks do NOT affect what the LLM receives - only the tool method's return value
     * is sent to the LLM.
     * 此方法通过onToolChunk（）向已注册的钩子发送中间消息。
     * 发出的块不会影响LLM接收到的内容，只会将工具方法的返回值发送给LLM。
     *
     * @param chunk The chunk to emit
     */
    void emit(ToolResultBlock chunk);
}
