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
package io.agentscope.core.agent;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for agents that can observe messages without generating replies.
 * 观察消息而不生成回复的智能体接口
 *
 * <p>This interface enables agents to receive and process messages from other agents
 * or the environment without responding. It's commonly used in multi-agent collaboration
 * scenarios where agents need to be aware of each other's actions.
 * 这个接口的设计使得agent能够接收和处理来自其他agent或环境的消息而不进行回复。
 * 这在多agent协作场景中很常见，在这种场景中，agent需要了解彼此的行为。
 * <p>Use cases include:
 * <ul>
 *   <li>Passive monitoring of conversation flow</li>
 *   对话流程的被动监控
 *   <li>Building shared context in multi-agent systems</li>
 *   多agent系统中构建共享上下文
 *   <li>Implementing observer patterns in agent pipelines</li>
 *    智能体管道中实现观察者模式
 * </ul>
 */
public interface ObservableAgent {

    /**
     * Observe a single message without generating a reply.
     * 观察单个消息而不生成回复
     *
     * @param msg The message to observe
     * @return Mono that completes when observation is done
     */
    Mono<Void> observe(Msg msg);

    /**
     * Observe multiple messages without generating a reply.
     * 观察多个消息而不生成回复
     *
     * @param msgs The messages to observe
     * @return Mono that completes when all observations are done
     */
    Mono<Void> observe(List<Msg> msgs);
}
