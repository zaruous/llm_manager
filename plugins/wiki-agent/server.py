"""
Wiki MCP Server — FastMCP 기반 위키 시맨틱 검색·페이지 조회·질의 서버.

service-packs/wiki-mcp.yml에 등록되어 LLM Manager의 서비스 목록으로 관리된다.
groovyScript가 --db-path, --vec0-path를 자동 주입하며,
사용자는 --workspace만 지정하면 된다.
"""

import argparse
import json
import sqlite3
import struct
import urllib.request
from pathlib import Path

# fastmcp는 설치 명령(pip install fastmcp)으로 제공된다.
try:
    from fastmcp import FastMCP
except ImportError:
    raise SystemExit(
        "fastmcp 패키지가 없습니다. 'pip install fastmcp>=2.0.0' 실행 후 재시작해 주세요."
    )

# ─────────────────────────────────────────────────────────────
# CLI 인수 (groovyScript에서 hidden argSpec으로 자동 주입)
# ─────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Wiki MCP Server")
parser.add_argument("--workspace", required=True, help="wiki/index.md가 있는 루트 디렉토리")
parser.add_argument("--db-path", default="", help="wiki-vector.sqlite 경로 (자동 주입)")
parser.add_argument("--vec0-path", default="", help="vec0.dll/.so 경로 (자동 주입)")
parser.add_argument("--embedding-url", default="http://localhost:18080", help="BGE-M3 서버 URL")
parser.add_argument("--port", type=int, default=18090, help="MCP 서버 포트")
parser.add_argument("--enable-write", action="store_true", help="wiki_ingest 도구 활성화")
args = parser.parse_args()

WORKSPACE = Path(args.workspace).resolve()
DB_PATH = args.db_path or str(WORKSPACE / ".llm-manager" / "wiki-vector.sqlite")
VEC0_PATH = args.vec0_path
EMBEDDING_URL = args.embedding_url.rstrip("/")

mcp = FastMCP("wiki-mcp")


# ─────────────────────────────────────────────────────────────
# 내부 유틸
# ─────────────────────────────────────────────────────────────

def _get_conn() -> sqlite3.Connection:
    """DB 연결을 반환한다. vec0 경로가 있으면 확장을 로드한다."""
    conn = sqlite3.connect(DB_PATH)
    if VEC0_PATH:
        try:
            conn.enable_load_extension(True)
            conn.load_extension(VEC0_PATH.replace("\\", "/"))
            conn.enable_load_extension(False)
        except Exception as e:
            pass  # vec0 없으면 wiki_search가 빈 결과를 반환
    return conn


def _embed(text: str) -> list[float]:
    """BGE-M3 서버에 텍스트를 임베딩 요청한다."""
    body = json.dumps({"input": [text], "model": "BAAI/bge-m3"}).encode()
    req = urllib.request.Request(
        EMBEDDING_URL + "/v1/embeddings",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return data["data"][0]["embedding"]


def _floats_to_blob(floats: list[float]) -> bytes:
    """float 배열을 LITTLE_ENDIAN BLOB으로 직렬화한다. Java toBlob() 호환."""
    return struct.pack(f"<{len(floats)}f", *floats)


# ─────────────────────────────────────────────────────────────
# MCP 도구
# ─────────────────────────────────────────────────────────────

@mcp.tool()
def wiki_search(query: str, top_k: int = 10, type_filter: str = "") -> str:
    """
    위키에서 쿼리와 의미적으로 유사한 청크를 시맨틱 검색합니다.
    벡터 색인이 없으면 빈 결과를 반환합니다.

    Args:
        query: 검색할 자연어 질문 또는 키워드
        top_k: 반환할 최대 결과 수 (기본 10)
        type_filter: 페이지 타입 필터 (entity/source/concept 등, 빈 값이면 전체)
    """
    try:
        emb = _embed(query)
        blob = _floats_to_blob(emb)
    except Exception as e:
        return json.dumps({"error": f"임베딩 서버 오류: {e}"}, ensure_ascii=False)

    try:
        conn = _get_conn()
        # vec0 MATCH 쿼리 — vec0 없으면 테이블이 없거나 다른 스키마이므로 빈 결과
        where_type = "AND c.type = ?" if type_filter else ""
        params = [blob, top_k] + ([type_filter] if type_filter else [])
        rows = conn.execute(
            f"""
            SELECT c.page_path, c.chunk_no, c.type, c.content, ce.distance
            FROM chunk_embeddings ce
            JOIN chunks c ON c.id = ce.rowid
            WHERE ce.embedding MATCH ?
              AND k = ?
              {where_type}
            ORDER BY ce.distance
            """,
            params,
        ).fetchall()
        conn.close()
    except Exception as e:
        return json.dumps({"error": f"검색 오류: {e}"}, ensure_ascii=False)

    results = [
        {"page": r[0], "chunk": r[1], "type": r[2], "content": r[3], "score": r[4]}
        for r in rows
    ]
    return json.dumps(results, ensure_ascii=False, indent=2)


@mcp.tool()
def wiki_get_page(page_path: str) -> str:
    """
    위키 페이지의 전체 마크다운 내용을 반환합니다.

    Args:
        page_path: 워크스페이스 상대 경로 (예: wiki/entities/JohnDoe.md)
    """
    target = WORKSPACE / page_path.replace("/", Path.sep)
    if not target.is_file():
        return f"페이지를 찾을 수 없습니다: {page_path}"
    return target.read_text(encoding="utf-8")


@mcp.tool()
def wiki_overview() -> str:
    """
    위키 전체 개요를 반환합니다. overview.md, index.md, 페이지 수 통계를 포함합니다.
    에이전트의 위키 구조 파악 첫 번째 단계로 사용하세요.
    """
    wiki_dir = WORKSPACE / "wiki"
    parts = []
    for name in ("overview.md", "index.md"):
        f = wiki_dir / name
        if f.exists():
            parts.append(f"### {name}\n" + f.read_text(encoding="utf-8"))

    # 페이지 수 통계
    counts: dict[str, int] = {}
    for cat in ("sources", "entities", "concepts", "syntheses"):
        cat_dir = wiki_dir / cat
        if cat_dir.is_dir():
            counts[cat] = len(list(cat_dir.glob("*.md")))

    stats = "\n### 페이지 통계\n" + "\n".join(
        f"- {cat}: {n}개" for cat, n in counts.items()
    )
    parts.append(stats)
    return "\n\n".join(parts) if parts else "위키가 비어 있습니다."


@mcp.tool()
def wiki_query(question: str) -> str:
    """
    위키를 기반으로 질문에 답합니다.
    wiki_search로 관련 청크를 수집해 컨텍스트로 제공합니다.
    LLM 호출이 발생하지 않으며, 수집된 관련 청크를 그대로 반환합니다.

    Args:
        question: 위키에 대한 자연어 질문
    """
    chunks_json = wiki_search(question, top_k=15)
    try:
        chunks = json.loads(chunks_json)
        if isinstance(chunks, dict) and "error" in chunks:
            return f"검색 실패: {chunks['error']}"
    except Exception:
        return chunks_json

    if not chunks:
        return "관련 페이지를 찾을 수 없습니다. 벡터 색인이 구축되어 있는지 확인해 주세요."

    context = "\n\n".join(
        f"[{c['page']}]\n{c['content']}" for c in chunks
    )
    return f"## 관련 청크 ({len(chunks)}건)\n\n{context}"


@mcp.tool()
def wiki_list_contradictions() -> str:
    """
    Contradictions 섹션이 있는 위키 페이지 목록을 반환합니다.
    lint 결과 활용 및 지식 품질 점검에 사용하세요.
    """
    wiki_dir = WORKSPACE / "wiki"
    results = []
    for md_file in wiki_dir.rglob("*.md"):
        content = md_file.read_text(encoding="utf-8", errors="ignore")
        if "## Contradictions" in content or "## 모순" in content:
            results.append(str(md_file.relative_to(WORKSPACE)).replace("\\", "/"))
    if not results:
        return "Contradictions 섹션이 있는 페이지가 없습니다."
    return json.dumps(results, ensure_ascii=False, indent=2)


if args.enable_write:
    @mcp.tool()
    def wiki_ingest(workspace: str = "") -> str:
        """
        위키 워크스페이스 전체를 재색인합니다.
        LLM Manager의 '벡터 재색인' 메뉴를 사용하는 것을 권장합니다.
        이 도구는 --enable-write 옵션이 활성화된 경우에만 사용할 수 있습니다.
        """
        return (
            "이 도구는 MCP 서버에서 직접 색인 실행을 지원하지 않습니다.\n"
            "LLM Manager의 플러그인 메뉴 > '벡터 재색인 (전체)'을 사용해 주세요."
        )


# ─────────────────────────────────────────────────────────────
# 서버 시작
# ─────────────────────────────────────────────────────────────

@mcp.custom_route("/health", methods=["GET"])
async def health():
    from starlette.responses import JSONResponse
    return JSONResponse({"status": "ok", "workspace": str(WORKSPACE)})


if __name__ == "__main__":
    print(f"[wiki-mcp] 시작 — workspace={WORKSPACE}, port={args.port}")
    mcp.run(transport="streamable-http", host="127.0.0.1", port=args.port)
