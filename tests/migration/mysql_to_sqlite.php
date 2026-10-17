<?php
declare(strict_types=1);

/**
 * Offline MySQL -> SQLite migration. NEVER modifies the source DB.
 * Exit status nonzero for duplicates, schema mismatches and verification loss.
 *
 * --check --sqlite=/absolute/target.sqlite
 * --apply --ack-offline --ack-collation-change --sqlite=/absolute/target.sqlite
 */
$options = [
    'check' => in_array('--check', $argv, true),
    'apply' => in_array('--apply', $argv, true),
    'offline' => in_array('--ack-offline', $argv, true),
    'collation' => in_array('--ack-collation-change', $argv, true),
];
$paths = array_values(array_filter(
    $argv,
    static fn (string $arg): bool => str_starts_with($arg, '--sqlite=')
));
if ($options['check'] === $options['apply'] || count($paths) !== 1) {
    fwrite(STDERR, "Usage: --check OR (--apply --ack-offline --ack-collation-change) --sqlite=/absolute/target.sqlite\n");
    exit(2);
}
if ($options['apply'] && (!$options['offline'] || !$options['collation'])) {
    fwrite(STDERR, "Apply requires offline and collation-change acknowledgments.\n");
    exit(2);
}
$target = substr($paths[0], strlen('--sqlite='));
if ($target === '' || $target[0] !== '/' || !is_file($target)) {
    fwrite(STDERR, "Target must be an existing, initialized SQLite file at an absolute path.\n");
    exit(2);
}
foreach (['SOURCE_DB_HOST', 'SOURCE_DB_NAME', 'SOURCE_DB_USER', 'SOURCE_DB_PASSWORD'] as $name) {
    if (getenv($name) === false) {
        fwrite(STDERR, "Missing source DB environment variable: $name\n");
        exit(2);
    }
}
const TABLE_COLUMNS = [
    'users' => ['userid', 'passwd'],
    'sessions' => ['userid','ip','port','sessid','gamesess','lastlogin','area','room','slot','state','gameid'],
    'hnpairs' => ['userid','handle','nickname'],
    'motd' => ['id','message','active']
];

function normalizeRows(array $rows, array $columns): array
{
    $canonical = [];
    foreach ($rows as $row) {
        $item = [];
        foreach ($columns as $name) {
            if (!array_key_exists($name, $row)) {
                throw new RuntimeException("Missing required column $name");
            }
            // Integers and PDO strings may differ across engines, but their
            // numeric values and NULLs must be identical; all text remains exact.
            $item[] = $row[$name] === null ? null : (string) $row[$name];
        }
        $canonical[] = json_encode($item, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
    }
    sort($canonical, SORT_STRING);
    return $canonical;
}

try {
    $source = new PDO(
        'mysql:host=' . getenv('SOURCE_DB_HOST') .
        ';dbname=' . getenv('SOURCE_DB_NAME') . ';charset=utf8mb4',
        (string) getenv('SOURCE_DB_USER'), (string) getenv('SOURCE_DB_PASSWORD'),
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
         PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
         PDO::ATTR_EMULATE_PREPARES => false]
    );
    $source->exec("SET time_zone = '+00:00'");
    $source->exec("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
    $source->exec('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY');

    $destination = new PDO('sqlite:' . $target, null, null,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
         PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
    $destination->exec('PRAGMA busy_timeout=10000');
    $destination->exec('PRAGMA foreign_keys=ON');
    $mode = $destination->query('PRAGMA journal_mode')->fetchColumn();
    if (strtolower((string) $mode) !== 'wal') {
        throw new RuntimeException('SQLite target is not configured for WAL');
    }

    $snapshots = [];
    $summary = [];
    foreach (TABLE_COLUMNS as $table => $columns) {
        $list = implode(',', $columns);
        $sourceRows = $source->query("SELECT $list FROM $table")->fetchAll();
        // SQLite schema narrows session identity: reject duplicate pairs that
        // were historically valid in MySQL; do not discard rows to force success.
        if ($table === 'sessions') {
            $seen = [];
            foreach ($sourceRows as $row) {
                $key = $row['gameid'] . ':' . $row['sessid'];
                if (isset($seen[$key])) {
                    throw new RuntimeException("Duplicate (gameid,sessid) in MySQL: $key");
                }
                $seen[$key] = true;
                if (strlen((string) $row['gameid']) !== 8 || strlen((string) $row['sessid']) !== 8) {
                    throw new RuntimeException("Invalid 8-character game ID or session ID: $key");
                }
            }
        }
        $snapshots[$table] = $sourceRows;
        $summary[$table] = count($sourceRows);
    }
    if ($options['check']) {
        echo json_encode(['status' => 'preflight_ok', 'counts' => $summary,
            'collation_note' => 'MySQL latin1_swedish_ci authentication is not binary-equivalent to SQLite TEXT; explicit acceptance required'],
            JSON_PRETTY_PRINT | JSON_THROW_ON_ERROR) . "\n";
        $source->exec('ROLLBACK');
        exit(0);
    }

    // No destructive reloads: a populated SQLite target always blocks import.
    foreach (TABLE_COLUMNS as $table => $columns) {
        $count = (int) $destination->query("SELECT COUNT(*) FROM $table")->fetchColumn();
        if ($count !== 0) {
            throw new RuntimeException("SQLite target not empty ($table contains $count rows)");
        }
    }
    $destination->beginTransaction();
    try {
        foreach (TABLE_COLUMNS as $table => $columns) {
            $list = implode(',', $columns);
            $placeholders = implode(',', array_fill(0, count($columns), '?'));
            $insert = $destination->prepare("INSERT INTO $table ($list) VALUES ($placeholders)");
            foreach ($snapshots[$table] as $row) {
                $insert->execute(array_map(
                    static fn (string $column) => $row[$column], $columns
                ));
            }
            $sqliteRows = $destination->query("SELECT $list FROM $table")->fetchAll();
            if (normalizeRows($snapshots[$table], $columns) !== normalizeRows($sqliteRows, $columns)) {
                throw new RuntimeException("Row-level content verification failed: $table");
            }
        }
        $destination->commit();
    } catch (Throwable $failure) {
        if ($destination->inTransaction()) $destination->rollBack();
        throw $failure;
    }
    if ($destination->query('PRAGMA integrity_check')->fetchColumn() !== 'ok') {
        throw new RuntimeException('SQLite integrity check failed after commit');
    }
    $source->exec('ROLLBACK');
    echo json_encode(['status' => 'import_verified', 'counts' => $summary,
        'collation_note' => 'Acknowledged behavior change for case-insensitive passwords'],
        JSON_PRETTY_PRINT | JSON_THROW_ON_ERROR) . "\n";
} catch (Throwable $error) {
    fwrite(STDERR, "Migration aborted: " . $error->getMessage() . "\n");
    exit(1);
}
