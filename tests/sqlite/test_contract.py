"""SQLite behavioral contract for both Outbreak game IDs.

Run before/after migration. Uses real SQLite and multiple independent connections.
"""
import os
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "docker/vars/sqlite/schema.sql"
BIO1, BIO2 = "00000002", "00000010"


class SQLiteContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.tmp.name) / "bioserver.sqlite")
        self.db = sqlite3.connect(self.path, timeout=5)
        self.db.executescript(SCHEMA.read_text(encoding="utf-8"))
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def session(self, userid, sid, game):
        self.db.execute(
            """INSERT INTO sessions(userid, ip, port, sessid, gameid, lastlogin)
               VALUES(?, '127.0.0.1', 6021, ?, ?, CURRENT_TIMESTAMP)""",
            (userid, sid, game),
        )
        self.db.commit()

    def test_exact_game_ids_do_not_cast_to_numbers(self):
        self.session("player", "10000001", BIO1)
        self.session("player2", "10000001", BIO2)
        for game, expected in ((BIO1, "player"), (BIO2, "player2")):
            self.assertEqual(self.db.execute(
                "SELECT userid FROM sessions WHERE sessid=? AND gameid=?",
                ("10000001", game)
            ).fetchone()[0], expected)
        self.assertIsNone(self.db.execute(
            "SELECT userid FROM sessions WHERE gameid=?", ("2",)
        ).fetchone())

    def test_accounts_are_unique_and_passwords_preserved(self):
        self.db.execute("INSERT INTO users VALUES(?, ?)", ("player", "AbC123"))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO users VALUES(?, ?)", ("player", "another"))
        self.assertEqual(self.db.execute(
            "SELECT passwd FROM users WHERE userid=?", ("player",)
        ).fetchone()[0], "AbC123")

    def test_restart_resets_only_current_game(self):
        self.session("p1", "10000001", BIO1)
        self.session("p2", "10000002", BIO2)
        self.db.execute("UPDATE sessions SET area=4, room=5, slot=6, state=7, gamesess=8")
        self.db.execute(
            "UPDATE sessions SET area=-1,room=0,slot=0,state=0,gamesess=0 WHERE gameid=?",
            (BIO1,)
        )
        self.assertEqual(self.db.execute(
            "SELECT area,room,slot,state,gamesess FROM sessions WHERE gameid=?",
            (BIO1,)
        ).fetchone(), (-1, 0, 0, 0, 0))
        self.assertEqual(self.db.execute(
            "SELECT area,room,slot,state,gamesess FROM sessions WHERE gameid=?",
            (BIO2,)
        ).fetchone(), (4, 5, 6, 7, 8))

    def test_unicode_nicknames_and_quotes(self):
        self.db.execute(
            "INSERT INTO hnpairs(userid,handle,nickname) VALUES(?,?,?)",
            ("player", "A1B2C3", "勇気 O'Neil")
        )
        self.assertEqual(self.db.execute(
            "SELECT nickname FROM hnpairs WHERE handle=?", ("A1B2C3",)
        ).fetchone()[0], "勇気 O'Neil")

    def test_motd_selects_latest_active(self):
        self.db.executemany("INSERT INTO motd(message,active) VALUES(?,?)",
                            [("old", 1), ("skip", 0), ("latest", 1)])
        self.assertEqual(self.db.execute(
            "SELECT message FROM motd WHERE active=1 ORDER BY id DESC LIMIT 1"
        ).fetchone()[0], "latest")

    def test_transactional_session_replacement_rollback(self):
        self.session("player", "10000001", BIO1)
        self.db.execute("BEGIN IMMEDIATE")
        self.db.execute("DELETE FROM sessions WHERE userid=?", ("player",))
        self.db.rollback()
        self.assertEqual(self.db.execute(
            "SELECT sessid FROM sessions WHERE userid=?", ("player",)
        ).fetchone()[0], "10000001")

    def test_two_connections_can_read_and_write_in_wal(self):
        self.db.execute("PRAGMA journal_mode=WAL")
        other = sqlite3.connect(self.path, timeout=5)
        try:
            self.session("player", "10000001", BIO1)
            self.assertEqual(other.execute("SELECT count(*) FROM sessions").fetchone()[0], 1)
            other.execute("INSERT INTO motd(message,active) VALUES('hello',1)")
            other.commit()
            self.assertEqual(self.db.execute("SELECT count(*) FROM motd").fetchone()[0], 1)
        finally:
            other.close()

    def test_schema_has_required_tables_and_indexes(self):
        tables = {r[0] for r in self.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )}
        self.assertTrue({"users", "sessions", "hnpairs", "motd"}.issubset(tables))
        self.assertGreaterEqual(len(list(self.db.execute("PRAGMA index_list('sessions')"))), 1)


if __name__ == "__main__":
    unittest.main()
