"""
prepare_domainrag_dataset.py
============================

从 DomainRAG（当前仓库根目录下的 `DomainRAG/BCM/labeled_data/`）抽样生成本项目评测数据集。

输出格式与 `run_ragas.py` 的 `load_dataset()` 约定一致，每行一个 json：
    {"user_input": "...", "reference": "..."}

抽样策略（可重复、便于追溯）：
  - 每个能力子集（子目录）取前 N 条（默认 3）
  - 对 conversation_qa：把 history_qa 以 Q/A 形式拼到 user_input 前缀里
  - reference：从 answers 中取“第一个可用答案”，列表/多答案会拼接成一行文本
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


def _first_non_empty_str(values: Iterable[Any]) -> Optional[str]:
    for v in values:
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


def _normalize_reference(obj: Dict[str, Any]) -> str:
    """
    DomainRAG 的 answers 结构常见形态：
      - answers: [["A"], ["A2"]] 或 answers: [["A", "B"]]
      - answers: ["A", "B"]（少见）
    """
    answers = obj.get("answers")
    if isinstance(answers, list) and answers:
        # list[list[str]] or list[str]
        if all(isinstance(x, list) for x in answers):
            # 取第一组
            first_group = answers[0] if answers else []
            if isinstance(first_group, list):
                s = _first_non_empty_str(first_group)
                if s:
                    return s
        s = _first_non_empty_str(answers)
        if s:
            return s

    # faithful_qa / structured_qa 等通常都有 answers；兜底用 question 以避免空 reference
    return (obj.get("reference") or obj.get("gold") or obj.get("question") or "").strip()


def _normalize_user_input(ability: str, obj: Dict[str, Any]) -> str:
    q = (obj.get("question") or "").strip()
    if ability == "conversation_qa":
        history = obj.get("history_qa")
        if isinstance(history, list) and history:
            turns: List[str] = []
            for t in history:
                if not isinstance(t, dict):
                    continue
                tq = (t.get("question") or "").strip()
                ta = _normalize_reference(t)  # history_qa 里也有 answers
                if tq:
                    turns.append(f"Q: {tq}")
                if ta:
                    turns.append(f"A: {ta}")
            if turns:
                return "对话历史：\n" + "\n".join(turns) + "\n\n当前问题：\n" + q
    return q


def _iter_jsonl(path: Path) -> Iterable[Dict[str, Any]]:
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            yield json.loads(line)


def build_tasks(domainrag_root: Path) -> List[Tuple[str, Path]]:
    base = domainrag_root / "BCM" / "labeled_data"
    return [
        ("extractive_qa", base / "extractive_qa" / "basic_qa.jsonl"),
        ("faithful_qa", base / "faithful_qa" / "faithful_qa.jsonl"),
        ("multi-doc_qa", base / "multi-doc_qa" / "multidoc_qa.jsonl"),
        ("structured_qa", base / "structured_qa" / "structured_qa_twopositive.jsonl"),
        ("time-sensitive_qa", base / "time-sensitive_qa" / "time_sensitive.jsonl"),
        ("conversation_qa", base / "conversation_qa" / "conversation_qa.jsonl"),
        ("noisy_qa", base / "noisy_qa" / "noisy_qa_ver3.jsonl"),
    ]


def main() -> None:
    ap = argparse.ArgumentParser(description="从 DomainRAG 抽样生成评测数据集（jsonl）")
    ap.add_argument("--count-per-ability", type=int, default=3, help="每个能力子集抽样条数")
    ap.add_argument(
        "--domainrag-root",
        default=str(Path(__file__).resolve().parents[2] / "DomainRAG"),
        help="DomainRAG 仓库根目录（默认：本仓库根目录下 ./DomainRAG）",
    )
    ap.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parents[1] / "datasets" / "domainrag_bcm_3each.jsonl"),
        help="输出 jsonl 路径",
    )
    args = ap.parse_args()

    domainrag_root = Path(args.domainrag_root).resolve()
    out_path = Path(args.out).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    tasks = build_tasks(domainrag_root)
    n = int(args.count_per_ability)

    items: List[Dict[str, Any]] = []
    for ability, src in tasks:
        if not src.exists():
            raise FileNotFoundError(f"找不到源文件：{src}")
        picked = 0
        for obj in _iter_jsonl(src):
            user_input = _normalize_user_input(ability, obj)
            reference = _normalize_reference(obj)
            if not user_input or not reference:
                continue
            items.append(
                {
                    "user_input": user_input,
                    "reference": reference,
                }
            )
            picked += 1
            if picked >= n:
                break
        if picked < n:
            raise RuntimeError(f"{ability} 抽样不足：需要 {n}，实际 {picked}（src={src}）")

    with out_path.open("w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")

    print(f"OK: wrote {len(items)} samples -> {out_path}")


if __name__ == "__main__":
    main()

