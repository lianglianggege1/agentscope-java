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

/**
 * Configuration for {@link SkillManageTool}.
 *
 * <p>Default values are tuned for enterprise production deployment: new skills land in a draft
 * subdirectory (not the live skills root), static security scanning is on, and the agent cannot
 * silently overwrite an existing skill via {@code create}.
 *
 * <p>For personal-assistant / experimental use the recommended override is
 * {@code SkillManageConfig.builder().autoPromote(true).build()} — agent writes go straight to the
 * live skills root, becoming visible on the next reasoning turn.
 */
/**
 * {@link SkillManageTool} 的配置项。
 *
 * <p>默认参数面向企业生产环境调优：新建技能存放于草稿子目录（而非正式技能根目录），
 * 静态安全扫描默认开启，智能体无法通过 {@code create} 静默覆盖已有技能。
 *
 * <p>个人助手/实验场景推荐覆盖配置：
 * {@code SkillManageConfig.builder().autoPromote(true).build()} —
 * 智能体写入的技能直接落地至正式技能根目录，在下一轮推理即可生效。
 */
public final class SkillManageConfig {

    /** Default subdirectory under workspace for staging drafts. */
    /** 工作空间下用于存放草稿暂存文件的默认子目录。 */
    public static final String DEFAULT_DRAFTS_DIR = "skills/_drafts";

    /** Default subdirectory under workspace for promoted skills. */
    /** 工作空间下存放已发布正式技能的默认子目录。 */
    public static final String DEFAULT_MAIN_DIR = "skills";

    private final boolean autoPromote;
    private final boolean securityScan;
    private final String draftsDir;
    private final String mainDir;

    /** 私有构造器：只能通过 {@link Builder} 创建实例。 */
    private SkillManageConfig(Builder b) {
        this.autoPromote = b.autoPromote;
        this.securityScan = b.securityScan;
        this.draftsDir = b.draftsDir;
        this.mainDir = b.mainDir;
    }

    /** Skip the draft staging path and write directly to the live skills root. */
    /** 跳过草稿暂存目录，直接写入正式技能根目录。 */
    public boolean autoPromote() {
        return autoPromote;
    }

    /** Run {@code SkillSecurityScanner} after every write. */
    /** 每次写入操作后执行 {@code SkillSecurityScanner} 安全扫描。 */
    public boolean securityScan() {
        return securityScan;
    }

    /** Workspace-relative directory where drafts land. */
    /** 草稿文件存放目录，路径相对于工作空间。 */
    public String draftsDir() {
        return draftsDir;
    }

    /** Workspace-relative directory where promoted skills live. */
    /** 已上线正式技能所在目录，路径相对于工作空间。 */
    public String mainDir() {
        return mainDir;
    }

    /** 返回默认配置实例（草稿暂存开启、安全扫描开启、禁止静默覆盖）。 */
    public static SkillManageConfig defaults() {
        return builder().build();
    }

    /** 创建新的 {@link Builder}。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 配置构建器：提供企业级默认值，可按部署场景覆盖。 */
    public static final class Builder {
        private boolean autoPromote = false;
        private boolean securityScan = true;
        private String draftsDir = DEFAULT_DRAFTS_DIR;
        private String mainDir = DEFAULT_MAIN_DIR;

        private Builder() {}

        /** 设置是否跳过草稿暂存、直接写入正式技能根目录。 */
        public Builder autoPromote(boolean autoPromote) {
            this.autoPromote = autoPromote;
            return this;
        }

        /** 设置是否在每次写入后执行安全扫描。 */
        public Builder securityScan(boolean securityScan) {
            this.securityScan = securityScan;
            return this;
        }

        /** 设置草稿目录（工作空间相对路径）；空白值忽略，保留默认。 */
        public Builder draftsDir(String draftsDir) {
            if (draftsDir != null && !draftsDir.isBlank()) {
                this.draftsDir = draftsDir;
            }
            return this;
        }

        /** 设置正式技能目录（工作空间相对路径）；空白值忽略，保留默认。 */
        public Builder mainDir(String mainDir) {
            if (mainDir != null && !mainDir.isBlank()) {
                this.mainDir = mainDir;
            }
            return this;
        }

        /** 构建不可变的 {@link SkillManageConfig} 实例。 */
        public SkillManageConfig build() {
            return new SkillManageConfig(this);
        }
    }
}
