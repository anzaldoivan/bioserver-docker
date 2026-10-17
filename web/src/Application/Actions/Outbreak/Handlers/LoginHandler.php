<?php
declare(strict_types=1);

namespace App\Application\Actions\Outbreak\Handlers;

use App\Domain\Outbreak\ValueObjects\Password;
use App\Domain\Outbreak\ValueObjects\UserName;
use App\Domain\Outbreak\LoginException;
use App\Domain\Outbreak\LoginHandlerInterface;
use PDO;

final class LoginHandler implements LoginHandlerInterface
{
    public function __construct(private PDO $db) {}

    public function handle(UserName $username, Password $password): void
    {
        $stmt = $this->db->prepare("SELECT COUNT(*) FROM users WHERE userid=? AND passwd=?");
        $stmt->execute([$username->value, $password->value]);
        if ((int) $stmt->fetchColumn() !== 1) {
            throw new LoginException("Login failed. Your login/password combination is wrong.");
        }
    }
}
