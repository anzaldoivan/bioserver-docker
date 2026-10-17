"""Executable initializer invariants; exercises production initializer, not a mocked schema."""
import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[2] / "docker/vars/sqlite/init_db.py"
spec = importlib.util.spec_from_file_location("bioserver_sqlite_init", MODULE)
assert spec is not None and spec.loader is not None
init = importlib.util.module_from_spec(spec)
spec.loader.exec_module(init)


class InitializerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "nested" / "bioserver.sqlite"

    def tearDown(self):
        self.tmp.cleanup()

    def test_creates_complete_db_and_wal_mode(self):
        init.initialize(self.path)
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
            self.assertEqual(db.execute("PRAGMA journal_mode").fetchone()[0], "wal")
            tables = {r[0] for r in db.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )}
            self.assertTrue({"users", "sessions", "hnpairs", "motd"}.issubset(tables))

    def test_second_initialization_does_not_erase_records(self):
        init.initialize(self.path)
        with sqlite3.connect(self.path) as db:
            db.execute("INSERT INTO users(userid,passwd) VALUES(?,?)", ("fixture_a", "KeepMe1"))
            db.commit()
        init.initialize(self.path)
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute("SELECT passwd FROM users WHERE userid='fixture_a'").fetchone()[0], "KeepMe1")


if __name__ == "__main__":
    unittest.main()
