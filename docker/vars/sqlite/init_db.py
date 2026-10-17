#!/usr/bin/env python3
"""Initialize a local SQLite BioServer DB idempotently; never deletes data."""
from pathlib import Path
import sqlite3
import sys

schema = Path(__file__).with_name("schema.sql")
target = Path(sys.argv[1] if len(sys.argv) > 1 else "/var/lib/bioserver/bioserver.sqlite")
target.parent.mkdir(parents=True, exist_ok=True)
with sqlite3.connect(target, timeout=10) as con:
    con.execute("PRAGMA busy_timeout=10000")
    con.execute("PRAGMA journal_mode=WAL")
    con.executescript(schema.read_text(encoding="utf-8"))
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
print(f"SQLite database ready: {target}")
