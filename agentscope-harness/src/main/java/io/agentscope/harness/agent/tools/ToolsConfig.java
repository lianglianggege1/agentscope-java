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
package io.agentscope.harness.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace-level tool configuration loaded from {@code workspace/tools.json}.
 *
 * <p>Two responsibilities:
 *
 * <ul>
 *   <li>{@link #allow} / {@link #deny} — filter the <em>product catalog</em> tool surface
 *       (filesystem, shell, web, …).
 *   <li>{@link #mcpServers} — declare additional tools served by external MCP servers.
 * </ul>
 *
 * <p>Filter semantics: when {@code allow} is non-empty, catalogued tools not listed are removed;
 * {@link HarnessPlatformTools} (subagents, teams, tasks, plan, skills, memory helpers, …) always
 * survive {@code allow}. {@code deny} always wins. Empty/absent values mean "no filtering on this
 * side".
 */
/**
 * 从 {@code workspace/tools.json} 加载的工作空间级工具配置。
 *
 * <p>承担两项职责：
 *
 * <ul>
 *   <li>{@link #allow} / {@link #deny} — 过滤框架内置工具集合。
 *   <li>{@link #mcpServers} — 声明由外部MCP服务端提供的扩展工具。
 * </ul>
 *
 * <p>过滤规则：若 {@code allow} 非空，仅保留名称在白名单内的工具；
 * {@code deny} 黑名单优先级高于白名单，始终生效。
 * 字段为空或未配置代表“不执行该维度过滤”。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolsConfig {

    /** When non-empty, catalogued tools not listed here are hidden (platform tools exempt). */
    /** When non-empty, only tools whose name is in this list are exposed to the model. */
    /** 当列表非空时，仅向模型暴露名称存在于此列表内的工具。 */
    @JsonProperty("allow")
    private List<String> allow;

    /** Tools whose name appears here are removed regardless of {@link #allow}. */
    /** 名称出现在此列表中的工具将被移除，优先级高于 {@link #allow} 白名单。 */
    @JsonProperty("deny")
    private List<String> deny;

    /** Map of MCP server identifier to its connection / tool-allowlist configuration. */
    /** MCP服务标识至连接配置、工具白名单配置的映射。 */
    @JsonProperty("mcpServers")
    private Map<String, McpServerConfig> mcpServers;

    private boolean defaultToolsEnabled = true;
    private boolean strictAllow;

    public boolean isStrictAllow() {
        return strictAllow;
    }

    public void setStrictAllow(boolean value) {
        strictAllow = value;
    }

    public boolean isDefaultToolsEnabled() {
        return defaultToolsEnabled;
    }

    public void setDefaultToolsEnabled(boolean value) {
        defaultToolsEnabled = value;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow;
    }

    public List<String> getDeny() {
        return deny;
    }

    public void setDeny(List<String> deny) {
        this.deny = deny;
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers != null ? new LinkedHashMap<>(mcpServers) : null;
    }
}
