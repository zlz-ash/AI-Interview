"""
prepare_domainrag_corpus_singlefile.py
======================================

把 DomainRAG 的 corpus（rdzs/json_output/*.json）合并成 **单个**可上传入库的 Markdown 文件。

为什么需要“截断”：
  - 你的后端 `KnowledgeBaseUploadService` 限制单文件最大 50MB；
  - DomainRAG rdzs 全量 json_output 总体积约 56MB（未含合并分隔符），单文件很可能超限。

因此本脚本默认生成 **< 48MB** 的单文件（可用 --max-bytes 调整），保证能上传。
如需全量入库，建议：
  - 提高后端 MAX_FILE_SIZE，或
  - 允许拆成多个文件（脚本可扩展）。

输出示例片段：
  ## <title>
  - url: ...
  - date: ...
  <contents>
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, Iterable, Optional


def iter_json_files(dir_path: Path) -> Iterable[Path]:
    # json_output 里文件名是数字（如 10.json），但不强依赖命名格式
    for p in sorted(dir_path.glob("*.json"), key=lambda x: int(x.stem) if x.stem.isdigit() else x.stem):
        if p.is_file():
            yield p


def read_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def to_md(doc: Dict[str, Any]) -> str:
    title = (doc.get("title") or "").strip() or (doc.get("id") or "untitled")
    url = (doc.get("url") or "").strip()
    date = (doc.get("date") or "").strip()
    contents = (doc.get("contents") or "").strip()

    # 只保留最关键字段，避免把 passages 等冗余再写入
    lines = [f"## {title}"]
    if url:
        lines.append(f"- url: {url}")
    if date:
        lines.append(f"- date: {date}")
    if contents:
        lines.append("")
        lines.append(contents)
    lines.append("\n---\n")
    return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser(description="合并 DomainRAG rdzs corpus 为单个 Markdown（<=50MB）")
    ap.add_argument(
        "--json-output-dir",
        default=str(Path(__file__).resolve().parents[2] / "DomainRAG" / "corpus" / "rdzs" / "json_output"),
        help="DomainRAG corpus 的 json_output 目录",
    )
    ap.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parents[1] / "datasets" / "domainrag_rdzs_corpus_under50mb.md"),
        help="输出 Markdown 路径（建议用 .md 上传入库）",
    )
    ap.add_argument(
        "--max-bytes",
        type=int,
        default=48 * 1024 * 1024,
        help="输出文件最大字节数（默认 48MB，留出上传/编码余量）",
    )
    args = ap.parse_args()

    src_dir = Path(args.json_output_dir).resolve()
    out_path = Path(args.out).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    if not src_dir.exists():
        raise FileNotFoundError(f"找不到目录: {src_dir}")

    max_bytes = int(args.max_bytes)
    written_bytes = 0
    doc_count = 0

    with out_path.open("w", encoding="utf-8") as f:
        header = (
            "# DomainRAG rdzs corpus (truncated)\n\n"
            f"- source: {src_dir.as_posix()}\n"
            f"- max_bytes: {max_bytes}\n\n"
            "---\n\n"
        )
        f.write(header)
        written_bytes += len(header.encode("utf-8"))

        for p in iter_json_files(src_dir):
            doc = read_json(p)
            md = to_md(doc)
            b = md.encode("utf-8")
            if written_bytes + len(b) > max_bytes:
                break
            f.write(md)
            written_bytes += len(b)
            doc_count += 1

    print(f"OK: wrote {doc_count} docs, bytes={written_bytes} -> {out_path}")


if __name__ == "__main__":
    main()

