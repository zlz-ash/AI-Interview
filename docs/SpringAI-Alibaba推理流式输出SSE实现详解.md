# Spring AI Alibaba 推理流式输出 SSE 实现详解

> 来源：spring-ai-alibaba-examples 仓库 (https://github.com/spring-ai-alibaba/examples)
> 以及 Spring AI Alibaba 官方文档 (https://java2ai.com)

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [仓库中的推理流式输出模块](#2-仓库中的推理流式输出模块)
3. [方案一：QWQ 模型 + ReasoningContentAdvisor（官方推荐）](#3-方案一qwq-模型--reasoningcontentadvisor官方推荐)
4. [方案二：Playground 的 deepThinkingChat 接口](#4-方案二playground-的-deepthinkingchat-接口)
5. [方案三：ReactAgent 流式 + SseEmitter（Agent 框架）](#5-方案三reactagent-流式--sseemitteragent-框架)
6. [DashScope 底层如何传递 reasoningContent](#6-dashscope-底层如何传递-reasoningcontent)
7. [Spring AI OpenAI 兼容层的推理内容处理](#7-spring-ai-openai-兼容层的推理内容处理)
8. [三种方案对比](#8-三种方案对比)

---

## 1. 整体架构概览

Spring AI Alibaba 的推理流式输出涉及以下层次：

```
┌─────────────────────────────────────────────────────────────────┐
│                         前端 (浏览器)                            │
│  EventSource / fetch → 解析 SSE 事件 → 分别渲染推理/回答          │
└──────────────────────────┬──────────────────────────────────────┘
                           │ SSE (text/event-stream)
┌──────────────────────────▼──────────────────────────────────────┐
│                    Controller 层                                 │
│  ┌─────────────────────┐  ┌──────────────────────────────────┐ │
│  │ ChatClient 方式      │  │ ReactAgent 方式                  │ │
│  │ chatClient.prompt()  │  │ agent.stream(prompt, config)     │ │
│  │   .stream().content()│  │   → Flux<NodeOutput>             │ │
│  │ → Flux<String>       │  │   → 区分 AGENT_MODEL_STREAMING   │ │
│  └──────────┬──────────┘  │     AGENT_TOOL_STREAMING 等      │ │
│             │              └──────────────┬───────────────────┘ │
└─────────────┼─────────────────────────────┼────────────────────┘
              │                             │
┌─────────────▼─────────────────────────────▼────────────────────┐
│                  Advisor 层 (拦截器)                             │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ ReasoningContentAdvisor                                  │  │
│  │   after() → 从 metadata 提取 reasoningContent            │  │
│  │   → 拼接到输出文本中: "﹤think﹥推理内容﹤/think﹥" + 正文  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────┬──────────────────────────────────┘
                              │
┌─────────────────────────────▼──────────────────────────────────┐
│                 ChatModel 层 (模型调用)                          │
│  ┌────────────────────┐  ┌─────────────────────────────────┐  │
│  │ DashScopeChatModel │  │ OpenAiChatModel (DeepSeek兼容)  │  │
│  │ (QWQ/Qwen3)       │  │ (DeepSeek-R1)                   │  │
│  │ → DashScopeApi     │  │ → OpenAiApi                     │  │
│  │   流式返回          │  │   流式返回                        │  │
│  │   reasoningContent │  │   reasoning_content              │  │
│  │   放入 metadata    │  │   放入 metadata                  │  │
│  └────────────────────┘  └─────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. 仓库中的推理流式输出模块

spring-ai-alibaba-examples 仓库中涉及推理流式输出的关键模块：

| 模块 | 路径 | 说明 |
|------|------|------|
| **spring-ai-alibaba-chat-example** | `spring-ai-alibaba-chat-example/` | 包含 QWQChatClientController，演示 QWQ 推理模型的流式调用 |
| **spring-ai-alibaba-playground** | `spring-ai-alibaba-playground/` | 包含 SAAChatController，提供 `deepThinkingChat` 接口 |
| **spring-ai-alibaba-helloworld** | `spring-ai-alibaba-helloworld/` | 最简示例，不含推理 |

---

## 3. 方案一：QWQ 模型 + ReasoningContentAdvisor（官方推荐）

这是 Spring AI Alibaba 官方文档 (java2ai.com) 推荐的方式，用于获取 QWQ / DeepSeek-R1 等推理模型的思维链输出。

### 3.1 依赖与配置

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
```

```yaml
server:
  port: 10002
spring:
  application:
    name: spring-ai-alibaba-qwq-chat-client-example
ai:
  dashscope:
    api-key: ${AI_DASHSCOPE_API_KEY}
    chat:
      options:
        model: qwq-plus
```

### 3.2 ReasoningContentAdvisor — 核心推理内容提取器

这是官方提供的 Advisor 实现，用于从 ChatResponse 的 metadata 中提取 `reasoningContent` 并拼接到输出文本中：

```java
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;

public class ReasoningContentAdvisor implements BaseAdvisor {

    private static final Logger logger = LoggerFactory.getLogger(ReasoningContentAdvisor.class);

    private final int order;

    public ReasoningContentAdvisor(Integer order) {
        this.order = order != null ? order : 0;
    }

    @NotNull
    @Override
    public AdvisedRequest before(@NotNull AdvisedRequest request) {
        return request;
    }

    @NotNull
    @Override
    public AdvisedResponse after(AdvisedResponse advisedResponse) {
        ChatResponse resp = advisedResponse.response();
        if (Objects.isNull(resp)) {
            return advisedResponse;
        }

        logger.debug(String.valueOf(resp.getResults().get(0).getOutput().getMetadata()));

        String reasoningContent = String.valueOf(
            resp.getResults().get(0).getOutput().getMetadata().get("reasoningContent")
        );

        if (StringUtils.hasText(reasoningContent)) {
            List<Generation> thinkGenerations = resp.getResults().stream()
                .map(generation -> {
                    AssistantMessage output = generation.getOutput();
                    AssistantMessage thinkAssistantMessage = new AssistantMessage(
                        String.format("﹤think﹥%s﹤/think﹥", reasoningContent) + output.getText(),
                        output.getMetadata(),
                        output.getToolCalls(),
                        output.getMedia()
                    );
                    return new Generation(thinkAssistantMessage, generation.getMetadata());
                }).toList();

            ChatResponse thinkChatResp = ChatResponse.builder()
                .from(resp)
                .generations(thinkGenerations)
                .build();

            return AdvisedResponse.from(advisedResponse)
                .response(thinkChatResp)
                .build();
        }
        return advisedResponse;
    }

    @Override
    public int getOrder() {
        return this.order;
    }
}
```

**关键点解析：**

1. **`reasoningContent` 从哪来？** — DashScopeChatModel 在流式调用时，会将模型返回的 `reasoning_content` 字段放入 `AssistantMessage` 的 `metadata` 中，key 为 `"reasoningContent"`。

2. **Advisor 的 `after()` 方法** — 在 ChatClient 的每次响应后执行，拦截 ChatResponse，提取推理内容。

3. **拼接策略** — 将推理内容用 `<think/>` 标签包裹，拼接到正文前面。这样前端可以通过解析标签来区分推理和正文。

### 3.3 QWQChatClientController — 完整控制器

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/qwq")
public class QWQChatClientController {

    private static final String DEFAULT_PROMPT = "9.11和9.8哪个大？";

    private final ChatModel chatModel;
    private final ChatClient chatClient;

    public QWQChatClientController(ChatModel chatModel) {
        this.chatModel = chatModel;

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(new InMemoryChatMemory())
                )
                .defaultAdvisors(
                        new SimpleLoggerAdvisor()
                )
                // ★ 关键：注入 ReasoningContentAdvisor
                .defaultAdvisors(
                        new ReasoningContentAdvisor(0)
                )
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7)
                                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                                .build()
                )
                .build();
    }

    @GetMapping(value = "/stream/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatClient.prompt(DEFAULT_PROMPT)
                .stream()
                .content();
    }
}
```

### 3.4 请求与输出示例

```bash
$ curl http://localhost:10002/qwq/chat-client/stream/chat
```

输出（流式逐字返回）：

```
﹤think﹥
好的，用户让我比较9.11和9.8哪个大。
我之前已经回答过一次了，现在又问同样的问题。
让我仔细分析一下...
9.11 = 9 + 0.11 = 9.11
9.8 = 9 + 0.8 = 9.80
因为 0.80 > 0.11，所以 9.8 > 9.11
﹤/think﹥
9.8比9.11大。因为在小数比较中...
```

### 3.5 QWQ 模型注意事项

| 限制 | 说明 |
|------|------|
| 只支持 Stream 调用 | 非流式调用会返回 400 错误 |
| 不支持 Function Call | 工具调用不可用 |
| 不支持 JSON Mode | 结构化输出不可用 |
| 不支持 temperature/top_p | 设置这些参数不会生效 |
| 不建议设置 System Message | 会影响推理效果 |

---

## 4. 方案二：Playground 的 deepThinkingChat 接口

spring-ai-alibaba-playground 模块中的 `SAAChatController` 提供了 `deepThinkingChat` 接口，路径为 `GET /api/v1/deep-thinking/chat`。

### 4.1 接口定义

```java
@GetMapping("/api/v1/deep-thinking/chat")
public Flux<ServerSentEvent<String>> deepThinkingChat(
        @RequestParam(value = "prompt", defaultValue = "你好") String prompt) {
    // 使用支持深度思考的模型（如 qwq-plus、qwen3-235b-a22b）
    // 通过 DashScopeChatOptions 开启思考模式
    return chatClient.prompt(prompt)
            .options(DashScopeChatOptions.builder()
                    .withModel("qwq-plus")
                    .build())
            .stream()
            .chatResponse()
            .map(resp -> {
                // 从 ChatResponse 中提取推理内容和正文
                String reasoningContent = (String) resp.getResult()
                        .getOutput().getMetadata().get("reasoningContent");
                String content = resp.getResult().getOutput().getText();

                // 构造 SSE 事件，区分推理和正文
                if (reasoningContent != null && !reasoningContent.isEmpty()) {
                    return ServerSentEvent.<String>builder()
                            .event("reasoning")
                            .data(reasoningContent)
                            .build();
                } else if (content != null && !content.isEmpty()) {
                    return ServerSentEvent.<String>builder()
                            .event("content")
                            .data(content)
                            .build();
                }
                return ServerSentEvent.<String>builder().build();
            });
}
```

### 4.2 SSE 事件格式

Playground 的 deepThinkingChat 使用 **SSE event 字段** 来区分推理和正文：

```
event: reasoning
data: "好的，让我分析一下这个问题..."

event: reasoning
data: "首先需要理解..."

event: content
data: "根据分析，"

event: content
data: "答案是..."
```

### 4.3 前端解析

```javascript
const eventSource = new EventSource('/api/v1/deep-thinking/chat?prompt=你好');

eventSource.addEventListener('reasoning', (e) => {
    // 渲染推理内容到思考区域
    thinkingArea.textContent += e.data;
});

eventSource.addEventListener('content', (e) => {
    // 渲染正文到回答区域
    answerArea.textContent += e.data;
});
```

---

## 5. 方案三：ReactAgent 流式 + SseEmitter（Agent 框架）

来自 spring-ai-alibaba 的 Agent Framework，使用 `ReactAgent` + `SseEmitter` 实现推理流式输出。这种方式适合需要工具调用和复杂 Agent 流程的场景。

### 5.1 创建 Agent

```java
private ReactAgent createAgent() {
    DashScopeApi api = DashScopeApi.builder()
            .apiKey(apiKeyConfig.getQwenKey())
            .build();

    DashScopeChatOptions options = DashScopeChatOptions.builder()
            .model("qwq-plus")
            .build();

    DashScopeChatModel chatModel = DashScopeChatModel.builder()
            .dashScopeApi(api)
            .defaultOptions(options)
            .build();

    RedisSaver redisSaver = RedisSaver.builder()
            .redisson(redissonClient)
            .build();

    HumanInTheLoopHook human = HumanInTheLoopHook.builder()
            .approvalOn("getCurrentTime",
                    ToolConfig.builder()
                            .description("Get the current time need human approval")
                            .build())
            .build();

    return ReactAgent.builder()
            .name("chat-agent")
            .model(chatModel)
            .hooks(human)
            .systemPrompt("你是一个乐于助人的智能助理。")
            .methodTools(new TimeTool(), new WeatherSearchTool())
            .saver(redisSaver)
            .build();
}
```

### 5.2 流式输出 + SseEmitter

```java
public void streamCall(String prompt, String sessionId) {
    ReactAgent agent = createAgent();

    RunnableConfig config = RunnableConfig.builder()
            .threadId(sessionId)
            .addMetadata("user_id", "hjh")
            .build();

    SseEmitter emitter = sseManager.getEmitter(sessionId);

    executor.submit(() -> {
        try {
            Flux<NodeOutput> stream = agent.stream(prompt, config);

            stream.subscribe(output -> {
                if (output instanceof StreamingOutput modelResponse) {
                    OutputType type = modelResponse.getOutputType();
                    Message message = modelResponse.message();

                    switch (type) {
                        case AGENT_MODEL_STREAMING -> {
                            // ★ 关键：从 metadata 中提取推理内容
                            Object thinkContent = message.getMetadata().get("reasoningContent");
                            if (thinkContent != null && !thinkContent.toString().isEmpty()) {
                                // 有推理内容 → 发送 thinking 事件
                                sseManager.sendEvent(emitter, sessionId,
                                        "thinking", thinkContent.toString());
                            } else {
                                // 纯模型输出 → 发送 model 事件
                                sseManager.sendEvent(emitter, sessionId,
                                        "model", message.getText());
                            }
                        }
                        case AGENT_TOOL_STREAMING ->
                            log.info("Tool streaming: {}", message.toString());
                        case AGENT_TOOL_FINISHED -> {
                            if (message instanceof ToolResponseMessage tool) {
                                tool.getResponses().forEach(response -> {
                                    String toolOutput = "id: " + response.id()
                                            + ", name: " + response.name()
                                            + ", data: " + response.responseData();
                                    sseManager.sendEvent(emitter, sessionId,
                                            "tool", toolOutput);
                                });
                            }
                        }
                        default ->
                            log.info("Other type: {}, message: {}",
                                    type, message == null ? "[No Text]" : message.getText());
                    }
                }
            }, error -> {
                log.error("Error in streaming: ", error);
                sseManager.sendEvent(emitter, sessionId,
                        "error", "An error occurred: " + error.getMessage());
                emitter.completeWithError(error);
            }, () -> {
                sseManager.sendEvent(emitter, sessionId, "complete", "Stream completed");
                emitter.complete();
            });
        } catch (Exception e) {
            log.error("Error during streaming call", e);
            sseManager.sendEvent(emitter, sessionId,
                    "error", "An error occurred: " + e.getMessage());
            emitter.completeWithError(e);
        }
    });
}
```

### 5.3 SSE 事件分类

| SSE event | 含义 | 数据来源 |
|-----------|------|----------|
| `thinking` | 推理过程 | `message.getMetadata().get("reasoningContent")` |
| `model` | 模型正文输出 | `message.getText()` |
| `tool` | 工具调用结果 | `ToolResponseMessage.getResponses()` |
| `complete` | 流式完成 | 无 |
| `error` | 错误信息 | 异常消息 |

### 5.4 SseEmitter 管理器

```java
@Component
public class SseManager {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时
        emitters.put(sessionId, emitter);
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        return emitter;
    }

    public SseEmitter getEmitter(String sessionId) {
        return emitters.get(sessionId);
    }

    public void sendEvent(SseEmitter emitter, String sessionId,
                          String eventName, String data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException e) {
            emitters.remove(sessionId);
        }
    }
}
```

---

## 6. DashScope 底层如何传递 reasoningContent

### 6.1 DashScope API 原始响应格式

当使用 QWQ / Qwen3 等推理模型时，DashScope API 的流式响应中会包含 `reasoning_content` 字段：

```json
{
    "output": {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": "9.8比9.11大。",
                    "reasoning_content": "让我分析一下：9.11和9.8..."
                }
            }
        ]
    }
}
```

流式模式下，每个 chunk 的格式：

```json
{
    "output": {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": "",
                    "reasoning_content": "让我"
                },
                "finish_reason": "null"
            }
        ]
    }
}
```

### 6.2 DashScopeChatModel 的处理流程

```
DashScope API 流式响应
    │
    ▼
DashScopeChatModel.internalStream()
    │
    ├─ 解析每个 chunk 的 reasoning_content 字段
    │
    ├─ 构造 AssistantMessage:
    │    new AssistantMessage(
    │        content,                    // 正文内容
    │        Map.of("reasoningContent",  // 推理内容放入 metadata
    │               reasoningContent)
    │    )
    │
    ▼
ChatResponse (每个流式分片)
    └─ getResult().getOutput()
        ├─ .getText()                    → 正文内容
        └─ .getMetadata().get("reasoningContent")  → 推理内容
```

### 6.3 关键源码位置

| 组件 | 仓库路径 |
|------|----------|
| DashScopeChatModel | `alibaba/spring-ai-alibaba` → `community/model-providers/spring-ai-alibaba-starter-dashscope/src/main/java/com/alibaba/cloud/ai/dashscope/chat/DashScopeChatModel.java` |
| DashScopeApi | `alibaba/spring-ai-alibaba` → `community/model-providers/spring-ai-alibaba-starter-dashscope/src/main/java/com/alibaba/cloud/ai/dashscope/api/DashScopeApi.java` |

### 6.4 DashScope 开启思考模式的参数

```yaml
ai:
  dashscope:
    chat:
      options:
        model: qwq-plus          # 推理模型
        # 或者通过 DashScopeChatOptions 编程式设置：
        # .withModel("qwen3-235b-a22b")
```

对于 Qwen3 系列，还可以通过 `enable_thinking` 参数开启思考模式：

```java
DashScopeChatOptions.builder()
    .withModel("qwen3-235b-a22b")
    // enable_thinking 通过额外参数传递
    .build();
```

---

## 7. Spring AI OpenAI 兼容层的推理内容处理

Spring AI 的 OpenAI 兼容层（用于 DeepSeek-R1 等）也支持推理内容，处理方式类似：

### 7.1 DeepSeek-R1 的流式响应

```json
{
    "choices": [
        {
            "delta": {
                "reasoning_content": "让我思考一下...",
                "content": null
            }
        }
    ]
}
```

### 7.2 OpenAiChatModel 的处理

Spring AI 1.1.0+ 版本中，`OpenAiChatModel.internalStream()` 会将 `reasoning_content` 放入 metadata：

```java
// OpenAiChatModel 内部处理（简化）
Map<String, Object> metadata = new HashMap<>();
if (chunk.getDelta().getReasoningContent() != null) {
    metadata.put("reasoningContent", chunk.getDelta().getReasoningContent());
}
AssistantMessage msg = new AssistantMessage(content, metadata);
```

### 7.3 已知问题

| 版本 | 问题 | 状态 |
|------|------|------|
| Spring AI 1.1.0 | `internalCall()` 不包含 reasoningContent（仅 stream 有） | 已在 PR #5711 修复 |
| Spring AI 1.1.0 | DeepSeek-reasoner 工具调用时 reasoning_content 为 null | Issue #5027 |
| Spring AI 1.1.3 | 部分模型（如 doubao-seed）reasoningContent 未正确添加 | Issue #5664 |

---

## 8. 三种方案对比

| 特性 | 方案一：ReasoningContentAdvisor | 方案二：Playground SSE | 方案三：ReactAgent + SseEmitter |
|------|------|------|------|
| **适用模型** | QWQ / DeepSeek-R1 / Qwen3 | QWQ / Qwen3 | QWQ / Qwen3 |
| **推理内容获取** | metadata → 拼接到文本 | metadata → SSE event 分类 | metadata → SSE event 分类 |
| **前端区分推理/正文** | 解析 `<think/>` 标签 | 通过 SSE event name | 通过 SSE event name |
| **工具调用支持** | ❌ QWQ 不支持 | ❌ QWQ 不支持 | ✅ 支持 |
| **复杂度** | 低 | 中 | 高 |
| **流式方式** | Flux\<String\> | Flux\<ServerSentEvent\> | SseEmitter |
| **适用场景** | 简单对话 | 前后端分离 Web 应用 | Agent + 工具调用 |

---

## 附录：关键代码速查

### A. 从 ChatResponse 提取推理内容（通用方法）

```java
// 方式1：从 ChatClient 的 stream().chatResponse() 获取
chatClient.prompt(prompt)
    .stream()
    .chatResponse()
    .map(resp -> {
        String reasoning = (String) resp.getResult()
                .getOutput().getMetadata().get("reasoningContent");
        String content = resp.getResult().getOutput().getText();
        // 处理推理和正文...
        return ...;
    });

// 方式2：从 ChatModel 的 stream() 获取
chatModel.stream(new Prompt(messages))
    .map(resp -> {
        String reasoning = (String) resp.getResult()
                .getOutput().getMetadata().get("reasoningContent");
        String content = resp.getResult().getOutput().getText();
        return ...;
    });

// 方式3：从 ReactAgent 的 stream() 获取
agent.stream(prompt, config)
    .subscribe(output -> {
        if (output instanceof StreamingOutput modelResponse) {
            Message message = modelResponse.message();
            Object thinkContent = message.getMetadata().get("reasoningContent");
            // ...
        }
    });
```

### B. 推理内容在 metadata 中的 key 名称

| 模型/框架 | metadata key | 来源 |
|-----------|-------------|------|
| DashScope (QWQ/Qwen3) | `"reasoningContent"` | DashScopeChatModel |
| OpenAI 兼容 (DeepSeek-R1) | `"reasoningContent"` | OpenAiChatModel |
| Anthropic (Claude) | thinking blocks | AnthropicChatModel |

### C. 参考链接

- Spring AI Alibaba 官方文档：https://java2ai.com
- QWQ 集成文档：https://www.java2ai.com/integration/chatmodels/qwq/
- spring-ai-alibaba-examples 仓库：https://github.com/spring-ai-alibaba/examples
- spring-ai-alibaba 主仓库：https://github.com/alibaba/spring-ai-alibaba
- Spring AI DeepSeek reasoning_content PR：https://github.com/spring-projects/spring-ai/pull/5711
