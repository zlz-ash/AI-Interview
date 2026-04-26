"""
prepare_domainrag_20q_pack.py
==============================

目标：
1) 缩减 DomainRAG 语料到“只覆盖 20 道题涉及的参考文档范围”，生成单文件 Markdown，方便上传入库
2) 重新抽取 20 道评测题（来自不同能力子集），并保证其参考文档一定出现在上述缩减语料中

输出：
- evaluation/datasets/domainrag_rdzs_corpus_20q.md
- evaluation/datasets/domainrag_bcm_20q.jsonl

说明：
- 题目来源：DomainRAG/BCM/labeled_data/**.jsonl
- 文档来源：直接使用 labeled_data 中的 positive_reference[].contents（它们本身来自 DomainRAG corpus）
- evaluation dataset 字段遵循 run_ragas.py：{"user_input": "...", "reference": "..."}
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


@dataclass(frozen=True)
class AbilitySpec:
    ability: str
    path: Path


def iter_jsonl(path: Path) -> Iterable[Dict[str, Any]]:
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            yield json.loads(line)


def first_answer(obj: Dict[str, Any]) -> str:
    """
    answers 常见形态：
      - answers: [["A"], ["A2"]] 或 answers: [["A", "B"]]
      - answers: ["A", "B"]
    """
    # DomainRAG 不同子集字段略有差异：有的用 answers，有的用 answer
    answers = obj.get("answers")
    if isinstance(answers, list) and answers:
        if all(isinstance(x, list) for x in answers):
            g0 = answers[0] if answers else []
            if isinstance(g0, list):
                for v in g0:
                    if isinstance(v, str) and v.strip():
                        return v.strip()
        for v in answers:
            if isinstance(v, str) and v.strip():
                return v.strip()
    # 单字符串 answer
    ans = obj.get("answer")
    if isinstance(ans, str) and ans.strip():
        return ans.strip()

    # 兜底，避免 reference 为空导致评测脚本报错
    return (obj.get("reference") or obj.get("gold") or obj.get("question") or "").strip()


def normalize_user_input(ability: str, obj: Dict[str, Any]) -> str:
    q = (obj.get("question") or "").strip()
    if ability == "conversation_qa":
        history = obj.get("history_qa")
        if isinstance(history, list) and history:
            turns: List[str] = []
            for t in history:
                if not isinstance(t, dict):
                    continue
                tq = (t.get("question") or "").strip()
                ta = first_answer(t)
                if tq:
                    turns.append(f"Q: {tq}")
                if ta:
                    turns.append(f"A: {ta}")
            if turns:
                return "对话历史：\n" + "\n".join(turns) + "\n\n当前问题：\n" + q
    return q


def extract_refs(obj: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    统一把 positive_reference 变成 list[dict]，并只保留我们需要的字段。
    """
    # 不同子集字段命名差异：positive_reference vs positive_references
    refs = obj.get("positive_reference")
    if refs is None:
        refs = obj.get("positive_references")
    if refs is None:
        return []
    if isinstance(refs, dict):
        refs = [refs]
    if not isinstance(refs, list):
        return []
    out: List[Dict[str, Any]] = []
    for r in refs:
        if not isinstance(r, dict):
            continue
        out.append(
            {
                "id": str(r.get("id") or "").strip(),
                "psg_id": r.get("psg_id"),
                "title": (r.get("title") or "").strip(),
                "url": (r.get("url") or "").strip(),
                "date": (r.get("date") or "").strip(),
                "contents": (r.get("contents") or "").strip(),
            }
        )
    return out


def build_specs(domainrag_root: Path) -> List[AbilitySpec]:
    base = domainrag_root / "BCM" / "labeled_data"
    return [
        AbilitySpec("extractive_qa", base / "extractive_qa" / "basic_qa.jsonl"),
        AbilitySpec("faithful_qa", base / "faithful_qa" / "faithful_qa.jsonl"),
        AbilitySpec("multi-doc_qa", base / "multi-doc_qa" / "multidoc_qa.jsonl"),
        AbilitySpec("structured_qa", base / "structured_qa" / "structured_qa_twopositive.jsonl"),
        AbilitySpec("time-sensitive_qa", base / "time-sensitive_qa" / "time_sensitive.jsonl"),
        AbilitySpec("conversation_qa", base / "conversation_qa" / "conversation_qa.jsonl"),
        AbilitySpec("noisy_qa", base / "noisy_qa" / "noisy_qa_ver3.jsonl"),
    ]


def pick_questions(
    specs: Sequence[AbilitySpec],
    total: int,
    per_ability_floor: int = 2,
) -> List[Tuple[str, Dict[str, Any]]]:
    """
    抽题策略：
    - 先保证每个能力子集至少抽 per_ability_floor 条（默认 2）
    - 再从前几个能力子集中补齐到 total
    - 为确定性与可复现：按文件顺序取“前 N 条有效样本”（有 question + answers + positive_reference.contents）
    """
    picked: List[Tuple[str, Dict[str, Any]]] = []

    def is_valid(ability: str, obj: Dict[str, Any]) -> bool:
        if not (obj.get("question") or "").strip():
            return False
        if not first_answer(obj).strip():
            return False
        refs = extract_refs(obj)
        if not refs:
            return False
        if not any((r.get("contents") or "").strip() for r in refs):
            return False
        return True

    # floor
    for spec in specs:
        cnt = 0
        for obj in iter_jsonl(spec.path):
            if is_valid(spec.ability, obj):
                picked.append((spec.ability, obj))
                cnt += 1
                if cnt >= per_ability_floor:
                    break
        if cnt < per_ability_floor:
            raise RuntimeError(f"{spec.ability} 抽样不足：需要 {per_ability_floor}，实际 {cnt}（{spec.path}）")

    # top-up
    if len(picked) > total:
        return picked[:total]

    for spec in specs:
        for obj in iter_jsonl(spec.path):
            if len(picked) >= total:
                break
            if is_valid(spec.ability, obj):
                # 避免重复：用 question 文本去重
                q = (obj.get("question") or "").strip()
                if any(((o.get("question") or "").strip() == q) for _, o in picked):
                    continue
                picked.append((spec.ability, obj))
        if len(picked) >= total:
            break

    if len(picked) < total:
        raise RuntimeError(f"抽题不足：需要 {total}，实际 {len(picked)}")
    return picked


def build_corpus_md(samples: Sequence[Tuple[str, Dict[str, Any]]]) -> str:
    """
    把所有样本引用到的 positive_reference 合并成 Markdown。
    - 按 url+id+psg_id 去重
    """
    seen = set()
    blocks: List[str] = []

    header = [
        "# DomainRAG rdzs corpus (20Q pack)",
        "",
        "- source: DomainRAG/BCM/labeled_data/**.jsonl (positive_reference.contents)",
        "- purpose: 仅覆盖本 pack 的 20 道题所需文档范围",
        "",
        "---",
        "",
    ]
    blocks.append("\n".join(header))

    for ability, obj in samples:
        refs = extract_refs(obj)
        for r in refs:
            url = r.get("url") or ""
            rid = r.get("id") or ""
            psg_id = r.get("psg_id")
            key = (url, rid, str(psg_id))
            if key in seen:
                continue
            seen.add(key)

            title = r.get("title") or rid or url or "untitled"
            date = r.get("date") or ""
            contents = (r.get("contents") or "").strip()
            if not contents:
                continue

            blocks.append(f"## {title}")
            blocks.append(f"- ability: {ability}")
            if url:
                blocks.append(f"- url: {url}")
            if date:
                blocks.append(f"- date: {date}")
            if rid:
                blocks.append(f"- source_id: {rid}")
            if psg_id is not None:
                blocks.append(f"- psg_id: {psg_id}")
            blocks.append("")
            blocks.append(contents)
            blocks.append("\n---\n")

    return "\n".join(blocks)


def write_jsonl_dataset(out_path: Path, samples: Sequence[Tuple[str, Dict[str, Any]]]) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        for ability, obj in samples:
            item = {
                "user_input": normalize_user_input(ability, obj),
                "reference": first_answer(obj),
            }
            f.write(json.dumps(item, ensure_ascii=False) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description="生成 DomainRAG 缩减语料 + 20 道题评测集")
    ap.add_argument(
        "--domainrag-root",
        default=str(Path(__file__).resolve().parents[2] / "DomainRAG"),
        help="DomainRAG 根目录（默认：项目根目录下 ./DomainRAG）",
    )
    ap.add_argument("--total", type=int, default=20, help="总题数")
    ap.add_argument("--floor", type=int, default=2, help="每个能力子集至少抽几题")
    ap.add_argument(
        "--out-corpus",
        default=str(Path(__file__).resolve().parents[1] / "datasets" / "domainrag_rdzs_corpus_20q.md"),
        help="输出缩减语料 Markdown",
    )
    ap.add_argument(
        "--out-dataset",
        default=str(Path(__file__).resolve().parents[1] / "datasets" / "domainrag_bcm_20q.jsonl"),
        help="输出评测数据集 jsonl",
    )
    args = ap.parse_args()

    domainrag_root = Path(args.domainrag_root).resolve()
    specs = build_specs(domainrag_root)
    for s in specs:
        if not s.path.exists():
            raise FileNotFoundError(f"缺少 labeled_data 文件：{s.path}")

    picked = pick_questions(specs, total=int(args.total), per_ability_floor=int(args.floor))

    # 1) dataset
    out_dataset = Path(args.out_dataset).resolve()
    write_jsonl_dataset(out_dataset, picked)

    # 2) corpus md
    out_corpus = Path(args.out_corpus).resolve()
    out_corpus.parent.mkdir(parents=True, exist_ok=True)
    md = build_corpus_md(picked)
    out_corpus.write_text(md, encoding="utf-8")

    print(f"OK: dataset={out_dataset} ({len(picked)} items)")
    print(f"OK: corpus ={out_corpus} (bytes={out_corpus.stat().st_size})")


if __name__ == "__main__":
    main()

