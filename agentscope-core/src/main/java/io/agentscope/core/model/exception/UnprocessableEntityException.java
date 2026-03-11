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
 * Exception thrown when the request is well-formed but semantically incorrect (HTTP 422).
 * 当请求格式正确但语义错误时抛出异常（HTTP 422）。
 * <p>Common causes:
 * <ul>
 *   <li>Parameter values out of range</li>
 *   参数值超出范围
 *   <li>Conflicting parameters</li>
 *   参数冲突
 *   <li>Validation errors</li>
 *    验证错误
 * </ul>
 */
public class UnprocessableEntityException extends OpenAIException {

    public UnprocessableEntityException(String message, String errorCode, String responseBody) {
        super(message, 422, errorCode, responseBody);
    }
}
