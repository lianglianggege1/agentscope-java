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
package io.agentscope.harness.agent.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Manages memory flush operations: extracting long-term memories from a conversation
 * window and appending them to today's daily memory ledger.
 *
 * <p><b>Two-layer memory model</b> (this class owns only the first layer):
 * <ul>
 *   <li>{@code memory/YYYY-MM-DD.md} — append-only daily ledger. Each compaction's flush
 *       appends a timestamped section here. Written ONLY by this class.</li>
 *   <li>{@code MEMORY.md} — globally curated, deduplicated, size-bounded long-term memory.
 *       Written ONLY by {@link MemoryConsolidator} on a periodic schedule. Treated as
 *       read-only context here.</li>
 * </ul>
 */
/**
 * 管理记忆落盘操作：从会话窗口提取长期记忆，并追加写入当日的每日记忆账本。
 *
 * <p><b>双层记忆模型</b>（当前类仅负责第一层）：
 * <ul>
 *   <li>{@code memory/YYYY-MM-DD.md} — 仅支持追加写入的每日账本。每次压缩落盘都会在此追加带时间戳的片段。仅由当前类执行写入。</li>
 *   <li>{@code MEMORY.md} — 全局整理、去重、容量受限的长期记忆。仅由 {@link MemoryConsolidator} 周期性写入。本组件将其视为只读上下文。</li>
 * </ul>
 */
public class MemoryFlushManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushManager.class);

    /*
     记忆提取环节的默认提示词。对外公开，调用方在构建
     {@link io.agentscope.harness.agent.memory.MemoryConfig} 时可进行扩展
    （例如追加项目专属约束）。
    public static final String DEFAULT_FLUSH_PROMPT =
        """
        你是记忆提取助手。分析下方对话内容，提取需要留存至后续会话的重要事实、决策、偏好与上下文信息。

        仅以Markdown无序列表形式输出提取出的记忆内容。每条内容为简洁且独立完整的信息；存在日期、人名、具体细节时一并保留。

        若无值得记忆的内容，严格输出：NO_REPLY

        提取准则：
        - 提取用户偏好、个人信息、项目相关决议
        - 记录关键技术决策及其背后理由
        - 记下所有承诺、截止时间与待执行事项
        - 留存人员协作信息（分工、团队架构）
        - 忽略常规问候、工具调用、临时状态信息

        重要写入规则（目标文件为追加模式）：
        - 你写入的是**当日每日记忆账本（memory/YYYY-MM-DD.md）**，而非MEMORY.md。每日账本仅支持追加，你的输出会新增至已有记录末尾。
        - MEMORY.md 是经过整理的长期记忆文件，仅作为只读上下文提供参考。不要重复记录MEMORY.md或今日已存在的条目；后续独立的整合任务会定期将新增账本内容合并至MEMORY.md。
        - 每条记录保持独立完整，支持单独检索。
        """;
     */

    /**
     * Default prompt for the memory extraction step. Exposed publicly so callers can extend
     * (e.g. append project-specific guidelines) when constructing
     * {@link io.agentscope.harness.agent.memory.MemoryConfig}.
     */
    public static final String DEFAULT_FLUSH_PROMPT =
            """
            You are a memory extraction assistant. Analyze the conversation below and extract \
            important facts, decisions, preferences, and contextual information that should be \
            remembered for future conversations.

            Output ONLY the extracted memories as a markdown bullet list. Each item should be \
            a concise, self-contained fact. Include dates, names, and specifics when available.

            If there is nothing worth remembering, respond with exactly: NO_REPLY

            Guidelines:
            - Extract user preferences, personal information, project decisions
            - Capture important technical decisions and their rationale
            - Note any commitments, deadlines, or action items
            - Record relationship context (who works on what, team structure)
            - Ignore routine greetings, tool invocations, and ephemeral status updates

            IMPORTANT — write target and append-only rules:
            - You are writing to TODAY'S daily memory ledger (memory/YYYY-MM-DD.md), NOT to \
            MEMORY.md. The daily ledger is append-only — your output will be appended after the \
            entries already shown below.
            - MEMORY.md is the curated long-term memory and is shown ONLY as read-only context. \
            Do NOT restate facts already covered by MEMORY.md or by today's earlier entries; a \
            separate consolidation step periodically merges new daily entries into MEMORY.md.
            - Keep each bullet point independent and self-contained so entries can be searched \
            individually.\
            """;

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final String flushPrompt;

    public MemoryFlushManager(WorkspaceManager workspaceManager, Model model) {
        this(workspaceManager, model, DEFAULT_FLUSH_PROMPT);
    }

    /**
     * @param flushPrompt SYSTEM prompt for the extraction LLM call. {@code null} falls back to
     *     {@link #DEFAULT_FLUSH_PROMPT}.
     */
    /**
     * @param flushPrompt 记忆提取大模型调用使用的系统提示词。传入 {@code null}
     *     将使用 {@link #DEFAULT_FLUSH_PROMPT}。
     */
    public MemoryFlushManager(WorkspaceManager workspaceManager, Model model, String flushPrompt) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.flushPrompt = flushPrompt != null ? flushPrompt : DEFAULT_FLUSH_PROMPT;
    }

    /**
     * Extracts long-term memories from messages using the model and writes them to disk.
     *
     * <p>Provides existing MEMORY.md and today's daily file content to the extraction LLM
     * so it can effectively deduplicate and avoid re-extracting known facts.
     */
    /**
     * 调用模型从会话消息中提取长期记忆并写入磁盘。
     *
     * <p>会将当前 MEMORY.md 与当日每日账本内容一并提供给记忆提取大模型，
     * 以此实现有效去重，避免重复提取已记录信息。
     */
    public Mono<Void> flushMemories(RuntimeContext rc, List<Msg> messages) {
        String conversationText = serializeMessages(messages);
        if (conversationText.isBlank()) {
            return Mono.empty();
        }

        String existingMemory = readExistingContent(rc, WorkspaceConstants.MEMORY_MD);
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String existingDaily = readExistingContent(rc, dailyRelPath);

        StringBuilder userPrompt = new StringBuilder();
        if (!existingMemory.isBlank()) {
            userPrompt
                    .append("MEMORY.md (read-only curated long-term memory — do NOT restate):\n")
                    .append(existingMemory)
                    .append("\n\n");
        }
        if (!existingDaily.isBlank()) {
            userPrompt
                    .append("Today's daily ledger so far (your output will be appended after):\n")
                    .append(existingDaily)
                    .append("\n\n");
        }
        userPrompt
                .append(
                        "Extract NEW memories from this conversation window (skip anything"
                                + " already covered above):\n\n")
                .append(conversationText);

        List<Msg> flushInput = new ArrayList<>();
        flushInput.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(flushPrompt).build())
                        .build());
        flushInput.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userPrompt.toString()).build())
                        .build());

        return model.stream(flushInput, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, chatResponse) -> {
                            List<ContentBlock> blocks = chatResponse.getContent();
                            if (blocks != null) {
                                for (ContentBlock block : blocks) {
                                    if (block instanceof TextBlock tb) {
                                        String t = tb.getText();
                                        if (t != null) {
                                            sb.append(t);
                                        }
                                    }
                                }
                            }
                            return sb;
                        })
                .flatMap(
                        sb -> {
                            String extracted = sb.toString();
                            if (extracted.isBlank() || extracted.strip().equals("NO_REPLY")) {
                                log.debug("No memories to flush");
                                return Mono.empty();
                            }
                            writeMemoryFiles(rc, extracted);
                            return Mono.empty();
                        });
    }

    /**
     * Returns the string path of the session JSONL file where messages for the given agent and
     * session are offloaded. Used by the compaction layer to embed the archive location in the
     * summary message so the agent can retrieve full history if needed.
     */
    /**
     * 返回会话JSONL文件的字符串路径，指定智能体与会话的消息将转储至该文件。
     * 压缩层使用该路径，将归档位置嵌入摘要消息，以便智能体在需要时读取完整历史记录。
     */
    public String resolveOffloadPath(RuntimeContext rc, String agentId, String sessionId) {
        try {
            Path p = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
            return p != null ? p.toString() : "";
        } catch (Exception e) {
            log.debug(
                    "Could not resolve offload path for agent={}, session={}: {}",
                    agentId,
                    sessionId,
                    e.getMessage());
            return "";
        }
    }

    /**
     * Offloads raw messages to the JSONL session tree.
     */
    /**
     * 将原始消息转储至JSONL会话目录树。
     */
    public void offloadMessages(
            RuntimeContext rc, List<Msg> messages, String agentId, String sessionId) {
        offloadToSessionTree(rc, messages, agentId, sessionId);

        log.debug(
                "Offloaded {} messages for agent={}, session={}",
                messages.size(),
                agentId,
                sessionId);
        workspaceManager.updateSessionIndex(rc, agentId, sessionId, "conversation offloaded");
    }

    private void offloadToSessionTree(
            RuntimeContext rc, List<Msg> messages, String agentId, String sessionId) {
        try {
            Path contextFile = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
            String contextRelPath =
                    WorkspaceConstants.AGENTS_DIR
                            + "/"
                            + agentId
                            + "/"
                            + WorkspaceConstants.SESSIONS_DIR
                            + "/"
                            + sessionId
                            + WorkspaceConstants.SESSION_CONTEXT_EXT;
            SessionTree tree =
                    new SessionTree(
                                    contextFile,
                                    workspaceManager.getWorkspace(),
                                    workspaceManager.getFilesystem(),
                                    workspaceManager.getIndex(),
                                    contextRelPath)
                            .setRuntimeContext(rc);
            tree.load();
            // Sync from remote before appending so that entries written by a previous replica
            // (cross-machine handoff) are included in the merged file pushed to remote.
            // 追加数据前先从远端同步，确保上一副本（跨机器交接场景）写入的条目
            // 能够纳入合并后的文件并推送至远端。
            tree.syncFromRemote();

            List<SessionEntry> existingEntries = new ArrayList<>(tree.getAllEntries());
            Set<String> existingIds =
                    existingEntries.stream()
                            .map(SessionEntry::getId)
                            .collect(Collectors.toCollection(HashSet::new));
            String lastId =
                    existingEntries.isEmpty()
                            ? null
                            : existingEntries.get(existingEntries.size() - 1).getId();

            // The caller passes the full conversation on every turn. Use the stable Msg IDs
            // to keep the session JSONL append-only and idempotent across repeated offloads.
            // 调用方每一轮都会传入完整对话。依靠稳定的消息ID，
            // 保障会话JSONL文件仅支持追加写入，且多次转储操作具备幂等性。
            for (Msg msg : messages) {
                if (msg.getRole() == null || isSessionContextMessage(msg)) {
                    continue;
                }
                String rendered = renderContentBlocks(msg);
                if (rendered == null || rendered.isBlank()) {
                    continue;
                }
                String entryId = normalizeEntryId(msg.getId());
                if (entryId == null) {
                    log.warn(
                            "Msg without stable ID encountered (role={}); dedup skipped",
                            msg.getRole());
                }
                if (entryId != null && existingIds.contains(entryId)) {
                    continue;
                }
                String toolCallId = extractToolCallId(msg);
                SessionEntry.MessageEntry entry =
                        new SessionEntry.MessageEntry(
                                entryId, lastId, null, msg.getRole().name(), rendered, toolCallId);
                tree.append(entry);
                existingIds.add(entry.getId());
                lastId = entry.getId();
            }

            tree.flush();
        } catch (Exception e) {
            log.warn("Failed to offload to JSONL session tree: {}", e.getMessage());
        }
    }

    /**
     * Extracts a representative tool call ID from a message, if present.
     * For TOOL messages, returns the first ToolResultBlock's id.
     * For ASSISTANT messages with tool calls, returns the first ToolUseBlock's id.
     */
    /**
     * 从消息中提取具有代表性的工具调用ID（如有）。
     * 若为工具消息，返回首个工具结果块的ID。
     * 若为携带工具调用的助手消息，返回首个工具调用块的ID。
     */
    private static String extractToolCallId(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock tr && tr.getId() != null) {
                return tr.getId();
            }
            if (block instanceof ToolUseBlock tu && tu.getId() != null) {
                return tu.getId();
            }
        }
        return null;
    }

    private static String normalizeEntryId(String id) {
        return id != null && !id.isBlank() ? id : null;
    }

    /**
     * Appends the extracted entries to today's daily memory ledger.
     *
     * <p>MEMORY.md is intentionally <b>NOT</b> touched here — it is owned by
     * {@link MemoryConsolidator}, which periodically merges the daily ledgers into a
     * curated, size-bounded MEMORY.md.
     */
    /**
     * 将提取出的条目追加至当日记忆台账。
     *
     * <p>此处刻意不操作 MEMORY.md 文件，该文件由 {@link MemoryConsolidator} 统一管理；
     * 记忆整合器会定期合并各日台账，生成经过整理、容量受控的 MEMORY.md。
     */
    private void writeMemoryFiles(RuntimeContext rc, String content) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        String dailyEntry =
                String.format(
                        "\n## Memory Flush — %s\n%s\n",
                        java.time.Instant.now().toString(), content);

        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        workspaceManager.appendUtf8WorkspaceRelative(rc, dailyRelPath, dailyEntry);
    }

    private String readExistingContent(RuntimeContext rc, String relativePath) {
        try {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relativePath);
            return content != null ? content : "";
        } catch (Exception e) {
            log.debug("Could not read {}: {}", relativePath, e.getMessage());
            return "";
        }
    }

    private static final String SESSION_CONTEXT_TAG = "<session_context>";

    /**
     * Serializes all messages into a textual representation for the memory extraction model.
     * Includes USER, ASSISTANT, and TOOL messages. Assistant tool-call blocks and tool-result
     * blocks are rendered as concise text so the model can extract memories from tool interactions.
     * The injected {@code <session_context>} user message is skipped as it contains only
     * environment metadata, not real conversation content.
     */
    /**
     * 将所有消息序列化为文本形式，供记忆抽取模型使用。
     * 包含用户、助手及工具消息。助手工具调用块与工具结果块会被精简渲染，
     * 使模型能够从工具交互内容中提取记忆。
     * 注入的{@code <session_context>}用户消息将被跳过，因其仅包含环境元数据，不含真实对话内容。
     */
    private String serializeMessages(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != null && m.getRole() != MsgRole.SYSTEM)
                .filter(m -> !isSessionContextMessage(m))
                .map(this::renderMessage)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static boolean isSessionContextMessage(Msg msg) {
        if (msg.getRole() != MsgRole.USER) {
            return false;
        }
        String text = msg.getTextContent();
        return text != null && text.contains(SESSION_CONTEXT_TAG);
    }

    private String renderMessage(Msg msg) {
        String body = renderContentBlocks(msg);
        if (body == null) {
            return null;
        }
        return "[" + msg.getRole().name() + "]: " + body;
    }

    /**
     * Renders all content blocks of a message into a single text string.
     * Returns null if no renderable content is found.
     */
    /**
     * 将消息内所有内容块渲染为单个文本字符串。
     * 若无可渲染内容，则返回 null。
     */
    private String renderContentBlocks(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                String text = tb.getText();
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            } else if (block instanceof ToolUseBlock tu) {
                parts.add(renderToolUse(tu));
            } else if (block instanceof ToolResultBlock tr) {
                parts.add(renderToolResult(tr));
            }
        }

        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n", parts);
    }

    private static String renderToolUse(ToolUseBlock tu) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool_call: ").append(tu.getName());
        if (tu.getInput() != null && !tu.getInput().isEmpty()) {
            try {
                String inputJson = JsonUtils.getJsonCodec().toJson(tu.getInput());
                if (inputJson.length() > 500) {
                    inputJson = inputJson.substring(0, 500) + "...";
                }
                sb.append("(").append(inputJson).append(")");
            } catch (Exception e) {
                sb.append("(...)");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String renderToolResult(ToolResultBlock tr) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool_result");
        if (tr.getName() != null) {
            sb.append(": ").append(tr.getName());
        }
        sb.append("] ");

        List<ContentBlock> outputs = tr.getOutput();
        if (outputs != null) {
            for (ContentBlock out : outputs) {
                if (out instanceof TextBlock tb) {
                    String text = tb.getText();
                    if (text != null) {
                        if (text.length() > 1000) {
                            sb.append(text, 0, 1000).append("...(truncated)");
                        } else {
                            sb.append(text);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }
}
