<?php
declare(strict_types=1);

/**
 * Deterministic synthetic-only fixture shared by the SQLite and MySQL CI jobs.
 * Never run against a real player database.
 */
if (getenv('SYNTHETIC_DB_FIXTURE') !== '1') {
    fwrite(STDERR, "Refusing to seed without SYNTHETIC_DB_FIXTURE=1\n");
    exit(1);
}
$engine = getenv('DB_ENGINE') ?: 'mysql';
if ($engine === 'sqlite') {
    $path = getenv('DB_SQLITE_PATH');
    if (!is_string($path) || $path === '' || !file_exists($path)) {
        throw new RuntimeException('SQLite test database must already exist');
    }
    $pdo = new PDO('sqlite:' . $path);
} elseif ($engine === 'mysql') {
    $pdo = new PDO(
        'mysql:host=' . (getenv('DB_HOST') ?: '127.0.0.1') .
        ';dbname=' . (getenv('DB_DATABASE') ?: 'bioserver') . ';charset=utf8mb4',
        getenv('DB_USER') ?: 'bioserver',
        getenv('DB_PASSWORD') ?: 'bioserver'
    );
} else {
    throw new RuntimeException('Unsupported test database backend');
}
$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

$rows = [
    'users' => [
        ['userid' => 'fixture_a', 'passwd' => 'Fixtur3PassA'],
        ['userid' => 'fixture_b', 'passwd' => 'Fixtur3PassB']
    ],
    'sessions' => [
        ['userid' => 'fixture_a', 'ip' => '127.0.0.1', 'port' => 6021,
         'sessid' => '87654321', 'gameid' => '00000002'],
        ['userid' => 'fixture_b', 'ip' => '127.0.0.2', 'port' => 6022,
         'sessid' => '87654322', 'gameid' => '00000010']
    ],
    'hnpairs' => [
        ['userid' => 'fixture_a', 'handle' => 'A1B2C3', 'nickname' => '勇気'],
        ['userid' => 'fixture_b', 'handle' => 'D4E5F6', 'nickname' => "O'Neil"]
    ],
    'motd' => [
        ['id' => 1001, 'message' => 'Synthetic inactive MOTD', 'active' => 0],
        ['id' => 1002, 'message' => 'Synthetic fixture MOTD', 'active' => 1]
    ]
];
$keys = [
    'users' => ['userid'],
    'sessions' => ['sessid', 'gameid'],
    'hnpairs' => ['userid', 'handle'],
    'motd' => ['id']
];

$pdo->beginTransaction();
try {
    foreach ($rows as $table => $tableRows) {
        foreach ($tableRows as $values) {
            $condition = implode(' AND ', array_map(
                static fn (string $key): string => "$key=?",
                $keys[$table]
            ));
            $exists = $pdo->prepare("SELECT COUNT(*) FROM $table WHERE $condition");
            $exists->execute(array_map(
                static fn (string $key) => $values[$key],
                $keys[$table]
            ));
            if ((int) $exists->fetchColumn() > 0) {
                continue;
            }
            $columns = array_keys($values);
            $insert = $pdo->prepare(
                "INSERT INTO $table (" . implode(',', $columns) . ") VALUES (" .
                implode(',', array_fill(0, count($columns), '?')) . ")"
            );
            $insert->execute(array_values($values));
        }
    }
    $pdo->commit();
} catch (Throwable $error) {
    $pdo->rollBack();
    throw $error;
}
echo "Synthetic BioServer fixtures loaded for $engine\n";
