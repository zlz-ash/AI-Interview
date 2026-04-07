# Hybrid Retrieval 设计说明（知识库查询）

## 1. 背景与目标

本设计用于升级当前知识库查询链路，使其支持以下能力：

- 混合检索：`pgvector` 语义检索 + PostgreSQL FTS 关键词检索。
- 三档路由：`无问句`、`有问句但无 Embedding`、`有问句且有 Embedding`。
- 重排策略：有模型用模型，无模型走规则（可插拔）。
- 后处理：根据返回文档数量自适应放宽阈值。
- 返回结构：统一前端友好格式，减少前端逻辑负担。

当前确认约束：

- 检索底座：双存储（`pgvector + PostgreSQL FTS`）。
- 阶段目标：效果优先，暂不设硬性性能 SLA。
- 重排模型：当前无可用模型，先规则重排。
- Mode A（无问句）：固定为文档切片明细浏览（A2），非问答场景。

## 2. 总体架构与职责边界

### 2.1 Query Orchestrator（入口编排）

- 统一接收请求并做输入归一化。
- 调用意图路由、召回、融合重排、后处理。
- 组装统一响应，不承载具体检索算法细节。

### 2.2 Intent Router（意图路由）

- 路由到三档模式：
  - `MODE_A_DOC_CHUNKS_DETAIL`
  - `MODE_B_QUERY_NO_EMBEDDING`
  - `MODE_C_QUERY_WITH_EMBEDDING`
- 输出“语义倾向 vs 关键词倾向”用于动态融合权重计算。

### 2.3 Hybrid Retriever（双通道召回）

- 向量通道：`pgvector` 同维度余弦相似检索。
- 关键词通道：PostgreSQL FTS（`tsvector/tsquery`）。
- 两通道结果标准化后汇入统一候选池并去重。

### 2.4 Reranker（重排器）

- `rerank.mode=auto`：
  - 可用模型存在时走模型重排。
  - 否则走规则重排。
- 输出结构保持一致：`score`、`rankReason`、`source`。

### 2.5 Adaptive Post Processor（后处理）

- 若有效命中文档不足，触发阈值放宽并重试。
- 有最大轮次与阈值下限保护，避免无界放宽。

### 2.6 Response Assembler（响应组装）

- 返回前端可直接渲染的数据结构，提供必要元信息。

## 3. 三档路由设计

### 3.1 Mode A：无问句（文档切片明细浏览，A2）

判定：

- `question` 为空或仅空白。

目标：

- 展示“某文档如何被切分”的结果，不做问答。

固定输入模式（低自由度）：

- 仅接受 `documentId`（可选分页参数）。
- 不开放自由检索条件，不接收检索阈值/重排参数。

行为：

- 只读切片明细（按 `chunkIndex ASC`）。
- 不执行向量召回、FTS、重排、阈值放宽。

### 3.2 Mode B：有问句但无 Embedding

判定：

- 有问句，且 Embedding 服务不可用/未配置/超时。

行为：

- FTS 主召回 + 可选字符匹配补召回。
- 规则重排。
- 命中不足时允许阈值放宽。

### 3.3 Mode C：有问句且有 Embedding

判定：

- 有问句，且 Embedding 服务可用。

行为：

- 向量召回 + FTS 并行召回。
- 融合 + 重排（模型优先，规则兜底）。
- 命中不足时允许阈值放宽。

## 4. PostgreSQL FTS 关键词召回策略

### 4.1 查询函数顺序

默认按以下顺序执行（可配置）：

1. `websearch_to_tsquery`（优先，支持引号/OR/排除词语法）
2. `plainto_tsquery`（稳健降级）
3. 可选补召回：`ILIKE` 或 `pg_trgm`（仅命中不足时启用）

### 4.2 字段权重与相关性

- `title`：高权重（A）
- `sectionHeading`：中高权重（B）
- `chunkText`：基础权重（C）
- 评分函数建议：`ts_rank_cd`

### 4.3 过滤与排序

- 先做知识库/文档范围过滤，再按 `ftsScore` 排序。
- 通过 `ftsMinRank` 过滤低相关结果。

### 4.4 中文场景建议

- 若中文召回质量不稳定，建议启用中文分词扩展（如 `pg_jieba`）。
- 在分词改造前保留 `ILIKE` 补召回兜底。

## 5. 融合与重排细则

### 5.1 统一候选池

标准化字段示例：

- `chunkId`, `docId`, `source`, `vecScore`, `ftsScore`, `metadata`

去重规则：

- 同 `chunkId` 合并为一条候选。
- 同文档不同 `chunkId` 保留多条。

### 5.2 动态融合权重（意图驱动）

根据 query 意图分配通道权重：

- 偏语义：`w_vec=0.75`, `w_fts=0.25`
- 平衡：`w_vec=0.55`, `w_fts=0.45`
- 偏关键词：`w_vec=0.30`, `w_fts=0.70`

融合分：

- `hybridScore = w_vec * vecNorm + w_fts * ftsNorm`

### 5.3 重排策略

- 若配置了可用重排模型：按 `modelScore` 排序。
- 否则：按规则分 `ruleScore` 排序。

规则重排默认公式：

- `ruleScore = a*hybridScore + b*fieldBoost + c*freshness + d*exactMatchBoost`
- 建议初始系数：`a=0.70, b=0.15, c=0.05, d=0.10`

### 5.4 可解释性

每条命中返回 `rankReason`，用于前端展示与调参。

## 6. 阈值放宽后处理（Mode B/C）

触发条件：

- `effectiveHits < minEffectiveHits`。

放宽流程：

1. 首轮按默认阈值检索。
2. 未达标则逐轮放宽阈值并扩大 `topK`。
3. 达标或触达边界后停止。

停止条件：

- 命中达标，或
- 达到 `maxRelaxRounds`，或
- 触达阈值下限。

质量保护：

- 最终仍命中不足时，返回 `lowConfidence=true` 与建议提示。

## 7. Mode A（A2）返回契约（后端）

建议响应结构（支持“列表 + 详情”读取）：

- `mode`: `DOC_CHUNKS_DETAIL`
- `document`: `{ id, name, knowledgeBaseId }`
- `chunkList`: 轻量列表项（`chunkId`, `chunkIndex`, `preview`, `length`, `tokenEstimate`）
- `chunkDetail`: 详情项（`chunkId`, `chunkIndex`, `content`, `metadata`）
- `page`: `{ page, pageSize, totalChunks, hasNext }`
- `stats`: `{ avgChunkLength, minChunkLength, maxChunkLength }`

说明：

- `chunkDetail` 可根据 `selectedChunkId` 返回，若未指定可返回当前页首条。

## 8. Mode B/C 统一返回契约（后端）

建议响应结构：

- `mode`: `QUERY_NO_EMBEDDING | QUERY_WITH_EMBEDDING`
- `answer`（若为问答接口）
- `hits[]`：`chunkId`, `docId`, `source`, `score`, `highlight`, `rankReason`
- `meta`：`rounds`, `thresholdUsed`, `weightsUsed`, `lowConfidence`
- `stats`：`rawHitCount`, `effectiveHitCount`, `channelContribution`

兼容策略：

- 保持既有关键字段兼容，新增信息放在 `hits/meta/stats`。

## 9. 配置项建议（草案）

- `app.search.mode-a.enabled=true`
- `app.search.mode-a.default=doc_chunks_detail`
- `app.search.hybrid.fts.enabled=true`
- `app.search.hybrid.vector.enabled=true`
- `app.search.rerank.mode=auto`
- `app.search.threshold.min-effective-hits=5`
- `app.search.threshold.max-relax-rounds=3`

## 10. 验收标准（本轮）

- 无问句请求固定进入文档切片明细模式（A2）。
- 有问句请求能在 B/C 两模式间稳定切换。
- FTS 策略按设定顺序执行并可观察命中差异。
- 重排可在“模型/规则”之间无缝切换（当前默认规则）。
- 命中不足时触发阈值放宽，且有明确边界与低置信标记。
- 返回结构可直接支持前端渲染与调试观察。
