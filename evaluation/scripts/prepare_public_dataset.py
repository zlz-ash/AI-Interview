"""
prepare_public_dataset.py
=========================

把 HuggingFace 公共问答数据集转换为本项目可直接使用的 ragas 评测集格式：

每行输出为：
{"user_input": "...", "reference": "...", "knowledge_base_ids": [1]}

默认数据源：squad / validation。

使用示例：
    .venv\\Scripts\\python.exe scripts\\prepare_public_dataset.py --count 30 --kb-ids 1
    .venv\\Scripts\\python.exe scripts\\prepare_public_dataset.py --dataset squad --split train --count 50 --out datasets\\public_squad_50.jsonl
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path
from typing import Any, Dict, Iterable, List

from datasets import load_dataset


def normalize_text(text: str) -> str:
    """做最小清洗：去首尾空白、压缩多空格。"""
    text = (text or "").strip()
    return " ".join(text.split())


def pick_answer(item: Dict[str, Any]) -> str:
    """
    从公共数据项里提取一个 reference。

    针对 SQuAD 结构：
      item["answers"] = {"text": [...], "answer_start": [...]}。
    这里优先取第一个非空答案。
    """
    answers = item.get("answers")
    if isinstance(answers, dict):
        texts = answers.get("text") or []
        for t in texts:
            t = normalize_text(t)
            if t:
                return t
    # 兜底：有些数据集用 answer 字段
    ans = normalize_text(str(item.get("answer", "")))
    return ans


def to_eval_items(rows: Iterable[Dict[str, Any]], kb_ids: List[int], count: int, seed: int) -> List[Dict[str, Any]]:
    """将公共数据集行转换为项目评测集行。"""
    rows = list(rows)
    random.Random(seed).shuffle(rows)

    seen_questions = set()
    out: List[Dict[str, Any]] = []

    for row in rows:
        q = normalize_text(str(row.get("question", "")))
        a = pick_answer(row)

        # 过滤空值/重复问题，尽量保证样本质量
        if not q or not a or q in seen_questions:
            continue
        seen_questions.add(q)

        out.append(
            {
                "user_input": q,
                "reference": a,
                "knowledge_base_ids": kb_ids,
            }
        )
        if len(out) >= count:
            break

    if len(out) < count:
        raise RuntimeError(
            f"可用样本不足：期望 {count} 条，实际仅 {len(out)} 条。"
            "可尝试换 split/train 或降低 --count。"
        )
    return out


def write_jsonl(path: Path, items: List[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="把公共数据集转成本项目 ragas 评测集")
    parser.add_argument("--dataset", default="squad", help="HuggingFace 数据集名，默认 squad")
    parser.add_argument("--config", default=None, help="数据集配置名（有些数据集需要）")
    parser.add_argument("--split", default="validation", help="split 名，默认 validation")
    parser.add_argument("--count", type=int, default=30, help="输出样本条数，默认 30")
    parser.add_argument("--seed", type=int, default=42, help="随机种子，默认 42")
    parser.add_argument("--kb-ids", default="1", help="知识库 ID 列表，逗号分隔，如 1,2")
    parser.add_argument(
        "--out",
        default="datasets/public_squad_30.jsonl",
        help="输出文件（相对 evaluation 目录）",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    kb_ids = [int(x.strip()) for x in args.kb_ids.split(",") if x.strip()]
    if not kb_ids:
        raise RuntimeError("--kb-ids 不能为空，例如 --kb-ids 1")

    print(f"加载数据集: {args.dataset} / config={args.config} / split={args.split}")
    ds = load_dataset(args.dataset, args.config, split=args.split)

    items = to_eval_items(ds, kb_ids=kb_ids, count=args.count, seed=args.seed)
    out_path = Path(args.out)
    write_jsonl(out_path, items)

    print(f"已生成: {out_path} (共 {len(items)} 条)")
    print("前 2 条示例：")
    for i, it in enumerate(items[:2], start=1):
        print(f"[{i}] Q={it['user_input']}")
        print(f"    A={it['reference']}")


if __name__ == "__main__":
    main()
