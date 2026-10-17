<?php
declare(strict_types=1);

/** Exercise real PDO SQLite session replacement during both Java writers. */
$path = getenv('DB_SQLITE_PATH');
if (!is_string($path) || !is_file($path)) {
    throw new RuntimeException('Missing initialized SQLite path');
}
$pdo = new PDO('sqlite:' . $path);
$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
$pdo->exec('PRAGMA busy_timeout=10000');
$delete = $pdo->prepare('DELETE FROM sessions WHERE userid=?');
$insert = $pdo->prepare(
    "INSERT INTO sessions(userid,ip,port,sessid,gameid,lastlogin) VALUES(?,'127.0.0.1',6021,?, ?,CURRENT_TIMESTAMP)"
);
for ($i = 0; $i < 180; ++$i) {
    $pdo->beginTransaction();
    try {
        $delete->execute(['php_load']);
        $insert->execute(['php_load', sprintf('%08d', 81000000 + $i), '00000002']);
        $pdo->commit();
    } catch (Throwable $failure) {
        $pdo->rollBack();
        throw $failure;
    }
    usleep(1000);
}
echo "PHP concurrent writer success\n";
