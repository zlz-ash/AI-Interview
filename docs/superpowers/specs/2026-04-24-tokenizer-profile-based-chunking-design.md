# Tokenizer Profile 绑定知识库切分设计

## 1. 背景与目标

当前知识库切分使用 `token` 命名参数，但实现是按字符估算（`chars/4`）切分与计数。该方式在中英文混排、代码片段、符号密集内容上偏差较大，导致分块边界和真实模型 token 预算不一致。

本设计目标：

- 在不处理历史知识库兼容的前提下，仅针对新建知识库引入真实 tokenizer 计数。
- 采用“每个知识库固定一个 tokenizer profile，创建时确定”的治理方式。
- 采用方案 2：抽象统一 chunking core，避免切分策略分散在不同文档 splitter 中。

非目标：

- 不迁移旧知识库。
- 不在首版支持每次检索动态更换切分口径。
- 不在首版引入外部 tokenizer 微服务。

## 2. 约束与决策

已确认决策：

- 方案：统一 chunking core（方案 2）。
- 粒度：每个知识库绑定一个 profile（`B`）。
- 绑定时机：创建知识库时指定并固定（`A`）。
- 旧数据：不做兼容迁移（`A`）。
- tokenizer 接入：Java 本地库（`A`）。
- 创建体验：上传时只传 `tokenizerProfileId`，其他参数走系统默认策略（`A`）。

## 3. 架构设计

### 3.1 分层

1. `TokenizerProfileRegistry`
   - 输入：`tokenizerProfileId`
   - 输出：`model`、`provider`、`chunkingPolicyVersion`、`TokenCounter` 实现
   - 职责：校验 profile 合法性，提供运行时绑定信息

2. `TokenCounter`（接口）
   - `int count(String text)`
   - `String truncateToTokens(String text, int maxTokens)`
   - 职责：真实 tokenizer 计数与截断能力

3. `ChunkBudgetPolicy`
   - 输入：文档类型配置（target/overlap）
   - 输出：统一预算规则（最大 token、overlap token、边界控制）
   - 职责：统一预算语义，避免各 splitter 重复实现

4. `ChunkingCoreService`
   - 输入：结构片段（由各 splitter 提供）+ token counter + budget policy
   - 输出：`List<IngestChunkDTO>`（真实 token 计数）
   - 职责：统一裁切、重叠、token 计数、chunk 组装

5. 各文档 splitter（Markdown/PDF/Excel）
   - 职责仅保留“结构预切分”（章节、段落、行块）
   - 不再执行 `*4` 字符换算和 `/4` token 估算

### 3.2 数据流

1. 上传接口收到 `tokenizerProfileId`。
2. `KnowledgeBaseUploadService` 校验 profile 合法，保存到知识库实体。
3. 向量化消费者读取知识库记录，按 profile 获取 `TokenCounter` 与策略。
4. `ChunkSplitService` 路由到对应结构 splitter。
5. 结构片段进入 `ChunkingCoreService` 统一执行预算裁切和 overlap。
6. 产出带真实 token 的 `IngestChunkDTO`，继续 enrich + 向量化落库。

## 4. 数据模型与 API 变更

### 4.1 数据表字段（knowledge_bases）

新增字段：

- `tokenizer_profile_id` `varchar(64)` not null
- `tokenizer_model` `varchar(128)` not null
- `chunking_policy_version` `varchar(32)` not null default `'v1'`

说明：

- 新建知识库必须写入，历史数据不做回填。
- `revectorize` 默认沿用已绑定字段，不允许隐式切换。

### 4.2 上传接口

接口：`POST /api/knowledgebase/upload`

新增参数：

- `tokenizerProfileId`（必填）

校验规则：

- 为空：返回 400（参数缺失）
- 未注册 profile：返回 400（未知 profile）
- 不做 silent fallback，避免误绑定

## 5. 关键实现改造点（文件级）

1. `KnowledgeBaseEntity`
   - 增加 profile/model/policyVersion 三字段

2. `KnowledgeBaseController#uploadKnowledgeBase`
   - 接收 `tokenizerProfileId` 参数并传入 service

3. `KnowledgeBaseUploadService`
   - 上传流程中校验并解析 profile
   - 保存知识库时写入 profile 三元组

4. `KnowledgeBasePersistenceService#saveKnowledgeBase`
   - 方法签名扩展以持久化 profile 三元组

5. `ChunkSplitService`
   - 扩展入参（或上下文）承载 profile 信息
   - 路由结构 splitter 后统一进入 `ChunkingCoreService`

6. `MarkdownChunkSplitter` / `PdfChunkSplitter` / `ExcelChunkSplitter`
   - 删除 `maxTok*4`、`overlapTok*4` 和 `estimateTokens(chars/4)` 路径
   - 输出结构片段给 core

7. 新增组件
   - `TokenCounter` 接口 + 本地实现
   - `TokenizerProfileRegistry`
   - `ChunkBudgetPolicy`
   - `ChunkingCoreService`

8. `VectorChunkBrowseRepository`
   - 移除 `length/4` 的假设估算
   - token 展示改读真实 token（建议来源 metadata 的 `token_count`）

9. `ChunkEnrichService`
   - `importance_score` 继续使用 `chunk.tokenEstimate()`，但该值变为真实 token

## 6. 配置设计

在 `app.ingest` 下新增 profile 注册配置（示例）：

- `app.ingest.tokenizer.profiles[0].id=dashscope-text-embedding-v3`
- `app.ingest.tokenizer.profiles[0].model=text-embedding-v3`
- `app.ingest.tokenizer.profiles[0].provider=dashscope-openai-compatible`
- `app.ingest.chunking.policy-version=v1`

保留现有文档类型预算配置（markdown/excel/pdf 的 target/overlap token），但语义从“字符近似预算”升级为“真实 token 预算”。

## 7. 测试与验收

### 7.1 单元测试

- profile 校验：合法/非法/缺失
- chunking core：不超过目标 token，overlap 按 token 生效
- 多文本场景：中文、英文、混排、代码块、超长段落
- splitter 合约：结构切分输出非空且顺序稳定

### 7.2 集成测试

- 上传（md/pdf/xlsx）带 `tokenizerProfileId` 成功入库
- 向量化链路成功，chunk token 统计稳定
- chunks 浏览接口 token 字段与真实计数一致

### 7.3 验收指标

- 切分块 token 超预算比例接近 0
- 平均 chunk token 落在目标区间附近
- 召回质量不低于旧策略（评估集对比）

## 8. 风险与缓解

- 本地 tokenizer 与目标模型版本漂移
  - 缓解：profile 中记录 model/version，升级前回归评估
- 切分口径切换导致 chunk 数变化
  - 缓解：通过评估基线对比召回与答案质量
- 单点实现错误影响三类文档
  - 缓解：core 层强化测试覆盖和回归样本库

## 9. 推进顺序

1. 数据模型与上传入参落地
2. profile registry + token counter 接入
3. chunking core 落地
4. 三个 splitter 迁移到结构预切分模式
5. browse/enrich 口径统一
6. 单测/集测/评估回归

## 10. 结论

本方案在“新库生效、按库固定、真实 tokenizer、本地实现”的约束下，以统一 chunking core 取代分散字符估算逻辑，兼顾准确性、可维护性和后续扩展能力，适合当前项目在上线前完成一次结构化治理。
