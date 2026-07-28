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
package io.agentscope.harness.agent.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic session-ID derivation. Produces a 12-character hex string from arbitrary input
 * parts using SHA-256, guaranteeing the same inputs always yield the same output regardless of
 * JVM instance or machine.
 */
/**
 * 确定性会话ID生成逻辑。基于任意输入片段通过SHA-256生成12位十六进制字符串，
 * 保证相同输入无论在哪个JVM实例或机器上运行，输出结果始终一致。
 */
public final class SessionIdUtils {

    private SessionIdUtils() {}

    /**
     * Returns a 12-character lowercase hex hash derived from the given parts joined by {@code "/"}.
     *
     * <p>The hash is deterministic: identical inputs produce identical output on any machine. The
     * output is filename-safe (lowercase hex only). Collision probability is negligible for
     * practical session counts (2^48 space).
     *
     * @param parts one or more non-null seed values
     * @return 12-char hex string
     */
    /**
     * 将传入的多个片段以 {@code "/"} 拼接后，生成12位小写十六进制哈希字符串并返回。
     *
     * <p>该哈希具备确定性：相同输入在任意机器上都会得到一致结果。
     * 输出内容仅包含小写十六进制字符，可安全用作文件名。
     * 在常规会话数量场景下，哈希冲突概率极低（取值空间为 2^48）。
     *
     * @param parts 一个或多个非空种子参数
     * @return 12位十六进制字符串
     */
    public static String deterministicHash(String... parts) {
        String seed = String.join("/", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
