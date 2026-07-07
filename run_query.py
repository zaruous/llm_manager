import sys
from pathlib import Path

# 원래 터미널에서 입력한 인자 추출
user_query = "작업지시 생성 방법"
top_k = 10
top_p = 0.0

i = 1
while i < len(sys.argv):
    if sys.argv[i] == "--top-k" and i + 1 < len(sys.argv):
        top_k = int(sys.argv[i + 1])
        i += 2
    elif sys.argv[i] == "--top-p" and i + 1 < len(sys.argv):
        top_p = float(sys.argv[i + 1])
        i += 2
    elif sys.argv[i].startswith("--"):
        i += 2  # 알 수 없는 플래그 건너뜀
    else:
        user_query = sys.argv[i]
        i += 1

# server.py 임포트 시 argparse 검증을 통과하기 위해 모킹 인수 주입
sys.argv = [
    "run_query.py",
    "--workspace", "D:/git/doc/mes-wiki/docs",
    "--db-path", "D:/git/doc/mes-wiki/wiki/.llm-manager/wiki-vector.sqlite"
]

# server.py 임포트 경로 등록
sys.path.insert(0, str(Path(__file__).resolve().parent / "plugins" / "wiki-agent"))

try:
    import server
except ImportError as e:
    print(f"Error: server.py를 임포트할 수 없습니다. 경로를 확인해 주세요. ({e})")
    sys.exit(1)

print(f"\n=== [위키 지식 쿼리] ===")
print(f"  질문   : '{user_query}'")
print(f"  top_k  : {top_k}")
print(f"  top_p  : {top_p}")
print()
try:
    result = server._wiki_query_logic(user_query, top_k=top_k, top_p=top_p)
    print(result)
except Exception as e:
    import traceback
    traceback.print_exc()
print("========================\n")
