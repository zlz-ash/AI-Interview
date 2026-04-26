"""
run_factual_reference_control.py
================================

对照实验脚本：
把每条样本的 response 临时替换为 reference，再仅评估 FactualCorrectness。

用途：
1) 验证 factual_correctness 指标链路是否工作正常；
2) 若该实验分数接近 1，而真实链路分数很低，通常说明问题在“回答-参考答案口径对齐”，
   不是指标链路本身坏了。

用法示例：
  .venv\\Scripts\\python.exe scripts\\run_factual_reference_control.py
  .venv\\Scripts\\python.exe scripts\\run_factual_reference_control.py --dataset datasets/road_traffic_law_smoke_10.jsonl --limit 5
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
from typing import Any, Dict, List

import pandas as pd
from dotenv import load_dotenv


def load_dataset(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {path}")

    text = path.read_text(encoding="utf-8").strip()
    items: List[Dict[str, Any]] = []
    if path.suffix.lower() == ".json":
        items = json.loads(text)
    else:
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            items.append(json.loads(line))

    for i, it in enumerate(items):
        if "user_input" not in it or "reference" not in it:
            raise ValueError(f"第 {i} 条缺字段 user_input/reference: {it}")
    return items


def configure_proxy_from_env() -> None:
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
        for key in ["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"]:
            os.environ.pop(key, None)

    os.environ["NO_PROXY"] = no_proxy
    os.environ["no_proxy"] = no_proxy


def build_judge_llm():
    from langchain_openai import ChatOpenAI
    from ragas.llms import LangchainLLMWrapper

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
    return LangchainLLMWrapper(llm)


def run_eval(samples: List[Dict[str, Any]], max_workers: int):
    from ragas import EvaluationDataset, evaluate
    from ragas.run_config import RunConfig
    try:
        from ragas.metrics.collections import FactualCorrectness
    except ImportError:
        from ragas.metrics import FactualCorrectness

    dataset = EvaluationDataset.from_list(samples)
    llm = build_judge_llm()
    result = evaluate(
        dataset=dataset,
        metrics=[FactualCorrectness()],
        llm=llm,
        run_config=RunConfig(max_workers=max_workers),
        show_progress=True,
    )
    return result


def dump_result(result, samples: List[Dict[str, Any]], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

    try:
        score_df = result.to_pandas()
    except Exception:
        score_df = pd.DataFrame(result.scores) if hasattr(result, "scores") else pd.DataFrame()

    raw_df = pd.DataFrame(samples)
    full_df = pd.concat([raw_df.reset_index(drop=True), score_df.reset_index(drop=True)], axis=1)
    csv_path = out_dir / f"factual-control-{ts}.csv"
    full_df.to_csv(csv_path, index=False, encoding="utf-8-sig")

    try:
        summary = dict(result)
    except Exception:
        if hasattr(result, "scores") and result.scores:
            key = next(iter(result.scores[0].keys()))
            summary = {key: float(sum(d.get(key, 0) for d in result.scores) / len(result.scores))}
        else:
            summary = {}

    json_path = out_dir / f"factual-control-{ts}-summary.json"
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=============== 对照实验结果 ===============")
    for k, v in summary.items():
        try:
            print(f"{k}: {float(v):.4f}")
        except (ValueError, TypeError):
            print(f"{k}: {v}")
    print(f"CSV: {csv_path}")
    print(f"JSON: {json_path}")
    print("说明：该实验中 response 被替换为 reference，理想情况下 factual_correctness 应接近 1。")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="FactualCorrectness 对照实验：response=reference")
    parser.add_argument(
        "--dataset",
        default=str(Path(__file__).parent.parent / "datasets" / "road_traffic_law_smoke_10.jsonl"),
        help="数据集路径（jsonl/json）",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="仅跑前 N 条（0=全部）",
    )
    parser.add_argument(
        "--out",
        default=str(Path(__file__).parent.parent / "results"),
        help="输出目录",
    )
    return parser.parse_args()


def main() -> None:
    root = Path(__file__).parent.parent
    load_dotenv(root / ".env")
    configure_proxy_from_env()

    args = parse_args()
    dataset_path = Path(args.dataset)
    out_dir = Path(args.out)
    max_workers = int(os.getenv("RAGAS_MAX_WORKERS", "4"))

    raw_items = load_dataset(dataset_path)
    if args.limit > 0:
        raw_items = raw_items[: args.limit]

    # 关键对照：把 response 直接设为 reference
    samples = [
        {
            "user_input": item["user_input"],
            "retrieved_contexts": [],
            "response": item["reference"],
            "reference": item["reference"],
        }
        for item in raw_items
    ]

    print(f"数据集: {dataset_path}，样本数: {len(samples)}")
    result = run_eval(samples, max_workers=max_workers)
    dump_result(result, samples, out_dir)


if __name__ == "__main__":
    main()
