import bioserver.Database;

/**
 * Smoke tests the real game Database JDBC implementation against isolated MySQL.
 * Compile/run once for each game's independently built bioserver package.
 */
public final class DatabaseSmoke {
    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected game ID, user ID and session ID");
        }
        String game = args[0], user = args[1], session = args[2];
        if (!game.equals("00000002") && !game.equals("00000010")) {
            throw new AssertionError("Unrecognized game ID");
        }
        Database db = new Database(
            "bioserver", "bioserver", "127.0.0.1",
            "&allowPublicKeyRetrieval=true&useSSL=false", "bioserver"
        );
        expect(user.equals(db.getUserid(session)), "Session lookup failed for " + game);
        expect("Synthetic fixture MOTD".equals(db.getMOTD()), "MOTD mismatch");
        expect(db.getHNPairs(user).getArray()[0] == 1, "Handle list mismatch");
        expect(!db.checkHandle(game.equals("00000002") ? "A1B2C3" : "D4E5F6"), "Reserved handle shown as free");
        expect(db.checkHandle("ZZZZZZ"), "Unused handle marked occupied");
        db.updateClientGame(user, 42);
        expect(db.getGameNumber(user) == 42, "Game number write/read mismatch");
        db.updateClientOrigin(user, 3, 4, 5, 6);
        System.out.println("PASS: " + game + " JDBC synthetic DB smoke");
    }

    private static void expect(boolean truth, String message) {
        if (!truth) throw new AssertionError(message);
    }
}
