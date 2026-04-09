# Native Reasoning + Dual-Channel SSE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 RAG 对话与知识库流式查询从「单通道纯文本 SSE」升级为「`data:` 内 JSON 信封：`type` + `delta`」，从 Spring AI `ChatClient` 的 `stream().chatResponse()` 拆分推理与正文增量；前端分区展示可折叠「思考」与 Markdown 正文；落库仍只保存正文（与设计 spec §3、持久化策略 A 一致）。

**Architecture:** 新增 `StreamPart` 与 `ChatResponseStreamMapper`（`Flux<ChatResponse>` → `Flux<StreamPart>`，对累积型流式文本做前缀差分）。`KnowledgeBaseQueryService` 在原有检索与 `normalizeStreamOutput` 探测逻辑之上改为产出 `Flux<StreamPart>`。控制器层用统一工具将 `StreamPart` 序列化为单行 JSON 写入 `ServerSentEvent`，保留末尾 `data: [DONE]`。前端抽取共用 SSE 解析器，按帧 `JSON.parse` 分发；会话消息仅在流结束时写入拼接后的 **content** 增量。

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC + Reactor (`Flux`, `ServerSentEvent`), Spring AI 2.0.0-M2 (`ChatClient.StreamResponseSpec#chatResponse()`), Jackson, TypeScript, React 19, Vite

---

## Scope Check

单计划覆盖：**后端双通道协议 + RAG/知识库两条流式 API + 前端问答页**。不包含面试/简历等其它 LLM 流（与设计 spec §6 一致），除非显式复用工具类。

## File Structure Map

**Create**

- `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/StreamPart.java`  
  内部统一片段：`type`（`reasoning` | `content`）、`delta`（UTF-8，可空串）；静态工厂方法 `reasoning(String)`、`content(String)`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/ChatResponseStreamMapper.java`  
  有状态映射器：输入 `Flux<ChatResponse>`，输出 `Flux<StreamPart>`；从 `Generation.getOutput()` 的 `AssistantMessage` 取正文 `getText()`，从 `getMetadata()` 中按优先级读取推理全文键（至少包含 `reasoning`、`reasoning_content`；实现时可增列 `thinking`）并做前缀差分；若上游改为「真增量」而非累积，可通过首帧启发式检测（若新文本长度 &lt; last 且非前缀关系则整段替换为增量）——实现时选一种并写清注释。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/StreamPartSseSerializer.java`  
  使用 `ObjectMapper` 将 `StreamPart` 序列化为单行 JSON 字符串（与设计 spec §3.2 字段名 `type`、`delta` 一致）；**非法 JSON 不得手工拼接**，避免转义错误。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/DualChannelSse.java`  
  静态方法：`Flux<StreamPart> partsToSseEvents(Flux<StreamPart>, ObjectMapper)` → `Flux<ServerSentEvent<String>>`，每帧 `ServerSentEvent.builder().data(jsonLine).build()`；**不**对 JSON 再做 `\n` → `\\n` 替换（旧代码针对裸文本；JSON 由 Jackson 处理）。
- `interview-platform/src/test/java/com/ash/springai/interview_platform/streaming/ChatResponseStreamMapperTest.java`  
  覆盖：仅正文递增、仅推理递增、交错、元数据无推理、正文被模型全量替换（非前缀）时的行为。

**Modify**

- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`  
  - `answerQuestionStream` 返回类型改为 `Flux<StreamPart>`（或保留旧名并新增 `answerQuestionStreamParts` 再删旧签名——计划内二选一，**全仓库编译通过为准**）。  
  - 将 `chatClient.prompt()...stream().content()` 改为 `...stream().chatResponse()` 后接 `ChatResponseStreamMapper.toStreamParts(...)`。  
  - 将 `normalizeStreamOutput(Flux<String>)` 重构为 `normalizeStreamParts(Flux<StreamPart>)`：仅在 **content** 通道上保留原「首段 48 字符探测无结果句式」逻辑；命中时只发出一条 `StreamPart.content(NO_RESULT_RESPONSE)` 后完成；推理帧在探测完成前可丢弃或暂缓（实现时固定：**探测期丢弃 reasoning**，避免思考区闪现后再被截断）。  
  - 错误/无命中短路的 `Flux.just(NO_RESULT_RESPONSE)` 改为 `Flux.just(StreamPart.content(NO_RESULT_RESPONSE))`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java`  
  - `getStreamAnswer` 返回 `Flux<StreamPart>`，直接委托 `queryService.answerQuestionStream(...)`（或新方法名）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/RagChatController.java`  
  - 用 `StringBuilder contentBuffer` **仅追加** `type=content` 的 delta；`doOnComplete` / `doOnError` 调用 `completeStreamMessage(messageId, contentBuffer.toString())`。  
  - SSE：`DualChannelSse.partsToSseEvents(...).concatWith(Flux.just(ServerSentEvent.builder().data("[DONE]").build()))`（`[DONE]` 保持明文，与现网一致）。  
  - **JSON 解析失败策略（固定）：** 序列化阶段若 `ObjectMapper` 失败属编程错误；出站后若需防御性处理应在客户端；服务端发射前保证结构合法。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`  
  - `queryKnowledgeBaseStream` 返回类型从 `Flux<String>` 改为 `Flux<ServerSentEvent<String>>`（与 `RagChatController` 对齐）。  
  - 删除 `Flux.concat(..., Flux.just("[DONE"))` 中前半段对「裸字符串」的依赖，改为 `DualChannelSse` + 末尾 `[DONE]`。
- `interview-platform-frontend/src/api/streamTypes.ts`  
  导出 `export type StreamEnvelope = { type: 'reasoning' | 'content'; delta: string }`。
- `interview-platform-frontend/src/api/parseDualChannelSse.ts`  
  导出 `parseDualChannelSseResponse(response: Response, onPart: (p: StreamEnvelope) => void, onDone: () => void, onError: (e: Error) => void): Promise<void>`：按行缓冲解析 SSE；`data:` 内容为 `[DONE]`（trim 后）则结束；否则 `JSON.parse` 为 `StreamEnvelope`；**解析失败策略（固定）：** 跳过该 `data` 行并 `console.warn`，不终止整流（与设计 spec 服务端策略对称，便于兼容旧代理插入的脏行）。
- `interview-platform-frontend/src/api/ragChat.ts`  
  `sendMessageStream` 增加参数或改为 `onPart(StreamEnvelope)`；内部调用 `parseDualChannelSse`；保留 401 处理与 `[DONE]` 行为。
- `interview-platform-frontend/src/api/knowledgebase.ts`  
  `queryKnowledgeBaseStream` 同样改为 `onPart` 或双回调；与 `ragChat` 共用 `parseDualChannelSse`。
- `interview-platform-frontend/src/pages/KnowledgeBaseQueryPage.tsx`  
  - 扩展 `Message`：`reasoningText?: string`（仅当次流式 assistant 消息使用）。  
  - 流式回调：收到 `reasoning` 追加到 `reasoningText`，收到 `content` 追加到 `content`。  
  - UI：当 `reasoningText` 非空时渲染可折叠块（默认展开或折叠二选一，实现时固定 **默认折叠** 以减少跳动）；正文区继续 `ReactMarkdown`。  
  - 无推理：从不设置 `reasoningText` 或保持空字符串，**不渲染**思考区容器。

**不修改（本期）**

- `RagChatMessageEntity` 表结构（推理不落库）。

---

## Task 1: `StreamPart` + SSE 序列化 + 控制器共用工具

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/StreamPart.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/StreamPartSseSerializer.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/DualChannelSse.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/streaming/StreamPartSseSerializerTest.java`

- [ ] **Step 1: 新增 `StreamPart` record**

```java
package com.ash.springai.interview_platform.streaming;

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

- [ ] **Step 2: 新增序列化器与 DualChannelSse**

`StreamPartSseSerializer` 注入或构造传入 `ObjectMapper`，方法 `String toJsonLine(StreamPart part)`，内部 `objectMapper.writeValueAsString(Map.of("type", part.type(), "delta", part.delta()))` 或使用 `@JsonProperty` 的私有 DTO 再序列化（二选一，**禁止手写 JSON 字符串拼接**）。

`DualChannelSse.java`:

```java
public static Flux<ServerSentEvent<String>> partsToSseEvents(Flux<StreamPart> parts, ObjectMapper objectMapper) {
    StreamPartSseSerializer serializer = new StreamPartSseSerializer(objectMapper);
    return parts.map(p -> ServerSentEvent.<String>builder().data(serializer.toJsonLine(p)).build());
}
```

- [ ] **Step 3: 编写 `StreamPartSseSerializerTest`**

断言：给定 `StreamPart.content("a\nb")`，JSON 解析后 `delta` 等于 `a\nb`，`type` 为 `content`。

- [ ] **Step 4: 运行测试**

Run: `cd interview-platform && mvn -q test -Dtest=StreamPartSseSerializerTest`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/StreamPart.java interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/StreamPartSseSerializer.java interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/DualChannelSse.java interview-platform/src/test/java/com/ash/springai/interview_platform/streaming/StreamPartSseSerializerTest.java
git commit -m "feat(streaming): add StreamPart and dual-channel SSE JSON helpers"
```

---

## Task 2: `ChatResponseStreamMapper` + 单元测试

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/ChatResponseStreamMapper.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/streaming/ChatResponseStreamMapperTest.java`

- [ ] **Step 1: 实现映射器核心逻辑**

- 入参：`Flux<ChatResponse>`。  
- 状态：`lastContentFull`、`lastReasoningFull`（字符串）。  
- 每帧：`AssistantMessage msg = response.getResult().getOutput()`（空则 skip）。  
- `contentDelta`：`msg.getText()` 相对 `lastContentFull` 做前缀差分；若新串非前缀则整段替换为 delta 并覆盖 `lastContentFull`（与 Task 描述一致）。  
- `reasoningFull`：从 `msg.getMetadata()` 依次取 `reasoning`、`reasoning_content`、`thinking` 中第一个非 null；转字符串；无则 `""`。  
- `reasoningDelta`：相对 `lastReasoningFull` 同前缀差分。  
- 每帧发出 0～2 个 `StreamPart`（先 reasoning 后 content 或按产品固定顺序，**全仓库统一为先 reasoning 后 content**）。

- [ ] **Step 2: 编写测试：累积型正文**

手动构造三个 `ChatResponse`，`AssistantMessage` 正文依次为 `"Hel"`、`"Hello"`、`"Hello world"`，断言三次发出的 content delta 为 `"Hel"`、`"lo"`、`" world"`。

- [ ] **Step 3: 编写测试：推理元数据**

构造 `AssistantMessage.builder().content("x").properties(Map.of("reasoning", "A")).build()`（`properties` 写入 `AbstractMessage` 的 metadata），验证映射器发出非空 `reasoning` delta。

- [ ] **Step 4: 运行测试**

Run: `cd interview-platform && mvn -q test -Dtest=ChatResponseStreamMapperTest`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/streaming/ChatResponseStreamMapper.java interview-platform/src/test/java/com/ash/springai/interview_platform/streaming/ChatResponseStreamMapperTest.java
git commit -m "feat(streaming): map ChatResponse stream to StreamPart deltas"
```

---

## Task 3: `KnowledgeBaseQueryService` 接入 `chatResponse` 与 `normalizeStreamParts`

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`（`answerQuestionStream` 及 `normalizeStreamOutput` 相关私有方法）

- [ ] **Step 1: 将 `answerQuestionStream` 返回类型改为 `Flux<StreamPart>`**

签名示例：

```java
public Flux<StreamPart> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
```

- [ ] **Step 2: 替换流来源**

将

```java
Flux<String> responseFlux = chatClient.prompt()
    .system(systemPrompt)
    .user(userPrompt)
    .stream()
    .content();
```

改为：

```java
Flux<StreamPart> responseFlux = ChatResponseStreamMapper.toStreamParts(
    chatClient.prompt()
        .system(systemPrompt)
        .user(userPrompt)
        .stream()
        .chatResponse()
);
```

（`toStreamParts` 为 `ChatResponseStreamMapper` 的静态方法名，可与实现一致。）

- [ ] **Step 3: 实现 `normalizeStreamParts`**

逻辑等价于原 `normalizeStreamOutput`（`STREAM_PROBE_CHARS = 48`、`isNoResultLike`），但探测缓冲只拼接 **content** 的 delta；在 `passthrough` 为 true 前丢弃所有 `reasoning` 帧；一旦判定无结果，只 `sink.next(StreamPart.content(NO_RESULT_RESPONSE))` 后 `complete()`。

- [ ] **Step 4: 所有返回 `Flux.just(NO_RESULT_RESPONSE)` 的分支改为 `Flux.just(StreamPart.content(NO_RESULT_RESPONSE))`**

- [ ] **Step 5: 编译**

Run: `cd interview-platform && mvn -q -DskipTests compile`

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java
git commit -m "feat(kb-query): emit StreamPart from chatResponse with probe normalization"
```

---

## Task 4: `RagChatSessionService` + `RagChatController`

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/RagChatController.java`

- [ ] **Step 1: `RagChatSessionService.getStreamAnswer`**

返回 `Flux<StreamPart>`，方法体调用 `queryService.answerQuestionStream(kbIds, question)`。

- [ ] **Step 2: `RagChatController` 注入 `ObjectMapper`**

Spring Boot 默认 `ObjectMapper` Bean 可直接构造注入。

- [ ] **Step 3: 重写 `sendMessageStream` SSE 管道**

伪代码结构：

```java
StringBuilder contentOnly = new StringBuilder();
Flux<ServerSentEvent<String>> events = DualChannelSse.partsToSseEvents(
    sessionService.getStreamAnswer(sessionId, request.question())
        .doOnNext(part -> {
            if (StreamPart.TYPE_CONTENT.equals(part.type())) {
                contentOnly.append(part.delta());
            }
        }),
    objectMapper
);
return Flux.concat(events, Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()))
    .doOnComplete(() -> sessionService.completeStreamMessage(messageId, contentOnly.toString()))
    .doOnError(e -> { ... completeStreamMessage(messageId,  contentOnly.length()>0 ? contentOnly.toString() : "【错误】..."); });
```

删除对 `chunk.replace("\n", "\\n")` 的旧逻辑。

- [ ] **Step 4: 编译**

Run: `cd interview-platform && mvn -q -DskipTests compile`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java interview-platform/src/main/java/com/ash/springai/interview_platform/controller/RagChatController.java
git commit -m "feat(rag-chat): dual-channel JSON SSE and persist content only"
```

---

## Task 5: `KnowledgeBaseController.queryKnowledgeBaseStream`

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`

- [ ] **Step 1: 修改方法签名**

```java
public Flux<ServerSentEvent<String>> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
```

- [ ] **Step 2: 返回**

```java
return Flux.concat(
    DualChannelSse.partsToSseEvents(
        queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question()),
        objectMapper
    ),
    Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build())
);
```

- [ ] **Step 3: 注入 `ObjectMapper`**

- [ ] **Step 4: 编译**

Run: `cd interview-platform && mvn -q -DskipTests compile`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java
git commit -m "feat(kb): emit dual-channel JSON SSE for knowledge base query stream"
```

---

## Task 6: 前端共用解析与 API

**Files:**
- Create: `interview-platform-frontend/src/api/streamTypes.ts`
- Create: `interview-platform-frontend/src/api/parseDualChannelSse.ts`
- Modify: `interview-platform-frontend/src/api/ragChat.ts`
- Modify: `interview-platform-frontend/src/api/knowledgebase.ts`

- [ ] **Step 1: 实现 `parseDualChannelSse`**

- 使用 `ReadableStream` 与现有 `ragChat.ts` 相同的 buffer 按行分割策略；  
- 支持多行 `data:` 合并（与现 `ragChat` 的 `extractEventContent` 行为对齐）；  
- 若合并后 `trim() === '[DONE]'` → `onDone()`；  
- 否则 `JSON.parse` → 校验 `type` 与 `delta` 存在 → `onPart`；catch 时 `console.warn` 并跳过。

- [ ] **Step 2: 修改 `ragChat.sendMessageStream`**

签名改为：

```typescript
async sendMessageStream(
  sessionId: number,
  question: string,
  onPart: (part: StreamEnvelope) => void,
  onComplete: () => void,
  onError: (error: Error) => void
): Promise<void>
```

内部调用 `parseDualChannelSse`。

- [ ] **Step 3: 修改 `knowledgebase.queryKnowledgeBaseStream`**

与 Step 2 同签名风格。

- [ ] **Step 4: 运行前端类型检查**

Run: `cd interview-platform-frontend && npx tsc --noEmit`

Expected: 无错误（若项目无该脚本，以 `npm run build` 成功为准）。

- [ ] **Step 5: Commit**

```bash
git add interview-platform-frontend/src/api/streamTypes.ts interview-platform-frontend/src/api/parseDualChannelSse.ts interview-platform-frontend/src/api/ragChat.ts interview-platform-frontend/src/api/knowledgebase.ts
git commit -m "feat(frontend): parse dual-channel JSON SSE for rag and kb streams"
```

---

## Task 7: `KnowledgeBaseQueryPage` UI

**Files:**
- Modify: `interview-platform-frontend/src/pages/KnowledgeBaseQueryPage.tsx`

- [ ] **Step 1: 扩展 `Message`**

```typescript
interface Message {
  id?: number;
  type: 'user' | 'assistant';
  content: string;
  reasoningText?: string;
  timestamp: Date;
}
```

- [ ] **Step 2: 更新 `handleSubmitQuestion` 内 `sendMessageStream` 回调**

维护 `let reasoning = ''`、`let fullContent = ''`；`onPart` 中若 `part.type === 'reasoning'` 则 `reasoning += part.delta`，否则 `fullContent += part.delta`；`updateAssistantMessage` 同时传入 `content` 与 `reasoningText`。

- [ ] **Step 3: 渲染 assistant 气泡**

当 `message.reasoningText` 非空时，在正文上方渲染 `<details>` 或带 `ChevronDown` 的可折叠面板（**默认折叠**），内部 `<pre className="whitespace-pre-wrap text-sm opacity-80">` 展示推理纯文本。

- [ ] **Step 4: 构建**

Run: `cd interview-platform-frontend && npm run build`

Expected: 成功

- [ ] **Step 5: Commit**

```bash
git add interview-platform-frontend/src/pages/KnowledgeBaseQueryPage.tsx
git commit -m "feat(ui): collapsible reasoning panel for assistant stream"
```

---

## Task 8: 回归与集成验证

- [ ] **Step 1: 全量单测**

Run: `cd interview-platform && mvn -q test`

Expected: BUILD SUCCESS

- [ ] **Step 2: 手动联调清单（本地）**

1. 启动后端 + 前端，打开知识库问答页，发送问题；确认正文仍正常流式、历史仅正文。  
2. 使用**带推理字段**的模型时，确认思考区折叠块出现；切换无推理模型时思考区不出现。  
3. 打开浏览器 Network，确认 SSE `data:` 行为 JSON 且最后为 `[DONE]`。

- [ ] **Step 3: Commit（若仅文档/脚本则跳过）**

无代码变更则无需 commit。

---

## Spec Coverage（自检）

| Spec 章节 | 对应任务 |
|-----------|----------|
| §3 JSON 信封 `type`/`delta` | Task 1、6 |
| §3.3 `[DONE]` | Task 4、5、6 |
| §4.2 `chatResponse` 全量 delta | Task 2、3 |
| §4.3 仅 content 落库 | Task 4 |
| §4.4 解析失败 | Task 6（前端跳过） |
| §5 前端分区 / 无推理退化 | Task 7 |
| §7 测试要点 | Task 2、8 |

## Placeholder Scan

本计划不含 “TBD/TODO/稍后实现”；错误与解析策略均已固定为「开发期序列化用 Jackson」「前端 JSON 失败跳过该行」。

## Type / Name Consistency

- Java：`StreamPart.TYPE_CONTENT`、`TYPE_REASONING` 与 JSON 字符串一致。  
- TS：`StreamEnvelope.type` 联合类型 `'reasoning' | 'content'` 与后端一致。  
- `completeStreamMessage` 始终接收 **仅正文** 拼接串。

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-09-native-reasoning-dual-sse-implementation-plan.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — 每个 Task 派生子代理执行，任务间人工复核，迭代快  

**2. Inline Execution** — 本会话按 Task 顺序执行，配合 executing-plans 的检查点批量推进  

**Which approach?**
