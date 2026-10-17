<?php
declare(strict_types=1);

namespace App\Application\Actions\Outbreak\Handlers;

use App\Domain\Outbreak\ValueObjects\Password;
use App\Domain\Outbreak\ValueObjects\UserName;
use App\Domain\Outbreak\LoginException;
use App\Domain\Outbreak\LoginHandlerInterface;
use PDO;
use PDOException;

final class RegisterHandler implements LoginHandlerInterface
{
    public function __construct(private PDO $db) {}

    public function handle(UserName $username, Password $password): void
    {
        try {
            $stmt = $this->db->prepare("INSERT INTO users (userid,passwd) VALUES (?,?)");
            $stmt->execute([$username->value, $password->value]);
        } catch (PDOException $e) {
            throw new LoginException("Registration failed.", 0, $e);
        }
    }
}
