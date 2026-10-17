# SQLite TDD migration — experimental

MySQL remains the default backend. Do not deploy until the complete CI suite and two-client smoke tests pass.

Schema: docker/vars/sqlite/schema.sql
Initializer: python3 docker/vars/sqlite/init_db.py ./sqlite-data/bioserver.sqlite
Regressions: python3 -m unittest discover -s tests/sqlite -p 'test_*.py' -v

Game IDs must remain exact 8-character TEXT strings (00000002, 00000010).
WAL supports readers and a single writer, with busy_timeout.
Share the DB directory only among processes on a single Docker host.
Backup using SQLite online backup API while writers are active.
SQLite NOCASE is ASCII-limited, not identical to MySQL latin1_swedish_ci.
The unique (sessid,gameid) index is a compatibility tightening; audit duplicates before import.

Before migration: back up MySQL; stop all writers or take a consistent snapshot; export all four tables using an importer (not a MySQL SQL dump); verify row counts, game IDs, nickname encoding, unique sessions; run PHP and JDBC integration and two-client smoke; preserve the original MySQL volume for rollback.
