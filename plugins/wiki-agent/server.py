"""
Wiki MCP Server — FastMCP 기반 위키 시맨틱 검색·페이지 조회·질의 서버.

service-packs/wiki-mcp.yml에 등록되어 LLM Manager의 서비스 목록으로 관리된다.
groovyScript가 --db-path, --vec0-path를 자동 주입하며,
사용자는 --workspace만 지정하면 된다.
"""

import argparse
import base64
import fnmatch
import hashlib
import json
import os
import re
import shutil
import sqlite3
import statistics
import struct
import subprocess
import sys
import threading
import urllib.request
from collections import defaultdict
from datetime import date, datetime
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
parser.add_argument("--cursor-sidecar", default="", help="Cursor Agent sidecar 스크립트 경로")
parser.add_argument("--cursor-model", default="", help="Cursor 모델 (비우면 플러그인 설정 사용)")
parser.add_argument("--cursor-timeout", type=int, default=-1, help="실행 제한(초, -1=플러그인 설정, 0=무제한)")
parser.add_argument("--plugin-config", default="", help="wiki-agent plugin.json 경로")
parser.add_argument("--settings-path", default="", help="LLM Manager settings.json 경로")
args = parser.parse_args()

WORKSPACE = Path(args.workspace).resolve()
DB_PATH = args.db_path or str(WORKSPACE / ".llm-manager" / "wiki-vector.sqlite")
VEC0_PATH = args.vec0_path
EMBEDDING_URL = args.embedding_url.rstrip("/")
WIKI_DIR = WORKSPACE / "wiki" if (WORKSPACE / "wiki" / "index.md").is_file() else WORKSPACE
META_PAGE_NAMES = {"index.md", "log.md", "lint-report.md", "health-report.md"}
STUB_THRESHOLD_CHARS = 100
INGEST_LOCK = threading.Lock()
CURSOR_SIDECAR = (
    Path(args.cursor_sidecar).resolve()
    if args.cursor_sidecar
    else Path(__file__).resolve().parent / "cursor-agent-sidecar.js"
)
PLUGIN_CONFIG = (
    Path(args.plugin_config).resolve()
    if args.plugin_config
    else Path(__file__).resolve().parent / "plugin.json"
)
SETTINGS_PATH = (
    Path(args.settings_path).expanduser().resolve()
    if args.settings_path
    else Path.home() / "llm-services" / "settings.json"
)
mcp = FastMCP("wiki-mcp")


# ─────────────────────────────────────────────────────────────
# 내부 유틸
# ─────────────────────────────────────────────────────────────

def _get_conn() -> sqlite3.Connection:
    """DB 연결을 반환한다. vec0 경로가 있으면 확장을 로드한다."""
    conn = sqlite3.connect(DB_PATH)
    if VEC0_PATH:
        conn.enable_load_extension(True)
        try:
            conn.load_extension(VEC0_PATH.replace("\\", "/"))
        except Exception:
            print(f"[wiki-mcp] WARNING: vec0 로드 실패: {VEC0_PATH}", file=sys.stderr)
        finally:
            conn.enable_load_extension(False)
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


def _relative(path: Path) -> str:
    """워크스페이스 기준 상대 경로를 슬래시 구분자로 반환한다."""
    try:
        return str(path.resolve().relative_to(WORKSPACE)).replace("\\", "/")
    except ValueError:
        return str(path).replace("\\", "/")


def _resolve_page(page_path: str) -> Path:
    """workspace/wiki 루트와 wiki 루트 직접 지정 방식을 모두 지원한다."""
    clean = page_path.strip().replace("\\", "/").lstrip("/")
    candidates = [WORKSPACE / clean, WIKI_DIR / clean]
    if clean.startswith("wiki/"):
        candidates.append(WIKI_DIR / clean[5:])

    for candidate in candidates:
        resolved = candidate.resolve()
        try:
            resolved.relative_to(WORKSPACE)
        except ValueError:
            continue
        if resolved.is_file():
            return resolved

    # 파일이 없는 경우 반환할 폴백 경로에도 경계 검사를 적용한다.
    # clean에 "../" 시퀀스가 포함되면 WORKSPACE 밖으로 벗어날 수 있으므로,
    # 그 경우 파일명만 남겨 WORKSPACE 내부 경로로 고정한다.
    fallback = (WORKSPACE / clean).resolve()
    try:
        fallback.relative_to(WORKSPACE)
        return fallback
    except ValueError:
        return WORKSPACE / Path(clean).name


def _safe_filename(value: str, fallback: str = "mcp-ingest") -> str:
    """사용자 입력을 raw 스테이징에 안전한 파일명으로 정규화한다."""
    stem = Path(value).stem if value else fallback
    safe = re.sub(r"[^0-9A-Za-z가-힣._-]+", "-", stem).strip("._-")
    return (safe or fallback)[:100]


def _read_json_file(path: Path) -> dict:
    """설정 파일이 없거나 손상되어도 서버 시작을 막지 않고 빈 설정으로 폴백한다."""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _ingest_config() -> dict:
    """wiki-agent plugin.json에서 필수 ingest 설정을 읽고 형식을 검증한다."""
    config = _read_json_file(PLUGIN_CONFIG)
    if not config:
        raise ValueError(f"wiki-agent 설정 파일을 읽을 수 없습니다: {PLUGIN_CONFIG}")

    ingest = config.get("ingest")
    if not isinstance(ingest, dict):
        raise ValueError(f"wiki-agent 설정에 ingest 객체가 없습니다: {PLUGIN_CONFIG}")

    includes = ingest.get("include")
    excludes = ingest.get("exclude")
    if not isinstance(includes, list) or not all(isinstance(item, str) for item in includes):
        raise ValueError("wiki-agent ingest.include는 문자열 배열이어야 합니다.")
    if not isinstance(excludes, list) or not all(isinstance(item, str) for item in excludes):
        raise ValueError("wiki-agent ingest.exclude는 문자열 배열이어야 합니다.")
    content_max_bytes = ingest.get("contentMaxBytes")
    if not isinstance(content_max_bytes, int) or content_max_bytes <= 0:
        raise ValueError("wiki-agent ingest.contentMaxBytes는 양의 정수여야 합니다.")
    staging_directory = ingest.get("stagingDirectory")
    if not isinstance(staging_directory, str) or not staging_directory.strip():
        raise ValueError("wiki-agent ingest.stagingDirectory는 비어 있지 않은 문자열이어야 합니다.")
    return ingest


def _ingest_rules() -> tuple[list[str], list[str]]:
    """검증된 ingest 설정에서 include/exclude 규칙을 반환한다."""
    ingest = _ingest_config()
    includes = ingest["include"]
    excludes = ingest["exclude"]
    return includes, excludes


def _plugin_settings(plugin_id: str) -> dict:
    """LLM Manager settings.json에서 지정 플러그인의 비밀값 제외 설정을 읽는다."""
    plugin_settings = _read_json_file(SETTINGS_PATH).get("pluginSettings", {})
    if not isinstance(plugin_settings, dict):
        return {}
    values = plugin_settings.get(plugin_id, {})
    return values if isinstance(values, dict) else {}


def _glob_matches(value: str, pattern: str) -> bool:
    """GUI의 gitignore 스타일 선행 **/가 루트 경로도 포함하도록 보정한다."""
    normalized = pattern.lower()
    return (
        fnmatch.fnmatch(value, normalized)
        or (normalized.startswith("**/")
            and fnmatch.fnmatch(value, normalized[3:]))
    )


def _matches_ingest_rules(path: Path) -> tuple[bool, str]:
    """GUI ingest와 같은 plugin.json 규칙으로 file_path 허용 여부를 판정한다."""
    includes, excludes = _ingest_rules()
    filename = path.name.lower()
    if includes and not any(_glob_matches(filename, str(pattern))
                            for pattern in includes):
        return False, f"wiki-agent ingest.include에 허용되지 않은 파일입니다: {path.name}"

    try:
        candidate = path.relative_to(WORKSPACE).as_posix()
    except ValueError:
        candidate = path.as_posix()
    candidate = candidate.lower()
    if any(_glob_matches(candidate, str(pattern)) for pattern in excludes):
        return False, f"wiki-agent ingest.exclude에 해당하는 파일입니다: {path}"
    return True, ""


def _resolve_cursor_options() -> tuple[str, int | None]:
    """서버 기동 인수와 플러그인 설정에서 Cursor 모델·타임아웃을 결정한다."""
    cursor_settings = _plugin_settings("cursor-agent-runner")
    wiki_settings = _plugin_settings("wiki-agent")

    model = args.cursor_model.strip()
    if not model:
        model = str(cursor_settings.get("cursor.defaultModel", "auto")).strip() or "auto"

    if args.cursor_timeout >= 0:
        timeout = args.cursor_timeout or None
    else:
        raw_minutes = str(wiki_settings.get("timeoutMinutes", "")).strip()
        try:
            timeout = max(0, int(raw_minutes) * 60) or None
        except ValueError:
            timeout = None
    return model, timeout


def _file_digest(path: Path) -> str:
    """큰 파일을 메모리에 전부 올리지 않고 내용 식별자를 계산한다."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()[:12]


def _stage_ingest_source(title: str, content: str, desc: str, tags: str) -> Path:
    """YAML frontmatter + 마크다운 본문을 workspace/raw/mcp-ingest 아래 불변 파일로 저장한다."""
    if not content.strip():
        raise ValueError("content는 비울 수 없습니다.")

    ingest = _ingest_config()
    staging_root = (WORKSPACE / ingest["stagingDirectory"]).resolve()
    try:
        staging_root.relative_to(WORKSPACE)
    except ValueError as exc:
        raise ValueError(
            "wiki-agent ingest.stagingDirectory는 워크스페이스 내부 경로여야 합니다."
        ) from exc

    day_dir = staging_root / date.today().isoformat()
    day_dir.mkdir(parents=True, exist_ok=True)

    # YAML frontmatter 조립
    fm = ["---", f"title: {title}"]
    if desc.strip():
        fm.append(f"desc: {desc.strip()}")
    if tags.strip():
        fm.append(f"tags: [{tags.strip()}]")
    fm.append(f"ingest_at: {datetime.now().isoformat(timespec='seconds')}")
    fm.append("---")
    fm.append("")

    document = "\n".join(fm) + content.strip() + "\n"
    encoded = document.encode("utf-8")

    content_max_bytes = ingest["contentMaxBytes"]
    if len(encoded) > content_max_bytes:
        raise ValueError(
            f"content는 최대 {content_max_bytes}바이트까지 입력할 수 있습니다."
        )

    base = _safe_filename(title)
    digest = hashlib.sha256(encoded).hexdigest()[:12]
    target = day_dir / f"{base}-{digest}.md"
    if not target.exists():
        target.write_bytes(encoded)
    return target


def _sync_wiki_tools() -> None:
    """설치된 wiki-agent 도구를 워크스페이스에 동기화해 GUI ingest와 실행 조건을 맞춘다."""
    source_dir = Path(__file__).resolve().parent / "tools"
    if not source_dir.is_dir():
        return
    target_dir = WORKSPACE / "tools"
    target_dir.mkdir(parents=True, exist_ok=True)
    for source in source_dir.glob("*.py"):
        shutil.copy2(source, target_dir / source.name)


def _run_cursor_ingest(source: Path) -> tuple[bool, str]:
    """기존 Cursor sidecar를 사용해 AGENTS.md 기반 ingest를 실행한다."""
    if not CURSOR_SIDECAR.is_file():
        return False, f"Cursor sidecar 스크립트가 없습니다: {CURSOR_SIDECAR}"
    agents_md = WORKSPACE / "AGENTS.md"
    if not agents_md.is_file():
        # server.py 옆에 번들된 AGENTS.md를 워크스페이스에 자동 설치
        bundled = Path(__file__).resolve().parent / "AGENTS.md"
        if not bundled.is_file():
            return False, "AGENTS.md가 없습니다. LLM Wiki Agent 골격을 먼저 설치하세요."
        shutil.copy2(bundled, agents_md)
    if not os.getenv("CURSOR_API_KEY"):
        return False, "CURSOR_API_KEY 환경변수가 없습니다."

    try:
        _sync_wiki_tools()
    except OSError as exc:
        return False, f"wiki-agent 도구 동기화 실패: {exc}"

    resolved_model, timeout = _resolve_cursor_options()
    try:
        includes, excludes = _ingest_rules()
    except ValueError as exc:
        return False, str(exc)
    relative_source = _relative(source)
    prompt = (
        "Follow the wiki workflow defined in AGENTS.md. "
        "Ingest the following staged source file into the wiki. "
        "Treat the file as immutable source material. If it was already ingested and the "
        "corresponding wiki/sources page is up to date, skip it and report that result. "
        "Create or update the source page, index, overview, entity/concept pages, "
        "contradictions, log, and run post-ingest validation. "
        "Honor the wiki-agent plugin ingest configuration when handling sources. "
        f"Configured include patterns: {json.dumps(includes, ensure_ascii=False)}. "
        f"Configured exclude patterns: {json.dumps(excludes, ensure_ascii=False)}. "
        f"Source file: {relative_source}"
    )
    payload = {
        "commandId": "wiki.ingest",
        "cwd": str(WORKSPACE),
        "prompt": prompt,
        "model": resolved_model,
        "mode": "sdk",
    }
    encoded = base64.b64encode(
        json.dumps(payload, ensure_ascii=False).encode("utf-8")
    ).decode("ascii")
    env = os.environ.copy()
    env["LLM_MANAGER_STREAM"] = "1"

    try:
        result = subprocess.run(
            ["node", str(CURSOR_SIDECAR), encoded],
            cwd=WORKSPACE,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return False, "Node.js 실행 파일을 찾을 수 없습니다."
    except subprocess.TimeoutExpired:
        return False, f"Cursor Agent 실행 시간이 초과되었습니다 ({timeout}초)."
    except Exception as exc:
        return False, f"Cursor Agent 실행 실패: {exc}"

    output_lines = []
    final_success = result.returncode == 0
    for line in result.stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            if line.strip():
                output_lines.append(line.strip())
            continue
        event_type = event.get("type", "")
        message = str(event.get("message", "")).strip()
        if event_type == "error":
            final_success = False
        if event_type == "done":
            final_success = final_success and bool(event.get("success", True))
        if message and event_type in {"text", "done", "error"}:
            output_lines.append(message)

    if result.stderr.strip():
        output_lines.append(result.stderr.strip())
    summary = "\n".join(output_lines).strip()
    if not summary:
        summary = f"Cursor Agent 종료 코드: {result.returncode}"
    # MCP 응답이 과도하게 커지는 것을 막되 최종 결과는 보존한다.
    if len(summary) > 20000:
        summary = "...(앞부분 생략)...\n" + summary[-20000:]
    return final_success, summary


def _page_type(path: Path) -> str:
    """frontmatter type 또는 1차 디렉토리명으로 페이지 타입을 추정한다."""
    try:
        content = path.read_text(encoding="utf-8", errors="ignore")
        if content.startswith("---"):
            end = content.find("\n---", 3)
            if end > 0:
                for line in content[3:end].splitlines():
                    if line.lower().startswith("type:"):
                        return line.split(":", 1)[1].strip()
    except Exception:
        pass

    try:
        rel = path.relative_to(WIKI_DIR)
        return rel.parts[0] if len(rel.parts) > 1 else "page"
    except Exception:
        return "page"


def _keyword_search(query: str, top_k: int = 10, type_filter: str = "") -> list[dict]:
    """벡터 색인이 없을 때 사용할 단순 키워드 검색 폴백."""
    terms = [t.lower() for t in query.replace("/", " ").split() if len(t.strip()) >= 2]
    if not terms:
        terms = [query.lower()]

    results = []
    for md_file in WIKI_DIR.rglob("*.md"):
        try:
            content = md_file.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        page_type = _page_type(md_file)
        if type_filter and page_type != type_filter:
            continue

        lower = content.lower()
        title = md_file.stem.lower()
        score = sum(lower.count(term) for term in terms) + sum(3 for term in terms if term in title)
        if score <= 0:
            continue

        first_hit = min((lower.find(term) for term in terms if lower.find(term) >= 0), default=0)
        start = max(0, first_hit - 180)
        excerpt = content[start:start + 900].strip()
        results.append({
            "page": _relative(md_file),
            "chunk": 0,
            "type": page_type,
            "content": excerpt,
            "score": float(score),
            "mode": "keyword",
        })

    results.sort(key=lambda r: r["score"], reverse=True)
    return results[:top_k]


def _read_file(path: Path) -> str:
    """UTF-8 텍스트 파일을 읽고, 없거나 읽기 실패하면 빈 문자열을 반환한다."""
    try:
        return path.read_text(encoding="utf-8", errors="ignore") if path.exists() else ""
    except Exception:
        return ""


def _wiki_relative(path: Path) -> str:
    """위키 루트 기준 상대 경로를 슬래시 구분자로 반환한다."""
    try:
        return path.resolve().relative_to(WIKI_DIR).as_posix()
    except ValueError:
        return _relative(path)


def _all_wiki_pages() -> list[Path]:
    """MCP 품질검사 대상 위키 페이지 목록을 반환한다."""
    if not WIKI_DIR.exists():
        return []
    return sorted(p for p in WIKI_DIR.rglob("*.md") if p.name not in META_PAGE_NAMES)


def _strip_frontmatter(content: str) -> str:
    """YAML frontmatter를 제거한 본문을 반환한다."""
    if content.startswith("---"):
        end = content.find("\n---", 3)
        if end != -1:
            return content[end + 4:].strip()
    return content.strip()


def _extract_wikilinks(content: str) -> list[str]:
    """[[Page]], [[Page#Heading]], [[Page|Alias]] 형태의 위키링크 대상명을 추출한다."""
    links = []
    for raw in re.findall(r"\[\[([^\]]+)\]\]", content):
        target = raw.split("|", 1)[0].split("#", 1)[0].strip()
        if target:
            links.append(target)
    return links


def _build_page_lookup(pages: list[Path]) -> dict[str, list[Path]]:
    """위키링크 이름을 페이지 파일로 해석하기 위한 lookup을 구성한다."""
    lookup: dict[str, list[Path]] = defaultdict(list)
    for page in pages:
        rel = _wiki_relative(page)
        keys = {
            page.stem.lower(),
            rel.lower(),
            rel[:-3].lower() if rel.endswith(".md") else rel.lower(),
        }
        for key in keys:
            lookup[key].append(page)
    return lookup


def _resolve_wikilink(link: str, lookup: dict[str, list[Path]]) -> list[Path]:
    """위키링크 문자열을 실제 페이지 후보로 해석한다."""
    clean = link.replace("\\", "/").strip()
    if clean.endswith(".md"):
        keys = [clean.lower(), clean[:-3].lower(), Path(clean).stem.lower()]
    else:
        keys = [clean.lower(), Path(clean).stem.lower()]

    matches: list[Path] = []
    seen: set[Path] = set()
    for key in keys:
        for page in lookup.get(key, []):
            if page not in seen:
                matches.append(page)
                seen.add(page)
    return matches


def _parse_frontmatter_title(content: str) -> str:
    """frontmatter title 값을 간단히 추출한다."""
    match = re.search(r"^title:\s*(.+?)\s*$", content, re.MULTILINE)
    if not match:
        return ""
    raw = match.group(1).strip()
    if len(raw) >= 2 and raw[0] == raw[-1] == '"':
        raw = raw[1:-1].replace(r"\"", '"').replace(r"\\", "\\")
    elif len(raw) >= 2 and raw[0] == raw[-1] == "'":
        raw = raw[1:-1].replace("''", "'")
    return raw.strip()


def _check_empty_files(pages: list[Path], threshold: int = STUB_THRESHOLD_CHARS) -> list[dict]:
    """본문 길이가 임계값 미만인 빈 파일/스텁 파일을 찾는다."""
    results = []
    for page in pages:
        raw = _read_file(page)
        body = _strip_frontmatter(raw)
        if len(body) < threshold:
            results.append({
                "path": _relative(page),
                "total_bytes": len(raw),
                "body_bytes": len(body),
                "status": "empty" if len(body) == 0 else "stub",
            })
    return sorted(results, key=lambda item: item["body_bytes"])


def _parse_index_links(index_content: str) -> set[str]:
    """index.md의 markdown 링크 대상 중 .md 파일 링크만 추출한다."""
    links = set()
    for raw in re.findall(r"\[.*?\]\(([^)]+\.md(?:#[^)]+)?)\)", index_content):
        link = raw.split("#", 1)[0].strip()
        if link and "://" not in link and not link.startswith("#"):
            links.add(link)
    return links


def _check_index_sync(pages: list[Path]) -> dict:
    """index.md 링크와 실제 디스크 파일 목록의 차이를 계산한다."""
    index_links = _parse_index_links(_read_file(WIKI_DIR / "index.md"))
    meta_pages = {"overview.md"}

    index_paths = set()
    for link in index_links:
        resolved = (WIKI_DIR / link.replace("\\", "/")).resolve()
        if Path(link).name not in meta_pages:
            index_paths.add(resolved)

    disk_paths = {
        page.resolve()
        for page in pages
        if page.name not in meta_pages
    }

    stale = []
    for path in sorted(index_paths - disk_paths):
        try:
            stale.append(_relative(path))
        except Exception:
            stale.append(path.as_posix())

    missing = [_relative(path) for path in sorted(disk_paths - index_paths)]
    return {
        "in_index_not_on_disk": stale,
        "on_disk_not_in_index": missing,
    }


def _parse_log_entries(log_content: str) -> set[str]:
    """log.md의 ingest 제목을 소문자 집합으로 추출한다."""
    return {
        match.group(1).strip().lower()
        for match in re.finditer(
            r"^## \[\d{4}-\d{2}-\d{2}\] ingest \| (.+)$",
            log_content,
            re.MULTILINE,
        )
    }


def _check_log_coverage() -> list[dict]:
    """sources/*.md 중 log.md ingest 기록과 매칭되지 않는 파일을 찾는다."""
    source_dir = WIKI_DIR / "sources"
    if not source_dir.exists():
        return []

    logged_titles = _parse_log_entries(_read_file(WIKI_DIR / "log.md"))
    missing = []
    for page in sorted(source_dir.glob("*.md")):
        slug = page.stem.lower().replace("-", " ").replace("_", " ")
        title = _parse_frontmatter_title(_read_file(page))
        if slug not in logged_titles and title.lower() not in logged_titles:
            missing.append({
                "path": _relative(page),
                "slug": page.stem,
                "title": title or page.stem,
            })
    return missing


def _run_health() -> dict:
    """구조적 health check를 실행하고 구조화 결과를 반환한다."""
    pages = _all_wiki_pages()
    return {
        "date": date.today().isoformat(),
        "workspace": str(WORKSPACE),
        "wiki_dir": str(WIKI_DIR),
        "total_pages": len(pages),
        "empty_files": _check_empty_files(pages),
        "index_sync": _check_index_sync(pages),
        "log_coverage": _check_log_coverage(),
    }


def _format_health_report(results: dict) -> str:
    """health check 결과를 markdown으로 포맷한다."""
    lines = [
        f"# Wiki Health Report - {results['date']}",
        "",
        f"Workspace: `{results['workspace']}`",
        f"Wiki dir: `{results['wiki_dir']}`",
        f"Scanned {results['total_pages']} wiki pages. Checks are deterministic and do not call an LLM.",
        "",
    ]

    empty = results["empty_files"]
    lines += [f"## Empty / Stub Files ({len(empty)} found)", ""]
    if empty:
        lines += ["| Page | Total Bytes | Body Bytes | Status |", "|---|---:|---:|---|"]
        lines += [
            f"| `{item['path']}` | {item['total_bytes']} | {item['body_bytes']} | {item['status']} |"
            for item in empty
        ]
    else:
        lines.append("All pages have content beyond frontmatter.")
    lines.append("")

    index_sync = results["index_sync"]
    stale = index_sync["in_index_not_on_disk"]
    missing = index_sync["on_disk_not_in_index"]
    lines += [f"## Index Sync ({len(stale) + len(missing)} issues)", ""]
    if stale:
        lines.append("### Stale Index Entries")
        lines += [f"- `{path}`" for path in stale]
        lines.append("")
    if missing:
        lines.append("### Missing from Index")
        lines += [f"- `{path}`" for path in missing]
        lines.append("")
    if not stale and not missing:
        lines += ["index.md is in sync with disk.", ""]

    log_missing = results["log_coverage"]
    lines += [f"## Log Coverage ({len(log_missing)} source pages without log entry)", ""]
    if log_missing:
        lines += [f"- `{item['path']}` - {item['title']}" for item in log_missing]
    else:
        lines.append("All source pages have corresponding ingest log entries.")
    lines.append("")
    return "\n".join(lines)


def _find_orphans(pages: list[Path], lookup: dict[str, list[Path]]) -> list[Path]:
    """다른 페이지에서 inbound wikilink가 없는 페이지를 찾는다."""
    inbound: dict[Path, int] = defaultdict(int)
    for page in pages:
        for link in _extract_wikilinks(_read_file(page)):
            for target in _resolve_wikilink(link, lookup):
                if target != page:
                    inbound[target] += 1
    return [
        page
        for page in pages
        if inbound[page] == 0 and page.name != "overview.md"
    ]


def _find_broken_links(pages: list[Path], lookup: dict[str, list[Path]]) -> list[dict]:
    """실제 페이지로 해석되지 않는 위키링크를 찾는다."""
    broken = []
    for page in pages:
        for link in _extract_wikilinks(_read_file(page)):
            if not _resolve_wikilink(link, lookup):
                broken.append({"page": _relative(page), "link": link})
    return broken


def _find_missing_entities(pages: list[Path], lookup: dict[str, list[Path]]) -> list[dict]:
    """여러 페이지에서 언급되지만 별도 페이지가 없는 위키링크 대상을 찾는다."""
    mention_pages: dict[str, set[str]] = defaultdict(set)
    display_names: dict[str, str] = {}
    for page in pages:
        for link in set(_extract_wikilinks(_read_file(page))):
            if _resolve_wikilink(link, lookup):
                continue
            key = link.lower()
            mention_pages[key].add(_relative(page))
            display_names.setdefault(key, link)

    results = []
    for key, source_pages in sorted(mention_pages.items()):
        if len(source_pages) >= 3:
            results.append({
                "name": display_names[key],
                "mentions": len(source_pages),
                "pages": sorted(source_pages),
            })
    return results


def _check_link_density(pages: list[Path], min_outbound: int = 2) -> list[dict]:
    """outbound 위키링크 수가 기준 미만인 페이지를 찾는다."""
    sparse = []
    for page in pages:
        if page.name == "overview.md":
            continue
        links = sorted({link.lower() for link in _extract_wikilinks(_read_file(page))})
        if len(links) < min_outbound:
            sparse.append({
                "path": _relative(page),
                "outbound_links": len(links),
                "links": links,
            })
    return sorted(sparse, key=lambda item: (item["outbound_links"], item["path"]))


def _find_contradiction_pages(pages: list[Path]) -> list[str]:
    """Contradictions 섹션이 있는 페이지를 찾는다."""
    results = []
    for page in pages:
        content = _read_file(page)
        if "## Contradictions" in content or "## 모순" in content:
            results.append(_relative(page))
    return results


def _graph_json_path() -> Path | None:
    """워크스페이스 배치 방식에 맞는 graph.json 경로를 찾는다."""
    candidates = [
        WORKSPACE / "graph" / "graph.json",
        WIKI_DIR.parent / "graph" / "graph.json",
    ]
    seen: set[Path] = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        if resolved.is_file():
            return resolved
    return None


def _load_graph_data() -> tuple[dict | None, str]:
    """graph.json을 읽고, 없거나 깨졌으면 상태 문자열을 함께 반환한다."""
    graph_path = _graph_json_path()
    if graph_path is None:
        return None, "missing"
    try:
        return json.loads(graph_path.read_text(encoding="utf-8")), str(graph_path)
    except Exception:
        return None, "invalid"


def _build_degree_map(graph_data: dict) -> dict[str, int]:
    """graph edge를 기준으로 node degree를 계산한다."""
    degrees = {node.get("id"): 0 for node in graph_data.get("nodes", []) if node.get("id")}
    for edge in graph_data.get("edges", []):
        source = edge.get("from")
        target = edge.get("to")
        if source:
            degrees[source] = degrees.get(source, 0) + 1
        if target:
            degrees[target] = degrees.get(target, 0) + 1
    return degrees


def _build_community_map(graph_data: dict) -> dict[str, int]:
    """graph node의 community/group 매핑을 구성한다."""
    return {
        node["id"]: node.get("group", -1)
        for node in graph_data.get("nodes", [])
        if node.get("id")
    }


def _check_hub_stubs(graph_data: dict, pages: list[Path], min_content_chars: int = 500) -> list[dict]:
    """연결이 많은 hub인데 본문이 짧은 페이지를 찾는다."""
    degrees = _build_degree_map(graph_data)
    degree_values = list(degrees.values())
    if len(degree_values) < 2:
        return []

    threshold = statistics.mean(degree_values) + 2 * statistics.stdev(degree_values)
    node_to_path = {
        _wiki_relative(page).replace(".md", ""): page
        for page in pages
    }

    results = []
    for node_id, degree in degrees.items():
        if degree <= threshold:
            continue
        page = node_to_path.get(node_id)
        if not page:
            continue
        content_len = len(_strip_frontmatter(_read_file(page)))
        if content_len < min_content_chars:
            results.append({
                "node_id": node_id,
                "degree": degree,
                "content_len": content_len,
                "path": _relative(page),
            })
    return sorted(results, key=lambda item: item["degree"], reverse=True)


def _check_fragile_bridges(graph_data: dict) -> list[dict]:
    """커뮤니티 간 연결이 단일 edge에 의존하는 bridge를 찾는다."""
    community_map = _build_community_map(graph_data)
    cross_community: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for edge in graph_data.get("edges", []):
        source = edge.get("from")
        target = edge.get("to")
        source_community = community_map.get(source, -1)
        target_community = community_map.get(target, -1)
        if source_community < 0 or target_community < 0 or source_community == target_community:
            continue
        key = (min(source_community, target_community), max(source_community, target_community))
        cross_community[key].append(edge)

    return [
        {
            "community_a": pair[0],
            "community_b": pair[1],
            "bridge_from": edges[0].get("from", ""),
            "bridge_to": edges[0].get("to", ""),
        }
        for pair, edges in sorted(cross_community.items())
        if len(edges) == 1
    ]


def _check_isolated_communities(graph_data: dict) -> list[dict]:
    """외부 연결이 없는 2개 이상 노드 커뮤니티를 찾는다."""
    community_map = _build_community_map(graph_data)
    members: dict[int, list[str]] = defaultdict(list)
    for node_id, community_id in community_map.items():
        if community_id >= 0:
            members[community_id].append(node_id)

    has_external: set[int] = set()
    for edge in graph_data.get("edges", []):
        source_community = community_map.get(edge.get("from"), -1)
        target_community = community_map.get(edge.get("to"), -1)
        if source_community >= 0 and target_community >= 0 and source_community != target_community:
            has_external.add(source_community)
            has_external.add(target_community)

    return [
        {
            "community_id": community_id,
            "node_count": len(node_ids),
            "members": sorted(node_ids)[:10],
        }
        for community_id, node_ids in sorted(members.items())
        if len(node_ids) >= 2 and community_id not in has_external
    ]


def _run_lint() -> dict:
    """MCP용 deterministic lint를 실행한다. LLM/API 호출은 하지 않는다."""
    pages = _all_wiki_pages()
    lookup = _build_page_lookup(pages)
    graph_data, graph_status = _load_graph_data()

    graph_checks = {
        "status": graph_status,
        "hub_stubs": [],
        "fragile_bridges": [],
        "isolated_communities": [],
    }
    if graph_data and graph_data.get("nodes") and graph_data.get("edges"):
        graph_checks.update({
            "node_count": len(graph_data.get("nodes", [])),
            "edge_count": len(graph_data.get("edges", [])),
            "hub_stubs": _check_hub_stubs(graph_data, pages),
            "fragile_bridges": _check_fragile_bridges(graph_data),
            "isolated_communities": _check_isolated_communities(graph_data),
        })
    elif graph_data:
        graph_checks["status"] = "empty"

    return {
        "date": date.today().isoformat(),
        "workspace": str(WORKSPACE),
        "wiki_dir": str(WIKI_DIR),
        "total_pages": len(pages),
        "orphans": [_relative(page) for page in _find_orphans(pages, lookup)],
        "broken_links": _find_broken_links(pages, lookup),
        "missing_entities": _find_missing_entities(pages, lookup),
        "sparse_pages": _check_link_density(pages),
        "contradiction_pages": _find_contradiction_pages(pages),
        "graph": graph_checks,
    }


def _format_lint_report(results: dict) -> str:
    """lint 결과를 markdown으로 포맷한다."""
    lines = [
        f"# Wiki Lint Report - {results['date']}",
        "",
        f"Workspace: `{results['workspace']}`",
        f"Wiki dir: `{results['wiki_dir']}`",
        f"Scanned {results['total_pages']} wiki pages. MCP lint is deterministic and does not call an LLM.",
        "",
    ]

    orphans = results["orphans"]
    lines += [f"## Orphan Pages ({len(orphans)})", ""]
    lines += [f"- `{path}`" for path in orphans] if orphans else ["No orphan pages found."]
    lines.append("")

    broken = results["broken_links"]
    lines += [f"## Broken Wikilinks ({len(broken)})", ""]
    if broken:
        lines += ["| Page | Link |", "|---|---|"]
        lines += [f"| `{item['page']}` | `[[{item['link']}]]` |" for item in broken]
    else:
        lines.append("No broken wikilinks found.")
    lines.append("")

    missing_entities = results["missing_entities"]
    lines += [f"## Missing Entity Pages ({len(missing_entities)})", ""]
    if missing_entities:
        lines += ["| Entity | Mentions | Pages |", "|---|---:|---|"]
        for item in missing_entities:
            pages = ", ".join(f"`{page}`" for page in item["pages"][:5])
            if len(item["pages"]) > 5:
                pages += ", ..."
            lines.append(f"| `[[{item['name']}]]` | {item['mentions']} | {pages} |")
    else:
        lines.append("No missing entity page candidates found.")
    lines.append("")

    sparse_pages = results["sparse_pages"]
    lines += [f"## Sparse Pages ({len(sparse_pages)})", ""]
    if sparse_pages:
        lines += ["| Page | Outbound Links | Existing Links |", "|---|---:|---|"]
        for item in sparse_pages:
            links = ", ".join(f"`[[{link}]]`" for link in item["links"]) if item["links"] else "-"
            lines.append(f"| `{item['path']}` | {item['outbound_links']} | {links} |")
    else:
        lines.append("All checked pages meet the outbound link density threshold.")
    lines.append("")

    contradictions = results["contradiction_pages"]
    lines += [f"## Contradiction Sections ({len(contradictions)})", ""]
    lines += [f"- `{path}`" for path in contradictions] if contradictions else ["No contradiction sections found."]
    lines.append("")

    graph = results["graph"]
    lines += [f"## Graph-Aware Issues ({graph['status']})", ""]
    if graph["status"] in {"missing", "invalid", "empty"}:
        lines.append("Graph-aware checks were skipped or unavailable.")
    else:
        lines.append(f"Graph nodes: {graph.get('node_count', 0)}, edges: {graph.get('edge_count', 0)}")
        lines.append("")
        lines.append(f"### Hub Stubs ({len(graph['hub_stubs'])})")
        if graph["hub_stubs"]:
            lines += ["| Page | Degree | Body Length |", "|---|---:|---:|"]
            lines += [
                f"| `{item['path']}` | {item['degree']} | {item['content_len']} |"
                for item in graph["hub_stubs"]
            ]
        else:
            lines.append("No hub stubs found.")
        lines.append("")

        lines.append(f"### Fragile Bridges ({len(graph['fragile_bridges'])})")
        lines += [
            f"- Community {item['community_a']} <-> {item['community_b']}: "
            f"`{item['bridge_from']}` -> `{item['bridge_to']}`"
            for item in graph["fragile_bridges"]
        ] if graph["fragile_bridges"] else ["No fragile bridges found."]
        lines.append("")

        lines.append(f"### Isolated Communities ({len(graph['isolated_communities'])})")
        if graph["isolated_communities"]:
            lines += ["| Community | Nodes | Members |", "|---|---:|---|"]
            for item in graph["isolated_communities"]:
                members = ", ".join(f"`{member}`" for member in item["members"])
                lines.append(f"| {item['community_id']} | {item['node_count']} | {members} |")
        else:
            lines.append("No isolated communities found.")
    lines.append("")

    return "\n".join(lines)


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
    db_file = Path(DB_PATH)
    if not db_file.is_file():
        return json.dumps(_keyword_search(query, top_k, type_filter), ensure_ascii=False, indent=2)

    try:
        emb = _embed(query)
        blob = _floats_to_blob(emb)
    except Exception:
        return json.dumps(_keyword_search(query, top_k, type_filter), ensure_ascii=False, indent=2)

    try:
        conn = _get_conn()
        where_type = "AND c.type = ?" if type_filter else ""
        params = [blob, top_k] + ([type_filter] if type_filter else [])
        try:
            # vec0 MATCH 쿼리 — vec0 없으면 OperationalError 발생하여 폴백으로 이동
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
        finally:
            conn.close()
    except Exception:
        return json.dumps(_keyword_search(query, top_k, type_filter), ensure_ascii=False, indent=2)

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
    target = _resolve_page(page_path)
    if not target.is_file():
        return f"페이지를 찾을 수 없습니다: {page_path}"
    return target.read_text(encoding="utf-8")


@mcp.tool()
def wiki_overview() -> str:
    """
    위키 전체 개요를 반환합니다. overview.md, index.md, 페이지 수 통계를 포함합니다.
    에이전트의 위키 구조 파악 첫 번째 단계로 사용하세요.
    """
    parts = []
    for name in ("overview.md", "index.md"):
        f = WIKI_DIR / name
        if f.exists():
            parts.append(f"### {name}\n" + f.read_text(encoding="utf-8"))

    # 페이지 수 통계
    counts: dict[str, int] = {}
    for cat in ("sources", "entities", "concepts", "syntheses"):
        cat_dir = WIKI_DIR / cat
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
    results = []
    for md_file in WIKI_DIR.rglob("*.md"):
        content = md_file.read_text(encoding="utf-8", errors="ignore")
        if "## Contradictions" in content or "## 모순" in content:
            results.append(str(md_file.relative_to(WORKSPACE)).replace("\\", "/"))
    if not results:
        return "Contradictions 섹션이 있는 페이지가 없습니다."
    return json.dumps(results, ensure_ascii=False, indent=2)


@mcp.tool()
def wiki_health(save: bool = False, as_json: bool = False) -> str:
    """
    위키의 구조적 무결성을 빠르게 점검합니다. LLM/API 호출은 발생하지 않습니다.

    점검 항목:
    - 빈 파일 또는 스텁 파일
    - index.md와 실제 파일 목록 동기화
    - sources/*.md의 log.md ingest 기록 누락

    Args:
        save: true이면 wiki/health-report.md에 markdown 리포트를 저장
        as_json: true이면 구조화된 JSON으로 반환
    """
    results = _run_health()
    if as_json:
        return json.dumps(results, ensure_ascii=False, indent=2)

    report = _format_health_report(results)
    if save:
        WIKI_DIR.mkdir(parents=True, exist_ok=True)
        (WIKI_DIR / "health-report.md").write_text(report, encoding="utf-8")
    return report


@mcp.tool()
def wiki_lint(save: bool = False, as_json: bool = False) -> str:
    """
    위키의 링크/품질 문제를 점검합니다. LLM/API 호출은 발생하지 않습니다.

    점검 항목:
    - inbound 링크가 없는 고아 페이지
    - 깨진 [[WikiLink]]
    - 여러 페이지에서 언급되지만 페이지가 없는 엔티티 후보
    - outbound 링크가 부족한 sparse 페이지
    - Contradictions 섹션이 있는 페이지
    - graph/graph.json이 있으면 hub stub, fragile bridge, isolated community

    시맨틱 품질 검사(모순·오래된 요약·데이터 공백)는 wiki-agent 플러그인 커맨드에서 실행합니다.

    Args:
        save: true이면 wiki/lint-report.md에 markdown 리포트를 저장
        as_json: true이면 구조화된 JSON으로 반환
    """
    results = _run_lint()
    if as_json:
        return json.dumps(results, ensure_ascii=False, indent=2)

    report = _format_lint_report(results)
    if save:
        WIKI_DIR.mkdir(parents=True, exist_ok=True)
        (WIKI_DIR / "lint-report.md").write_text(report, encoding="utf-8")
    return report


if args.enable_write:
    @mcp.tool()
    def wiki_ingest(
        title: str,
        content: str,
        desc: str = "",
        tags: str = "",
    ) -> str:
        """
        마크다운 내용을 YAML frontmatter와 함께 raw/mcp-ingest/에 불변 저장한 뒤
        Cursor Agent로 위키에 수집합니다.
        서버 시작 시 --workspace로 지정된 워크스페이스만 수정합니다.
        이 도구는 --enable-write 옵션이 활성화된 경우에만 노출됩니다.

        Args:
            title: 위키 페이지 제목 (파일명 슬러그로 자동 변환)
            content: 마크다운 본문 (필수)
            desc: 한 줄 요약 — Cursor Agent 컨텍스트로 전달
            tags: 콤마 구분 태그 — 위키 분류·링크 힌트 (예: "AI, Claude, LLM")
        """
        with INGEST_LOCK:
            started_at = datetime.now().isoformat(timespec="seconds")
            try:
                staged = _stage_ingest_source(title, content, desc, tags)
            except ValueError as exc:
                return json.dumps(
                    {"success": False, "error": str(exc)},
                    ensure_ascii=False,
                    indent=2,
                )

            success, message = _run_cursor_ingest(staged)
        return json.dumps(
            {
                "success": success,
                "workspace": str(WORKSPACE),
                "staged_source": _relative(staged),
                "started_at": started_at,
                "message": message,
            },
            ensure_ascii=False,
            indent=2,
        )


# ─────────────────────────────────────────────────────────────
# 서버 시작
# ─────────────────────────────────────────────────────────────

@mcp.custom_route("/health", methods=["GET"])
async def health(request):
    from starlette.responses import JSONResponse
    return JSONResponse({"status": "ok", "workspace": str(WORKSPACE)})


if __name__ == "__main__":
    print(f"[wiki-mcp] 시작: workspace={WORKSPACE}, port={args.port}")
    mcp.run(transport="streamable-http", host="127.0.0.1", port=args.port)
