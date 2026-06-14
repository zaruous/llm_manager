# Upstream 출처

`tools/*.py`는 llm-wiki-agent 프로젝트에서 반입한 독립 실행 Python 도구다.

- 원본 저장소: D:\git\doc\llm-wiki-agent (SamurAIGPT/llm-wiki-agent, MIT License — LICENSE.upstream 참조)
- 반입 커밋: 23491f216a7b01c53eed8bd988c4b3e09b96a234 (2026-06-03)
- 반입일: 2026-06-12

## 자체 추가 도구 (업스트림 아님)

- `tools/extract_pages.py` — 대용량 PDF를 페이지 텍스트(.md)+렌더 이미지(.png)로
  분해하는 ingest 전처리 도구. llm_manager가 런타임에 직접 실행 (LLM 호출 없음).
  요구사항: `pip install pymupdf`

## 실행 요구사항

- Python 3.8+
- `pip install litellm` (필수)
- `pip install markitdown` (선택 — PDF/DOCX 등 비마크다운 변환)
- `pip install networkx` (선택 — 그래프 커뮤니티 검출)
- `pip install pymupdf` (선택 — 대용량 PDF 페이지 전처리)
- 환경변수 `ANTHROPIC_API_KEY` (LLM 호출 시 — 종량제 과금 발생)
