# Ragas 风格文档入库优化设计

## 1. 目标与约束

本设计用于优化“文档上传 -> 向量存储”链路，参考 Ragas 的核心思想（分层 transform、结构化抽取、可扩展流水线），并结合当前仓库实现进行后端改造。

明确约束（已确认）：

- 落地路径选择：方案 B（类型化切分 + 并行 enrich + pre-chunked 支持）。
- 优先级：检索效果优先。
- 测评策略：不做 A/B 与离线评测。
- 历史数据策略：旧文档与旧向量全部删除后重建。

## 2. 总体架构

### 2.1 五阶段流水线

入库链路统一为：

1. `Parse`：解析并标准化文档内容。
2. `Split`：按文档类型切分（非单一 splitter）。
3. `Enrich`：并行抽取关键词/实体/结构字段。
4. `Store`：写入向量存储与检索元数据。
5. `Validate`：入库质量门禁与状态落盘。

### 2.2 设计原则

- 以结构和语义边界为先，避免“统一长度切分”带来的上下文断裂。
- 入库时即生成可检索、可重排、可追踪 metadata。
- 支持 pre-chunked 输入，避免优质预切分被破坏。

## 3. 文档类型化切分策略

### 3.1 类型路由

首期支持三类：

- `markdown_text`（Markdown/纯文本）
- `excel_table`（Excel/表格文档）
- `pdf_longform`（PDF长文）

### 3.2 markdown_text

- 先按标题层级切分，再做长度二次切分。
- 目标长度：`350~550 tokens/chunk`，重叠 `50~80 tokens`。
- 必填 metadata：`section_path`, `heading`, `chunk_index`。

### 3.3 excel_table

- 以“单行或相关行组”为最小语义单元，不按自然段切。
- 先做列标准化（表头归一、空值和单位处理）。
- 目标长度：`200~400 tokens/chunk`，重叠 `20~40 tokens`。
- 必填 metadata：`sheet_name`, `row_range`, `primary_columns`, `record_id`, `record_link_id`。

### 3.4 pdf_longform

- 先做段落恢复（页眉页脚清理、断行修复），再按句边界切分。
- 目标长度：`400~650 tokens/chunk`，重叠 `60~100 tokens`。
- 表格或列表尽量作为单独块，避免切断关键结构。

### 3.5 pre-chunked 通道

- 若客户端传入 `chunks[]`，默认不再二次切分，仅做校验和 enrich。
- 仅在 chunk 明显违规（空块、超长、乱码）时回退到类型化 splitter。

## 4. Enrich 与 Metadata 设计

### 4.1 并行抽取

并行执行以下抽取器，失败可降级但不阻断：

- `KeyphraseExtractor`
- `EntityExtractor`
- `StructureExtractor`

### 4.2 通用必填 metadata

- `kb_id`, `doc_id`, `chunk_id`, `chunk_index`
- `doc_type`, `source_path`
- `content_hash`, `ingest_version`

### 4.3 检索增强字段（必填）

- `keywords[]`
- `entities[]`
- `section_path`（文本类）
- `importance_score`
- `quality_flags[]`

### 4.4 Excel 专属字段

- `sheet_name`
- `header_map`
- `row_range`
- `primary_columns[]`
- `record_id`, `record_link_id`
- `numeric_signals`

## 5. 直接替换与全删重建策略

### 5.1 上线策略

- 新流水线直接作为默认上传入库路径。
- 不进行 A/B 分流与离线效果评测。

### 5.2 旧数据处理（硬约束）

- 旧文档与旧向量全部删除。
- 删除后基于新的入库流水线重新上传/重建。

### 5.3 最小安全护栏

- 采用批次删除（按知识库或分类分批），避免一次性不可恢复故障扩大。
- 删除前生成审计清单：`kb_id/doc_name/delete_time/operator`。
- 删除后执行连通性校验：至少确保“重建后可检索可访问”。
- 对失败重建项保留“待恢复列表”，支持重试。

### 5.4 风险说明

- 由于“文档和向量全删”，无法进行旧数据秒级回滚。
- 回滚仅能依赖原始来源重新导入。

## 6. 执行标准（无测评版）

在不做效果测评前提下，采用工程可执行标准：

- 入库成功率：`>= 99%`
- 重试后最终成功率：`>= 99.5%`
- 空/噪声 chunk 占比：`<= 0.5%`
- 重复 chunk 占比：`<= 1%`
- 可追踪率（可定位源文档和位置）：`= 100%`
- 删除与重建批次日志完整率：`= 100%`

## 7. 实施边界

- 当前仅覆盖后端上传、切分、enrich、向量写入和状态管理。
- 不包含前端交互改造，不包含额外评测系统建设。
- 不引入新向量数据库产品，沿用现有 PostgreSQL + pgvector。

## 8. 直接替换操作步骤（运行手册）

1. 调用 `POST /api/knowledgebase/legacy/cleanup?batchSize=50&operator=ash`（需登录态），按批次删除知识库记录、对象存储文件与对应向量行；响应中的 `auditRecords` 为审计清单，`failures` 为可重试的失败项。
2. 重新上传知识库文件，触发 v2 流水线（Stream 消息仅携带 `storageKey` / `originalFilename` / `contentType` / `ingestVersion`，由消费端下载、Parse、Split、Enrich、Store、Validate）。
3. 在列表或详情中确认 `vectorStatus=COMPLETED`，并通过 `GET /api/knowledgebase/documents/{id}/chunks` 抽查 chunk 与 metadata（含 `keywords`、`entities`、`content_hash`、`ingest_version` 等）。
