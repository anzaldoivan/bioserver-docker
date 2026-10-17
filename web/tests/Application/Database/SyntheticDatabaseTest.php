<?php
declare(strict_types=1);

namespace Tests\Application\Database;

use PDO;
use Tests\TestCase;

/** Same executable fixtures and assertions against SQLite and MySQL. */
final class SyntheticDatabaseTest extends TestCase
{
    private function db(): PDO
    {
        return self::getApp()->getContainer()->get(PDO::class);
    }

    public function testExactLeadingZeroGameIdsArePreserved(): void
    {
        $query = $this->db()->prepare('SELECT userid FROM sessions WHERE gameid=? AND sessid=?');
        foreach ([
            ['00000002', '87654321', 'fixture_a'],
            ['00000010', '87654322', 'fixture_b']
        ] as [$game, $session, $expected]) {
            $query->execute([$game, $session]);
            self::assertSame($expected, $query->fetchColumn());
            $query->closeCursor();
        }
    }

    public function testSyntheticUserCredentialsAreUnchanged(): void
    {
        $query = $this->db()->prepare('SELECT passwd FROM users WHERE userid=?');
        $query->execute(['fixture_a']);
        self::assertSame('Fixtur3PassA', $query->fetchColumn());
    }

    public function testJapaneseAndQuotedNicknamesRoundTrip(): void
    {
        $query = $this->db()->prepare('SELECT nickname FROM hnpairs WHERE handle=?');
        foreach (['A1B2C3' => '勇気', 'D4E5F6' => "O'Neil"] as $handle => $expected) {
            $query->execute([$handle]);
            self::assertSame($expected, $query->fetchColumn());
            $query->closeCursor();
        }
    }

    public function testMostRecentActiveMessageWins(): void
    {
        self::assertSame(
            'Synthetic fixture MOTD',
            $this->db()->query('SELECT message FROM motd WHERE active=1 ORDER BY id DESC LIMIT 1')->fetchColumn()
        );
    }

    public function testFixtureSeedingIsIdempotent(): void
    {
        $db = $this->db();
        foreach ([
            "SELECT COUNT(*) FROM users WHERE userid IN ('fixture_a','fixture_b')" => 2,
            "SELECT COUNT(*) FROM sessions WHERE sessid IN ('87654321','87654322')" => 2,
            "SELECT COUNT(*) FROM hnpairs WHERE handle IN ('A1B2C3','D4E5F6')" => 2,
            'SELECT COUNT(*) FROM motd WHERE id IN (1001,1002)' => 2
        ] as $sql => $expected) {
            self::assertSame($expected, (int) $db->query($sql)->fetchColumn());
        }
    }

    public function testSessionReplacementRollsBackOnFailure(): void
    {
        // PHPUnit's outer transaction rolls back this savepoint after the test.
        $db = $this->db();
        $db->exec('SAVEPOINT fixture_session');
        try {
            $delete = $db->prepare('DELETE FROM sessions WHERE userid=?');
            $delete->execute(['fixture_a']);
            self::assertSame(0, (int) $db->query(
                "SELECT COUNT(*) FROM sessions WHERE userid='fixture_a'"
            )->fetchColumn());
            $db->exec('ROLLBACK TO SAVEPOINT fixture_session');
        } finally {
            $db->exec('RELEASE SAVEPOINT fixture_session');
        }
        self::assertSame(1, (int) $db->query(
            "SELECT COUNT(*) FROM sessions WHERE userid='fixture_a'"
        )->fetchColumn());
    }
}
