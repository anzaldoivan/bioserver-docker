<?php

declare(strict_types=1);

namespace App\Application\Settings;

use App\Domain\EnvironmentType;
use Exception;
use Monolog\Logger;

class EnvSettings implements SettingsInterface
{
    private array $settings;

    public function __construct()
    {
        $production = EnvironmentType::isProduction();
        // CLI PHP can omit environment variables from $_ENV (variables_order=GPCS).
        $env = static function (string $name, ?string $default = null): ?string {
            $value = $_ENV[$name] ?? getenv($name);
            return $value === false ? $default : $value;
        };

        $this->settings = [
            "version"             => "2.4.0",
            "production"          => $production,
            "force_dev_login"     => filter_var($env("FORCE_DEV_LOGIN", "false"), FILTER_VALIDATE_BOOL),
            "displayErrorDetails" => ! $production,
            "logError"            => filter_var($env("LOG_ERROR", "false"), FILTER_VALIDATE_BOOL),
            "logErrorDetails"     => filter_var($env("LOG_ERROR_DETAILS", "false"), FILTER_VALIDATE_BOOL),
            "logger" => [
                "name"  => "bioserver-web-ui",
                "path"  => "php://stdout",
                "level" => $env("LOG_LEVEL", (string) Logger::DEBUG)
            ],
            "db" => [
                "engine" => $env("DB_ENGINE", "mysql"),
                "sqlite_path" => $env("DB_SQLITE_PATH", "/var/lib/bioserver/bioserver.sqlite"),
                "host" => $env("DB_HOST", "biomysql"),
                "database" => $env("DB_DATABASE", "bioserver"),
                "user" => $env("DB_USER", "bioserver"),
                "password" => $env("DB_PASSWORD", "")
            ]
        ];
    }

    /**
     * @return mixed
     */
    public function get(string $key = '')
    {
        if (! isset($this->settings[$key])) {
            throw new Exception("Unknown setting $key.");
        }

        return $this->settings[$key];
    }
}
