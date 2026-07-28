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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fuzziness ladder for {@code SkillManageTool#patch}. The LLM rarely reproduces whitespace
 * exactly — indentation drift, trailing-space normalisation in editors, and the parser's
 * frontmatter-whitespace eat (see {@code MarkdownSkillParser.FRONTMATTER_PATTERN}) all create
 * mismatches that a strict {@code String.indexOf} would reject.
 *
 * <p>The matcher tries progressively looser comparisons and reports back which level made the
 * match so the caller can surface that to the LLM. Each looser level maintains a per-character
 * map back to the original {@code existing} string so the patch result is applied to the
 * unmodified bytes — we never touch whitespace the LLM didn't ask to change.
 *
 * <ul>
 *   <li>{@link Level#EXACT} — strict {@code String.indexOf}, preserved for byte-for-byte fidelity
 *   <li>{@link Level#TRAILING_WS_STRIPPED} — same after stripping {@code ' '/'\t'} at the end of
 *       every line on both sides. Catches editor-side trailing-whitespace normalisation.
 *   <li>{@link Level#WHITESPACE_COLLAPSED} — same after also stripping leading whitespace per
 *       line and collapsing every internal whitespace run to a single {@code ' '}. Catches
 *       indentation drift (tabs ↔ spaces, 2-space ↔ 4-space) and re-wrapped lines.
 * </ul>
 */
/**
 * {@code SkillManageTool#patch} 的模糊匹配层级机制。大模型很难精准还原空白字符：
 * 缩进偏移、编辑器自动清理行尾空格、解析器对元数据前置区域空白的吞噬逻辑
 *（参见 {@code MarkdownSkillParser.FRONTMATTER_PATTERN}）都会造成文本不匹配，
 * 严格的 {@code String.indexOf} 会直接判定匹配失败。
 *
 * <p>匹配器会逐级放宽比对规则，并返回最终匹配成功的层级，供上层向大模型反馈。
 * 每一级宽松匹配都会维护字符与原始 {@code existing} 文本的映射关系，
 * 保证补丁最终作用于原始未改动字节；不会修改大模型未要求变更的空白字符。
 *
 * <ul>
 *   <li>{@link Level#EXACT} — 严格 {@code String.indexOf} 匹配，保障字节级完全一致
 *   <li>{@link Level#TRAILING_WS_STRIPPED} — 双方每行末尾剔除空格/制表符后比对。
 *       用于兼容编辑器自动清理行尾空格场景。
 *   <li>{@link Level#WHITESPACE_COLLAPSED} — 额外剔除每行前置空白，同时将所有连续空白
 *       合并为单个空格。兼容缩进差异（制表符↔空格、2空格↔4空格）以及换行重排版场景。
 * </ul>
 */
final class FuzzyTextMatcher {

    /** Match strictness level. Ordered most-strict-first so callers can compare with {@code <}. */
    /** 匹配严格等级。按从最严格到宽松排序，调用方可直接使用 {@code <} 进行大小比较。 */
    enum Level {
        EXACT,
        TRAILING_WS_STRIPPED,
        WHITESPACE_COLLAPSED
    }

    /** A match in {@code existing}: original-string byte offsets, inclusive-exclusive. */
    /** 在原始文本{@code existing}中的匹配区间：原始字符串字节偏移量，左闭右开。 */
    record MatchRange(int start, int end, Level level) {
        int length() {
            return end - start;
        }
    }

    /** Outcome bundle for the caller. */
    /** 搜索结果封装类，包含匹配区间列表和匹配级别。 */
    record SearchResult(List<MatchRange> matches, Level level) {
        boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    private FuzzyTextMatcher() {}

    /**
     * Searches {@code existing} for {@code needle} starting at the strictest level and walking
     * looser ladders until at least one match is found.
     *
     * @return {@link SearchResult} with every match found at the first non-empty level (so the
     *     caller can apply uniqueness checks against same-level peers); {@code matches} is empty
     *     when nothing matches at any level.
     */
    /**
     * 在{@code existing}中检索{@code needle}，从最严格等级开始逐级放宽匹配规则，直至找到匹配项。
     *
     * @return {@link SearchResult}，包含首个存在匹配结果等级下找到的全部匹配项（调用方可基于同等级匹配项做唯一性校验）；
     *         所有等级均无匹配时，{@code matches}为空集合。
     */
    static SearchResult search(String existing, String needle) {
        if (existing == null || needle == null || needle.isEmpty()) {
            return new SearchResult(Collections.emptyList(), Level.EXACT);
        }

        // Level 1: exact. Cheap; common case.
        // 等级1：精确匹配。开销低，为常见场景。
        List<MatchRange> exact = findAll(existing, needle, Level.EXACT);
        if (!exact.isEmpty()) {
            return new SearchResult(exact, Level.EXACT);
        }

        // Level 2: trailing-ws stripped per line on both sides.
        // 等级2：双方每行均剔除行尾空白字符后匹配。
        Normalized existingTws = stripTrailingWhitespace(existing);
        Normalized needleTws = stripTrailingWhitespace(needle);
        List<MatchRange> tws =
                findAllNormalized(existingTws, needleTws, Level.TRAILING_WS_STRIPPED);
        if (!tws.isEmpty()) {
            return new SearchResult(tws, Level.TRAILING_WS_STRIPPED);
        }

        // Level 3: full whitespace collapse (strip leading + trailing per line, collapse internal
        // runs to single space). Most lenient — surfaced as a warning to the LLM.
        // 等级3：完整空白压缩（剔除每行首尾空白，内部连续空白合并为单个空格）。
        // 最为宽松，匹配成功时向大模型抛出警告。
        Normalized existingWsc = collapseWhitespace(existing);
        Normalized needleWsc = collapseWhitespace(needle);
        List<MatchRange> wsc =
                findAllNormalized(existingWsc, needleWsc, Level.WHITESPACE_COLLAPSED);
        if (!wsc.isEmpty()) {
            return new SearchResult(wsc, Level.WHITESPACE_COLLAPSED);
        }

        return new SearchResult(Collections.emptyList(), Level.EXACT);
    }

    // ---------------------------------------------------------------------
    //  Exact-mode helpers
    // ---------------------------------------------------------------------

    private static List<MatchRange> findAll(String haystack, String needle, Level level) {
        List<MatchRange> out = new ArrayList<>();
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            out.add(new MatchRange(idx, idx + needle.length(), level));
            idx += needle.length();
        }
        return out;
    }

    // ---------------------------------------------------------------------
    //  Normalised search
    // ---------------------------------------------------------------------

    /**
     * Finds every match of {@code needle.text} inside {@code haystack.text}, then maps the
     * normalised positions back to original-string offsets via {@link Normalized#originalIndex}.
     */
    /**
     * 在{@code haystack.text}中查找{@code needle.text}所有匹配位置，
     * 再通过{@link Normalized#originalIndex}将归一化后的位置映射回原始字符串偏移量。
     */
    private static List<MatchRange> findAllNormalized(
            Normalized haystack, Normalized needle, Level level) {
        List<MatchRange> out = new ArrayList<>();
        if (needle.text.isEmpty()) {
            return out;
        }
        int idx = 0;
        while ((idx = haystack.text.indexOf(needle.text, idx)) >= 0) {
            int origStart = haystack.originalIndex[idx];
            int needleEndExcl = idx + needle.text.length();
            int origEnd;
            if (needleEndExcl < haystack.originalIndex.length) {
                origEnd = haystack.originalIndex[needleEndExcl];
            } else {
                origEnd = haystack.originalLength;
            }
            out.add(new MatchRange(origStart, origEnd, level));
            idx = needleEndExcl;
        }
        return out;
    }

    /**
     * A normalised view of a string plus a per-character map back to the original string. For
     * every {@code i} in {@code [0, text.length())}, {@code originalIndex[i]} is the offset in
     * the original string of the source character that emitted {@code text.charAt(i)}.
     */
    /**
     * 字符串归一化视图，并提供逐字符映射以回溯原始字符串。
     * 对于任意下标 {@code i ∈ [0, text.length())}，
     * {@code originalIndex[i]} 为生成 {@code text.charAt(i)} 的源字符在原始字符串内的偏移量。
     */
    private record Normalized(String text, int[] originalIndex, int originalLength) {}

    /**
     * Drop trailing {@code ' '} and {@code '\t'} from each line. Newlines and inner-line content
     * are preserved verbatim. Mapping always points at the source character that produced the
     * emitted char (including the newline).
     */
    /**
     * 移除每行末尾的 {@code ' '} 和 {@code '\t'}。换行符与行内内容原样保留。
     * 映射关系始终指向生成输出字符的源字符（包含换行符）。
     */
    private static Normalized stripTrailingWhitespace(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int[] map = new int[len + 1];
        int mapLen = 0;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            // Drop ' ' / '\t' from the tail of this line.
            int trimEnd = lineEnd;
            while (trimEnd > lineStart
                    && (s.charAt(trimEnd - 1) == ' ' || s.charAt(trimEnd - 1) == '\t')) {
                trimEnd--;
            }
            for (int j = lineStart; j < trimEnd; j++) {
                out.append(s.charAt(j));
                map[mapLen++] = j;
            }
            if (lineEnd < len) {
                out.append('\n');
                map[mapLen++] = lineEnd;
            }
            i = lineEnd + 1;
        }
        return new Normalized(out.toString(), Arrays.copyOf(map, mapLen), len);
    }

    /**
     * Aggressive normalisation: per line, strip leading + trailing whitespace, and collapse every
     * internal run of {@code ' '}/{@code '\t'} into a single {@code ' '}. Newlines are kept so
     * line structure still constrains the match.
     */
    /**
     * 强归一化处理：每行剔除首尾空白字符，同时将内部连续的 {@code ' '}/{@code '\t'}
     * 合并为单个 {@code ' '}。保留换行符，保证匹配仍受行文结构约束。
     */
    private static Normalized collapseWhitespace(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int[] map = new int[len + 1];
        int mapLen = 0;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            // Skip leading whitespace.
            int j = lineStart;
            while (j < lineEnd && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                j++;
            }
            boolean inRun = false;
            int runOrigStart = -1;
            int contentEmittedAt = mapLen;
            for (; j < lineEnd; j++) {
                char c = s.charAt(j);
                if (c == ' ' || c == '\t') {
                    if (!inRun) {
                        inRun = true;
                        runOrigStart = j;
                    }
                } else {
                    if (inRun) {
                        // Emit a single collapsed space mapping back to where the run started.
                        out.append(' ');
                        map[mapLen++] = runOrigStart;
                        inRun = false;
                    }
                    out.append(c);
                    map[mapLen++] = j;
                }
            }
            // Trailing whitespace on this line is dropped along with `inRun`.
            // Only emit a newline if we actually had a line terminator AND we wrote some content
            // (suppressing newlines for fully-blank lines keeps the collapsed view denser and
            // less sensitive to inserted blank lines on the LLM side).
            if (lineEnd < len && mapLen > contentEmittedAt) {
                out.append('\n');
                map[mapLen++] = lineEnd;
            }
            i = lineEnd + 1;
        }
        return new Normalized(out.toString(), Arrays.copyOf(map, mapLen), len);
    }
}
