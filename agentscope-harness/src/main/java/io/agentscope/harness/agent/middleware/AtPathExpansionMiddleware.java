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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Expands {@code @path} references in user messages by reading the referenced files through the
 * active {@link AbstractFilesystem} and appending an {@code <attached_file>} block at the end of
 * the message. Mirrors the Claude Code CLI's {@code @file} input syntax.
 *
 * <p>Dispatch by filesystem type (kept as in-line {@code instanceof} since there is exactly one
 * call site):
 *
 * <ul>
 *   <li><b>Local</b> ({@link OverlayFilesystem} wrapping {@link LocalFilesystemWithShell}) —
 *       attempts {@code fs.read}; success triggers attachment, failure (typically because the
 *       path falls outside the {@code PathPolicy} allow-list) leaves the {@code @path} text
 *       untouched.
 *   <li><b>Sandbox</b> ({@link AbstractSandboxFilesystem}, not wrapped in overlay) — treats the
 *       reference as a path inside the sandbox; host-path upload is out of scope for this stage.
 *   <li><b>Remote</b> ({@link CompositeFilesystem}) — disabled. The agent is not co-located with
 *       the user so {@code @path} has no meaningful resolution; references are left as-is.
 * </ul>
 *
 * <p>Recognises three reference shapes: absolute ({@code @/abs/path}), relative
 * ({@code @./rel} or {@code @rel/foo}), and home-anchored ({@code @~/file}). A bare {@code @word}
 * with no path-like character (slash, dot, tilde) is ignored to avoid swallowing handles such as
 * {@code @alice}.
 */
/**
 * 展开用户消息中的 {@code @路径} 引用：通过当前生效的 {@link AbstractFilesystem} 读取引用文件，
 * 并在消息末尾追加 {@code <attached_file>} 区块。对应 Claude Code CLI 的 {@code @file} 输入语法。
 *
 * <p>按文件系统类型分发（仅有一个调用点，故保留内联 {@code instanceof} 判断）：
 *
 * <ul>
 *   <li><b>本地</b>（包装 {@link LocalFilesystemWithShell} 的 {@link OverlayFilesystem}）：
 *       尝试 {@code fs.read}；成功则附加文件内容，失败（通常因路径不在
 *       {@code PathPolicy} 白名单内）则保留 {@code @路径} 原文不处理。</li>
 *   <li><b>沙箱</b>（未被叠加层包装的 {@link AbstractSandboxFilesystem}）：
 *       将引用视为沙箱内部路径；本阶段不支持从宿主上传文件。</li>
 *   <li><b>远程</b>（{@link CompositeFilesystem}）：禁用。智能体与用户不部署在同一台机器，
 *       {@code @路径} 无法有意义地解析，引用原样保留。</li>
 * </ul>
 *
 * <p>识别三种引用形态：绝对路径（{@code @/abs/path}）、相对路径
 * （{@code @./rel} 或 {@code @rel/foo}）、家目录锚定（{@code @~/file}）。
 * 不含路径特征字符（斜杠、点、波浪号）的裸 {@code @单词} 会被忽略，
 * 避免误吞 {@code @alice} 这类用户句柄。
 */
public class AtPathExpansionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AtPathExpansionMiddleware.class);

    /**
     * Matches an {@code @} immediately followed by a path token. Tokens must contain at least one
     * path indicator (slash, dot, or tilde) to avoid swallowing user handles. Trailing
     * punctuation ({@code . , ; : ! ? CJK marks}) is excluded so sentence boundaries don't end
     * up inside the captured path.
     */
    /**
     * 匹配紧跟路径词元的 {@code @}。词元必须包含至少一个路径指示符
     * （斜杠、点或波浪号），避免误吞用户句柄。
     * 排除结尾标点（{@code . , ; : ! ? 及中文标点}），防止把句子边界符捕获进路径。
     *
     * <p>四个候选分支依次匹配：Windows 盘符路径（{@code C:\...}）、
     * 以 {@code ~}、{@code .}、{@code /} 开头的路径、含 {@code /} 或 {@code .} 的相对路径。
     * 前置负向后行断言确保 {@code @} 前不是字母数字下划线（如邮箱地址不误匹配）。
     */
    private static final Pattern AT_PATH =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])"
                            + "@(?<path>[A-Za-z]:[\\\\/][\\w\\\\./\\-~]*"
                            + "|[~./][\\w./\\-~]*"
                            + "|/[\\w./\\-~]+"
                            + "|[\\w\\-]+[/.][\\w./\\-~]*)");

    /** Cap the bytes pulled in per attached file so a stray {@code @log} doesn't blow context. */
    /** 限制每个附加文件读入的行数上限，防止误引用大文件（如 {@code @log}）撑爆上下文。 */
    private static final int MAX_ATTACHED_LINES = 1000;

    /** 工作区管理器，提供当前生效的文件系统用于读取引用文件。 */
    private final WorkspaceManager workspaceManager;

    /**
     * @param workspaceManager 工作区管理器，提供文件系统访问能力
     */
    public AtPathExpansionMiddleware(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    /**
     * 中间件钩子：在智能体处理输入前，对用户消息中的 {@code @路径} 引用做展开。
     *
     * <p>仅当文件系统类型支持展开时才处理；只改写 USER 角色的消息，
     * 其余消息原样保留。所有消息都未变化时直接透传原输入对象，避免无谓拷贝。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        // 文件系统不支持展开（null 或远程分布式部署）时直接透传
        if (!supportsExpansion(fs)) {
            return next.apply(input);
        }

        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();

        List<Msg> rewritten = new ArrayList<>(input.msgs().size());
        boolean changed = false;
        for (Msg msg : input.msgs()) {
            // 只展开用户消息，系统/助手消息中的 @ 不做处理
            if (msg.getRole() != MsgRole.USER) {
                rewritten.add(msg);
                continue;
            }
            Msg expanded = expand(msg, fs, rc);
            if (expanded != msg) {
                changed = true;
            }
            rewritten.add(expanded);
        }
        return next.apply(changed ? new AgentInput(rewritten) : input);
    }

    /**
     * 判断当前文件系统是否支持 @路径展开。
     * 远程分布式部署（{@link CompositeFilesystem}）没有宿主路径概念，不支持。
     */
    private boolean supportsExpansion(AbstractFilesystem fs) {
        if (fs == null) {
            return false;
        }
        // Remote (CompositeFilesystem) — distributed deployment, no host paths.
        // 远程分布式部署（CompositeFilesystem）没有宿主路径概念，不支持展开。
        if (fs instanceof CompositeFilesystem) {
            return false;
        }
        return true;
    }

    /**
     * 展开单条消息：用正则找出全部 {@code @路径} 引用，逐个尝试读取文件。
     *
     * <p>读取成功的引用按出现顺序（{@link LinkedHashMap} 去重保序）在消息末尾
     * 追加 {@code <attached_file path="...">} 区块；全部读取失败则原样返回原消息对象
     * （用对象引用比较判断是否发生了变化）。
     */
    private Msg expand(Msg msg, AbstractFilesystem fs, RuntimeContext rc) {
        String text = msg.getTextContent();
        // 无文本或不含 @ 时快速返回，避免正则开销
        if (text == null || text.indexOf('@') < 0) {
            return msg;
        }

        Matcher m = AT_PATH.matcher(text);
        Map<String, String> attached = new LinkedHashMap<>();
        while (m.find()) {
            String ref = m.group("path");
            // 同一路径多次引用只读取一次
            if (attached.containsKey(ref)) {
                continue;
            }
            String content = tryRead(fs, rc, ref);
            if (content != null) {
                attached.put(ref, content);
            }
        }
        if (attached.isEmpty()) {
            return msg;
        }

        // 原文保持不动，附加内容统一追加在消息末尾
        StringBuilder sb = new StringBuilder(text);
        for (Map.Entry<String, String> e : attached.entrySet()) {
            sb.append("\n\n<attached_file path=\"")
                    .append(e.getKey())
                    .append("\">\n")
                    .append(e.getValue())
                    .append(e.getValue().endsWith("\n") ? "" : "\n")
                    .append("</attached_file>");
        }
        return msg.withContent(List.of(TextBlock.builder().text(sb.toString()).build()));
    }

    /**
     * 尝试读取引用路径指向的文件，最多读取 {@link #MAX_ATTACHED_LINES} 行。
     *
     * <p>返回文件内容表示附加成功；返回 null 表示放弃附加（读取失败、路径越权、
     * 二进制文件等），此时 {@code @路径} 原文保留在消息中。
     */
    private String tryRead(AbstractFilesystem fs, RuntimeContext rc, String ref) {
        String resolved = expandHome(ref);
        try {
            ReadResult r = fs.read(rc, resolved, 0, MAX_ATTACHED_LINES);
            if (r.isSuccess() && r.fileData() != null && r.fileData().content() != null) {
                String content = r.fileData().content();
                // Skip binary attachments — base64 in a user message is rarely useful and just
                // burns tokens.
                // 跳过二进制附件——用户消息里塞 base64 很少有意义，只会白白消耗 token。
                if ("base64".equals(r.fileData().encoding())) {
                    return null;
                }
                return content;
            }
        } catch (SecurityException e) {
            // 路径策略拒绝（越权访问），仅调试级记录
            log.debug("@-path expansion refused for {} ({})", ref, e.getMessage());
        } catch (Exception e) {
            log.debug("@-path expansion failed for {}: {}", ref, e.getMessage());
        }
        return null;
    }

    /** 将 {@code ~/} 开头的引用展开为用户家目录下的绝对路径。 */
    private static String expandHome(String ref) {
        if (ref.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return home != null ? home + ref.substring(1) : ref;
        }
        return ref;
    }
}
