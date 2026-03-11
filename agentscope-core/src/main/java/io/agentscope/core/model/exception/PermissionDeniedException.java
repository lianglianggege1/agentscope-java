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
package io.agentscope.core.model.exception;

/**
 * Exception thrown when permission is denied (HTTP 403).
 * 权限被拒绝时抛出异常（HTTP 403）。
 *
 * <p>Common causes:
 * <ul>
 *   <li>API key lacks required permissions</li>
 *    API 密钥缺乏所需权限
 *   <li>Resource access denied</li>
 *    资源访问被拒绝
 *   <li>Organization restrictions</li>
 *   组织限制
 * </ul>
 */
public class PermissionDeniedException extends OpenAIException {

    public PermissionDeniedException(String message, String errorCode, String responseBody) {
        super(message, 403, errorCode, responseBody);
    }
}
