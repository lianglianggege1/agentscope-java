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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a method as a tool that can be invoked by AI agents.
 * 注释，将方法标记为可由AI代理调用的工具。
 *
 * <p>Methods annotated with {@code @Tool} are automatically registered with the toolkit and made
 * available to agents for execution. The toolkit uses reflection to discover tool methods and
 * generate appropriate JSON schemas for LLM consumption.
 * 用@Tool注释的方法会自动注册到工具包中，并可供智能体执行。
 * 该工具包使用反射来发现工具方法，并为LLM消费生成适当的JSON模式。
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * public class WeatherTools {
 *     @Tool(name = "get_weather", description = "Get current weather for a city")
 *     public String getWeather(
 *         @ToolParam(name = "city", description = "City name") String city,
 *         @ToolParam(name = "unit", description = "Temperature unit") String unit) {
 *         // Implementation
 *         return "Weather data...";
 *     }
 * }
 * }</pre>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>All parameters must be annotated with {@link ToolParam} (except {@link ToolEmitter})</li>
 *   所有参数都必须使用{@link ToolParam}进行注释（{@link ToolsEmitter}除外）
 *   <li>Return type must be String, Mono&lt;String&gt;, or other reactive types</li>
 *   返回类型必须是字符串、单声道&lt；字符串&gt；，或其他反应型
 *   <li>Tool names should follow snake_case convention for LLM compatibility</li>
 *   为了LLM兼容性，工具名称应遵循snake_case约定
 *   <li>Descriptions should clearly explain what the tool does and when to use it</li>
 *   描述应清楚地解释工具的功能以及何时使用
 * </ul>
 *
 * @see ToolParam
 * @see Toolkit
 * @see ToolEmitter
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * The name of the tool.
     *
     * <p>If not provided, the method name will be used. Tool names should follow snake_case
     * convention (e.g., "get_weather", "send_email") for compatibility with various LLM providers.
     *
     * @return The tool name, or empty string to use method name
     */
    String name() default "";

    /**
     * The description of the tool that explains its purpose and usage.
     * 对工具的描述，解释其用途和用法。
     *
     * <p>This description is sent to the LLM to help it decide when to invoke the tool. It should
     * clearly explain:
     * 此描述将发送给LLM，以帮助其决定何时调用该工具。它应该清楚地解释：
     * <ul>
     *   <li>What the tool does</li>
     *   干什么
     *   <li>When it should be used</li>
     *   何时干
     *   <li>What kind of results it returns</li>
     *   返回什么结果
     * </ul>
     *
     * <p>If not provided, a generic description based on the method name will be generated.
     * 如果没有提供，将生成基于方法名称的通用描述。
     *
     * @return The tool description, or empty string to auto-generate
     */
    String description() default "";

    /**
     * Custom result converter for this tool.
     *
     * <p>Converters transform tool method return values into {@link io.agentscope.core.message.ToolResultBlock}
     * instances suitable for LLM consumption. Use custom converters to:
     * <ul>
     *   <li>Filter sensitive data from results</li>
     *   <li>Format output in specific ways</li>
     *   <li>Add metadata to results</li>
     *   <li>Compress or summarize large outputs</li>
     * </ul>
     *
     * <p><b>Usage Example:</b>
     * <pre>{@code
     * @Tool(
     *     name = "get_data",
     *     converter = CustomJsonConverter.class
     * )
     * public MyData getData(String id) {
     *     return dataService.findById(id);
     * }
     * }</pre>
     *
     * <p>If not specified, the default converter ({@link DefaultToolResultConverter}) is used,
     * which provides JSON serialization with schema information.
     *
     * <p><b>Note:</b> If you need complex processing with multiple steps, implement your own
     * converter that combines the necessary logic.
     *
     * @return Converter class
     * @see ToolResultConverter
     * @see DefaultToolResultConverter
     */
    Class<? extends ToolResultConverter> converter() default DefaultToolResultConverter.class;
}
