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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Convenience tool that lets the agent propose a brand-new skill in one call: name +
 * description + body + optional scripts. Internally translates to
 * {@code SkillManageTool.create + skill_manage write_file*} so the standard staging /
 * validation / scanning pipeline applies.
 *
 * <p>Compared to {@code skill_manage(action="create")} this tool: takes the body and frontmatter
 * separately (no need for the agent to hand-write YAML), and accepts a list of scripts to
 * upload in the same call. Useful when the agent has just discovered a reusable approach and
 * wants to commit it without authoring the full SKILL.md text.
 */
/**
 * 便捷工具，允许智能体单次调用创建全新技能：名称、描述、正文及可选脚本。
 * 内部等价执行 {@code SkillManageTool.create} + 多次 {@code skill_manage write_file*}，
 * 保障标准暂存、校验、扫描流程正常生效。
 *
 * <p>相较于 {@code skill_manage(action="create")}，本工具特性：正文与前置元数据分开入参
 *（无需智能体手动编写YAML），支持在同一次调用中传入待上传脚本列表。
 * 适用于智能体梳理出可复用方案后，无需完整编写SKILL.md文本即可提交技能。
 */
public class ProposeSkillTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ProposeSkillTool.class);

    public static final String NAME = "propose_skill";

    private final SkillManageTool delegate;

    /** 构造器：注入被委托的 {@link SkillManageTool}，复用其完整的暂存/校验/扫描链路。 */
    public ProposeSkillTool(SkillManageTool delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Propose a brand-new skill in one shot: pass name, description, body, and"
                + " optional scripts. Equivalent to skill_manage(create) + multiple"
                + " skill_manage(write_file) calls but doesn't require you to hand-write"
                + " the YAML frontmatter. Use this when you've just discovered a reusable"
                + " approach and want to commit it without re-deriving the SKILL.md format.";
    }

    /** 构造工具入参 JSON Schema：name/description/body 必填，scripts 可选。 */
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "name",
                Map.of(
                        "type", "string",
                        "description", "Skill name (kebab/snake case)."));
        properties.put(
                "description",
                Map.of(
                        "type", "string",
                        "description",
                                "One-sentence description of when this skill should be used."));
        properties.put(
                "body",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Markdown body of SKILL.md (no frontmatter — added" + " for you)."));
        properties.put(
                "scripts",
                Map.of(
                        "type", "array",
                        "description", "Optional list of {path, content} pairs to add as scripts/.",
                        "items",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "path", Map.of("type", "string"),
                                                        "content", Map.of("type", "string")),
                                        "required", List.of("path", "content"))));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "description", "body"));
        return schema;
    }

    /**
     * 工具执行主体：校验必填参数 → 拼接带 frontmatter 的 SKILL.md →
     * 委托 {@code skill_manage(action=create)} 创建技能 →
     * 成功后按序上传可选脚本列表 → 汇总结果返回。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        if (input == null) {
            return Mono.just(ToolResultBlock.error("Missing input."));
        }
        String name = stringOf(input, "name");
        String description = stringOf(input, "description");
        String body = stringOf(input, "body");
        if (name == null
                || description == null
                || body == null
                || name.isBlank()
                || description.isBlank()
                || body.isBlank()) {
            return Mono.just(
                    ToolResultBlock.error(
                            "name, description, and body are required for propose_skill"));
        }
        String skillMd = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        // Step 1: create the skill via the existing SkillManageTool path (validation +
        // staging + scanner all reused).
        // 第一步：经既有 SkillManageTool 路径创建技能（复用校验、暂存与扫描流程）。
        Map<String, Object> createInput = new HashMap<>();
        createInput.put("action", "create");
        createInput.put("name", name);
        createInput.put("content", skillMd);
        return delegate.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(param.getToolUseBlock())
                                .input(createInput)
                                .agent(param.getAgent())
                                .runtimeContext(param.getRuntimeContext())
                                .build())
                .flatMap(
                        createResult -> {
                            if (textOf(createResult).startsWith("Error:")) {
                                return Mono.just(createResult);
                            }
                            // Step 2: optional scripts. Delegate sequentially via skill_manage
                            // write_file (same staging / scan / sidecar plumbing).
                            // 第二步：处理可选脚本列表。按序委托 skill_manage write_file 逐个上传
                            // （复用同一套暂存、扫描与遥测机制）。
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> scripts =
                                    (List<Map<String, Object>>) input.get("scripts");
                            if (scripts == null || scripts.isEmpty()) {
                                return Mono.just(createResult);
                            }
                            return uploadScripts(name, scripts, param)
                                    .thenReturn(
                                            ToolResultBlock.text(
                                                    textOf(createResult)
                                                            + "\nUploaded "
                                                            + scripts.size()
                                                            + " script(s)."));
                        })
                .onErrorResume(
                        e -> {
                            log.warn("propose_skill failed: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "propose_skill failed: " + e.getMessage()));
                        });
    }

    /**
     * 按序上传脚本列表：每个脚本委托 {@code skill_manage(action=write_file)} 写入。
     * path 与 content 缺失的条目直接跳过；path 不含目录分隔符时自动补 {@code scripts/} 前缀。
     */
    private Mono<Void> uploadScripts(
            String name, List<Map<String, Object>> scripts, ToolCallParam parent) {
        return reactor.core.publisher.Flux.fromIterable(scripts)
                .concatMap(
                        script -> {
                            String path = stringOf(script, "path");
                            String content = stringOf(script, "content");
                            if (path == null || content == null) {
                                return Mono.empty();
                            }
                            // Default to scripts/<filename>.sh if the agent forgot the prefix.
                            // 若智能体未带目录前缀，默认归入 scripts/<文件名>。
                            if (!path.contains("/")) {
                                path = "scripts/" + path;
                            }
                            Map<String, Object> in = new HashMap<>();
                            in.put("action", "write_file");
                            in.put("name", name);
                            in.put("file_path", path);
                            in.put("file_content", content);
                            return delegate.callAsync(
                                    ToolCallParam.builder()
                                            .toolUseBlock(parent.getToolUseBlock())
                                            .input(in)
                                            .agent(parent.getAgent())
                                            .runtimeContext(parent.getRuntimeContext())
                                            .build());
                        })
                .then();
    }

    /** 从 Map 中安全读取字符串值：不存在或非字符串时返回其 toString，键缺失返回 null。 */
    private static String stringOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /** 提取工具结果块中所有 {@code TextBlock} 的文本并拼接，用于判断结果内容。 */
    private static String textOf(ToolResultBlock r) {
        if (r == null || r.getOutput() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var b : r.getOutput()) {
            if (b instanceof io.agentscope.core.message.TextBlock t) {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }
}
