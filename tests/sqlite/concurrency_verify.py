#!/usr/bin/env python3
"""Validate three-writer workload, WAL checkpoint and state after all processes exit."""
import os
import sqlite3
from pathlib import Path

path = Path(os.environ["DB_SQLITE_PATH"])
with sqlite3.connect(path) as conn:
    conn.execute("PRAGMA busy_timeout=10000")
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    for game, user in [("00000002", "fixture_a"), ("00000010", "fixture_b")]:
        result = conn.execute(
            "SELECT gamesess FROM sessions WHERE gameid=? AND userid=?",
            (game, user)
        ).fetchone()
        assert result is not None and result[0] == 179, (game, result)
    result = conn.execute(
        "SELECT sessid FROM sessions WHERE userid='php_load'"
    ).fetchall()
    assert result == [("81000179",)], result
    busy, wal_pages, checkpointed = conn.execute(
        "PRAGMA wal_checkpoint(TRUNCATE)"
    ).fetchone()
    assert busy == 0, ("WAL checkpoint busy", busy, wal_pages, checkpointed)
print("PASS concurrent Java #1 / Java #2 / PHP WAL writers and recovery")
