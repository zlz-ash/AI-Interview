# Hybrid Retrieval Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有知识库后端中落地“文档切片浏览 + FTS/向量混合检索 + 模型优先/规则兜底重排 + 阈值自适应放宽”的可测试实现。  

**Architecture:** 新增一个专用文档切片浏览链路（Mode A），与现有问答检索链路解耦；问答链路拆分为“关键词检索（Mode B）”和“混合检索（Mode C）”两种执行分支，统一输出标准化命中与元信息。通过可配置策略实现重排和阈值放宽，并保持与当前 `QueryResponse` 的向后兼容。  

**Tech Stack:** Java 21, Spring Boot, Spring MVC, Spring AI (VectorStore), PostgreSQL (pgvector + FTS), JdbcTemplate, JUnit 5, Mockito, MockMvc

---

## Scope Check

本计划只覆盖一个后端子系统（知识库查询链路升级），不包含前端实现与模型服务部署，范围可在单计划内闭环。

## File Structure Map

**Create**

- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/DocumentChunksResponse.java`  
文档切片浏览响应 DTO（列表+详情+统计）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/ChunkItemDTO.java`  
切片条目 DTO（`chunkId/chunkIndex/preview/content/...`）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/HybridHitDTO.java`  
混合检索标准命中 DTO。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/HybridMetaDTO.java`  
检索元信息 DTO（轮次、阈值、权重、低置信标记）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorChunkBrowseRepository.java`  
文档切片明细查询（`vector_store`）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/FtsSearchRepository.java`  
PostgreSQL FTS 检索与回退查询。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseService.java`  
Mode A 业务编排。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/QueryIntentClassifier.java`  
语义/关键词倾向判别。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RuleRerankService.java`  
规则重排实现。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicy.java`  
阈值放宽策略实现。

**Modify**

- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryResponse.java`  
增加 `hits/meta/mode`，保留 `answer` 兼容字段。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`  
新增文档切片浏览接口；查询接口返回增强结构。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`  
接入 FTS、动态融合、重排和阈值放宽。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/exception/ErrorCode.java`  
增加文档切片相关错误码。
- `interview-platform/src/main/resources/application.properties`  
增加检索策略配置项。

**Test**

- `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseServiceTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicyTests.java`

---

### Task 1: 搭建返回契约与错误码基础

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/HybridHitDTO.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/HybridMetaDTO.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryResponse.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/exception/ErrorCode.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java`
- **Step 1: 先写失败测试（新增字段可序列化）**

```java
@Test
void shouldSerializeQueryResponseWithHitsAndMeta() {
    QueryResponse response = QueryResponse.withSearch(
        "ok",
        1L,
        "示例文档",
        "QUERY_WITH_EMBEDDING",
        List.of(new HybridHitDTO("c-1", 1L, "vector", 0.91, "命中片段", "向量高相似")),
        new HybridMetaDTO(1, 0.30, 0.12, 0.75, 0.25, false)
    );
    assertEquals("QUERY_WITH_EMBEDDING", response.mode());
    assertEquals(1, response.hits().size());
    assertFalse(response.meta().lowConfidence());
}
```

- **Step 2: 运行测试并确认失败**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseQueryServiceHybridTests#shouldSerializeQueryResponseWithHitsAndMeta test`  
Expected: FAIL，提示 `QueryResponse` 缺少 `mode/hits/meta` 或工厂方法。

- **Step 3: 最小实现 DTO 与 QueryResponse 扩展**

```java
public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String mode,
    List<HybridHitDTO> hits,
    HybridMetaDTO meta
) {
    public QueryResponse(String answer, Long knowledgeBaseId, String knowledgeBaseName) {
        this(answer, knowledgeBaseId, knowledgeBaseName, null, List.of(), null);
    }

    public static QueryResponse withSearch(
        String answer,
        Long knowledgeBaseId,
        String knowledgeBaseName,
        String mode,
        List<HybridHitDTO> hits,
        HybridMetaDTO meta
    ) {
        return new QueryResponse(answer, knowledgeBaseId, knowledgeBaseName, mode, hits, meta);
    }
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseQueryServiceHybridTests#shouldSerializeQueryResponseWithHitsAndMeta test`  
Expected: PASS，测试显示 `BUILD SUCCESS`。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/QueryResponse.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/HybridHitDTO.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/HybridMetaDTO.java interview-platform/src/main/java/com/ash/springai/interview_platform/exception/ErrorCode.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java
git commit -m "feat: add hybrid query response contract and metadata"
```

### Task 2: 实现 Mode A（文档切片浏览）后端链路

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/ChunkItemDTO.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/DocumentChunksResponse.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorChunkBrowseRepository.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java`
- **Step 1: 先写失败测试（点击文档名接口返回切片）**

```java
@Test
void shouldReturnDocumentChunksByDocumentId() throws Exception {
    when(chunkBrowseService.getDocumentChunks(1L, 1, 10, null))
        .thenReturn(sampleResponse());

    mockMvc.perform(get("/api/knowledgebase/documents/1/chunks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.mode").value("DOC_CHUNKS_DETAIL"))
        .andExpect(jsonPath("$.data.chunkList[0].chunkIndex").value(1));
}
```

- **Step 2: 运行测试并确认失败**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseControllerChunkApiTests#shouldReturnDocumentChunksByDocumentId test`  
Expected: FAIL，提示无对应路由或 `chunkBrowseService` 未注入。

- **Step 3: 最小实现仓库+服务+控制器接口**

```java
@GetMapping("/api/knowledgebase/documents/{documentId}/chunks")
public Result<DocumentChunksResponse> getDocumentChunks(
    @PathVariable Long documentId,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "10") int pageSize,
    @RequestParam(required = false) Long selectedChunkId
) {
    return Result.success(chunkBrowseService.getDocumentChunks(documentId, page, pageSize, selectedChunkId));
}
```

```java
public List<ChunkItemDTO> findChunksByDocumentId(Long documentId, int offset, int limit) {
    String sql = """
        SELECT id AS chunk_id, content, metadata
        FROM vector_store
        WHERE metadata->>'kb_id' = ?
        ORDER BY id ASC
        OFFSET ? LIMIT ?
        """;
    return jdbcTemplate.query(sql, rowMapper, documentId.toString(), offset, limit);
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseControllerChunkApiTests test`  
Expected: PASS，断言 `mode/chunkList/chunkDetail` 字段存在。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/ChunkItemDTO.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/DocumentChunksResponse.java interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorChunkBrowseRepository.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseService.java interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java
git commit -m "feat: add document chunk browsing API for mode A"
```

### Task 3: 落地 PostgreSQL FTS 检索（Mode B）

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/FtsSearchRepository.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java`
- **Step 1: 先写失败测试（Embedding 不可用时走 FTS）**

```java
@Test
void shouldFallbackToFtsWhenEmbeddingUnavailable() {
    when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
        .thenThrow(new RuntimeException("embedding unavailable"));
    when(ftsSearchRepository.search(anyString(), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(sampleFtsHit()));

    QueryResponse response = service.queryKnowledgeBase(new QueryRequest(List.of(1L), "Spring 事务传播"));

    assertEquals("QUERY_NO_EMBEDDING", response.mode());
    assertEquals("fts", response.hits().getFirst().source());
}
```

- **Step 2: 运行测试并确认失败**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseQueryServiceHybridTests#shouldFallbackToFtsWhenEmbeddingUnavailable test`  
Expected: FAIL，提示 `FtsSearchRepository` 未实现或 `mode` 不正确。

- **Step 3: 实现 FTS 查询与回退顺序**

```java
public List<HybridHitDTO> search(String question, List<Long> kbIds, int topK, double minRank) {
    List<HybridHitDTO> websearch = queryByTsQuery("websearch_to_tsquery('simple', ?)", question, kbIds, topK, minRank);
    if (!websearch.isEmpty()) return websearch;
    List<HybridHitDTO> plain = queryByTsQuery("plainto_tsquery('simple', ?)", question, kbIds, topK, minRank);
    if (!plain.isEmpty()) return plain;
    return queryByILike(question, kbIds, topK);
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseQueryServiceHybridTests#shouldFallbackToFtsWhenEmbeddingUnavailable test`  
Expected: PASS，命中 `QUERY_NO_EMBEDDING` 且来源为 `fts`。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/FtsSearchRepository.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java
git commit -m "feat: add postgres fts retrieval path for mode B"
```

### Task 4: 实现 Mode C 混合融合与规则重排

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/QueryIntentClassifier.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/RuleRerankService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java`
- **Step 1: 先写失败测试（偏语义问题提高向量权重）**

```java
@Test
void shouldUseSemanticHeavyWeightsForNaturalQuestion() {
    QueryIntent intent = classifier.classify("为什么 Spring 事务会失效");
    assertTrue(intent.semanticWeight() > intent.keywordWeight());
}
```

- **Step 2: 运行测试并确认失败**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseQueryServiceHybridTests#shouldUseSemanticHeavyWeightsForNaturalQuestion test`  
Expected: FAIL，提示 `QueryIntentClassifier` 或权重策略不存在。

- **Step 3: 最小实现意图判别+融合+规则重排**

```java
public QueryIntent classify(String question) {
    String q = question == null ? "" : question.trim();
    boolean natural = q.contains("为什么") || q.contains("怎么") || q.contains("如何");
    if (natural) return new QueryIntent(0.75, 0.25, "SEMANTIC");
    boolean keywordLike = q.matches(".*[A-Z_]{2,}.*") || q.matches(".*\\d+\\.\\d+.*");
    if (keywordLike) return new QueryIntent(0.30, 0.70, "KEYWORD");
    return new QueryIntent(0.55, 0.45, "BALANCED");
}
```

```java
public double score(HybridHitDTO hit, QueryIntent intent) {
    double vec = normalize(hit.vecScore());
    double fts = normalize(hit.ftsScore());
    double hybrid = intent.semanticWeight() * vec + intent.keywordWeight() * fts;
    return 0.70 * hybrid + 0.15 * hit.fieldBoost() + 0.05 * hit.freshness() + 0.10 * hit.exactMatchBoost();
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseQueryServiceHybridTests test`  
Expected: PASS，包含语义倾向与规则重排断言。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/QueryIntentClassifier.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/RuleRerankService.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java
git commit -m "feat: add hybrid fusion and rule reranker for mode C"
```

### Task 5: 实现阈值放宽策略与低置信标记

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicy.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java`
- Modify: `interview-platform/src/main/resources/application.properties`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicyTests.java`
- **Step 1: 先写失败测试（命中不足触发放宽）**

```java
@Test
void shouldRelaxThresholdWhenHitsNotEnough() {
    ThresholdRelaxationPolicy policy = new ThresholdRelaxationPolicy(5, 3, 0.12, 0.05);
    RelaxationState state = policy.initial(0.30, 0.12, 20, 20);
    RelaxationState next = policy.next(state, 2);
    assertEquals(0.26, next.vecMinScore(), 1e-6);
    assertEquals(0.10, next.ftsMinRank(), 1e-6);
}
```

- **Step 2: 运行测试并确认失败**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=ThresholdRelaxationPolicyTests#shouldRelaxThresholdWhenHitsNotEnough test`  
Expected: FAIL，提示策略类不存在或计算不匹配。

- **Step 3: 最小实现策略与配置接入**

```java
public RelaxationState next(RelaxationState current, int effectiveHits) {
    if (effectiveHits >= minEffectiveHits || current.round() >= maxRounds) return current.stop(true);
    double vec = Math.max(current.vecMinScore() - 0.04, vecFloor);
    double fts = Math.max(current.ftsMinRank() - 0.02, ftsFloor);
    return current.nextRound(vec, fts, current.topKVec() + 5, current.topKFts() + 5);
}
```

```properties
app.search.threshold.min-effective-hits=5
app.search.threshold.max-relax-rounds=3
app.search.threshold.vec-floor=0.12
app.search.threshold.fts-floor=0.05
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=ThresholdRelaxationPolicyTests test`  
Expected: PASS，且边界值断言通过。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicy.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryService.java interview-platform/src/main/resources/application.properties interview-platform/src/test/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicyTests.java
git commit -m "feat: add adaptive threshold relaxation for retrieval"
```

### Task 6: 控制器整合与回归验证

**Files:**

- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java`
- **Step 1: 先写失败测试（查询接口返回增强结构且兼容 answer）**

```java
@Test
void shouldReturnAnswerAndHybridMetaFromQueryApi() throws Exception {
    when(queryService.queryKnowledgeBase(any())).thenReturn(sampleQueryResponse());
    mockMvc.perform(post("/api/knowledgebase/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"knowledgeBaseIds\":[1],\"question\":\"Spring事务\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.answer").isNotEmpty())
        .andExpect(jsonPath("$.data.mode").value("QUERY_WITH_EMBEDDING"))
        .andExpect(jsonPath("$.data.meta.rounds").value(1));
}
```

- **Step 2: 运行测试并确认失败**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseControllerChunkApiTests,KnowledgeBaseQueryServiceHybridTests test`  
Expected: FAIL，控制器响应字段或 mock 结构不一致。

- **Step 3: 最小实现控制器接线与返回统一**

```java
@PostMapping("/api/knowledgebase/query")
@RateLimit(dimensions = {RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP}, count = 10)
public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
    QueryResponse response = queryService.queryKnowledgeBase(request);
    return Result.success(response);
}
```

- **Step 4: 运行全量相关测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd -Dtest=KnowledgeBaseControllerChunkApiTests,KnowledgeBaseChunkBrowseServiceTests,KnowledgeBaseQueryServiceHybridTests,ThresholdRelaxationPolicyTests test`  
Expected: PASS，输出 `BUILD SUCCESS`。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseServiceTests.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseQueryServiceHybridTests.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/ThresholdRelaxationPolicyTests.java
git commit -m "test: add controller and service regression coverage for hybrid retrieval"
```

---

## Final Verification Checklist

- `GET /api/knowledgebase/documents/{documentId}/chunks` 返回 `DOC_CHUNKS_DETAIL`、`chunkList`、`chunkDetail`、分页统计。
- `POST /api/knowledgebase/query` 在 Embedding 异常时稳定走 Mode B（FTS）。
- Embedding 可用时走 Mode C（向量+FTS 融合）并输出 `hits/meta`。
- 重排策略在无模型配置时走规则重排，分数可解释。
- 命中不足时触发阈值放宽，并在不足场景标记 `lowConfidence=true`。
- 现有 `answer/knowledgeBaseId/knowledgeBaseName` 字段未破坏。

## Self-Review (spec -> plan)

- Spec coverage:  
  - Mode A 文档切片浏览：Task 2 + Task 6  
  - FTS 策略（websearch -> plainto -> ILIKE）：Task 3  
  - 融合权重与规则重排：Task 4  
  - 阈值放宽与低置信：Task 5  
  - 前端友好结构与兼容：Task 1 + Task 6
- Placeholder scan: 未使用 `TODO/TBD/implement later` 等占位语。  
- Type consistency: `QueryResponse` 扩展字段与测试断言一致；`HybridHitDTO/HybridMetaDTO` 在任务间命名保持一致。

