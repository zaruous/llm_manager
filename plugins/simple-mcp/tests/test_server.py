"""
simple-mcp server.py 단위 테스트 — DB 없이 문장 검사·URL 정규화·JSON 변환만 검증한다.

실행: python -m unittest plugins/simple-mcp/tests/test_server.py
(fastmcp·psycopg가 설치돼 있어야 server를 임포트할 수 있다.)
"""

import json
import sys
import unittest
from datetime import date
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

# plugins/simple-mcp 디렉토리를 path에 추가하여 server.py를 임포트할 수 있게 함
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import server  # noqa: E402


class TestValidateReadOnly(unittest.TestCase):

    def test_allows_select_variants(self):
        for stmt in ("SELECT 1", "  select * from t;", "WITH x AS (SELECT 1) SELECT * FROM x",
                     "explain select 1", "VALUES (1)", "TABLE foo", "SHOW search_path"):
            self.assertTrue(server.validate_read_only(stmt), stmt)

    def test_strips_trailing_semicolon_only(self):
        self.assertEqual("SELECT 1", server.validate_read_only("SELECT 1;"))
        self.assertEqual("SELECT 1", server.validate_read_only("SELECT 1 ; "))

    def test_rejects_write_statements(self):
        for stmt in ("INSERT INTO t VALUES (1)", "update t set a=1", "DELETE FROM t",
                     "DROP TABLE t", "TRUNCATE t", "CREATE TABLE t(a int)", "BEGIN",
                     "COPY t TO '/tmp/x'"):
            with self.assertRaises(ValueError, msg=stmt):
                server.validate_read_only(stmt)

    def test_rejects_multiple_statements(self):
        with self.assertRaises(ValueError):
            server.validate_read_only("SELECT 1; DELETE FROM t")

    def test_rejects_empty_and_comment_only(self):
        for stmt in ("", "   ", ";", "-- only a comment", "/* block */"):
            with self.assertRaises(ValueError, msg=repr(stmt)):
                server.validate_read_only(stmt)

    def test_keyword_hidden_behind_comment_is_still_checked(self):
        # 주석 뒤에 숨긴 쓰기 문장도 첫 키워드로 판정한다
        with self.assertRaises(ValueError):
            server.validate_read_only("-- SELECT\nDELETE FROM t")
        with self.assertRaises(ValueError):
            server.validate_read_only("/* SELECT */ UPDATE t SET a = 1")


class TestBuildExplainSql(unittest.TestCase):

    def test_wraps_select_with_explain_json(self):
        self.assertEqual("EXPLAIN (FORMAT JSON) SELECT * FROM t",
                         server.build_explain_sql("SELECT * FROM t;", analyze=False))

    def test_analyze_option(self):
        self.assertEqual("EXPLAIN (FORMAT JSON, ANALYZE) SELECT 1",
                         server.build_explain_sql("SELECT 1", analyze=True))

    def test_rejects_write_and_multi_statement_before_explain(self):
        # 레거시 sql-gen-mcp explain_query는 검증 없이 EXPLAIN 뒤에 문자열을 붙였다 — 여기서는 먼저 거른다
        for stmt in ("DELETE FROM t", "SELECT 1; DELETE FROM t", "UPDATE t SET a = 1"):
            with self.assertRaises(ValueError, msg=stmt):
                server.build_explain_sql(stmt, analyze=True)

    def test_rejects_nested_explain(self):
        with self.assertRaises(ValueError):
            server.build_explain_sql("EXPLAIN SELECT 1", analyze=False)


class TestNormalizeDbUrl(unittest.TestCase):

    def test_strips_jdbc_prefix(self):
        self.assertEqual("postgresql://h:5433/db",
                         server.normalize_db_url("jdbc:postgresql://h:5433/db"))
        self.assertEqual("postgresql://h:5433/db",
                         server.normalize_db_url("  JDBC:postgresql://h:5433/db "))

    def test_keeps_plain_url(self):
        self.assertEqual("postgresql://h/db", server.normalize_db_url("postgresql://h/db"))

    def test_masked_url_hides_password(self):
        with patch.object(server.args, "db_url", "jdbc:postgresql://u:secret@h:5432/db"):
            self.assertEqual("postgresql://u:***@h:5432/db", server._masked_db_url())


class TestJsonConversion(unittest.TestCase):

    def test_decimal_and_date_are_serializable(self):
        out = json.loads(server._dump({"a": Decimal("1.50"), "b": Decimal("3"), "c": date(2026, 1, 2)}))
        self.assertEqual({"a": "1.50", "b": 3, "c": "2026-01-02"}, out)


class TestResolveSchema(unittest.TestCase):

    def test_falls_back_to_default_schema(self):
        self.assertEqual(server.args.db_schema, server._resolve_schema(""))
        self.assertEqual("mes", server._resolve_schema(" mes "))

    def test_rejects_non_identifier(self):
        with self.assertRaises(ValueError):
            server._resolve_schema("public; DROP SCHEMA x")


if __name__ == "__main__":
    unittest.main()
