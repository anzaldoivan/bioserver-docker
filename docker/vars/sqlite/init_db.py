#!/usr/bin/env python3
"""Idempotent SQLite initializer for disposable tests or an explicitly selected DB."""
from pathlib import Path
from contextlib import closing
import sqlite3
import sys


def initialize(path: Path) -> None:
    schema = Path(__file__).with_name("schema.sql")
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path, timeout=10)) as connection:
        connection.execute("PRAGMA busy_timeout=10000")
        connection.execute("PRAGMA journal_mode=WAL")
        with connection:
            connection.executescript(schema.read_text(encoding="utf-8"))
            if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("SQLite integrity check failed")


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "/var/lib/bioserver/bioserver.sqlite")
    initialize(target)
    print(f"SQLite database ready: {target}")
