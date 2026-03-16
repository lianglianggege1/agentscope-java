/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import java.util.List;

/**
 * State record for toolkit active groups.
 * 工具箱活动组的状态记录。
 *
 * <p>This record captures the active tool groups configuration for persistence. The toolkit itself
 * is stateless, but its activeGroups configuration needs to be persisted. This state is managed by
 * {@link io.agentscope.core.ReActAgent}.
 * 此记录捕获了用于持久化的活动工具组配置。
 * 该工具包本身是无状态的，但其activeGroups配置需要持久化。
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ToolkitState state = new ToolkitState(List.of("web", "file", "calculator"));
 * session.save(sessionKey, "toolkit_activeGroups", state);
 *
 * // Later, restore the state
 * Optional<ToolkitState> loaded = session.get(sessionKey, "toolkit_activeGroups", ToolkitState.class);
 * loaded.ifPresent(s -> toolkit.setActiveGroups(s.activeGroups()));
 * }</pre>
 *
 * @param activeGroups the list of currently active tool group names
 * @see State
 * @see io.agentscope.core.tool.Toolkit
 */
public record ToolkitState(List<String> activeGroups) implements State {}
