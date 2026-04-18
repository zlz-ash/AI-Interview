# RAG 会话检索模式 + 仅流式问答 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在仅保留流式问答的前提下，为 RAG 会话增加「混合检索 / 向量检索」两种会话级策略；删除同步 `POST /api/knowledgebase/query` 与无会话的 `POST /api/knowledgebase/query/stream`；不修改 `interview-platform-frontend` 源码。

**Architecture:** `rag_chat_sessions` 持久化 `retrieval_mode`（`HYBRID` | `VECTOR`）。`RagChatSessionService.getStreamAnswer` 将会话模式传入 `KnowledgeBaseQueryService.answerQuestionStream`。向量模式沿用现有 `retrieveRelevantDocs`；混合模式复用原 `queryKnowledgeBase` 中的向量+FTS+合并/重排+阈值松弛循环，对最终命中的 `chunkId` 列表用 `vector_store` 批量加载**完整 `content`** 构造 `List<Document>`，再与向量模式共用同一套提示词与流式 LLM（含 `normalizeStreamParts`）。`KnowledgeBaseController` 不再依赖 `KnowledgeBaseQueryService`（仅 RAG 流式入口保留问答能力）。

**Tech Stack:** Java 21、Spring Boot 4、Spring AI `Document`/`ChatClient`、JPA（`ddl-auto=update`）、JUnit 5、Mockito、Maven。

**前置说明（与 brainstorming 一致）:** 若使用独立 git worktree，请在 worktree 中执行本计划；否则在仓库根目录 `d:\springAI智能面试平台` 下执行。

---

## 文件结构（新建 / 修改职责）

| 路径 | 职责 |
|------|------|
| `interview-platform/src/main/java/com/ash/springai/interview_platform/enums/RetrievalMode.java` | `HYBRID`、`VECTOR` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorStoreChunkContentRepository.java`（或同级命名） | 按 `chunk_id` 列表从 `vector_store` 批量读取 `content`+`metadata`，按调用方给定顺序还原为 `List<Document>` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/RagChatSessionEntity.java` | 字段 `retrievalMode` + `@PostLoad` 空值兼容 |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/RagChatDTO.java` | `CreateSessionRequest` 可选 `RetrievalMode`；`SessionDTO` / `SessionDetailDTO` 增加 `retrievalMode`（JSON 兼容）；新增 `UpdateRetrievalModeRequest` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/mapper/RagChatMapper.java` | 映射 `retrievalMode` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java` | 创建/更新会话模式；`getStreamAnswer` 传入 `session.getRetrievalMode()` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java` | `answerQuestionStream(..., RetrievalMode)`；抽取 `retrieveHybridDocumentsForPrompt`；删除 `queryKnowledgeBase`、`answerQuestion`（若不再被引用） |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java` | 删除 `query` 与 `query/stream` 两个方法；删除对 `KnowledgeBaseQueryService` 的依赖 |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/RagChatController.java` | 可选：`PUT .../retrieval-mode` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/config/SseStreamingHeadersFilter.java` | SSE 路径仅保留 `/messages/stream` |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryRequest.java` | 若全仓库无引用则删除 |
| `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryResponse.java` | 若仅测试使用，可保留或合并进测试数据构造 |
| `AGENT_ARCHITECTURE_AND_API.md` | 更新 API 表 |

---

### Task 1: 引入 `RetrievalMode` 与会话字段

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/enums/RetrievalMode.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/RagChatSessionEntity.java`

- [ ] **Step 1: 新建枚举**

```java
package com.ash.springai.interview_platform.enums;

public enum RetrievalMode {
    HYBRID,
    VECTOR
}
```

- [ ] **Step 2: 在 `RagChatSessionEntity` 增加字段与兼容加载**

在 `isPinned` 字段附近增加：

```java
import com.ash.springai.interview_platform.enums.RetrievalMode;

@Enumerated(EnumType.STRING)
@Column(length = 16)
private RetrievalMode retrievalMode = RetrievalMode.HYBRID;
```

在 `onLoad()` 中、`isPinned` 处理之后增加：

```java
if (retrievalMode == null) {
    retrievalMode = RetrievalMode.HYBRID;
}
```

- [ ] **Step 3: 编译**

Run: `cd interview-platform && mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/enums/RetrievalMode.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/RagChatSessionEntity.java
git commit -m "feat(rag): add RetrievalMode enum and session column mapping"
```

---

### Task 2: 批量加载 chunk 全文（混合检索喂给 LLM）

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorStoreChunkContentRepository.java`

- [ ] **Step 1: 新建 Repository（完整可编译实现）**

```java
package com.ash.springai.interview_platform.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class VectorStoreChunkContentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 按 chunkId 顺序返回 Document；某 id 在库中不存在则跳过该条。
     */
    public List<Document> loadDocumentsInOrder(List<String> chunkIdsOrdered) {
        if (chunkIdsOrdered == null || chunkIdsOrdered.isEmpty()) {
            return List.of();
        }
        String inClause = String.join(",", java.util.Collections.nCopies(chunkIdsOrdered.size(), "?"));
        String sql = """
            SELECT id::text AS chunk_id, content, metadata::text AS metadata
            FROM vector_store
            WHERE id::text IN (""" + inClause + ")";
        Map<String, Document> byId = new LinkedHashMap<>();
        jdbcTemplate.query(sql, (ResultSet rs) -> {
            while (rs.next()) {
                String chunkId = rs.getString("chunk_id");
                String content = rs.getString("content");
                Map<String, Object> meta = parseMetadata(rs.getString("metadata"));
                byId.put(chunkId, new Document(content != null ? content : "", meta));
            }
            return null;
        }, chunkIdsOrdered.toArray());
        List<Document> out = new ArrayList<>();
        for (String id : chunkIdsOrdered) {
            Document d = byId.get(id);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
```

**注意：** `JdbcTemplate#query(String, ResultSetExtractor, Object...)` 的 lambda 参数为 `ResultSetExtractor<T>`，上例用 `(ResultSet rs) -> { ...; return null; }` 需写为显式 `ResultSetExtractor<Void>` 并在循环后 `return null;`。实现时以编译器为准。若 `metadata` 列类型导致 `getString` 异常，改用 `rs.getObject("metadata")` 再 `objectMapper.writeValueAsString` 或 `toString()` 后解析。

- [ ] **Step 2: 编译**

Run: `cd interview-platform && mvn -q -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorStoreChunkContentRepository.java
git commit -m "feat(rag): load full chunk content from vector_store by id order"
```

---

### Task 3: `KnowledgeBaseQueryService` — 按模式检索 + 删除同步问答 API 依赖链

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`（构造函数注入 `VectorStoreChunkContentRepository`；新增 `RetrievalMode` 分支）

**核心逻辑（实现要求，非占位）：**

1. 将现有 `public Flux<StreamPart> answerQuestionStream(List<Long> knowledgeBaseIds, String question)` 改为：

```java
public Flux<StreamPart> answerQuestionStream(List<Long> knowledgeBaseIds, String question, RetrievalMode mode) {
```

2. `RetrievalMode.VECTOR`：保留当前 `retrieveRelevantDocs` → `List<Document>` 路径不变。

3. `RetrievalMode.HYBRID`：把 **原** `queryKnowledgeBase` 方法里 `while (!relaxState.stop()) { ... }` 循环整体抽取为私有方法，例如：

```java
private List<HybridHitDTO> computeHybridHits(QueryRequest-like inputs)
```

但 `QueryRequest` 将删除，因此签名改为 `(List<Long> knowledgeBaseIds, String question)` 或内部先 `normalize` + `buildQueryContext`。

循环结束后得到 `List<HybridHitDTO> hybridHits`（重排后顺序）。从中提取 `chunkId` 列表（去重保序），调用 `VectorStoreChunkContentRepository.loadDocumentsInOrder(chunkIds)` 得到 `List<Document> relevantDocs`。

4. **删除** `public QueryResponse queryKnowledgeBase(QueryRequest request)` 与 **删除** `public String answerQuestion(...)`（确认无其他引用后删除；当前仅 `queryKnowledgeBase` 内部调用 `answerQuestion`）。

5. 混合模式下若向量不可用时的 FTS 回退逻辑保持与原 `queryKnowledgeBase` 一致；**不得**用 `HybridHitDTO.highlight`（截断/ts_headline）作为唯一上下文来源。

6. 从 `hybridHits` 提取 `chunkId` 时做 **保序去重**（例如 `LinkedHashSet`），再传入 `loadDocumentsInOrder`。

- [ ] **Step 1: 实现 `answerQuestionStream(List<Long>, String, RetrievalMode)`**

- `VECTOR`：调用现有私有方法 `retrieveRelevantDocs`（或等价逻辑）得到 `List<Document>`。

- `HYBRID`：调用抽取后的 `computeHybridHits(...)`（原 `queryKnowledgeBase` 循环体）→ `List<HybridHitDTO>` → `chunkIds` → `VectorStoreChunkContentRepository.loadDocumentsInOrder(chunkIds)`。

- 空问题 / 空 kbIds：保持现有早退，返回 `Flux.just(StreamPart.content(NO_RESULT_RESPONSE))`。

- [ ] **Step 2: 删除 `public QueryResponse queryKnowledgeBase` 与 `public String answerQuestion`**

- [ ] **Step 3: 运行测试**

Run: `cd interview-platform && mvn -q test`

Expected: 全部通过；修正 `KnowledgeBaseQueryServiceHybridTests`（仅保留不依赖已删 service 方法的断言，例如 `QueryResponse` 序列化与 `QueryIntentClassifier`）。

- [ ] **Step 4:（推荐）为 `VectorStoreChunkContentRepository` 增加轻量测试**

若本机/CI 无 Postgres，可跳过；若有，使用 `@SpringBootTest` + `@Autowired VectorStoreChunkContentRepository` + `@Sql` 插入一行 `vector_store` 后断言 `loadDocumentsInOrder` 返回全文。**禁止**提交带 `@Disabled` 且无说明的占位测试。

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java
git add interview-platform/src/test/java/com/ash/springai/interview_platform/service/
git commit -m "feat(rag): answerQuestionStream supports HYBRID and VECTOR; remove sync query paths"
```

---

### Task 4: `RagChatSessionService` 传入会话 `RetrievalMode`

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java`

- [ ] **Step 1: 修改 `getStreamAnswer`**

```java
import com.ash.springai.interview_platform.enums.RetrievalMode;

public Flux<StreamPart> getStreamAnswer(Long sessionId, String question) {
    RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

    List<Long> kbIds = session.getKnowledgeBaseIds();
    RetrievalMode mode = session.getRetrievalMode() != null ? session.getRetrievalMode() : RetrievalMode.HYBRID;

    return queryService.answerQuestionStream(kbIds, question, mode);
}
```

- [ ] **Step 2: 修改 `createSession` 接受可选模式**

在 `session.setKnowledgeBases` 之后、`save` 之前：

```java
if (request.retrievalMode() != null) {
    session.setRetrievalMode(request.retrievalMode());
}
```

（`CreateSessionRequest` 在 Task 5 中添加 `RetrievalMode retrievalMode` 字段，可为 `null`。）

- [ ] **Step 3: 运行测试**

Run: `cd interview-platform && mvn -q test`

- [ ] **Step 4: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java
git commit -m "feat(rag): pass session RetrievalMode into streaming answer"
```

---

### Task 5: DTO 与 MapStruct — 暴露 `retrievalMode`（向后兼容 JSON）

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/RagChatDTO.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/mapper/RagChatMapper.java`

- [ ] **Step 1: 扩展 record 字段**

在 `RagChatDTO.java` 顶部增加 import：`com.ash.springai.interview_platform.enums.RetrievalMode`。

`CreateSessionRequest` 增加最后一个参数（records 顺序注意编译处所有 call site）：

```java
public record CreateSessionRequest(
    @NotEmpty(message = "至少选择一个知识库")
    List<Long> knowledgeBaseIds,

    String title,

    RetrievalMode retrievalMode
) {}
```

**所有** 构造 `CreateSessionRequest` 的测试/代码需补 `null` 作为第三参数（仅 `title` 时第四参数为 `null` —— 以你最终字段顺序为准：建议顺序 `knowledgeBaseIds, title, retrievalMode`）。

`SessionDTO` 增加 `RetrievalMode retrievalMode`。

`SessionDetailDTO` 增加 `RetrievalMode retrievalMode`。

新增：

```java
public record UpdateRetrievalModeRequest(
    @jakarta.validation.constraints.NotNull(message = "retrievalMode 不能为空")
    RetrievalMode retrievalMode
) {}
```

- [ ] **Step 2: 更新 `RagChatMapper`**

`toSessionDTO`：增加 `@Mapping(target = "retrievalMode", source = "retrievalMode")`（若字段同名 MapStruct 自动映射可省略）。

`toSessionDetailDTO` 手动构建处增加 `session.getRetrievalMode()`。

- [ ] **Step 3: 编译**

Run: `cd interview-platform && mvn -q -DskipTests compile`

- [ ] **Step 4: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/RagChatDTO.java interview-platform/src/main/java/com/ash/springai/interview_platform/mapper/RagChatMapper.java
git commit -m "feat(rag): optional retrievalMode on create; expose on session DTOs"
```

---

### Task 6: 可选运维接口 — 更新会话检索模式（无前端依赖）

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/RagChatController.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java`

- [ ] **Step 1: Service 方法**

```java
@Transactional
public void updateRetrievalMode(Long sessionId, com.ash.springai.interview_platform.enums.RetrievalMode mode) {
    RagChatSessionEntity session = sessionRepository.findById(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
    session.setRetrievalMode(mode);
    sessionRepository.save(session);
}
```

- [ ] **Step 2: Controller**

```java
@PutMapping("/api/rag-chat/sessions/{sessionId}/retrieval-mode")
public Result<Void> updateRetrievalMode(
        @PathVariable Long sessionId,
        @Valid @RequestBody com.ash.springai.interview_platform.Entity.RagChatDTO.UpdateRetrievalModeRequest request) {
    sessionService.updateRetrievalMode(sessionId, request.retrievalMode());
    return Result.success(null);
}
```

（将 FQCN 改为 import 简化。）

- [ ] **Step 3: `mvn test`**

- [ ] **Step 4: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/controller/RagChatController.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/RagChatSessionService.java
git commit -m "feat(rag): PUT endpoint to update session retrieval mode"
```

---

### Task 7: `KnowledgeBaseController` 删除问答接口并移除多余依赖

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`

- [ ] **Step 1: 删除方法**

删除 `@PostMapping("/api/knowledgebase/query")` 与 `@PostMapping(value = "/api/knowledgebase/query/stream", ...)` 整个方法。

- [ ] **Step 2: 删除字段 `queryService` 与 `objectMapper`**

删除 `private final KnowledgeBaseQueryService queryService` 与 `private final ObjectMapper objectMapper`（二者仅被已删除的流式/同步问答方法使用）。删除相关 import。

- [ ] **Step 3: 修正依赖该构造函数的测试**

`interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java`：`@BeforeEach` 中删除 `queryService` mock；`new KnowledgeBaseController(uploadService, listService, deleteService, chunkBrowseService, legacyCleanupService)`（共 5 个依赖，无 `ObjectMapper`）。

- [ ] **Step 4: 编译与测试**

Run: `cd interview-platform && mvn -q test`

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java
git commit -m "refactor(kb): remove sync and sessionless stream query endpoints"
```

---

### Task 8: 清理 `QueryRequest`、收窄 SSE Filter、更新文档

**Files:**
- Delete（若 grep 无引用）: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryRequest.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/config/SseStreamingHeadersFilter.java`
- Modify: `AGENT_ARCHITECTURE_AND_API.md`

- [ ] **Step 1: 全仓库 grep `QueryRequest`**

Run: `rg "QueryRequest" interview-platform`

若仅删除文件即可，执行删除。

- [ ] **Step 2: 修改 `isSseStreamPath`**

```java
private static boolean isSseStreamPath(String uri) {
    return uri.contains("/messages/stream");
}
```

- [ ] **Step 3: 更新 `AGENT_ARCHITECTURE_AND_API.md`**

删除 `POST /api/knowledgebase/query` 与 `POST /api/knowledgebase/query/stream` 行；增加 `PUT /api/rag-chat/sessions/{id}/retrieval-mode`；在 RAG 会话创建处注明可选 `retrievalMode`。

- [ ] **Step 4: `mvn test`**

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/config/SseStreamingHeadersFilter.java AGENT_ARCHITECTURE_AND_API.md
git add -u interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryRequest.java
git commit -m "chore: drop QueryRequest; narrow SSE filter; update API doc"
```

---

## Spec 自检（计划作者执行）

| 需求 | 对应 Task |
|------|-----------|
| 仅流式、删除同步 query | Task 7 |
| 删除无会话 query/stream | Task 7 + Task 8 |
| 会话级 HYBRID / VECTOR | Task 1, 4, 5, 6 |
| 混合检索语义与原文档全文 | Task 2 + Task 3 |
| 不改前端 | 全计划仅 `interview-platform/**` 与根文档 |
| 运维可改模式（无 UI） | Task 6 |

**Placeholder 扫描：** 计划中任务均给出具体类名、方法签名与提交命令；若 Task 3 集成测试选择 Spring Boot 全量启动，须在实现时写明 `spring.main.lazy-initialization` 或现有 IT 基类用法，**禁止**留「之后写集成测试」类语句。

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-18-rag-session-retrieval-modes-stream-only.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — 每个 Task 派生子代理逐步实施，任务间人工复核，迭代快。

**2. Inline Execution** — 在本会话用 executing-plans 连续执行，检查点处暂停复核。

**Which approach?**
