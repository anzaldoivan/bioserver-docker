<?php

declare(strict_types=1);

use App\Application\Actions\Outbreak\Validators\Password\DevPasswordValidator;
use App\Application\Actions\Outbreak\Validators\Password\ProductionPasswordValidator;
use App\Application\Actions\Outbreak\Validators\UserName\DevUserNameValidator;
use App\Application\Actions\Outbreak\Validators\UserName\ProductionUserNameValidator;
use App\Application\Settings\SettingsInterface;
use App\Domain\Outbreak\PasswordValidatorInterface;
use App\Domain\Outbreak\UserNameValidatorInterface;
use DI\ContainerBuilder;
use Monolog\Handler\StreamHandler;
use Monolog\Logger;
use Monolog\Processor\UidProcessor;
use Psr\Container\ContainerInterface;
use Psr\Log\LoggerInterface;

return function (ContainerBuilder $containerBuilder) {
    $containerBuilder->addDefinitions([
        LoggerInterface::class => function (ContainerInterface $c) {
            $settings = $c->get(SettingsInterface::class);

            $loggerSettings = $settings->get("logger");
            $logger = new Logger($loggerSettings["name"]);

            $processor = new UidProcessor();
            $logger->pushProcessor($processor);

            $handler = new StreamHandler($loggerSettings["path"], $loggerSettings["level"]);
            $logger->pushHandler($handler);

            return $logger;
        },
        PDO::class => function (ContainerInterface $c) {
            $settings = $c->get(SettingsInterface::class);

            $db = $settings->get("db");

            $options = [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
            ];
            if ($db["engine"] === "sqlite") {
                // SQLite silently creates missing files; refuse an uninitialized deployment.
                if (! is_file($db["sqlite_path"])) {
                    throw new DomainException("SQLite database is missing; run schema initialization first.");
                }
                $connection = new PDO("sqlite:" . $db["sqlite_path"], null, null, $options);
                $connection->exec("PRAGMA busy_timeout = 10000");
                if ($connection->query("PRAGMA journal_mode")->fetchColumn() !== "wal") {
                    throw new DomainException("SQLite database must use WAL journal mode.");
                }
                foreach (["users", "sessions", "hnpairs", "motd"] as $table) {
                    $connection->query("SELECT * FROM {$table} LIMIT 0");
                }
                return $connection;
            }
            if ($db["engine"] !== "mysql") {
                throw new DomainException("Unsupported database engine: " . $db["engine"]);
            }
            return new PDO(
                "mysql:host=" . $db["host"] . ";dbname=" . $db["database"] . ";charset=utf8mb4",
                $db["user"],
                $db["password"],
                $options
            );
        },
        PasswordValidatorInterface::class => function (ContainerInterface $c) {
            $settings = $c->get(SettingsInterface::class);

            $production = $settings->get("production") && ! $settings->get("force_dev_login");

            return $production
                ? new ProductionPasswordValidator()
                : new DevPasswordValidator();
        },
        UserNameValidatorInterface::class => function (ContainerInterface $c) {
            $settings = $c->get(SettingsInterface::class);

            $production = $settings->get("production") && ! $settings->get("force_dev_login");

            return $production
                ? new ProductionUserNameValidator()
                : new DevUserNameValidator();
        }
    ]);
};
