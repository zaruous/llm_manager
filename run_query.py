#!/usr/bin/env python3
"""
wiki_query 로컬 테스트 하네스.

server.py의 _wiki_query_logic()을 MCP 서버 기동 없이 터미널에서 직접 실행한다.

Usage:
    python run_query.py "작업지시 생성 방법"
    python run_query.py "질문" --top-k 5 --top-p 0.85
    python run_query.py "질문" --workspace D:/path/to/docs --db-path D:/path/to/wiki-vector.sqlite
"""

import argparse
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="wiki_query 로컬 테스트 하네스")
    parser.add_argument("question", help="위키에 질의할 자연어 질문")
    parser.add_argument("--top-k", type=int, default=10,
                        help="반환할 최대 결과 수 (기본 10, 하드 상한)")
    parser.add_argument("--top-p", type=float, default=0.0,
                        help="누적 점수 기반 동적 컷오프 (0.0이면 비활성, 0.85 권장)")
    parser.add_argument("--workspace", default="D:/git/doc/mes-wiki/docs",
                        help="위키 워크스페이스 디렉토리")
    parser.add_argument("--db-path", default="D:/git/doc/mes-wiki/wiki/.llm-manager/wiki-vector.sqlite",
                        help="위키 벡터 색인 SQLite 파일 경로")
    args = parser.parse_args()

    # server.py는 임포트 시점에 argparse로 sys.argv를 파싱하므로,
    # 임포트 전에 server.py가 기대하는 인자 형식으로 바꿔치기한다
    sys.argv = [
        "run_query.py",
        "--workspace", args.workspace,
        "--db-path", args.db_path,
    ]

    sys.path.insert(0, str(Path(__file__).resolve().parent / "plugins" / "wiki-agent"))

    try:
        import server
    except ImportError as e:
        print(f"Error: server.py를 임포트할 수 없습니다. 경로를 확인해 주세요. ({e})")
        sys.exit(1)

    print("\n=== [위키 지식 쿼리] ===")
    print(f"  질문      : '{args.question}'")
    print(f"  top_k     : {args.top_k}")
    print(f"  top_p     : {args.top_p}")
    print(f"  workspace : {args.workspace}")
    print(f"  db_path   : {args.db_path}")
    print()
    try:
        result = server._wiki_query_logic(args.question, top_k=args.top_k, top_p=args.top_p)
        print(result)
    except Exception:
        import traceback
        traceback.print_exc()
    print("========================\n")


if __name__ == "__main__":
    main()
