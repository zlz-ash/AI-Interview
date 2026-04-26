# Spring AI 推理流式输出 SSE 完整参考

## 目录

1. [核心概念](#1-核心概念)
2. [方案一：Spring AI DeepSeek 官方模块](#2-方案一spring-ai-deepseek-官方模块)
3. [方案二：Spring AI Alibaba DashScope](#3-方案二spring-ai-alibaba-dashscope)
4. [方案三：SseEmitter + 事件分类（生产级）](#4-方案三sseemitter--事件分类生产级)
5. [本项目的双通道 SSE 实现](#5-本项目的双通道-sse-实现)
6. [前端解析与展示](#6-前端解析与展示)
7. [各模型推理字段映射表](#7-各模型推理字段映射表)
8. [完整数据流图](#8-完整数据流图)

---

## 1. 核心概念

### 1.1 什么是推理流式输出

推理模型（如 DeepSeek-R1、MiniMax-M2.7）在生成回答时，会先输出一段**推理/思考过程**，再输出**正式回答**。推理流式输出就是将这两部分**分离**后通过 SSE 分别推送给前端。

### 1.2 推理 vs 正文的关键区别

| 属性 | 推理（Reasoning） | 正文（Content） |
|------|-------------------|-----------------|
| 含义 | 模型内部思考过程 | 面向用户的最终回答 |
| 时序 | 先输出 | 后输出 |
| 格式 | 纯文本 | 通常为 Markdown |
| 持久化 | **不落库** | 落库保存 |
| 两者关系 | **不会同时有值** | 推理结束后才开始输出正文 |

### 1.3 三种主流推理方式

| 方式 | 代表模型 | 特点 |
|------|----------|------|
| Extended Thinking | DeepSeek-R1, OpenAI o1 | 先想完再答，推理和正文严格分离 |
| Interleaved Thinking | MiniMax-M2.7, Claude | 边想边答，推理和正文可交错 |
| Metadata 透出 | DashScope 接入的推理模型 | 推理内容放在 metadata 字段中 |

---

## 2. 方案一：Spring AI DeepSeek 官方模块

### 2.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>
```

### 2.2 配置

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-reasoner   # 关键：使用推理模型
```

### 2.3 核心代码：同步调用获取推理

```java
@GetMapping("/sync")
public String chatSync(@RequestParam String message) {
    ChatResponse chatResponse = chatModel.call(new Prompt(new UserMessage(message)));
    DeepSeekAssistantMessage msg = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();

    String reasoning = msg.getReasoningContent();  // 推理过程
    String answer = msg.getText();                  // 正式回答

    System.out.println("推理: " + reasoning);
    System.out.println("回答: " + answer);
    return answer;
}
```

### 2.4 核心代码：流式推理输出

```java
@RestController
@RequestMapping("/v1/ai")
public class DeepSeekR1StreamController {

    @Resource
    private DeepSeekChatModel chatModel;

    @GetMapping(value = "/generateStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateStream(@RequestParam String message) {
        Prompt prompt = new Prompt(new UserMessage(message));

        return chatModel.stream(prompt)
                .mapNotNull(chatResponse -> {
                    DeepSeekAssistantMessage msg = (DeepSeekAssistantMessage)
                        chatResponse.getResult().getOutput();

                    String reasoningContent = msg.getReasoningContent();  // 推理
                    String text = msg.getText();                           // 正文

                    // 推理和正文不会同时有值
                    // 有推理返回推理，推理结束后返回正文
                    return StringUtils.isNotBlank(reasoningContent) ? reasoningContent : text;
                });
    }
}
```

### 2.5 关键类：DeepSeekAssistantMessage

```java
// Spring AI 官方 DeepSeek 模块中的类
public class DeepSeekAssistantMessage extends AssistantMessage {

    private final String reasoningContent;  // 推理内容（可为 null）

    public String getReasoningContent() {
        return reasoningContent;
    }

    // getText() 继承自 AssistantMessage，返回正式回答
}
```

### 2.6 流式输出时序

```
时间 ──────────────────────────────────────────────►

推理阶段:  "首先" → "我需要" → "分析这个问题" → "得出结论"
                                                     ↓ 推理结束
正文阶段:                                          "根据分析" → "答案是" → "42"

getReasoningContent() 返回值:
  帧1: "首先"
  帧2: "首先我需要"          ← 注意：是累积的，不是增量的！
  帧3: "首先我需要分析这个问题"
  帧4: null                   ← 推理结束

getText() 返回值:
  帧1~3: null 或 ""
  帧5: "根据分析"
  帧6: "根据分析答案是"       ← 同样是累积的
  帧7: "根据分析答案是42"
```

**⚠️ 重要**：DeepSeek 官方模块返回的 `reasoningContent` 和 `text` 都是**累积型**的（每帧包含完整内容），不是增量型。需要做前缀差分才能得到增量。

---

## 3. 方案二：Spring AI Alibaba DashScope

### 3.1 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter</artifactId>
    <version>1.1.2.2</version>
</dependency>
```

### 3.2 配置

```yaml
spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
      chat:
        options:
          model: deepseek-r1          # 或 MiniMax/MiniMax-M2.7
```

### 3.3 核心代码：同步获取推理

```java
@RestController
public class DeepSeekDashScopeController {

    private final DashScopeChatModel chatModel;

    public DeepSeekDashScopeController(DashScopeChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/{prompt}")
    public String chat(@PathVariable String prompt) {
        ChatResponse chatResponse = chatModel.call(new Prompt(prompt));
        if (!chatResponse.getResults().isEmpty()) {
            Map<String, Object> metadata = chatResponse.getResults()
                    .get(0).getOutput().getMetadata();
            // 推理内容在 metadata 中
            String reasoning = (String) metadata.get("reasoningContent");
            System.out.println("推理: " + reasoning);
        }
        return chatResponse.getResult().getOutput().getContent();
    }
}
```

### 3.4 核心代码：流式推理输出

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamChat(@RequestParam String message) {
    Prompt prompt = new Prompt(new UserMessage(message));
    StringBuilder lastReasoning = new StringBuilder();
    StringBuilder lastContent = new StringBuilder();

    return chatModel.stream(prompt)
            .mapNotNull(chatResponse -> {
                if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                    return null;
                }
                AssistantMessage msg = chatResponse.getResult().getOutput();
                Map<String, Object> meta = msg.getMetadata();

                // 从 metadata 提取推理内容
                String reasoningFull = meta != null ? (String) meta.get("reasoningContent") : null;
                String contentFull = msg.getText();

                // 前缀差分得到增量
                String reasoningDelta = "";
                if (reasoningFull != null && reasoningFull.startsWith(lastReasoning.toString())) {
                    reasoningDelta = reasoningFull.substring(lastReasoning.length());
                    lastReasoning.setLength(0);
                    lastReasoning.append(reasoningFull);
                }

                String contentDelta = "";
                if (contentFull != null && contentFull.startsWith(lastContent.toString())) {
                    contentDelta = contentFull.substring(lastContent.length());
                    lastContent.setLength(0);
                    lastContent.append(contentFull);
                }

                // 构建双通道 SSE 事件
                if (!reasoningDelta.isEmpty()) {
                    return ServerSentEvent.<String>builder()
                        .data("{\"type\":\"reasoning\",\"delta\":\"" + escapeJson(reasoningDelta) + "\"}")
                        .build();
                } else if (!contentDelta.isEmpty()) {
                    return ServerSentEvent.<String>builder()
                        .data("{\"type\":\"content\",\"delta\":\"" + escapeJson(contentDelta) + "\"}")
                        .build();
                }
                return null;
            })
            .concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
}
```

### 3.5 DashScope 推理字段位置

DashScope 将推理内容放在 `AssistantMessage.getMetadata()` 中，key 为 `reasoningContent`。

---

## 4. 方案三：SseEmitter + 事件分类（生产级）

来源：[alibaba/spring-ai-alibaba Issue #4217](https://github.com/alibaba/spring-ai-alibaba/issues/4217)

此方案使用 Spring MVC 的 `SseEmitter` 而非 WebFlux 的 `Flux`，适合传统 Servlet 容器。

### 4.1 SSE 事件管理器

```java
@Component
public class SseEmitterManager {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String sessionId) {
        SseEmitter emitter = new SseEmitter(60_000L);  // 60秒超时
        emitters.put(sessionId, emitter);
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        return emitter;
    }

    public SseEmitter getEmitter(String sessionId) {
        return emitters.get(sessionId);
    }

    public void sendEvent(SseEmitter emitter, String sessionId, String eventType, String data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(data));
        } catch (Exception e) {
            emitters.remove(sessionId);
        }
    }
}
```

### 4.2 常量定义

```java
public class Constants {
    public static final String SSE_EVENT_THINKING = "thinking";   // 推理事件
    public static final String SSE_EVENT_MODEL = "model";         // 正式回答事件
    public static final String SSE_EVENT_TOOL = "tool";           // 工具调用事件
    public static final String SSE_EVENT_ERROR = "error";         // 错误事件
    public static final String SSE_EVENT_COMPLETE = "complete";   // 流结束事件
}
```

### 4.3 核心流式调用

```java
@Service
public class ChatStreamService {

    @Resource
    private SseEmitterManager sseManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SseEmitter streamCall(String prompt, String sessionId) {
        SseEmitter emitter = sseManager.createEmitter(sessionId);

        executor.submit(() -> {
            try {
                // 使用 Spring AI ReactAgent 或 ChatModel
                Flux<NodeOutput> stream = agent.stream(prompt, config);

                stream.subscribe(output -> {
                    if (output instanceof StreamingOutput modelResponse) {
                        OutputType type = modelResponse.getOutputType();
                        Message message = modelResponse.message();

                        switch (type) {
                            case AGENT_MODEL_STREAMING -> {
                                // 从 metadata 提取推理内容
                                Object thinkContent = message.getMetadata().get("reasoningContent");
                                if (thinkContent != null && !thinkContent.toString().isEmpty()) {
                                    // 推理事件
                                    sseManager.sendEvent(emitter, sessionId,
                                        Constants.SSE_EVENT_THINKING, thinkContent.toString());
                                } else {
                                    // 正式回答事件
                                    sseManager.sendEvent(emitter, sessionId,
                                        Constants.SSE_EVENT_MODEL, message.getText());
                                }
                            }
                            case AGENT_TOOL_STREAMING -> {
                                log.info("Tool streaming: {}", message);
                            }
                            case AGENT_TOOL_FINISHED -> {
                                if (message instanceof ToolResponseMessage tool) {
                                    tool.getResponses().forEach(response -> {
                                        sseManager.sendEvent(emitter, sessionId,
                                            Constants.SSE_EVENT_TOOL, response.responseData().toString());
                                    });
                                }
                            }
                            default -> log.info("Other type: {}", type);
                        }
                    }
                }, error -> {
                    sseManager.sendEvent(emitter, sessionId,
                        Constants.SSE_EVENT_ERROR, error.getMessage());
                    emitter.completeWithError(error);
                }, () -> {
                    sseManager.sendEvent(emitter, sessionId,
                        Constants.SSE_EVENT_COMPLETE, "Stream completed");
                    emitter.complete();
                });
            } catch (Exception e) {
                sseManager.sendEvent(emitter, sessionId,
                    Constants.SSE_EVENT_ERROR, e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
```

### 4.4 Controller

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Resource
    private ChatStreamService chatStreamService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam String message,
                                  @RequestParam String sessionId) {
        return chatStreamService.streamCall(message, sessionId);
    }
}
```

### 4.5 SSE 事件分类

| 事件名 | 含义 | 前端处理 |
|--------|------|----------|
| `thinking` | 推理/思考过程 | 展示在可折叠区域 |
| `model` | 正式回答 | 展示在主回答区域 |
| `tool` | 工具调用结果 | 展示工具执行信息 |
| `error` | 错误信息 | 展示错误提示 |
| `complete` | 流结束 | 关闭 loading 状态 |

---

## 5. 本项目的双通道 SSE 实现

本项目已实现了完整的推理 + 正文双通道 SSE，采用**JSON 信封**方式（不依赖 SSE `event:` 行）。

### 5.1 架构总览

```
上游模型 (DeepSeek-R1 / MiniMax-M2.7 / 普通模型)
    │
    │  Flux<ChatResponse>  (Spring AI 流式响应)
    │
    ▼
ChatResponseStreamMapper   ← 适配层：提取推理 + 正文，做前缀差分
    │
    │  Flux<StreamPart>    (统一内部表示)
    │
    ▼
DualChannelSse             ← 序列化层：StreamPart → JSON 信封 → ServerSentEvent
    │
    │  Flux<ServerSentEvent<String>>
    │
    ▼
Controller                 ← 控制层：拼接 [DONE]，处理落库
    │
    │  SSE HTTP Response
    │
    ▼
前端 parseDualChannelSse   ← 解析 JSON 信封，按 type 分发
```

### 5.2 StreamPart — 统一片段

文件：`streaming/StreamPart.java`

```java
public record StreamPart(String type, String delta) {
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_CONTENT = "content";

    public static StreamPart reasoning(String delta) {
        return new StreamPart(TYPE_REASONING, delta == null ? "" : delta);
    }

    public static StreamPart content(String delta) {
        return new StreamPart(TYPE_CONTENT, delta == null ? "" : delta);
    }
}
```

### 5.3 ChatResponseStreamMapper — 核心映射器

文件：`streaming/ChatResponseStreamMapper.java`

```java
public final class ChatResponseStreamMapper {

    // 兼容多种模型的推理字段名
    private static final List<String> REASONING_KEYS =
        List.of("reasoning", "reasoning_content", "thinking");

    public static Flux<StreamPart> toStreamParts(Flux<ChatResponse> chatResponses) {
        final String[] lastReasoning = {""};
        final String[] lastContent = {""};

        return chatResponses.concatMap(response -> {
            Generation gen = response.getResult();
            if (gen == null || gen.getOutput() == null) {
                return Flux.empty();
            }

            AssistantMessage msg = gen.getOutput();

            // 1. 提取推理全文（从 metadata）
            String newReasoningFull = extractReasoningFull(msg);
            // 2. 提取正文全文
            String newContentFull = msg.getText() != null ? msg.getText() : "";

            // 3. 前缀差分得到增量
            String reasoningDelta = deltaFromCumulative(lastReasoning[0], newReasoningFull);
            String contentDelta = deltaFromCumulative(lastContent[0], newContentFull);

            lastReasoning[0] = newReasoningFull;
            lastContent[0] = newContentFull;

            // 4. 先发推理，后发正文
            List<StreamPart> parts = new ArrayList<>();
            if (!reasoningDelta.isEmpty()) {
                parts.add(StreamPart.reasoning(reasoningDelta));
            }
            if (!contentDelta.isEmpty()) {
                parts.add(StreamPart.content(contentDelta));
            }
            return Flux.fromIterable(parts);
        });
    }

    // 从 metadata 中按优先级提取推理内容
    static String extractReasoningFull(AssistantMessage msg) {
        Map<String, Object> meta = msg.getMetadata();
        if (meta == null || meta.isEmpty()) return "";
        for (String key : REASONING_KEYS) {
            Object v = meta.get(key);
            if (v != null) return String.valueOf(v);
        }
        return "";
    }

    // 前缀差分：从累积文本中提取增量部分
    static String deltaFromCumulative(String lastFull, String newFull) {
        String last = lastFull != null ? lastFull : "";
        String next = newFull != null ? newFull : "";
        if (next.startsWith(last)) {
            return next.substring(last.length());  // 正常增量
        }
        return next;  // 非前缀关系，整段作为 delta（处理模型重置）
    }
}
```

**前缀差分原理**：

```
帧1: lastContent=""   newContent="Hel"    delta="Hel"     (全部是新的)
帧2: lastContent="Hel" newContent="Hello"  delta="lo"      (前缀匹配，取后缀)
帧3: lastContent="Hello" newContent="Hello world" delta=" world"

特殊情况（模型重置）:
帧4: lastContent="Hello world" newContent="重新开始" delta="重新开始" (非前缀，整段替换)
```

### 5.4 StreamPartSseSerializer — JSON 序列化

文件：`streaming/StreamPartSseSerializer.java`

```java
public final class StreamPartSseSerializer {

    private final ObjectMapper objectMapper;

    public String toJsonLine(StreamPart part) {
        try {
            return objectMapper.writeValueAsString(
                Map.of("type", part.type(), "delta", part.delta()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("StreamPart JSON serialization failed", e);
        }
    }
}
```

**禁止手拼 JSON**：必须用 ObjectMapper 序列化，确保换行符、引号等特殊字符正确转义。

### 5.5 DualChannelSse — SSE 事件组装

文件：`streaming/DualChannelSse.java`

```java
public final class DualChannelSse {

    public static Flux<ServerSentEvent<String>> partsToSseEvents(
            Flux<StreamPart> parts, ObjectMapper objectMapper) {
        StreamPartSseSerializer serializer = new StreamPartSseSerializer(objectMapper);
        return parts.map(p ->
            ServerSentEvent.<String>builder().data(serializer.toJsonLine(p)).build());
    }
}
```

### 5.6 Controller — 完整流式管道

文件：`controller/RagChatController.java`

```java
@PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
             produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> sendMessageStream(
        @PathVariable Long sessionId,
        @Valid @RequestBody SendMessageRequest request) {

    // 1. 准备消息（保存用户消息，创建 AI 消息占位）
    Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

    // 2. 仅正文落库
    StringBuilder contentOnly = new StringBuilder();

    // 3. 组装双通道 SSE
    Flux<ServerSentEvent<String>> contentEvents = DualChannelSse.partsToSseEvents(
        sessionService.getStreamAnswer(sessionId, request.question())
            .doOnNext(part -> {
                if (StreamPart.TYPE_CONTENT.equals(part.type())) {
                    contentOnly.append(part.delta());  // 仅累积正文
                }
            }),
        objectMapper
    );

    // 4. 拼接 [DONE] 结束标记
    Flux<ServerSentEvent<String>> doneSignal = Flux.just(
        ServerSentEvent.<String>builder().data("[DONE]").build());

    // 5. 返回 + 完成回调
    return Flux.concat(contentEvents, doneSignal)
        .doOnComplete(() -> {
            sessionService.completeStreamMessage(messageId, contentOnly.toString());
        })
        .doOnError(e -> {
            String content = contentOnly.length() > 0
                ? contentOnly.toString()
                : "【错误】回答生成失败：" + e.getMessage();
            sessionService.completeStreamMessage(messageId, content);
        });
}
```

### 5.7 SSE 输出示例

```
data: {"type":"reasoning","delta":"首先"}

data: {"type":"reasoning","delta":"我需要分析这个问题"}

data: {"type":"reasoning","delta":"从多个角度思考"}

data: {"type":"content","delta":"根据"}

data: {"type":"content","delta":"分析"}

data: {"type":"content","delta":"，答案是42"}

data: [DONE]
```

---

## 6. 前端解析与展示

### 6.1 类型定义

文件：`api/streamTypes.ts`

```typescript
export type StreamEnvelope = { type: 'reasoning' | 'content'; delta: string };
```

### 6.2 SSE 解析器

文件：`api/parseDualChannelSse.ts`

```typescript
export async function parseDualChannelSseResponse(
  response: Response,
  onPart: (part: StreamEnvelope) => void,
  onDone: () => void,
  onError: (error: Error) => void
): Promise<void> {
  const reader = response.body?.getReader();
  if (!reader) { onError(new Error('无法获取响应流')); return; }

  const decoder = new TextDecoder();
  let buffer = '';
  let streamDone = false;

  while (!streamDone) {
    const { done, value } = await reader.read();
    if (done) { /* 处理剩余 buffer，结束 */ break; }

    buffer += decoder.decode(value, { stream: true });

    // 按 SSE 事件块分割（双换行分隔）
    while (!streamDone) {
      const splitIndex = findEventBoundary(buffer);
      if (splitIndex === -1) break;

      const eventBlock = buffer.substring(0, splitIndex);
      buffer = buffer.substring(splitIndex + splitLen);

      // 提取 data: 行内容
      const merged = mergeDataPayload(eventBlock);
      if (merged === null) continue;

      if (merged.trim() === '[DONE]') { finishStream(); return; }

      try {
        const obj = JSON.parse(merged);
        if (obj.type === 'reasoning' || obj.type === 'content') {
          onPart({ type: obj.type, delta: obj.delta });
        }
      } catch {
        console.warn('[SSE] skip frame: JSON parse error', merged.slice(0, 120));
      }
    }
  }
}
```

### 6.3 API 调用

文件：`api/ragChat.ts`

```typescript
async sendMessageStream(
  sessionId: number,
  question: string,
  onPart: (part: StreamEnvelope) => void,
  onComplete: () => void,
  onError: (error: Error) => void
): Promise<void> {
  const response = await fetch(url, { headers: { Accept: 'text/event-stream' } });

  if (!response.ok) { /* 401 处理等 */ }

  await parseDualChannelSseResponse(response, onPart, onComplete, onError);
}
```

### 6.4 前端 UI 展示逻辑

```typescript
// 消息状态
const [reasoningText, setReasoningText] = useState('');
const [contentText, setContentText] = useState('');

// 流式回调
onPart={(part) => {
  if (part.type === 'reasoning') {
    setReasoningText(prev => prev + part.delta);  // 追加推理
  } else {
    setContentText(prev => prev + part.delta);     // 追加正文
  }
}}

// 渲染
{reasoningText && (
  <details>  {/* 可折叠，默认折叠 */}
    <summary>思考过程</summary>
    <pre>{reasoningText}</pre>
  </details>
)}
<ReactMarkdown>{contentText}</ReactMarkdown>
```

---

## 7. 各模型推理字段映射表

| 模型 | 接入方式 | 推理字段位置 | 字段名 | 累积/增量 |
|------|----------|-------------|--------|-----------|
| DeepSeek-R1 | `spring-ai-deepseek` | `DeepSeekAssistantMessage.getReasoningContent()` | `reasoningContent` | 累积 |
| DeepSeek-R1 | DashScope | `AssistantMessage.getMetadata().get("reasoningContent")` | `reasoningContent` | 累积 |
| MiniMax-M2.7 | DashScope | `AssistantMessage.getMetadata().get("reasoningContent")` | `reasoningContent` | 累积 |
| MiniMax-M1 | OpenAI 兼容 | `delta.reasoning_content` (HTTP SSE) | `reasoning_content` | 增量 |
| DeepSeek-R1 | Ollama 本地 | `AssistantMessage.getMetadata().get("thinking")` | `thinking` | 累积 |
| 普通模型 | 任意 | 无 | - | - |

本项目的 `ChatResponseStreamMapper.REASONING_KEYS` 已兼容：

```java
List.of("reasoning", "reasoning_content", "thinking")
```

---

## 8. 完整数据流图

```
┌─────────────────────────────────────────────────────────────────────┐
│                          上游模型                                    │
│  DeepSeek-R1 / MiniMax-M2.7 / Qwen3 / 普通模型                      │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                   Flux<ChatResponse>
                   (Spring AI 流式响应)
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   ChatResponseStreamMapper                          │
│                                                                     │
│  1. 从 AssistantMessage.getMetadata() 提取推理全文                    │
│     按优先级: "reasoning" → "reasoning_content" → "thinking"         │
│                                                                     │
│  2. 从 AssistantMessage.getText() 提取正文全文                        │
│                                                                     │
│  3. 前缀差分: deltaFromCumulative(last, current)                     │
│     - current 以 last 为前缀 → 取后缀 (增量)                         │
│     - 非前缀关系 → 整段作为 delta (模型重置)                          │
│                                                                     │
│  4. 输出: 先 reasoning 后 content                                    │
│     无推理模型 → 仅输出 content，自动退化                              │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                     Flux<StreamPart>
                   { type, delta }
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      DualChannelSse                                 │
│                                                                     │
│  StreamPart → ObjectMapper.writeValueAsString() → ServerSentEvent   │
│                                                                     │
│  {"type":"reasoning","delta":"首先"}                                 │
│  {"type":"content","delta":"根据分析"}                                │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
              Flux<ServerSentEvent<String>>
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Controller                                    │
│                                                                     │
│  1. doOnNext: 仅 type=content 的 delta 追加到 contentOnly            │
│  2. concat [DONE] 结束标记                                           │
│  3. doOnComplete: completeStreamMessage(messageId, contentOnly)      │
│  4. doOnError: 落库错误信息                                          │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                      HTTP SSE Response
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
     data: {"type":    data: {"type":    data: [DONE]
     "reasoning",      "content",
     "delta":"..."}    "delta":"..."}
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   前端 parseDualChannelSse                          │
│                                                                     │
│  1. ReadableStream 按行读取                                          │
│  2. 提取 data: 行内容                                                │
│  3. "[DONE]" → 结束                                                  │
│  4. JSON.parse → { type, delta }                                    │
│  5. 按 type 分发:                                                    │
│     reasoning → setReasoningText(prev + delta)                      │
│     content   → setContentText(prev + delta)                        │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        UI 渲染                                       │
│                                                                     │
│  ┌──────────────────────────────────┐                               │
│  │ ▶ 思考过程 (可折叠，默认折叠)       │  ← reasoningText 非空时渲染  │
│  │   首先我需要分析这个问题...         │                               │
│  └──────────────────────────────────┘                               │
│                                                                     │
│  根据分析，答案是42                    ← contentText + ReactMarkdown │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 附录：三种方案对比

| 对比项 | 方案一 (DeepSeek 官方) | 方案二 (DashScope) | 方案三 (SseEmitter) | 本项目实现 |
|--------|----------------------|--------------------|--------------------|-----------|
| 推理获取 | `getReasoningContent()` | `metadata.get("reasoningContent")` | `metadata.get("reasoningContent")` | `metadata` 多 key 兼容 |
| SSE 方式 | `Flux<String>` | `Flux<ServerSentEvent>` | `SseEmitter` | `Flux<ServerSentEvent>` |
| 事件区分 | 无（混合输出） | JSON 信封 | `event:` 行 | JSON 信封 |
| 增量处理 | 无（需自行差分） | 需自行差分 | 需自行差分 | ✅ 前缀差分 |
| 多模型兼容 | 仅 DeepSeek | DashScope 模型 | 任意 | ✅ 适配层映射 |
| 前端展示 | 需自行实现 | 需自行实现 | 需自行实现 | ✅ 双通道解析 |
| 推理持久化 | 无 | 无 | 无 | ✅ 仅正文落库 |
