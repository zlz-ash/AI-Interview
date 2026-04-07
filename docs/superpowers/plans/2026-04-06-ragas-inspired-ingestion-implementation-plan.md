# Ragas-Inspired Ingestion Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有“上传即固定切分并向量化”链路升级为 Parse/Split/Enrich/Store/Validate 五阶段流水线，并按要求执行“旧文档+旧向量全删重建”。  

**Architecture:** 在现有 `KnowledgeBaseUploadService -> VectorizeStreamProducer/Consumer -> KnowledgeBaseVectorService` 基础上新增文档类型路由、切分策略与 enrich 抽取层。向量化入库从“字符串内容”升级为“结构化 chunk+metadata”写入，保证可追踪和可重试。新增批次删除与重建流程，支持审计清单和失败重试。  

**Tech Stack:** Java 21, Spring Boot 4, Spring AI pgvector, PostgreSQL, Redisson Stream, JUnit 5, Mockito, Spring MVC Test

---

## Scope Check

本计划只覆盖一个子系统（知识库入库流水线与旧数据替换），没有拆分为多个独立子项目的必要。

## File Structure Map

**Create**

- `interview-platform/src/main/java/com/ash/springai/interview_platform/enums/DocumentType.java`  
文档类型枚举（`MARKDOWN_TEXT/EXCEL_TABLE/PDF_LONGFORM`）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/IngestChunkDTO.java`  
统一 chunk 结构（文本、序号、metadata、token 估算）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/DocumentTypeRouterService.java`  
文档类型路由服务。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkSplitService.java`  
类型化切分总服务。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/MarkdownChunkSplitter.java`
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/ExcelChunkSplitter.java`
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/PdfChunkSplitter.java`
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkEnrichService.java`  
并行 enrich（关键词/实体/结构字段）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/LegacyKnowledgeBaseCleanupService.java`  
旧文档+向量全删与审计。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/LegacyCleanupResultDTO.java`  
批次删除结果与失败项。

**Modify**

- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/KnowledgeBaseEntity.java`  
增加 `documentType`, `ingestVersion`, `ingestStatus` 字段。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamProducer.java`  
消息体从 `content` 扩展为 `storageKey/originalFilename/documentType/ingestVersion`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumer.java`  
消费后执行 Parse/Split/Enrich/Store/Validate 流程。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadService.java`  
上传后只入队必要信息，不直接传整文内容。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseVectorService.java`  
新增 `vectorizeAndStoreChunks(Long kbId, List<IngestChunkDTO> chunks)`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorRepository.java`  
增加“按 kb 批量统计/清理”辅助查询。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`  
增加“批次清理旧知识库”接口。
- `interview-platform/src/main/resources/application.properties`  
新增切分阈值、重叠、批次清理配置。

**Test**

- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/DocumentTypeRouterServiceTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkEnrichServiceTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/service/LegacyKnowledgeBaseCleanupServiceTests.java`
- `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseCleanupApiTests.java`

---

### Task 1: 建立入库领域模型（DocumentType + IngestChunkDTO + Entity 字段）

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/enums/DocumentType.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/IngestChunkDTO.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/KnowledgeBaseEntity.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/DocumentTypeRouterServiceTests.java`
- **Step 1: 写失败测试（KnowledgeBaseEntity 包含新字段）**

```java
@Test
void shouldPersistDocumentTypeAndIngestVersion() {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setDocumentType(DocumentType.EXCEL_TABLE);
    kb.setIngestVersion("v2");
    kb.setIngestStatus("PENDING");
    assertEquals(DocumentType.EXCEL_TABLE, kb.getDocumentType());
    assertEquals("v2", kb.getIngestVersion());
    assertEquals("PENDING", kb.getIngestStatus());
}
```

- **Step 2: 运行测试确认失败**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=DocumentTypeRouterServiceTests#shouldPersistDocumentTypeAndIngestVersion" test`  
Expected: FAIL，提示 `KnowledgeBaseEntity` 缺少字段或枚举不存在。

- **Step 3: 最小实现领域模型**

```java
public enum DocumentType {
    MARKDOWN_TEXT,
    EXCEL_TABLE,
    PDF_LONGFORM
}
```

```java
public record IngestChunkDTO(
    int chunkIndex,
    String content,
    int tokenEstimate,
    Map<String, Object> metadata
) {}
```

```java
@Enumerated(EnumType.STRING)
@Column(length = 32)
private DocumentType documentType;

@Column(length = 32)
private String ingestVersion;

@Column(length = 32)
private String ingestStatus;
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=DocumentTypeRouterServiceTests#shouldPersistDocumentTypeAndIngestVersion" test`  
Expected: PASS。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/enums/DocumentType.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/IngestChunkDTO.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/KnowledgeBaseEntity.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/DocumentTypeRouterServiceTests.java
git commit -m "feat: add ingestion domain model for typed chunk pipeline"
```

### Task 2: 实现文档类型路由与类型化切分策略

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/DocumentTypeRouterService.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkSplitService.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/MarkdownChunkSplitter.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/ExcelChunkSplitter.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/PdfChunkSplitter.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java`
- **Step 1: 写失败测试（Excel 走 EXCEL_TABLE 路由）**

```java
@Test
void shouldRouteExcelToExcelSplitter() {
    DocumentType type = router.route("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "demo.xlsx", "header,a,b");
    assertEquals(DocumentType.EXCEL_TABLE, type);
}
```

- **Step 2: 运行测试确认失败**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=ChunkSplitServiceTests#shouldRouteExcelToExcelSplitter" test`  
Expected: FAIL，提示 `DocumentTypeRouterService` 未实现。

- **Step 3: 最小实现路由和分割器**

```java
public DocumentType route(String contentType, String filename, String content) {
    String lower = filename == null ? "" : filename.toLowerCase();
    if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return DocumentType.EXCEL_TABLE;
    if (lower.endsWith(".pdf")) return DocumentType.PDF_LONGFORM;
    return DocumentType.MARKDOWN_TEXT;
}
```

```java
public List<IngestChunkDTO> split(DocumentType type, String content) {
    return switch (type) {
        case EXCEL_TABLE -> excelSplitter.split(content);
        case PDF_LONGFORM -> pdfSplitter.split(content);
        default -> markdownSplitter.split(content);
    };
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=ChunkSplitServiceTests" test`  
Expected: PASS，覆盖 markdown/excel/pdf 的路由和长度约束。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/DocumentTypeRouterService.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkSplitService.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/MarkdownChunkSplitter.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/ExcelChunkSplitter.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/PdfChunkSplitter.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java
git commit -m "feat: add typed splitter pipeline for markdown excel and pdf"
```

### Task 3: 实现并行 enrich 与 metadata 合成

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkEnrichService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/IngestChunkDTO.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkEnrichServiceTests.java`
- **Step 1: 写失败测试（enrich 后包含 keywords/entities/qualityFlags）**

```java
@Test
void shouldEnrichChunkWithKeywordsEntitiesAndQualityFlags() {
    IngestChunkDTO raw = new IngestChunkDTO(1, "Spring Boot 事务传播 REQUIRES_NEW", 24, new HashMap<>());
    IngestChunkDTO enriched = service.enrich(raw, DocumentType.MARKDOWN_TEXT, "kb-1", "doc-1");
    assertTrue(enriched.metadata().containsKey("keywords"));
    assertTrue(enriched.metadata().containsKey("entities"));
    assertTrue(enriched.metadata().containsKey("quality_flags"));
}
```

- **Step 2: 运行测试确认失败**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=ChunkEnrichServiceTests#shouldEnrichChunkWithKeywordsEntitiesAndQualityFlags" test`  
Expected: FAIL，`ChunkEnrichService` 或字段缺失。

- **Step 3: 最小实现 enrich**

```java
public IngestChunkDTO enrich(IngestChunkDTO chunk, DocumentType type, String kbId, String docId) {
    Map<String, Object> meta = new HashMap<>(chunk.metadata());
    meta.put("kb_id", kbId);
    meta.put("doc_id", docId);
    meta.put("doc_type", type.name());
    meta.put("keywords", extractKeywords(chunk.content()));
    meta.put("entities", extractEntities(chunk.content()));
    meta.put("quality_flags", evaluateQualityFlags(chunk.content()));
    return new IngestChunkDTO(chunk.chunkIndex(), chunk.content(), chunk.tokenEstimate(), meta);
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=ChunkEnrichServiceTests" test`  
Expected: PASS。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkEnrichService.java interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/IngestChunkDTO.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkEnrichServiceTests.java
git commit -m "feat: add chunk enrich service with retrieval metadata"
```

### Task 4: 改造 Stream 消费端为五阶段流水线

**Files:**

- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamProducer.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumer.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseVectorService.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java`
- **Step 1: 写失败测试（Consumer 调用 Parse/Split/Enrich/Store）**

```java
@Test
void shouldRunFiveStagePipelineInConsumer() {
    Map<String, String> message = Map.of("kb_id", "1", "storage_key", "kb/1/demo.pdf", "original_filename", "demo.pdf");
    consumer.handleMessage(mockMessageId(), message);
    verify(parseService).downloadAndParseContent("kb/1/demo.pdf", "demo.pdf");
    verify(splitService).split(any(), anyString());
    verify(enrichService, atLeastOnce()).enrich(any(), any(), anyString(), anyString());
    verify(vectorService).vectorizeAndStoreChunks(eq(1L), anyList());
}
```

- **Step 2: 运行测试确认失败**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=VectorizeStreamConsumerPipelineTests#shouldRunFiveStagePipelineInConsumer" test`  
Expected: FAIL，消息体字段或方法不存在。

- **Step 3: 最小实现链路改造**

```java
record VectorizePayload(Long kbId, String storageKey, String originalFilename, String contentType, String ingestVersion) {}
```

```java
String content = parseService.downloadAndParseContent(payload.storageKey(), payload.originalFilename());
DocumentType type = router.route(payload.contentType(), payload.originalFilename(), content);
List<IngestChunkDTO> chunks = splitService.split(type, content);
List<IngestChunkDTO> enriched = enrichInParallel(chunks, type, payload.kbId());
vectorService.vectorizeAndStoreChunks(payload.kbId(), enriched);
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=VectorizeStreamConsumerPipelineTests" test`  
Expected: PASS。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamProducer.java interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumer.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadService.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseVectorService.java interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java
git commit -m "feat: refactor vectorization stream into parse split enrich store validate pipeline"
```

### Task 5: 实现“旧文档+旧向量全删”批次清理与审计

**Files:**

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/LegacyCleanupResultDTO.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/LegacyKnowledgeBaseCleanupService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorRepository.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/LegacyKnowledgeBaseCleanupServiceTests.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseCleanupApiTests.java`
- **Step 1: 写失败测试（批次删除返回清单）**

```java
@Test
void shouldDeleteLegacyDocsAndVectorsInBatch() {
    LegacyCleanupResultDTO result = cleanupService.cleanupLegacyKnowledgeBases(20, "ash");
    assertTrue(result.deletedKnowledgeBaseIds().size() <= 20);
    assertNotNull(result.auditRecords());
}
```

- **Step 2: 运行测试确认失败**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=LegacyKnowledgeBaseCleanupServiceTests#shouldDeleteLegacyDocsAndVectorsInBatch" test`  
Expected: FAIL，清理服务或 DTO 不存在。

- **Step 3: 最小实现清理服务与接口**

```java
@PostMapping("/api/knowledgebase/legacy/cleanup")
public Result<LegacyCleanupResultDTO> cleanupLegacy(
    @RequestParam(defaultValue = "50") int batchSize,
    @RequestParam String operator
) {
    return Result.success(cleanupService.cleanupLegacyKnowledgeBases(batchSize, operator));
}
```

```java
for (KnowledgeBaseEntity kb : batch) {
    vectorRepository.deleteByKnowledgeBaseId(kb.getId());
    fileStorageService.deleteFile(kb.getStorageKey());
    knowledgeBaseRepository.delete(kb);
    audits.add("kb=" + kb.getId() + ",name=" + kb.getName() + ",operator=" + operator);
}
```

- **Step 4: 运行测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=LegacyKnowledgeBaseCleanupServiceTests,KnowledgeBaseCleanupApiTests" test`  
Expected: PASS。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/LegacyCleanupResultDTO.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/LegacyKnowledgeBaseCleanupService.java interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorRepository.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/LegacyKnowledgeBaseCleanupServiceTests.java interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseCleanupApiTests.java
git commit -m "feat: add batch legacy cleanup for documents and vectors with audit records"
```

### Task 6: 配置、回归与操作文档收口

**Files:**

- Modify: `interview-platform/src/main/resources/application.properties`
- Modify: `docs/superpowers/specs/2026-04-06-ragas-inspired-ingestion-design.md`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java`
- **Step 1: 写失败测试（配置值影响分割行为）**

```java
@Test
void shouldUseConfiguredChunkTargetForMarkdownSplitter() {
    List<IngestChunkDTO> chunks = splitter.split("## A\n" + "x ".repeat(1400));
    assertTrue(chunks.stream().allMatch(c -> c.tokenEstimate() <= 560));
}
```

- **Step 2: 运行测试确认失败**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=ChunkSplitServiceTests#shouldUseConfiguredChunkTargetForMarkdownSplitter" test`  
Expected: FAIL，配置尚未接入 splitter。

- **Step 3: 最小实现配置与运行文档更新**

```properties
app.ingest.version=v2
app.ingest.markdown.target-min-tokens=350
app.ingest.markdown.target-max-tokens=550
app.ingest.excel.target-min-tokens=200
app.ingest.excel.target-max-tokens=400
app.ingest.pdf.target-min-tokens=400
app.ingest.pdf.target-max-tokens=650
app.ingest.cleanup.default-batch-size=50
```

```markdown
### 直接替换操作步骤
1. 调用 `/api/knowledgebase/legacy/cleanup?batchSize=50&operator=ash`
2. 重新上传知识库触发 v2 入库流水线
3. 检查 `vectorStatus=COMPLETED` 与 chunk 浏览接口
```

- **Step 4: 运行回归测试确认通过**

Run: `cd interview-platform; .\mvnw.cmd "-Dtest=DocumentTypeRouterServiceTests,ChunkSplitServiceTests,ChunkEnrichServiceTests,VectorizeStreamConsumerPipelineTests,LegacyKnowledgeBaseCleanupServiceTests,KnowledgeBaseCleanupApiTests" test`  
Expected: PASS，所有新增/改造测试通过。

- **Step 5: 提交本任务**

```bash
git add interview-platform/src/main/resources/application.properties docs/superpowers/specs/2026-04-06-ragas-inspired-ingestion-design.md interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java
git commit -m "chore: configure v2 ingestion pipeline defaults and runbook"
```

---

## Final Verification Checklist

- 上传后异步消费执行五阶段流水线，状态从 `PENDING -> PROCESSING -> COMPLETED/FAILED` 正常流转。
- 三类文档（markdown/excel/pdf）都走到对应 splitter，chunk metadata 完整。
- enrich 字段在 chunk 中可见：`keywords/entities/doc_type/content_hash`。
- 批次清理接口可删除“旧文档+旧向量”，并返回审计清单。
- 清理失败项可重试，失败原因可追踪。
- 新配置项可覆盖默认切分阈值，回归测试通过。

## Self-Review (spec -> plan)

- Spec coverage:
  - 五阶段流水线：Task 4
  - 三类切分策略：Task 2
  - enrich 与 metadata：Task 3
  - 全删重建策略：Task 5
  - 无测评执行标准与配置：Task 6
- Placeholder scan: 已检查，无占位型执行语句。
- Type consistency: `DocumentType/IngestChunkDTO/LegacyCleanupResultDTO` 在各任务命名一致，接口字段一致。

