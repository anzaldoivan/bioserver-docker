# BioServer test and source coverage pipeline

Workflow: .github/workflows/sqlite-tdd.yml. On branch pushes, PRs, or manual dispatch.

## Coverage products
- Python coverage.py XML: real SQLite initializer; SQL-only tests do not count as source coverage.
- PHP PHPUnit with Xdebug: Cobertura XML, Clover XML, HTML and JUnit XML, separately on fresh SQLite and MySQL databases.
- JaCoCo: File #1 and File #2 Java JDBC smoke tests against a fresh MySQL 9.0 container; **not yet SQLite JDBC**.
- GitHub Actions summary and downloadable artifacts for each component; optional Codecov upload when CODECOV_TOKEN repo secret exists. CI works without external aggregator.
- Do not combine PHP/Java/Python percentages into one misleading number. Coverage is informational until meaningful baselines are established.

## Deterministic synthetic data
Seed file: web/tests/fixtures/seed_synthetic.php.
It refuses to run unless SYNTHETIC_DB_FIXTURE=1. Use only on **disposable databases**. The seed is idempotent and CI loads it twice.
- users fixture_a, fixture_b
- 8-byte text game identifiers 00000002 and 00000010
- sessions 87654321 and 87654322
- handles A1B2C3 and D4E5F6
- UTF-8 nickname 勇気 and a quoted nickname O'Neil
- MOTD active/inactive rows

CI does not mount a production Docker database volume. No player data is used.
The PHP tests verify deterministic synthetic state across both PDO backends.
The Java smoke exercises existing MySQL JDBC methods; this is not a substitute for a game-client test.

## Running tests
- SQLite: python3 docker/vars/sqlite/init_db.py /tmp/bio-test.sqlite
- Set DB_ENGINE=sqlite DB_SQLITE_PATH=/tmp/bio-test.sqlite SYNTHETIC_DB_FIXTURE=1 and run php web/tests/fixtures/seed_synthetic.php
- Run Composer install and composer test under web/, with the same environment.
- Python: python3 -m unittest discover -s tests/sqlite -p 'test_*.py' -v
- MySQL: start a disposable MySQL 9 container, initialize docker/vars/mysql/2_init_db.sh with DB_DATABASE set inside the container, seed via the same PHP script, and run PHPUnit.
- Results: Actions workflow artifact coverage-combined-reports and coverage-report job summary.

## Relevant open repositories inspected, 2026-10-17
- https://github.com/panzer-punk/bioserver-docker : existing PHPUnit and captured DNAS binary fixtures (no JaCoCo found)
- https://github.com/Necrosiak/bioserver-docker : same PHPUnit suite, no additional DB tests identified
- https://github.com/corbin-zip/bioserver : historical game Java code, no automated tests identified
- https://github.com/samuk190/bioserver-docker and https://github.com/yabab/bioserver-docker : older Docker sources without tests in inspected default branches
- https://github.com/adrianopteodoro/obsrv-pack : original DB guidance, no automated tests identified
- https://github.com/Koosjuh/pal-outbreak-2-server : reverse-engineering coverage and protocol corpus audit; not PHPUnit/JaCoCo source coverage
Results relate only to inspected default-branch source trees, not uninspected development branches.

Do not deploy or merge SQLite until Java SQLite support, Docker volume wiring, lossless migration, concurrent writer, and game-client tests exist.
