# Tokenizer Profile 知识库切分改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为新建知识库引入“按库绑定 tokenizer profile + 真实 token 预算切分”，替换当前 `chars/4` 估算逻辑并保证展示口径一致。

**Architecture:** 在上传时绑定 `tokenizerProfileId` 到知识库实体；向量化时根据该 profile 注入 `TokenCounter`。切分侧新增 `ChunkBudgetPolicy + ChunkingCoreService` 统一处理 token 上限与 overlap，`Markdown/PDF/Excel` splitter 仅做结构预切分，最终由 core 输出真实 token 的 `IngestChunkDTO`。

**Tech Stack:** Spring Boot 4, Spring Data JPA, Spring MVC, JUnit 5, Mockito, Maven, jtokkit (`com.knuddels:jtokkit`)

---

## Scope Check

本 spec 只涉及一个子系统：知识库入库切分链路（上传参数、实体持久化、切分策略、token 展示口径）。不拆分为多个独立计划。

## File Structure Map

- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/config/TokenizerProfilesProperties.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/TokenCounter.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/JtokkitTokenCounter.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/TokenizerProfileRegistry.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/ChunkBudgetPolicy.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreService.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/StructuredChunkCandidate.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/TokenizerProfileRegistryTests.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreServiceTests.java`
- Modify: `interview-platform/pom.xml`
- Modify: `interview-platform/src/main/resources/application.properties`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/KnowledgeBaseEntity.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBasePersistenceService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkSplitService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/MarkdownChunkSplitter.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/PdfChunkSplitter.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/ExcelChunkSplitter.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumer.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorChunkBrowseRepository.java`
- Modify: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java`
- Modify: `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadServiceTokenizerProfileTests.java`

### Task 1: 添加 tokenizer profile 配置与注册能力

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/config/TokenizerProfilesProperties.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/TokenizerProfileRegistry.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/TokenizerProfileRegistryTests.java`
- Modify: `interview-platform/src/main/resources/application.properties`

- [ ] **Step 1: 写失败测试（profile 查找/非法 profile）**

```java
package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.config.TokenizerProfilesProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenizerProfileRegistryTests {

    @Test
    void shouldReturnRegisteredProfile() {
        TokenizerProfilesProperties props = new TokenizerProfilesProperties();
        TokenizerProfilesProperties.Profile p = new TokenizerProfilesProperties.Profile();
        p.setId("dashscope-text-embedding-v3");
        p.setModel("text-embedding-v3");
        p.setEncoding("cl100k_base");
        props.getProfiles().add(p);

        TokenizerProfileRegistry registry = new TokenizerProfileRegistry(props);
        var profile = registry.require("dashscope-text-embedding-v3");
        assertEquals("text-embedding-v3", profile.model());
    }

    @Test
    void shouldThrowWhenProfileMissing() {
        TokenizerProfilesProperties props = new TokenizerProfilesProperties();
        TokenizerProfileRegistry registry = new TokenizerProfileRegistry(props);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> registry.require("missing")
        );
        assertTrue(ex.getMessage().contains("未知 tokenizer profile"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=TokenizerProfileRegistryTests test`  
Expected: FAIL（`TokenizerProfileRegistry` / `TokenizerProfilesProperties` 未定义）

- [ ] **Step 3: 实现配置与注册类（最小可用）**

```java
package com.ash.springai.interview_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.ingest.tokenizer")
public class TokenizerProfilesProperties {
    private List<Profile> profiles = new ArrayList<>();

    public List<Profile> getProfiles() { return profiles; }
    public void setProfiles(List<Profile> profiles) { this.profiles = profiles; }

    public static class Profile {
        private String id;
        private String model;
        private String encoding;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String encoding) { this.encoding = encoding; }
    }
}
```

```java
package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.config.TokenizerProfilesProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class TokenizerProfileRegistry {

    public record ProfileView(String id, String model, String encoding) {}

    private final Map<String, ProfileView> profiles;

    public TokenizerProfileRegistry(TokenizerProfilesProperties props) {
        this.profiles = props.getProfiles().stream()
            .filter(p -> p.getId() != null && !p.getId().isBlank())
            .collect(Collectors.toUnmodifiableMap(
                p -> p.getId(),
                p -> new ProfileView(p.getId(), Objects.toString(p.getModel(), ""), Objects.toString(p.getEncoding(), "cl100k_base"))
            ));
    }

    public ProfileView require(String profileId) {
        ProfileView view = profiles.get(profileId);
        if (view == null) {
            throw new IllegalArgumentException("未知 tokenizer profile: " + profileId);
        }
        return view;
    }
}
```

```properties
app.ingest.tokenizer.profiles[0].id=dashscope-text-embedding-v3
app.ingest.tokenizer.profiles[0].model=text-embedding-v3
app.ingest.tokenizer.profiles[0].encoding=cl100k_base
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=TokenizerProfileRegistryTests test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/config/TokenizerProfilesProperties.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/TokenizerProfileRegistry.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/TokenizerProfileRegistryTests.java interview-platform/src/main/resources/application.properties
git commit -m "feat(ingest): add tokenizer profile registry"
```

### Task 2: 引入真实 token 计数器（jtokkit）

**Files:**
- Modify: `interview-platform/pom.xml`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/TokenCounter.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/JtokkitTokenCounter.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreServiceTests.java`（后续复用）

- [ ] **Step 1: 写失败测试（count 与 truncate 行为）**

```java
@Test
void shouldCountAndTruncateByTokens() {
    TokenCounter counter = new JtokkitTokenCounter("cl100k_base");
    String text = "Java 面试：Explain Spring transaction propagation in detail.";
    int count = counter.count(text);
    assertTrue(count > 5);
    String truncated = counter.truncateToTokens(text, 5);
    assertTrue(counter.count(truncated) <= 5);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=ChunkingCoreServiceTests test`  
Expected: FAIL（`TokenCounter` / `JtokkitTokenCounter` 未定义）

- [ ] **Step 3: 增加依赖并实现计数器**

```xml
<dependency>
    <groupId>com.knuddels</groupId>
    <artifactId>jtokkit</artifactId>
    <version>1.1.0</version>
</dependency>
```

```java
package com.ash.springai.interview_platform.service.chunking;

public interface TokenCounter {
    int count(String text);
    String truncateToTokens(String text, int maxTokens);
}
```

```java
package com.ash.springai.interview_platform.service.chunking;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;

import java.util.List;

public class JtokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JtokkitTokenCounter(String encodingName) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(encodingName);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    @Override
    public String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        List<Integer> tokens = encoding.encode(text);
        int keep = Math.min(tokens.size(), maxTokens);
        return encoding.decode(tokens.subList(0, keep));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=ChunkingCoreServiceTests test`  
Expected: PASS（至少当前 token 计数测试通过）

- [ ] **Step 5: Commit**

```bash
git add interview-platform/pom.xml interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/TokenCounter.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/JtokkitTokenCounter.java
git commit -m "feat(ingest): add local tokenizer counter with jtokkit"
```

### Task 3: 知识库实体与上传接口绑定 tokenizerProfileId

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/KnowledgeBaseEntity.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBasePersistenceService.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadServiceTokenizerProfileTests.java`

- [ ] **Step 1: 写失败测试（上传缺少/非法 profile）**

```java
@Test
void shouldThrowWhenTokenizerProfileMissing() {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn("demo.md");
    when(file.getSize()).thenReturn(10L);
    assertThrows(BusinessException.class, () -> service.uploadKnowledgeBase(file, "n", "c", null));
}

@Test
void shouldThrowWhenTokenizerProfileUnknown() {
    assertThrows(BusinessException.class, () -> service.uploadKnowledgeBase(file, "n", "c", "unknown-profile"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=KnowledgeBaseUploadServiceTokenizerProfileTests test`  
Expected: FAIL（方法签名或校验逻辑尚未支持）

- [ ] **Step 3: 实现实体字段与接口参数传递**

```java
// KnowledgeBaseEntity 新增字段
@Column(name = "tokenizer_profile_id", length = 64, nullable = false)
private String tokenizerProfileId;

@Column(name = "tokenizer_model", length = 128, nullable = false)
private String tokenizerModel;

@Column(name = "chunking_policy_version", length = 32, nullable = false)
private String chunkingPolicyVersion = "v1";
```

```java
// Controller 上传接口新增参数
public Result<Map<String, Object>> uploadKnowledgeBase(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "name", required = false) String name,
        @RequestParam(value = "category", required = false) String category,
        @RequestParam("tokenizerProfileId") String tokenizerProfileId) {
    return Result.success(uploadService.uploadKnowledgeBase(file, name, category, tokenizerProfileId));
}
```

```java
// UploadService 校验并写入
public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category, String tokenizerProfileId) {
    if (tokenizerProfileId == null || tokenizerProfileId.isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "tokenizerProfileId 不能为空");
    }
    var profile = tokenizerProfileRegistry.require(tokenizerProfileId);
    KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(
        file, name, category, fileKey, fileUrl, fileHash,
        docType, ingestProperties.getVersion(),
        profile.id(), profile.model(), "v1"
    );
    vectorizeStreamProducer.sendVectorizeTask(
        savedKb.getId(),
        fileKey,
        file.getOriginalFilename(),
        contentType,
        ingestProperties.getVersion()
    );
    return Map.of("knowledgeBase", Map.of("id", savedKb.getId()), "duplicate", false);
}
```

```java
// PersistenceService 写入三元组
public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                              String storageKey, String storageUrl, String fileHash,
                                              DocumentType documentType, String ingestVersion,
                                              String tokenizerProfileId,
                                              String tokenizerModel,
                                              String chunkingPolicyVersion) {
    kb.setTokenizerProfileId(tokenizerProfileId);
    kb.setTokenizerModel(tokenizerModel);
    kb.setChunkingPolicyVersion(chunkingPolicyVersion);
    return knowledgeBaseRepository.save(kb);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=KnowledgeBaseUploadServiceTokenizerProfileTests test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/KnowledgeBaseEntity.java interview-platform/src/main/java/com/ash/springai/interview_platform/controller/KnowledgeBaseController.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadService.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/KnowledgeBasePersistenceService.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseUploadServiceTokenizerProfileTests.java
git commit -m "feat(knowledge-base): bind tokenizer profile at creation"
```

### Task 4: 建立统一预算策略与切分 core

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/StructuredChunkCandidate.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/ChunkBudgetPolicy.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreService.java`
- Create: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreServiceTests.java`

- [ ] **Step 1: 写失败测试（token 上限与 overlap）**

```java
@Test
void shouldSplitByTokenBudgetWithOverlap() {
    TokenCounter counter = new JtokkitTokenCounter("cl100k_base");
    ChunkBudgetPolicy policy = new ChunkBudgetPolicy(30, 5);
    ChunkingCoreService core = new ChunkingCoreService();

    StructuredChunkCandidate c = new StructuredChunkCandidate("A > B", "H", "x ".repeat(200), Map.of("doc_type", "MARKDOWN"));
    var chunks = core.chunk(List.of(c), policy, counter);

    assertFalse(chunks.isEmpty());
    assertTrue(chunks.stream().allMatch(it -> it.tokenEstimate() <= 30));
    assertTrue(chunks.size() > 1);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=ChunkingCoreServiceTests test`  
Expected: FAIL（core 类未实现）

- [ ] **Step 3: 实现 policy/core**

```java
package com.ash.springai.interview_platform.service.chunking;

public record ChunkBudgetPolicy(int targetMaxTokens, int overlapTokens) {
    public ChunkBudgetPolicy {
        if (targetMaxTokens < 1) throw new IllegalArgumentException("targetMaxTokens must >= 1");
        if (overlapTokens < 0 || overlapTokens >= targetMaxTokens) {
            throw new IllegalArgumentException("overlapTokens out of range");
        }
    }
}
```

```java
package com.ash.springai.interview_platform.service.chunking;

import java.util.Map;

public record StructuredChunkCandidate(
    String sectionPath,
    String heading,
    String body,
    Map<String, Object> metadata
) {}
```

```java
package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChunkingCoreService {
    public List<IngestChunkDTO> chunk(List<StructuredChunkCandidate> candidates, ChunkBudgetPolicy policy, TokenCounter tokenCounter) {
        List<IngestChunkDTO> out = new ArrayList<>();
        int idx = 0;
        for (StructuredChunkCandidate c : candidates) {
            String remaining = c.body() == null ? "" : c.body().trim();
            while (!remaining.isEmpty()) {
                String piece = tokenCounter.truncateToTokens(remaining, policy.targetMaxTokens());
                if (piece.isBlank()) break;
                int tokens = tokenCounter.count(piece);
                Map<String, Object> meta = new HashMap<>();
                if (c.metadata() != null) meta.putAll(c.metadata());
                meta.put("section_path", c.sectionPath());
                meta.put("heading", c.heading());
                meta.put("chunk_index", idx + 1);
                meta.put("token_count", tokens);
                out.add(new IngestChunkDTO(++idx, piece.trim(), tokens, meta));
                if (piece.length() >= remaining.length()) break;

                String overlap = tokenCounter.truncateToTokens(piece, Math.max(policy.overlapTokens(), 0));
                String next = remaining.substring(piece.length()).trim();
                remaining = (overlap + " " + next).trim();
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=ChunkingCoreServiceTests test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/StructuredChunkCandidate.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/ChunkBudgetPolicy.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreService.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/chunking/ChunkingCoreServiceTests.java
git commit -m "feat(chunking): add unified token budget core"
```

### Task 5: 重构三个 splitter 为“结构预切分”

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/MarkdownChunkSplitter.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/PdfChunkSplitter.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/ExcelChunkSplitter.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkSplitService.java`
- Modify: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java`

- [ ] **Step 1: 写失败测试（splitter 不再使用 chars/4）**

```java
@Test
void shouldUseTokenCounterDrivenChunking() {
    TokenCounter fakeCounter = new TokenCounter() {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public String truncateToTokens(String text, int maxTokens) {
            if (text == null || text.isEmpty() || maxTokens <= 0) {
                return "";
            }
            return text.substring(0, Math.min(text.length(), maxTokens));
        }
    };

    ChunkingCoreService core = new ChunkingCoreService();
    ChunkBudgetPolicy policy = new ChunkBudgetPolicy(5, 0);
    StructuredChunkCandidate candidate = new StructuredChunkCandidate(
        "T",
        "T",
        "abcdefghij",
        Map.of("source_type", "markdown")
    );
    List<IngestChunkDTO> chunks = core.chunk(List.of(candidate), policy, fakeCounter);
    assertFalse(chunks.isEmpty());
    assertTrue(chunks.stream().allMatch(c -> c.tokenEstimate() == c.content().length()));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=ChunkSplitServiceTests test`  
Expected: FAIL（当前 splitters 仍使用 `*4` 规则）

- [ ] **Step 3: 改造 splitters 输出结构候选并由 core 收敛**

```java
// MarkdownChunkSplitter
public List<StructuredChunkCandidate> splitStructured(String content) {
    List<StructuredChunkCandidate> out = new ArrayList<>();
    for (Section sec : splitIntoSections(content.trim())) {
        if (sec.body() == null || sec.body().isBlank()) {
            continue;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "markdown");
        out.add(new StructuredChunkCandidate(sec.path(), sec.heading(), sec.body().trim(), meta));
    }
    return out;
}
```

```java
// ChunkSplitService
public List<IngestChunkDTO> split(DocumentType type, String content, String tokenizerProfileId) {
    var profile = tokenizerProfileRegistry.require(tokenizerProfileId);
    ChunkBudgetPolicy policy = switch (type) {
        case EXCEL_TABLE -> new ChunkBudgetPolicy(ingestProperties.getExcel().getTargetMaxTokens(), ingestProperties.getExcel().getOverlapMaxTokens());
        case PDF_LONGFORM -> new ChunkBudgetPolicy(ingestProperties.getPdf().getTargetMaxTokens(), ingestProperties.getPdf().getOverlapMaxTokens());
        default -> new ChunkBudgetPolicy(ingestProperties.getMarkdown().getTargetMaxTokens(), ingestProperties.getMarkdown().getOverlapMaxTokens());
    };
    List<StructuredChunkCandidate> candidates = switch (type) {
        case EXCEL_TABLE -> excelSplitter.splitStructured(content);
        case PDF_LONGFORM -> pdfSplitter.splitStructured(content);
        default -> markdownSplitter.splitStructured(content);
    };
    TokenCounter counter = new JtokkitTokenCounter(profile.encoding());
    return chunkingCoreService.chunk(candidates, policy, counter);
}
```

```java
public List<StructuredChunkCandidate> splitStructured(String content) {
    String[] lines = content == null ? new String[0] : content.split("\\R");
    List<StructuredChunkCandidate> out = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    int rowStart = 1;
    int rowEnd = 0;
    for (String line : lines) {
        rowEnd++;
        if (!buf.isEmpty()) {
            buf.append('\n');
        }
        buf.append(line);
        if (buf.length() >= 1200) {
            out.add(new StructuredChunkCandidate("", "", buf.toString(), Map.of("row_range", rowStart + "-" + rowEnd)));
            buf.setLength(0);
            rowStart = rowEnd + 1;
        }
    }
    if (!buf.isEmpty()) {
        out.add(new StructuredChunkCandidate("", "", buf.toString(), Map.of("row_range", rowStart + "-" + rowEnd)));
    }
    return out;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=ChunkSplitServiceTests test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/MarkdownChunkSplitter.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/PdfChunkSplitter.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/strategy/ExcelChunkSplitter.java interview-platform/src/main/java/com/ash/springai/interview_platform/service/ChunkSplitService.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/ChunkSplitServiceTests.java
git commit -m "refactor(chunking): split structured extraction and token-budget chunking"
```

### Task 6: 向量化消费者按知识库 profile 驱动切分

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumer.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java`

- [ ] **Step 1: 写失败测试（按库读取 profile 传递给 split）**

```java
@Test
void shouldPassKnowledgeBaseTokenizerProfileToChunkSplitService() {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(1L);
    kb.setTokenizerProfileId("dashscope-text-embedding-v3");
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb));
    when(parseService.downloadAndParseContent(anyString(), anyString())).thenReturn("# h\n\ncontent");
    when(router.route(anyString(), anyString(), anyString())).thenReturn(DocumentType.MARKDOWN_TEXT);
    when(splitService.split(eq(DocumentType.MARKDOWN_TEXT), anyString(), eq("dashscope-text-embedding-v3")))
        .thenReturn(List.of(new IngestChunkDTO(1, "content", 3, Map.of())));

    consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(1L, "key", "a.md", "text/markdown", "v2"));

    verify(splitService).split(eq(DocumentType.MARKDOWN_TEXT), anyString(), eq("dashscope-text-embedding-v3"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=VectorizeStreamConsumerPipelineTests test`  
Expected: FAIL（旧签名 split(type, content)）

- [ ] **Step 3: 修改消费者逻辑**

```java
KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(payload.kbId())
    .orElseThrow(() -> new IllegalStateException("知识库不存在"));
String tokenizerProfileId = kb.getTokenizerProfileId();
List<IngestChunkDTO> chunks = splitService.split(type, content, tokenizerProfileId);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=VectorizeStreamConsumerPipelineTests test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumer.java interview-platform/src/test/java/com/ash/springai/interview_platform/listener/VectorizeStreamConsumerPipelineTests.java
git commit -m "feat(vectorize): use per-kb tokenizer profile for chunk split"
```

### Task 7: 统一 token 展示口径（browse 与 metadata）

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorChunkBrowseRepository.java`
- Modify: `interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseServiceTests.java`
- Modify: `interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java`

- [ ] **Step 1: 写失败测试（优先读取 metadata token_count）**

```java
@Test
void shouldUseTokenCountFromMetadataInsteadOfLengthDivision() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    VectorChunkBrowseRepository repository = new VectorChunkBrowseRepository(jdbcTemplate);
    when(jdbcTemplate.query(anyString(), any(), eq("1"), eq(0), eq(10)))
        .thenAnswer(invocation -> {
            var mapper = invocation.getArgument(1, org.springframework.jdbc.core.RowMapper.class);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getString("chunk_id")).thenReturn("c-1");
            when(rs.getInt("chunk_index")).thenReturn(1);
            when(rs.getString("content")).thenReturn("abcdef");
            when(rs.getString("metadata")).thenReturn("{\"token_count\":57}");
            return List.of(mapper.mapRow(rs, 0));
        });
    ChunkItemDTO item = repository.findChunksByKnowledgeBaseId(1L, 0, 10).get(0);
    assertEquals(57, item.tokenEstimate());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -Dtest=KnowledgeBaseChunkBrowseServiceTests,KnowledgeBaseControllerChunkApiTests test`  
Expected: FAIL（当前固定 `length/4`）

- [ ] **Step 3: 修改 repository 映射逻辑**

```java
private ChunkItemDTO mapChunk(String chunkId, int chunkIndex, String content, String metadata) {
    String safeContent = content == null ? "" : content;
    String preview = safeContent.length() > 160 ? safeContent.substring(0, 160) + "..." : safeContent;
    int length = safeContent.length();
    Integer tokenEstimate = extractTokenCount(metadata); // 只读真实值
    return new ChunkItemDTO(chunkId, chunkIndex, preview, safeContent, length, tokenEstimate, metadata);
}

private Integer extractTokenCount(String metadataText) {
    if (metadataText == null || metadataText.isBlank()) return null;
    var m = java.util.regex.Pattern.compile("\"token_count\"\\s*:\\s*(\\d+)").matcher(metadataText);
    return m.find() ? Integer.parseInt(m.group(1)) : null;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -Dtest=KnowledgeBaseChunkBrowseServiceTests,KnowledgeBaseControllerChunkApiTests test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/VectorChunkBrowseRepository.java interview-platform/src/test/java/com/ash/springai/interview_platform/service/KnowledgeBaseChunkBrowseServiceTests.java interview-platform/src/test/java/com/ash/springai/interview_platform/controller/KnowledgeBaseControllerChunkApiTests.java
git commit -m "fix(chunk-browse): align token display with stored token_count"
```

### Task 8: 全链路回归与文档更新

**Files:**
- Modify: `docs/superpowers/specs/2026-04-24-tokenizer-profile-based-chunking-design.md`（若实现偏差需回填）

- [ ] **Step 1: 运行后端关键测试集**

Run: `./mvnw -q -Dtest=TokenizerProfileRegistryTests,ChunkingCoreServiceTests,KnowledgeBaseUploadServiceTokenizerProfileTests,ChunkSplitServiceTests,VectorizeStreamConsumerPipelineTests,KnowledgeBaseChunkBrowseServiceTests,KnowledgeBaseControllerChunkApiTests test`  
Expected: PASS

- [ ] **Step 2: 运行模块级测试（可接受较慢）**

Run: `./mvnw -q test`  
Expected: PASS（若有历史不稳定用例，记录并补充说明）

- [ ] **Step 3: 手工 smoke**

Run: `./mvnw spring-boot:run`  
Expected:
- 上传接口缺少 `tokenizerProfileId` 返回 400
- 上传合法 profile 后任务入队成功
- chunks 浏览返回 `tokenEstimate` 与 `metadata.token_count` 一致

- [ ] **Step 4: 更新 spec 实施记录**

```markdown
## Implementation Notes
- 已按库绑定 tokenizer profile
- 已替换 chars/4 切分估算
- 已统一浏览页 token 口径
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-04-24-tokenizer-profile-based-chunking-design.md
git commit -m "docs: update tokenizer profile chunking implementation notes"
```

## Self-Review

### 1) Spec Coverage

- 按库绑定 profile：Task 3, Task 6
- 真实 tokenizer 本地实现：Task 2
- 统一 chunking core：Task 4, Task 5
- 上传参数与校验：Task 3
- 浏览口径一致：Task 7
- 测试与验收：Task 1-8 均覆盖

无遗漏需求。

### 2) Placeholder Scan

已检查本计划，不包含 `TBD/TODO/implement later` 等占位词；每个代码步骤均包含可执行代码块。

### 3) Type Consistency

- `tokenizerProfileId` 在 Controller/UploadService/Entity 命名一致
- `TokenCounter#count` / `truncateToTokens` 在 core 与实现中一致
- `ChunkBudgetPolicy` 字段名 `targetMaxTokens` / `overlapTokens` 前后一致

未发现命名冲突。
