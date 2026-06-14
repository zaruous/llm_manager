#!/usr/bin/env python3
"""
PDF를 페이지 단위 텍스트(.md)와 렌더 이미지(.png)로 분해한다.

llm_manager의 ingest 전처리 구간에서 실행되는 자체 추가 도구다 (업스트림 아님).
LLM 호출 없음 — API 키 불필요. 산출물은 페이지 판독 패스에서 Cursor Agent가
이미지를 일차 소스로 읽고 추출 텍스트를 보조로 참조하는 데 쓰인다.

Usage:
    python tools/extract_pages.py <input.pdf> <output_dir> [--dpi 120]

Output:
    output_dir/page-0001.md     추출 텍스트 ('# Page 1/N' 헤더 포함)
    output_dir/page-0001.png    페이지 렌더 이미지
    output_dir/manifest.json    {"pages": N, "items": [{page, text, image, text_chars}]}

Requires:
    pip install pymupdf
"""

import argparse
import json
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Split a PDF into per-page text + image assets.")
    parser.add_argument("input", help="source PDF file")
    parser.add_argument("output_dir", help="directory for page assets")
    parser.add_argument("--dpi", type=int, default=120,
                        help="render resolution (default 120 — readable yet compact)")
    args = parser.parse_args()

    try:
        import fitz  # PyMuPDF
    except ImportError:
        print("ERROR: PyMuPDF not installed. Run: pip install pymupdf", file=sys.stderr)
        sys.exit(2)

    source = Path(args.input)
    if not source.is_file():
        print(f"ERROR: input not found: {source}", file=sys.stderr)
        sys.exit(1)

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)

    doc = fitz.open(source)
    total = doc.page_count
    items = []
    for index in range(total):
        page = doc.load_page(index)
        n = index + 1

        text = page.get_text("text").strip()
        text_file = out / f"page-{n:04d}.md"
        text_file.write_text(f"# Page {n}/{total}\n\n{text}\n", encoding="utf-8")

        image_file = out / f"page-{n:04d}.png"
        page.get_pixmap(dpi=args.dpi).save(image_file)

        items.append({
            "page": n,
            "text": text_file.name,
            "image": image_file.name,
            "text_chars": len(text),
        })
        print(f"page {n}/{total} ({len(text)} chars)")

    manifest = out / "manifest.json"
    manifest.write_text(
        json.dumps({"pages": total, "items": items}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    print(f"done: {total} pages -> {out}")


if __name__ == "__main__":
    main()
