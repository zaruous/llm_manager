import unittest
from unittest.mock import patch, MagicMock
from pathlib import Path
import sys

# plugins/wiki-agent 디렉토리를 path에 추가하여 server.py를 임포트할 수 있게 함
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# server.py 임포트 시 argparse 검증을 통과하기 위해 모킹 인수 주입
sys.argv = [
    sys.argv[0],
    "--workspace", "C:/mock/workspace",
    "--db-path", "C:/mock/workspace/.llm-manager/wiki-vector.sqlite"
]

import server

class TestWikiServer(unittest.TestCase):

    def setUp(self):
        self.original_workspace = server.WORKSPACE
        self.original_wiki_dir = server.WIKI_DIR
        self.original_db_path = server.DB_PATH

        server.WORKSPACE = Path("C:/mock/workspace")
        server.WIKI_DIR = Path("C:/mock/workspace/wiki")
        server.DB_PATH = "C:/mock/workspace/.llm-manager/wiki-vector.sqlite"

    def tearDown(self):
        server.WORKSPACE = self.original_workspace
        server.WIKI_DIR = self.original_wiki_dir
        server.DB_PATH = self.original_db_path

    def test_rrf_merge_basic(self):
        """벡터와 키워드 결과를 RRF로 병합하면 양쪽에 모두 등장하는 문서가 최상위에 온다."""
        print("\n\n=== [테스트] RRF 하이브리드 병합 (top_p 미적용) ===")
        vec_results = [
            {"page": "wiki/concepts/A.md", "chunk": 0, "type": "concept", "content": "Vector A content"},
            {"page": "wiki/concepts/B.md", "chunk": 0, "type": "concept", "content": "Vector B content"},
        ]
        key_results = [
            {"page": "wiki/concepts/B.md", "chunk": 0, "type": "concept", "content": "Keyword B content"},
            {"page": "wiki/concepts/C.md", "chunk": 0, "type": "concept", "content": "Keyword C content"},
        ]

        print("  벡터 검색:", [r["page"] for r in vec_results])
        print("  키워드 검색:", [r["page"] for r in key_results])

        merged = server._rrf_merge(vec_results, key_results, top_k=3)

        print("  병합 결과:", [(r["page"], f'{r["score"]:.5f}') for r in merged])
        self.assertEqual(len(merged), 3)
        self.assertEqual(merged[0]["page"], "wiki/concepts/B.md")
        self.assertEqual(merged[1]["page"], "wiki/concepts/A.md")
        self.assertEqual(merged[2]["page"], "wiki/concepts/C.md")

    def test_rrf_merge_with_top_p(self):
        """top_p를 적용하면 누적 점수 기준으로 저관련성 결과가 자동 제거된다."""
        print("\n=== [테스트] RRF 하이브리드 병합 (top_p=0.6 적용) ===")
        # 한 문서(B)만 양쪽에 등장 → B의 점수가 압도적으로 높음
        vec_results = [
            {"page": "wiki/concepts/B.md", "chunk": 0, "type": "concept", "content": "Vector B"},
        ]
        key_results = [
            {"page": "wiki/concepts/B.md", "chunk": 0, "type": "concept", "content": "Keyword B"},
            {"page": "wiki/concepts/C.md", "chunk": 0, "type": "concept", "content": "Keyword C"},
            {"page": "wiki/concepts/D.md", "chunk": 0, "type": "concept", "content": "Keyword D"},
            {"page": "wiki/concepts/E.md", "chunk": 0, "type": "concept", "content": "Keyword E"},
        ]

        # top_p 없이 전체 반환 (top_k=10)
        no_filter = server._rrf_merge(vec_results, key_results, top_k=10, top_p=0.0)
        print("  top_p=0.0 (비활성):", [(r["page"], f'{r["score"]:.5f}') for r in no_filter])

        # top_p=0.6 → B의 누적 확률만으로 60% 이상이면 B만 남음
        with_filter = server._rrf_merge(vec_results, key_results, top_k=10, top_p=0.6)
        print("  top_p=0.6 (활성) :", [(r["page"], f'{r["score"]:.5f}') for r in with_filter])

        self.assertGreater(len(no_filter), len(with_filter),
                           "top_p 적용 시 결과 수가 줄어야 합니다")
        self.assertEqual(with_filter[0]["page"], "wiki/concepts/B.md",
                         "최상위 결과는 여전히 B여야 합니다")
        print(f"  → top_p 적용으로 {len(no_filter)}건 → {len(with_filter)}건으로 저관련성 결과 자동 제거됨")

    def test_rrf_merge_top_p_guarantees_minimum(self):
        """top_p가 아무리 낮아도 최소 1건은 반환된다."""
        print("\n=== [테스트] top_p 극단값에서 최소 1건 보장 ===")
        results = [
            {"page": "wiki/concepts/A.md", "chunk": 0, "type": "concept", "content": "A"},
            {"page": "wiki/concepts/B.md", "chunk": 0, "type": "concept", "content": "B"},
        ]
        # top_p=0.01 — 사실상 1건만 반환되어야 하지만, 최소 1건은 보장
        merged = server._rrf_merge([], results, top_k=10, top_p=0.01)
        print(f"  top_p=0.01 → 반환 {len(merged)}건: {[r['page'] for r in merged]}")
        self.assertGreaterEqual(len(merged), 1)

    def test_get_node_id(self):
        print("\n=== [테스트] 노드 ID 정규화 변환 ===")
        server.WIKI_DIR = Path("C:/mock/workspace/wiki").resolve()
        server.WORKSPACE = Path("C:/mock/workspace").resolve()

        test_cases = [
            ("wiki/entities/John.md", "entities/John"),
            ("entities/John.md", "entities/John"),
            ("C:/mock/workspace/wiki/syntheses/Analysis.md", "syntheses/Analysis")
        ]
        for src, expected in test_cases:
            res = server._get_node_id(src)
            print(f"  입력: {src:50} → 노드 ID: {res}")
            self.assertEqual(res, expected)

    def test_find_neighbor_nodes(self):
        print("\n=== [테스트] 지식 그래프 이웃 노드 탐색 ===")
        graph_data = {
            "nodes": [{"id": "concepts/A"}, {"id": "concepts/B"}, {"id": "concepts/C"}],
            "edges": [
                {"from": "concepts/A", "to": "concepts/B"},
                {"from": "concepts/B", "to": "concepts/C"},
                {"from": "concepts/D", "to": "concepts/A"},
            ]
        }
        print("  기준 노드: ['concepts/A']")
        neighbors = server._find_neighbor_nodes(["concepts/A"], graph_data)
        print("  탐색된 이웃:", neighbors)
        self.assertIn("concepts/B", neighbors)
        self.assertIn("concepts/D", neighbors)
        self.assertNotIn("concepts/C", neighbors)

    @patch("server._embed")
    @patch("server._get_conn")
    @patch("server._keyword_search")
    @patch("server._load_graph_data")
    @patch("server._resolve_page")
    @patch("server._read_file")
    @patch("server._page_type")
    @patch("server.Path.is_file")
    def test_wiki_query_logic(self, mock_is_file, mock_page_type, mock_read_file, mock_resolve_page, mock_load_graph_data, mock_keyword_search, mock_get_conn, mock_embed):
        print("\n=== [테스트] wiki_query 최종 컨텍스트 합성 ===")
        mock_is_file.return_value = True
        mock_embed.return_value = [0.1, 0.2, 0.3]

        mock_conn = MagicMock()
        mock_get_conn.return_value = mock_conn
        mock_conn.execute.return_value.fetchall.return_value = [
            ("wiki/entities/John.md", 0, "entity", "John is a software engineer.", 0.1)
        ]
        mock_keyword_search.return_value = [
            {"page": "wiki/concepts/AI.md", "chunk": 0, "type": "concept", "content": "AI is artificial intelligence."}
        ]
        mock_resolve_page.side_effect = lambda p: Path(f"C:/mock/workspace/wiki/{p}")

        def mock_read_file_side_effect(path):
            path_str = str(path).replace("\\", "/")
            if "John.md" in path_str:
                return "---\ntitle: John\ndesc: A senior software engineer\ntags: [engineer, java]\n---\nJohn is a software engineer."
            if "AI.md" in path_str:
                return "---\ntitle: AI\ndesc: Core concept of AI\ntags: [tech, complex]\n---\nAI is artificial intelligence."
            if "LangChain.md" in path_str:
                return "---\ntitle: LangChain\ndesc: LLM orchestration framework\ntags: [framework]\n---\nLangChain is a framework."
            return ""
        mock_read_file.side_effect = mock_read_file_side_effect

        mock_load_graph_data.return_value = ({
            "nodes": [{"id": "entities/John"}, {"id": "concepts/AI"}, {"id": "concepts/LangChain"}],
            "edges": [
                {"from": "entities/John", "to": "concepts/AI"},
                {"from": "entities/John", "to": "concepts/LangChain"}
            ]
        }, "ok")
        mock_page_type.side_effect = lambda p: "entity" if "John" in str(p) else "concept"

        query_text = "Tell me about John and AI"
        print(f"  질문: '{query_text}'")
        result = server._wiki_query_logic(query_text)

        print("\n--- [지식 컨텍스트 출력] ---")
        print(result)
        print("----------------------------\n")

        self.assertIn("entities/John.md", result)
        self.assertIn("concepts/AI.md", result)
        self.assertIn("Description: A senior software engineer", result)
        self.assertIn("관련 연관 문서 (지식 그래프 연결)", result)
        self.assertIn("[[concepts/LangChain]]", result)

    @patch("server.Path.is_file")
    @patch("server._keyword_search")
    def test_wiki_query_no_results(self, mock_keyword_search, mock_is_file):
        print("\n=== [테스트] 검색 결과 없음 예외 처리 ===")
        mock_is_file.return_value = False
        mock_keyword_search.return_value = []

        query_text = "Non-existing query"
        result = server._wiki_query_logic(query_text)
        print(f"  질문: '{query_text}'")
        print(f"  반환: '{result}'")
        self.assertEqual(result, "관련 페이지를 찾을 수 없습니다. 위키 내용이 있거나 벡터 색인이 구축되어 있는지 확인해 주세요.")

if __name__ == "__main__":
    unittest.main()
