"""
run_ragas.py
============

用 Ragas 测评当前项目真实 RAG 链路的入口脚本。

整个流程：

  ┌──────────────┐      ┌──────────────────┐      ┌────────────────────┐
  │ 本地 jsonl   │ ---> │ Spring Boot 评测 │ ---> │ Ragas EvaluationDS │
  │ (question +  │      │ /api/rag-chat/   │      │ 构造 & 跑指标       │
  │  reference)  │      │      evaluate    │      │ (LLM + Embedding)  │
  └──────────────┘      └──────────────────┘      └────────────────────┘

核心要点：
  * answer / retrieved_contexts 全部来自「你真实后端链路」（混合检索 + MiniMax-M2.5 生成）
  * 裁判 LLM / Embedding 从 .env 读取（默认 SiliconFlow + DashScope，也可切 OpenAI）
  * 指标默认使用 ragas 的几个 RAG 必测项：
      - LLMContextRecall            : 上下文召回（reference vs retrieved_contexts）
      - LLMContextPrecisionWithReference : 上下文精度（严格版，有 reference）
      - Faithfulness                : 生成是否忠实于上下文（抗幻觉）
      - FactualCorrectness          : 事实正确性（response vs reference）
      - ResponseRelevancy           : 回答与问题的相关性（需 embedding）
  * 结果同时打屏 + 写到 results/<timestamp>.csv / .json

跑法：
    .venv\\Scripts\\python.exe scripts\\run_ragas.py
或：
    .venv\\Scripts\\python.exe scripts\\run_ragas.py --dataset datasets/sample.jsonl --limit 5
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import sys
import time
from pathlib import Path
from typing import List, Dict, Any

import pandas as pd
from dotenv import load_dotenv
from tqdm import tqdm

# 让 "python scripts/run_ragas.py" 能 import 到同级的 backend_client
sys.path.insert(0, str(Path(__file__).parent))

from backend_client import BackendClient, EvaluateResponse  # noqa: E402


def parse_bool_env(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def configure_proxy_from_env() -> None:
    """
    统一配置本次评测进程的代理行为。

    - EVAL_PROXY_URL 有值时：HTTP/HTTPS/ALL_PROXY 都指向该地址
    - EVAL_PROXY_URL 为空时：清空代理变量（防止被系统代理污染）
    - NO_PROXY 默认保留 localhost/127.0.0.1，避免本地后端请求被代理
    """
    proxy_url = (os.getenv("EVAL_PROXY_URL") or "").strip()
    no_proxy = (os.getenv("NO_PROXY") or os.getenv("no_proxy") or "").strip()
    if not no_proxy:
        no_proxy = "localhost,127.0.0.1"

    if proxy_url:
        os.environ["HTTP_PROXY"] = proxy_url
        os.environ["HTTPS_PROXY"] = proxy_url
        os.environ["ALL_PROXY"] = proxy_url
        os.environ["http_proxy"] = proxy_url
        os.environ["https_proxy"] = proxy_url
        os.environ["all_proxy"] = proxy_url
    else:
        for key in [
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "http_proxy",
            "https_proxy",
            "all_proxy",
        ]:
            os.environ.pop(key, None)

    os.environ["NO_PROXY"] = no_proxy
    os.environ["no_proxy"] = no_proxy


# =============================================================
# 数据集读取
# =============================================================
def load_dataset(path: Path) -> List[Dict[str, Any]]:
    """
    读取 jsonl / json 数据集。每行（或每项）至少含：
        {
          "user_input": "问题",
          "reference":  "参考答案（gold answer）",
          "knowledge_base_ids": [1, 2]   # 可选，未提供则用 EVAL_KB_IDS
        }
    """
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {path}")

    items: List[Dict[str, Any]] = []
    text = path.read_text(encoding="utf-8").strip()

    if path.suffix.lower() == ".json":
        items = json.loads(text)
    else:
        # 按 jsonl 解析
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            items.append(json.loads(line))

    # 做最小合法性校验，早失败
    for i, it in enumerate(items):
        if "user_input" not in it or "reference" not in it:
            raise ValueError(
                f"第 {i} 条缺字段：user_input / reference 必须都有。item={it}"
            )
    return items


# =============================================================
# 调后端，拿 (answer, contexts)
# =============================================================
def build_samples(
    client: BackendClient,
    raw_items: List[Dict[str, Any]],
    default_kb_ids: List[int],
    retrieval_mode: str,
    debug_print: bool,
) -> List[Dict[str, Any]]:
    """
    遍历每条问题，调用后端 /evaluate，组装 ragas 所需的样本字典：
        {
          "user_input":         str,
          "retrieved_contexts": list[str],
          "response":           str,
          "reference":          str
        }
    """
    samples: List[Dict[str, Any]] = []
    for idx, it in enumerate(tqdm(raw_items, desc="调用后端收集样本", ncols=90)):
        kb_ids = it.get("knowledge_base_ids") or default_kb_ids
        question = it["user_input"]
        reference = it["reference"]
        try:
            t0 = time.time()
            resp: EvaluateResponse = client.evaluate(
                kb_ids=kb_ids,
                question=question,
                retrieval_mode=retrieval_mode,
            )
            elapsed = time.time() - t0
        except Exception as e:
            # 单条失败不要挂整个评测；把样本记成空 contexts + 错误文案
            print(f"\n[WARN] 第 {idx} 条后端调用失败: {e}")
            resp = EvaluateResponse(answer=f"【调用失败】{e}", contexts=[])
            elapsed = 0.0

        contexts = [c.text for c in resp.contexts if c.text]
        samples.append(
            {
                "user_input": question,
                "retrieved_contexts": contexts,
                "response": resp.answer,
                "reference": reference,
            }
        )

        if debug_print:
            print(
                f"\n--- sample {idx} (耗时 {elapsed:.1f}s) ---\n"
                f"Q: {question}\n"
                f"A: {resp.answer[:160]}...\n"
                f"C({len(contexts)}): {[c[:60] + '...' for c in contexts[:3]]}"
            )

    return samples


# =============================================================
# 构造 Ragas 的 LLM / Embedding（走 .env 里配的 OpenAI 兼容服务）
# =============================================================
def build_judge_llm_and_embeddings():
    """
    返回 (evaluator_llm, evaluator_embeddings)，都已用 ragas 官方的 Wrapper 包好。
    所有走 OpenAI 协议的服务（SiliconFlow / DashScope / 官方 OpenAI）都适用。
    """
    # 动态引入，避免在没装 langchain 时就报错
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    judge_api_key = os.getenv("JUDGE_API_KEY") or ""
    judge_base_url = os.getenv("JUDGE_BASE_URL") or ""
    judge_model = os.getenv("JUDGE_MODEL") or "gpt-4o-mini"
    judge_temperature = float(os.getenv("JUDGE_TEMPERATURE", "0"))
    if not judge_api_key:
        raise RuntimeError("缺少 JUDGE_API_KEY（裁判 LLM 密钥）")

    llm = ChatOpenAI(
        model=judge_model,
        base_url=judge_base_url or None,
        api_key=judge_api_key,
        temperature=judge_temperature,
    )

    embed_api_key = os.getenv("EMBED_API_KEY") or ""
    embed_base_url = os.getenv("EMBED_BASE_URL") or ""
    embed_model = os.getenv("EMBED_MODEL") or "text-embedding-3-small"
    if not embed_api_key:
        raise RuntimeError("缺少 EMBED_API_KEY（embedding 密钥）")

    # 对非 OpenAI 官方兼容网关（如 DashScope）：
    # 关闭长度安全分词路径，避免把 token-id 数组发给 provider 引发 400。
    # 该模式会直接发送原始字符串列表，更兼容 OpenAI-compatible 服务。
    embed_check_ctx_length = parse_bool_env("EMBED_CHECK_CTX_LENGTH", default=False)
    embed_encoding_format = os.getenv("EMBED_ENCODING_FORMAT") or "float"

    embeddings = OpenAIEmbeddings(
        model=embed_model,
        base_url=embed_base_url or None,
        api_key=embed_api_key,
        check_embedding_ctx_length=embed_check_ctx_length,
        model_kwargs={"encoding_format": embed_encoding_format},
    )

    return LangchainLLMWrapper(llm), LangchainEmbeddingsWrapper(embeddings)


# =============================================================
# 跑 ragas 评测
# =============================================================
def run_ragas_eval(samples: List[Dict[str, Any]], max_workers: int):
    """
    执行 ragas.evaluate，返回原生 result 对象（可 .to_pandas()）。
    """
    from ragas import EvaluationDataset, evaluate
    # 兼容不同 ragas 版本：
    # - 新版本建议从 ragas.metrics.collections 导入
    # - 0.4.3 仍可能只在 ragas.metrics 暴露这些类
    try:
        from ragas.metrics.collections import (
            LLMContextRecall,
            LLMContextPrecisionWithReference,
            Faithfulness,
            FactualCorrectness,
            ResponseRelevancy,
        )
    except ImportError:
        from ragas.metrics import (
            LLMContextRecall,
            LLMContextPrecisionWithReference,
            Faithfulness,
            FactualCorrectness,
            ResponseRelevancy,
        )
    from ragas.run_config import RunConfig

    evaluator_llm, evaluator_embeddings = build_judge_llm_and_embeddings()

    dataset = EvaluationDataset.from_list(samples)

    # 部分 OpenAI 兼容模型（如 SiliconFlow 某些模型）不支持 n>1。
    # ResponseRelevancy 默认会用更高 strictness（内部可能触发 n>1），
    # 这里显式降到 strictness=1 以提高兼容性。
    try:
        response_relevancy_metric = ResponseRelevancy(strictness=1)
    except TypeError:
        response_relevancy_metric = ResponseRelevancy()

    metrics = [
        LLMContextRecall(),
        LLMContextPrecisionWithReference(),
        Faithfulness(),
        FactualCorrectness(),
        response_relevancy_metric,
    ]

    print(f"\n开始 ragas 评测：samples={len(samples)}，metrics={len(metrics)}")
    result = evaluate(
        dataset=dataset,
        metrics=metrics,
        llm=evaluator_llm,
        embeddings=evaluator_embeddings,
        run_config=RunConfig(max_workers=max_workers),
        show_progress=True,
    )
    return result


# =============================================================
# 结果落盘
# =============================================================
def dump_results(result, samples: List[Dict[str, Any]], out_dir: Path) -> Path:
    """
    把 ragas 结果 + 原始样本 一并保存到 out_dir。
    返回 CSV 路径。
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

    # 1) 每条样本的逐指标分数
    try:
        df: pd.DataFrame = result.to_pandas()
    except Exception:
        # 老/新版本接口差异兜底
        df = pd.DataFrame(result.scores) if hasattr(result, "scores") else pd.DataFrame()

    # 把原始 question / response / reference / contexts 也拼上，便于肉眼复查
    raw_df = pd.DataFrame(
        [
            {
                "user_input": s["user_input"],
                "reference": s["reference"],
                "response": s["response"],
                "retrieved_contexts": "\n\n---\n\n".join(s["retrieved_contexts"]),
            }
            for s in samples
        ]
    )
    full_df = pd.concat([raw_df.reset_index(drop=True), df.reset_index(drop=True)], axis=1)
    csv_path = out_dir / f"ragas-{ts}.csv"
    full_df.to_csv(csv_path, index=False, encoding="utf-8-sig")

    # 2) 全量 summary（各指标均值）
    summary: Dict[str, Any] = {}
    try:
        # ragas 0.4+ result 对象直接可 dict() 拿到 aggregate
        summary = dict(result)
    except Exception:
        if hasattr(result, "scores"):
            summary = {
                k: float(sum(d.get(k, 0) for d in result.scores) / max(len(result.scores), 1))
                for k in result.scores[0]
            }
    json_path = out_dir / f"ragas-{ts}-summary.json"
    json_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("\n================ Ragas 指标总览 ================")
    for k, v in summary.items():
        try:
            print(f"  {k:32s} : {float(v):.4f}")
        except (TypeError, ValueError):
            print(f"  {k:32s} : {v}")
    print(f"\n明细 CSV : {csv_path}")
    print(f"概要 JSON: {json_path}")
    return csv_path


# =============================================================
# 入口
# =============================================================
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="用 Ragas 评测本项目的 RAG 流程")
    parser.add_argument(
        "--dataset",
        default=str(Path(__file__).parent.parent / "datasets" / "domainrag_bcm_20q.jsonl"),
        help="评测数据集路径（jsonl 或 json）",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="仅评测前 N 条（0=全部），调试时很有用",
    )
    parser.add_argument(
        "--out",
        default=str(Path(__file__).parent.parent / "results"),
        help="结果输出目录",
    )
    return parser.parse_args()


def main() -> None:
    # 加载 .env（相对 evaluation/ 根目录）
    root = Path(__file__).parent.parent
    load_dotenv(root / ".env")
    configure_proxy_from_env()

    args = parse_args()
    dataset_path = Path(args.dataset)
    out_dir = Path(args.out)

    default_kb_ids = [
        int(x) for x in (os.getenv("EVAL_KB_IDS") or "").split(",") if x.strip()
    ]
    if not default_kb_ids:
        raise RuntimeError("请在 .env 里设置 EVAL_KB_IDS（如 EVAL_KB_IDS=1）")
    retrieval_mode = os.getenv("EVAL_RETRIEVAL_MODE", "HYBRID").upper()
    max_workers = int(os.getenv("RAGAS_MAX_WORKERS", "4"))
    debug_print = (os.getenv("DEBUG_PRINT_SAMPLES", "false").lower() == "true")

    # 1) 读数据集
    raw_items = load_dataset(dataset_path)
    if args.limit > 0:
        raw_items = raw_items[: args.limit]
    print(f"数据集: {dataset_path}，样本数: {len(raw_items)}")

    # 2) 调后端，生成 samples
    with BackendClient.from_env() as client:
        client.login()
        samples = build_samples(
            client=client,
            raw_items=raw_items,
            default_kb_ids=default_kb_ids,
            retrieval_mode=retrieval_mode,
            debug_print=debug_print,
        )

    # 3) 跑 ragas
    result = run_ragas_eval(samples, max_workers=max_workers)

    # 4) 写结果
    dump_results(result, samples, out_dir)


if __name__ == "__main__":
    main()
