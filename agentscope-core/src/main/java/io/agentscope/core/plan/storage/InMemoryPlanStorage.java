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
package io.agentscope.core.plan.storage;

import io.agentscope.core.plan.model.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of PlanStorage.
 * 计划存储
 *
 * <p>Stores plans in a concurrent hash map for thread-safe access. This implementation is suitable
 * for development and testing. For production use cases requiring persistence, implement a
 * database-backed storage.
 * 将计划存储在并发哈希映射中，
 * 以实现线程安全访问。此实现适用于开发和测试。对于需要持久性的生产用例，实现数据库支持的存储。
 */
public class InMemoryPlanStorage implements PlanStorage {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    /**
     * Adds a plan to the storage.
     * 将计划添加到存储中。
     *
     * <p>If a plan with the same ID already exists, it will be replaced.
     *
     * @param plan The plan to store
     * @return A Mono that completes when the plan is stored
     */
    @Override
    public Mono<Void> addPlan(Plan plan) {
        return Mono.fromRunnable(() -> plans.put(plan.getId(), plan));
    }

    /**
     * Retrieves a plan by its ID.
     * 按计划ID检索计划。
     *
     * @param planId The unique identifier of the plan
     * @return A Mono emitting the plan if found, or empty if not found
     */
    @Override
    public Mono<Plan> getPlan(String planId) {
        return Mono.justOrEmpty(plans.get(planId));
    }

    /**
     * Retrieves all stored plans.
     * 检索所有存储的计划。
     *
     * @return A Mono emitting a list of all plans (may be empty)
     */
    @Override
    public Mono<List<Plan>> getPlans() {
        return Mono.just(new ArrayList<>(plans.values()));
    }
}
