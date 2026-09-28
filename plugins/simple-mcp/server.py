"""
Simple MCP Server — 임베딩 없이 PostgreSQL을 조회만 하는 경량 MCP 서버.

sql-gen-mcp는 TEI(BGE-M3) 임베딩 서버·벡터 스토어까지 띄워야 해서 무겁다.
이 서버는 psycopg 하나로 DB에 직접 붙어 테이블 목록·컬럼 구조·SELECT 결과만 돌려준다.
service-packs/simple-mcp.yml에 등록되어 LLM Manager의 서비스 목록으로 관리된다.

읽기 전용 보장은 두 겹이다.
  1) 문장 검사 — 첫 키워드가 SELECT/WITH/VALUES/TABLE/EXPLAIN/SHOW가 아니거나
     세미콜론으로 여러 문장을 이어 붙이면 실행 전에 거부한다.
  2) 세션 READ ONLY — psycopg 연결을 read_only로 열어 INSERT/UPDATE/DELETE/DDL은
     문장 검사를 우회하더라도 PostgreSQL이 "read-only transaction" 오류로 막는다.
단, pg_terminate_backend() 같은 관리 함수 호출은 DB 권한이 최종 경계이므로
접속 계정은 SELECT 권한만 가진 읽기 전용 롤을 쓰는 것이 안전하다.
"""

import argparse
import json
import re
import sys
from datetime import date, datetime, time
from decimal import Decimal
from uuid import UUID

# fastmcp·psycopg는 설치 명령(pip install fastmcp psycopg[binary])으로 제공된다.
try:
    from fastmcp import FastMCP
except ImportError:
    raise SystemExit(
        "fastmcp 패키지가 없습니다. 'pip install fastmcp>=2.0.0' 실행 후 재시작해 주세요."
    )
try:
    import psycopg
    from psycopg import sql as pgsql
except ImportError:
    raise SystemExit(
        "psycopg 패키지가 없습니다. 'pip install \"psycopg[binary]\"' 실행 후 재시작해 주세요."
    )

# ─────────────────────────────────────────────────────────────
# CLI 인수 (simple-mcp.yml argSpecs와 1:1 대응)
# ─────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Simple MCP Server (read-only PostgreSQL)")
parser.add_argument("--db-url", default="postgresql://localhost:5432/postgres",
                    help="PostgreSQL 접속 URL. sql-gen-mcp와 같은 jdbc:postgresql://host:port/db 형식도 허용")
parser.add_argument("--db-user", default="", help="접속 사용자명 (URL에 없을 때)")
parser.add_argument("--db-pw", default="", help="접속 비밀번호 (URL에 없을 때)")
parser.add_argument("--db-schema", default="public", help="기본 스키마 (search_path)")
parser.add_argument("--port", type=int, default=7071, help="MCP 서버 포트")
parser.add_argument("--max-rows", type=int, default=200, help="run_query가 돌려주는 최대 행 수")
parser.add_argument("--statement-timeout", type=int, default=30,
                    help="쿼리 1건 제한 시간(초). 0이면 무제한")

# 단위 테스트가 server를 임포트할 때 테스트 러너의 argv를 파싱하지 않도록 __main__에서만 파싱한다
args = parser.parse_args() if __name__ == "__main__" else parser.parse_args([])

# 읽기 전용으로 허용하는 문장의 첫 키워드. TABLE은 `TABLE foo` (SELECT * FROM foo 축약형).
READ_ONLY_KEYWORDS = ("SELECT", "WITH", "VALUES", "TABLE", "EXPLAIN", "SHOW")

# search_path에 쓰는 스키마명은 Identifier로 인용하지만, 공백·따옴표가 섞인 값은 설정 실수이므로 거부한다
IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def normalize_db_url(url: str) -> str:
    """sql-gen-mcp 설정을 그대로 복사해 쓸 수 있도록 jdbc: 접두어를 제거한다.

    psycopg(libpq)는 postgresql:// 또는 postgres:// 스킴만 이해한다.
    """
    url = (url or "").strip()
    if url.lower().startswith("jdbc:"):
        url = url[len("jdbc:"):]
    return url


def strip_sql_comments(sql_text: str) -> str:
    """문장 검사용으로 -- 줄 주석과 /* */ 블록 주석을 제거한다. 실행에는 원문을 쓴다."""
    without_block = re.sub(r"/\*.*?\*/", " ", sql_text, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", " ", without_block)


def validate_read_only(sql_text: str) -> str:
    """실행 전 문장 검사. 통과하면 정리된 SQL을, 아니면 ValueError를 던진다.

    - 비어 있으면 거부
    - 끝의 세미콜론 하나는 허용, 그 외 세미콜론(다중 문장)은 거부
    - 첫 키워드가 READ_ONLY_KEYWORDS에 없으면 거부
    """
    cleaned = strip_sql_comments(sql_text or "").strip()
    if cleaned.endswith(";"):
        cleaned = cleaned[:-1].rstrip()
    if not cleaned:
        raise ValueError("SQL이 비어 있습니다.")
    if ";" in cleaned:
        raise ValueError("한 번에 한 문장만 실행할 수 있습니다 (세미콜론으로 이어 붙인 다중 문장 거부).")
    first = re.match(r"[A-Za-z]+", cleaned)
    keyword = first.group(0).upper() if first else ""
    if keyword not in READ_ONLY_KEYWORDS:
        raise ValueError(
            f"읽기 전용 서버입니다. {', '.join(READ_ONLY_KEYWORDS)}로 시작하는 문장만 허용됩니다 (받은 키워드: {keyword or '없음'})."
        )
    return cleaned


def to_jsonable(value):
    """psycopg가 돌려주는 Python 값 중 json.dumps가 모르는 타입을 문자열로 바꾼다."""
    if isinstance(value, Decimal):
        # 정수형 Decimal(NUMERIC(10,0))은 int로, 나머지는 정밀도 손실 없이 문자열로
        return int(value) if value == value.to_integral_value() else str(value)
    if isinstance(value, (datetime, date, time, UUID)):
        return str(value)
    if isinstance(value, (bytes, memoryview)):
        return f"<{len(bytes(value))} bytes>"
    return value


def _dump(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2, default=to_jsonable)


def _resolve_schema(schema: str) -> str:
    """도구 인수로 받은 스키마가 비어 있으면 --db-schema를 쓴다. 식별자 형식이 아니면 거부."""
    name = (schema or "").strip() or args.db_schema
    if not IDENTIFIER_RE.match(name):
        raise ValueError(f"스키마 이름이 올바르지 않습니다: {name!r}")
    return name


def _connect():
    """읽기 전용 세션 연결을 연다. 도구 호출마다 열고 닫는다 (연결 풀 없음 — 단순함 우선)."""
    kwargs = {}
    if args.db_user:
        kwargs["user"] = args.db_user
    if args.db_pw:
        kwargs["password"] = args.db_pw
    # statement_timeout은 SET 문에 바인드 파라미터를 쓸 수 없어 접속 옵션으로 넘긴다. 값은 int라 인용 불필요.
    if args.statement_timeout > 0:
        kwargs["options"] = f"-c statement_timeout={args.statement_timeout * 1000}"
    conn = psycopg.connect(normalize_db_url(args.db_url), **kwargs)
    # 트랜잭션 시작 전에 설정해야 세션 기본값(default_transaction_read_only)에 반영된다
    conn.read_only = True
    return conn


def _set_search_path(conn, schema: str) -> None:
    conn.execute(pgsql.SQL("SET search_path TO {}").format(pgsql.Identifier(schema)))


# ─────────────────────────────────────────────────────────────
# MCP 도구
# ─────────────────────────────────────────────────────────────
mcp = FastMCP("simple-mcp")


@mcp.tool()
def list_tables(schema: str = "") -> str:
    """스키마의 테이블·뷰 목록을 JSON으로 돌려준다.

    Args:
        schema: 조회할 스키마. 비우면 서버 기본 스키마(--db-schema).
    """
    try:
        schema_name = _resolve_schema(schema)
        with _connect() as conn:
            rows = conn.execute(
                """
                SELECT c.relname AS name,
                       CASE c.relkind WHEN 'v' THEN 'view' WHEN 'm' THEN 'materialized_view'
                                      WHEN 'p' THEN 'partitioned_table' ELSE 'table' END AS kind,
                       obj_description(c.oid, 'pg_class') AS comment
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = %s AND c.relkind IN ('r', 'v', 'm', 'p')
                 ORDER BY c.relname
                """,
                (schema_name,),
            ).fetchall()
        return _dump({
            "schema": schema_name,
            "tables": [{"name": r[0], "kind": r[1], "comment": r[2]} for r in rows],
        })
    except Exception as e:  # MCP 클라이언트에는 스택 대신 원인 한 줄만 전달
        return _dump({"error": str(e)})


@mcp.tool()
def describe_table(table: str, schema: str = "") -> str:
    """테이블의 컬럼(이름·타입·NULL 허용·기본값·주석)과 기본키를 JSON으로 돌려준다.

    Args:
        table: 테이블 또는 뷰 이름.
        schema: 스키마. 비우면 서버 기본 스키마(--db-schema).
    """
    try:
        schema_name = _resolve_schema(schema)
        table_name = (table or "").strip()
        if not table_name:
            raise ValueError("table 이름이 비어 있습니다.")
        with _connect() as conn:
            columns = conn.execute(
                """
                SELECT a.attname,
                       format_type(a.atttypid, a.atttypmod),
                       NOT a.attnotnull,
                       pg_get_expr(d.adbin, d.adrelid),
                       col_description(a.attrelid, a.attnum)
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE n.nspname = %s AND c.relname = %s
                   AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY a.attnum
                """,
                (schema_name, table_name),
            ).fetchall()
            if not columns:
                raise ValueError(f"테이블을 찾을 수 없습니다: {schema_name}.{table_name}")
            pk = conn.execute(
                """
                SELECT a.attname
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                 WHERE i.indisprimary AND n.nspname = %s AND c.relname = %s
                 ORDER BY array_position(i.indkey, a.attnum)
                """,
                (schema_name, table_name),
            ).fetchall()
        return _dump({
            "schema": schema_name,
            "table": table_name,
            "columns": [
                {"name": c[0], "type": c[1], "nullable": c[2], "default": c[3], "comment": c[4]}
                for c in columns
            ],
            "primary_key": [r[0] for r in pk],
        })
    except Exception as e:
        return _dump({"error": str(e)})


@mcp.tool()
def run_query(sql: str, max_rows: int = 0) -> str:
    """읽기 전용 SQL(SELECT 등) 한 문장을 실행해 컬럼·행을 JSON으로 돌려준다.

    Args:
        sql: 실행할 SQL. SELECT/WITH/VALUES/TABLE/EXPLAIN/SHOW로 시작하는 한 문장만 허용.
        max_rows: 돌려줄 최대 행 수. 0이면 서버 기본값(--max-rows), 서버 기본값을 넘길 수 없다.
    """
    try:
        cleaned = validate_read_only(sql)
        # 클라이언트가 서버 상한을 넘겨 요청해도 상한으로 자른다
        limit = args.max_rows if max_rows <= 0 else min(max_rows, args.max_rows)
        with _connect() as conn:
            _set_search_path(conn, _resolve_schema(""))
            with conn.cursor() as cur:
                cur.execute(cleaned)
                columns = [d.name for d in cur.description] if cur.description else []
                # 상한 +1개를 읽어 잘렸는지 판정한다
                fetched = cur.fetchmany(limit + 1) if cur.description else []
        truncated = len(fetched) > limit
        rows = [list(r) for r in fetched[:limit]]
        return _dump({
            "columns": columns,
            "rows": rows,
            "row_count": len(rows),
            "truncated": truncated,
            "max_rows": limit,
        })
    except Exception as e:
        return _dump({"error": str(e)})


# ─────────────────────────────────────────────────────────────
# 서버 시작
# ─────────────────────────────────────────────────────────────

@mcp.custom_route("/health", methods=["GET"])
async def health(request):
    """LLM Manager 헬스체크용. DB에는 붙지 않는다 — 주기 폴링마다 연결을 만들지 않기 위해서."""
    from starlette.responses import JSONResponse
    return JSONResponse({"status": "ok", "db": _masked_db_url()})


def _masked_db_url() -> str:
    """로그·헬스 응답용으로 URL의 비밀번호 부분(user:pw@)을 가린다."""
    return re.sub(r"://([^:/@]+):[^@]*@", r"://\1:***@", normalize_db_url(args.db_url))


if __name__ == "__main__":
    print(f"[simple-mcp] 시작: db={_masked_db_url()}, schema={args.db_schema}, "
          f"port={args.port}, max_rows={args.max_rows}", file=sys.stderr)
    mcp.run(transport="streamable-http", host="127.0.0.1", port=args.port)
