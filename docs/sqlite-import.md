# Offline MySQL to SQLite migration

**Never import a live database into a running game server.** SQLite is opt-in. MySQL deployment remains the default and the original MySQL volume is not altered.

## Preflight
1. Shut down the PHP and both Java writers. Create and verify an independent MySQL backup.
2. Create a fresh SQLite database with `python3 docker/vars/sqlite/init_db.py /absolute/path/bioserver.sqlite`.
3. Set SOURCE_DB_HOST, SOURCE_DB_NAME, SOURCE_DB_USER, SOURCE_DB_PASSWORD for a read-only MySQL account.
4. Run `php tests/migration/mysql_to_sqlite.php --check --sqlite=/absolute/path/bioserver.sqlite`.
5. Read the diagnostic counts and correct any duplicate `(gameid,sessid)` rows in MySQL before continuing.

## Import
Run `php tests/migration/mysql_to_sqlite.php --apply --ack-offline --ack-collation-change --sqlite=/absolute/path/bioserver.sqlite` after a backup and with all writers stopped.

The importer reads a consistent MySQL snapshot (REPEATABLE READ + READ ONLY, UTC), writes one SQLite transaction, and checks lossless canonical row equivalence for **all four tables** before committing. It refuses existing destination rows, invalid 8-character IDs and duplicate session IDs, and verifies SQLite integrity. It never modifies MySQL. A failed SQLite transaction is rolled back. The initialized SQLite DB file itself is intentionally not deleted if validation fails.

**Behavioral difference requiring acceptance:** MySQL's original `latin1_swedish_ci` collation allows case-insensitive password comparisons, while SQLite `passwd TEXT` compares strings case-sensitively. The importer preserves password data but does not silently claim login semantics are identical. `--ack-collation-change` makes acceptance explicit.

## Rollback
Keep the old MySQL volume offline and intact. To revert, stop all SQLite game/PHP writers and restore the original MySQL Compose mode and the backed-up MySQL data. Data created **after** switching to SQLite will not automatically replicate back to MySQL; do not promise lossless rollback without an explicit reverse migration.

Use the opt-in Compose mode: `make sqlite-build`, `make sqlite-run`, and `make sqlite-down`. SQLite's journal/WAL files and main DB all reside on the same Docker local volume, owned by UID/GID 33 for PHP FPM. Never put the SQLite volume on NFS/SMB or a shared multi-host filesystem.

## Importing directly into the persistent Docker volume

The SQLite deployment includes the opt-in `biosqlite-import` service under the `migration` profile. This uses the production PHP image with both PDO drivers and mounts **the same persistent SQLite volume** as the game servers. The migration entrypoint is not started during ordinary `make sqlite-run`.

1. Stop the original PHP and both Java game writers while keeping the **backed-up** MySQL database service online on the same `bio-network`.
2. Set SOURCE_DB_HOST=biomysql, SOURCE_DB_NAME, SOURCE_DB_USER and SOURCE_DB_PASSWORD in `.env` for a **read-only** MySQL account. Keep file permissions restrictive.
3. Run `docker compose --project-directory . -f docker/sqlite/compose.yaml --profile migration build biosqlite-import`.
4. Run `docker compose --project-directory . -f docker/sqlite/compose.yaml --profile migration run --rm biosqlite-import --check --sqlite=/var/lib/bioserver/bioserver.sqlite`.
5. After resolving preflight errors, run `docker compose --project-directory . -f docker/sqlite/compose.yaml --profile migration run --rm biosqlite-import --apply --ack-offline --ack-collation-change --sqlite=/var/lib/bioserver/bioserver.sqlite`.
6. Stop the MySQL server service, then start the SQLite deployment using `make sqlite-run`. Verify logins and sessions from actual File #1 and File #2 clients before public access.

The importer *refuses a populated SQLite database*, so you must use a fresh SQLite volume for a new import. Do not remove volumes containing player data; make a separate verified backup first. The original MySQL data volume remains unchanged by the importer. Retain it as rollback source.

**The importer is one-way.** Any registrations, sessions or nicknames created after moving to SQLite are not backported automatically to MySQL.
