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
package io.agentscope.core.hook;

/**
 * Enum representing all hook event types.
 * 所有Hook事件类型
 *
 * <p>These events are fired at different stages of agent execution and can be
 * intercepted by implementing the {@link Hook} interface.
 * 这些事件是在智能体执行中的不同阶段触发的，并且可以通过实现Hook接口进行拦截。
 *
 * @see Hook
 * @see HookEvent
 */
public enum HookEventType {
    /** Before agent starts processing */
    /** 在智能体开始运行之前 */
    PRE_CALL,

    /** After agent completes processing */
    /** 在智能体运行完成后 */
    POST_CALL,

    /** Before LLM reasoning */
    /** 在LLM推理之前 */
    PRE_REASONING,

    /** After LLM reasoning completes */
    /** 在LLM推理完成后 */
    POST_REASONING,

    /** During LLM reasoning streaming */
    /** 在LLM推理流式处理期间 */
    REASONING_CHUNK,

    /** Before tool execution */
    /** 在工具执行之前 */
    PRE_ACTING,

    /** After tool execution completes */
    /** 在工具执行完成后 */
    POST_ACTING,

    /** During tool execution streaming */
    /** 在工具执行流式处理期间 */
    ACTING_CHUNK,

    /** Before summary generation (when max iterations reached) */
    /** 在生成总结（当最大迭代数达到时）之前 */
    PRE_SUMMARY,

    /** After summary generation completes */
    /** 在生成总结完成后 */
    POST_SUMMARY,

    /** During summary streaming */
    /** 在生成总结流式处理期间 */
    SUMMARY_CHUNK,

    /** When an error occurs */
    /** 当发生错误时 */
    ERROR
}
