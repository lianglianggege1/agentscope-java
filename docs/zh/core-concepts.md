# AgentScope 核心概念详解

本文档详细介绍 AgentScope Java SDK 中的三个核心概念：Plan（计划）、Pipeline（管道）和 Tool（工具调用）。

---

## 1. Plan（计划）

### 概念定义

Plan 是 AgentScope 中用于**任务规划和执行跟踪**的核心概念。它将复杂任务拆分为可管理的子任务，并跟踪整体进度。

### 核心结构

**Plan 包含以下主要字段：**

| 字段 | 说明 |
|------|------|
| `name` | 计划名称（简洁，不超过10个词） |
| `description` | 计划描述，包含约束条件和目标 |
| `expectedOutcome` | 预期结果（具体、可衡量） |
| `subtasks` | 子任务列表（`List<SubTask>`） |

**SubTask（子任务）的字段：**

| 字段 | 说明 |
|------|------|
| `name` | 子任务名称 |
| `description` | 详细描述 |
| `expectedOutcome` | 预期结果 |
| `state` | 状态（TODO / IN_PROGRESS / DONE / ABANDONED） |
| `outcome` | 实际完成结果 |

### 状态流转

```
Plan: TODO → IN_PROGRESS → DONE (或 ABANDONED)
SubTask: TODO → IN_PROGRESS → DONE (或 ABANDONED)
```

### 使用示例

```java
Plan plan = new Plan(
    "Build E-commerce Website",
    "Build a complete e-commerce platform with authentication and payment",
    "Fully functional website deployed online",
    List.of(
        new SubTask("Setup", "Initialize project", "Project ready"),
        new SubTask("Auth", "Implement authentication", "Users can login"),
        new SubTask("Cart", "Implement shopping cart", "Cart works")
    )
);
```

### 作用

1. **任务分解**：将复杂任务拆分为可管理的子任务
2. **进度追踪**：通过状态字段跟踪执行进度
3. **结果验证**：记录预期结果 vs 实际结果，便于复盘

在 Agent 中，Plan 通常与 `ReActAgent` 配合使用，让智能体能够按照规划好的步骤逐步完成任务。

---

## 2. Pipeline（管道）

### 概念定义

Pipeline 是 AgentScope 中用于**编排和协调多个 Agent 执行**的核心组件。

### 核心接口

```java
public interface Pipeline<T> {
    Mono<T> execute(Msg input);
    Mono<T> execute(Msg input, Class<?> structuredOutputClass);
    String getDescription();
}
```

### 两种执行模式

#### 2.1 SequentialPipeline（顺序执行）

**执行流程**：Agent1 → Agent2 → Agent3 → ... → 结果

- 每个 Agent 的输出作为下一个 Agent 的输入
- 适用于需要**链式处理**的场景

```java
// 方式1: 工具类
Mono<Msg> result = Pipelines.sequential(agents, input);

// 方式2: 创建实例
SequentialPipeline pipeline = new SequentialPipeline(agents);
Mono<Msg> result = pipeline.execute(input);
```

#### 2.2 FanoutPipeline（并行/分发执行）

**执行流程**：同一个输入 → 同时发送给所有 Agent → 收集所有结果

- 所有 Agent 接收相同输入，并发执行
- 支持两种模式：
  - **并发执行** (`fanout`): 所有 Agent 同时运行
  - **顺序执行** (`fanoutSequential`): Agent 逐个运行，但各自独立

```java
// 并发执行 - 返回 List<Msg>
Mono<List<Msg>> results = Pipelines.fanout(agents, input);

// 顺序执行（独立）
Mono<List<Msg>> results = Pipelines.fanoutSequential(agents, input);
```

### 对比

| 模式 | 输入 | 输出 | 用途 |
|------|------|------|------|
| Sequential | 依次传递 | 单个 Msg | 流水线处理、多步骤任务 |
| Fanout | 相同输入 | List<Msg> | 并行搜索、多角度分析 |

### 组合使用

可以通过 `Pipelines.compose()` 组合多个 Pipeline：

```java
Pipeline<Msg> combined = Pipelines.compose(sequentialPipeline1, sequentialPipeline2);
```

### 使用示例

```java
// 顺序执行：分析师 -> 写手 -> 审核
List<AgentBase> agents = List.of(analystAgent, writerAgent, reviewerAgent);
Mono<Msg> result = Pipelines.sequential(agents, userInput);

// 并行执行：同时让3个专家提供建议
List<AgentBase> experts = List.of(expertA, expertB, expertC);
Mono<List<Msg>> suggestions = Pipelines.fanout(experts, question);
```

---

## 3. Tool（工具调用）

### 概念定义

Tool 是 AgentScope 中用于扩展 Agent 能力的机制，允许大模型调用外部函数、MCP 服务或其他 Agent。

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        大模型返回                                │
│                   (ToolUseBlock 列表)                           │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ToolExecutor                                 │
│  • 工具查找 & 验证                                               │
│  • 参数合并 (预设参数 + 输入参数)                                  │
│  • 超时/重试处理                                                │
│  • 并行/串行执行控制                                            │
└─────────────────────┬───────────────────────────────────────────┘
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │Function  │ │  MCP     │ │ SubAgent │
   │  Tool   │ │  Tool    │ │   Tool   │
   └──────────┘ └──────────┘ └──────────┘
```

### 核心组件

| 组件 | 职责 |
|------|------|
| `ToolUseBlock` | 大模型返回的工具调用请求（包含 name, arguments） |
| `ToolExecutor` | 统一执行器，负责工具查找、验证、执行 |
| `AgentTool` | 工具接口，所有工具实现此接口 |
| `Toolkit` | 工具注册表和管理器 |
| `ToolMethodInvoker` | 使用反射调用本地方法 |

### 三种工具类型实现

#### 3.1 Function Tool（普通方法工具）

通过 `@Tool` 注解标记方法：

```java
public class WeatherTools {
    @Tool(name = "get_weather", description = "获取城市天气")
    public String getWeather(
        @ToolParam(name = "city") String city,
        @ToolParam(name = "unit") String unit) {
        // 调用外部 API
        return weatherService.get(city, unit);
    }
}
```

**执行流程：**
1. `ToolMethodInvoker.invokeAsync()` 使用反射调用方法
2. 支持同步返回、`Mono<String>`、`CompletableFuture<String>` 三种方式
3. `ToolResultConverter` 将结果转换为 `ToolResultBlock`

#### 3.2 MCP Tool（远程工具）

```java
// McpTool 包装远程 MCP 服务
public class McpTool implements AgentTool {
    private final McpClientWrapper clientWrapper;

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 1. 合并预设参数
        Map<String, Object> args = mergeArguments(param.getInput());
        // 2. 通过 MCP 协议调用远程服务
        return clientWrapper.callTool(name, args)
            .map(response -> ToolResultBlock.ok(response));
    }
}
```

**特点：**
- 桥接 MCP（Model Context Protocol）协议
- 支持远程工具调用
- 可预设参数（如 API Key）

#### 3.3 SubAgent Tool（子智能体）

```java
// SubAgentTool 将 Agent 作为工具调用
public class SubAgentTool implements AgentTool {
    private final SubAgentProvider<?> agentProvider;

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 1. 获取或创建子 Agent 会话
        Agent agent = agentProvider.provide();
        // 2. 执行子 Agent
        return agent.call(message)
            .map(result -> ToolResultBlock.ok(result.toString()));
    }
}
```

**特点：**
- 支持多轮对话的子 Agent
- 支持会话管理（session_id）
- 可作为工具被其他 Agent 调用

### 工具执行核心流程

```java
// ToolExecutor.executeCore()
Mono<ToolResultBlock> executeCore(ToolCallParam param) {
    // 1. 查找工具
    AgentTool tool = toolRegistry.getTool(toolCall.getName());

    // 2. 检查激活状态
    if (!groupManager.isActiveTool(name)) {
        return Mono.just(ToolResultBlock.error("Unauthorized"));
    }

    // 3. 参数验证
    String error = ToolValidator.validateInput(arguments, tool.getParameters());
    if (error != null) {
        return Mono.just(ToolResultBlock.error(error));
    }

    // 4. 合并参数 (预设 + 输入)
    Map<String, Object> mergedInput = merge(
        registered.getPresetParameters(),
        param.getInput()
    );

    // 5. 执行工具
    return tool.callAsync(executionParam)
        .onErrorResume(e -> ToolResultBlock.error(e.getMessage()));
}
```

### 关键特性

1. **参数验证**：使用 JSON Schema 验证工具输入
2. **预设参数**：支持在注册时注入 API Key 等敏感信息（不暴露给 LLM）
3. **工具组**：支持动态启用/禁用工具组
4. **超时重试**：内置超时和指数退避重试机制
5. **流式输出**：支持 `ToolEmitter` 进行流式结果输出

---

## 总结

| 概念 | 用途 | 核心类 |
|------|------|--------|
| Plan | 任务规划与进度跟踪 | `Plan`, `SubTask`, `PlanState` |
| Pipeline | 多 Agent 编排 | `Pipeline`, `SequentialPipeline`, `FanoutPipeline` |
| Tool | 扩展 Agent 能力 | `AgentTool`, `ToolExecutor`, `Toolkit` |

这三个概念共同构成了 AgentScope 的核心能力：
- **Plan** 让 Agent 能够规划和分解任务
- **Pipeline** 让多个 Agent 能够协作
- **Tool** 让 Agent 能够调用外部能力
