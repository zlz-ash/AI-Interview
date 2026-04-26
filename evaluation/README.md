# Ragas 评测（Spring AI 智能面试平台 RAG 链路）

本目录用于给项目当前的 RAG 流程（检索 + MiniMax-M2.5 生成）做自动化测评。

```
evaluation/
├── .venv/                     # Python 3.13 虚拟环境（已建好，依赖已装）
├── requirements.txt           # ragas / langchain-openai / httpx / pandas ...
├── .env.example               # 环境变量模板（复制成 .env 再填）
├── datasets/
│   └── sample.jsonl           # 评测数据集样例（问题 + 参考答案）
├── scripts/
│   ├── backend_client.py      # 调 Spring Boot 的小客户端（登录 + /evaluate）
│   └── run_ragas.py           # 主入口：拉样本 → 跑 ragas → 落盘结果
└── results/                   # 评测结果 (CSV + JSON summary)
```

---

## 一、准备工作（ash 需要做的 5 件事）

### 1. 启动后端
保证 Spring Boot 已启动在 `http://localhost:8080`（或自定义 URL），数据库、pgvector、Redis 都正常，并且 **至少已有 1 个知识库导入完成**。

### 2. 复制 `.env` 并填写
```powershell
cd evaluation
copy .env.example .env
```
要填的关键字段：

| 变量 | 说明 |
|---|---|
| `BACKEND_BASE_URL` | 后端地址，例如 `http://localhost:8080` |
| `BACKEND_USERNAME` / `BACKEND_PASSWORD` | 你启动后端时 `APP_AUTH_USERNAME/PASSWORD` 对应的账号（需有 `RAG:ACCESS` 权限） |
| `EVAL_KB_IDS` | 要测评的知识库 ID，多个用逗号，例如 `1,3` |
| `EVAL_RETRIEVAL_MODE` | `HYBRID`（默认）或 `VECTOR` |
| `JUDGE_API_KEY` | 裁判 LLM 的 API Key（建议 SiliconFlow 上用 `Pro/deepseek-ai/DeepSeek-V3` 或官方 OpenAI `gpt-4o-mini`） |
| `JUDGE_BASE_URL` / `JUDGE_MODEL` | 同上，按服务商填 |
| `EMBED_API_KEY` | 评测用的 Embedding（建议沿用你当前 DashScope 的 `text-embedding-v3`） |
| `EVAL_PROXY_URL` | 可选，统一代理地址（如 `http://127.0.0.1:7897`） |
| `NO_PROXY` | 建议保留 `localhost,127.0.0.1`，避免本地后端走代理 |
| `EMBED_CHECK_CTX_LENGTH` | 对 OpenAI-compatible 网关建议 `false`，避免 embedding 400 |

### 3. 新增了一个 Java 端点（本次已自动完成）
后端多了一个评测专用端点：

```
POST /api/rag-chat/evaluate
Body: { knowledgeBaseIds: [1], question: "xxx", retrievalMode: "HYBRID" }
→ { answer, contexts: [{chunkId, knowledgeBaseId, source, score, text}, ...] }
```

它直接复用生产链路的检索策略（混合检索 + 规则重排 + 阈值松弛），并同步调用 LLM 生成答案；
**不走流式、不落库、不污染 question_count**。

登录用 `/api/auth/login`（已有）。路径受 `RAG:ACCESS` 权限保护，脚本会自动登录拿 token。

### 4. 准备自己的评测数据集
打开 `datasets/sample.jsonl`，每行一个对象：
```json
{"user_input": "……问题……", "reference": "……参考答案……", "knowledge_base_ids": [1]}
```
字段说明：
- `user_input`（必填）：业务真实问题
- `reference`（必填）：人工写的「标准答案」；ragas 很多指标要对比它
- `knowledge_base_ids`（可选）：只让这几个知识库参与检索；不填走 `EVAL_KB_IDS`

建议规模：**10~30 条**足够暴露大部分问题；低于 5 条指标方差会很大。

### 4.1 直接使用公共数据集（已支持）
如果你不想手写题目，可以直接从 HuggingFace 生成公共评测集：

```powershell
cd evaluation
.venv\Scripts\python.exe scripts\prepare_public_dataset.py --dataset squad --split validation --count 30 --kb-ids 1 --out datasets\public_squad_30.jsonl
```

然后直接跑：
```powershell
.venv\Scripts\python.exe scripts\run_ragas.py --dataset datasets\public_squad_30.jsonl
```

> 注意：公共问答集的题目必须与当前知识库内容有重叠，否则检索会空命中，分数会被系统性拉低。
> 如果你要用 `squad`，建议把其对应文档（context）先导入你的知识库，评测才有统计意义。

### 5. 运行评测
```powershell
cd evaluation
.venv\Scripts\python.exe scripts\run_ragas.py
# 或只跑前 5 条调试
.venv\Scripts\python.exe scripts\run_ragas.py --dataset datasets\sample.jsonl --limit 5
```

---

## 二、评测指标（默认启用）

| 指标 | 作用 | 需要 |
|---|---|---|
| `LLMContextRecall` | 检索是否把参考答案需要的信息都召回了（召回率） | reference |
| `LLMContextPrecisionWithReference` | 召回的 contexts 有多少是真正相关的（精度） | reference |
| `Faithfulness` | 生成答案是否忠实于 contexts（抗幻觉） | retrieved_contexts |
| `FactualCorrectness` | 答案事实是否与参考答案一致 | reference |
| `ResponseRelevancy` | 答案与问题是否相关（需 embedding） | embedding |

> 上面的指标里前四个靠「裁判 LLM」，最后一个靠 Embedding；所以 `.env` 里这两类密钥都要配齐。
> 如果 `answer_relevancy` 出现 `400 InvalidParameter(input.contents)`，请确认：
> 1) `EMBED_BASE_URL` 带 `/v1`；
> 2) `EMBED_CHECK_CTX_LENGTH=false`（让请求发字符串而不是 token-id）；
> 3) `EMBED_ENCODING_FORMAT=float`。

---

## 三、结果读取

运行完成会在 `results/` 里生成：
- `ragas-YYYYMMDD-HHMMSS.csv` ：**每条样本**的问题 / 参考答案 / 生成答案 / contexts + 各指标分数
- `ragas-YYYYMMDD-HHMMSS-summary.json` ：**总分**（各指标均值）

终端也会打印一份总分概览。

---

## 四、常见排查

| 现象 | 原因 / 处理 |
|---|---|
| `401 Unauthorized` | `.env` 里后端账号密码错误；或该账号没有 `RAG:ACCESS` 权限 |
| `连接被拒` | Spring Boot 没启动 / `BACKEND_BASE_URL` 错了 |
| `Faithfulness / Recall 全是 NaN` | 样本 contexts 为空（检索无命中），先用 `DEBUG_PRINT_SAMPLES=true` 观察真实检索 |
| 评测极慢 | 把 `JUDGE_MODEL` 换成更小更快的（如 `gpt-4o-mini`）；适度调大 `RAGAS_MAX_WORKERS` |
| 被限流（429） | 调小 `RAGAS_MAX_WORKERS`（默认 4，可降到 2） |

---

## 五、下一步扩展建议

- 把数据集换成业务真实问答（建议从线上已解决的 RAG 对话导出）
- 固定 seed + 保留历史 CSV，可做「每次迭代前后的分数对比」
- 需要的话可加 `AnswerCorrectness` / `ContextEntityRecall` 等更细的指标（在 `scripts/run_ragas.py` 里扩 `metrics=` 列表）

