import bioserver.Client;
import bioserver.Database;
import bioserver.HNPair;
import bioserver.ServerMain;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Execute the SAME behavioral contract for both game trees against both engines. */
public final class DatabaseSmoke {
    private static void expect(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static Connection inspect() throws Exception {
        if ("sqlite".equals(System.getenv().getOrDefault("DB_ENGINE", "mysql"))) {
            return DriverManager.getConnection("jdbc:sqlite:" + System.getenv("DB_SQLITE_PATH"));
        }
        return DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:3306/bioserver?allowPublicKeyRetrieval=true&useSSL=false",
            "bioserver", "bioserver"
        );
    }

    private static long state(String user, String game, String column) throws Exception {
        // Columns are fixed literals provided only by this test.
        try (Connection conn = inspect();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT " + column + " FROM sessions WHERE userid=? AND gameid=?")) {
            stmt.setString(1, user);
            stmt.setString(2, game);
            try (ResultSet rs = stmt.executeQuery()) {
                expect(rs.next(), "Session row missing");
                return rs.getLong(1);
            }
        }
    }

    private static String nickname(String handle) throws Exception {
        try (Connection conn = inspect();
             PreparedStatement stmt = conn.prepareStatement("SELECT nickname FROM hnpairs WHERE handle=?")) {
            stmt.setString(1, handle);
            try (ResultSet rs = stmt.executeQuery()) {
                expect(rs.next(), "Handle missing");
                return rs.getString(1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected game ID, userid, sessid");
        String game = args[0], user = args[1], session = args[2];
        expect(game.equals(ServerMain.GAMEID), "Executable game differs from requested game ID");

        String otherGame = game.equals("00000002") ? "00000010" : "00000002";
        String otherUser = game.equals("00000002") ? "fixture_b" : "fixture_a";

        // Explicit state before JVM startup checks game-specific restart isolation.
        try (Connection conn = inspect(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE sessions SET area=23,room=9,slot=7,state=8,gamesess=99 WHERE gameid=?")) {
            for (String id : new String[] {game, otherGame}) {
                stmt.setString(1, id);
                stmt.executeUpdate();
            }
        }

        try (Database db = new Database(
                "bioserver", "bioserver", "127.0.0.1",
                "&allowPublicKeyRetrieval=true&useSSL=false", "bioserver")) {

            expect(state(user, game, "area") == -1, "Restart did not reset own area");
            expect(state(user, game, "gamesess") == 0, "Restart did not reset own game number");
            expect(state(otherUser, otherGame, "area") == 23, "Restart incorrectly changed other game");

            expect(user.equals(db.getUserid(session)), "Session lookup failed");
            expect("".equals(db.getUserid("' OR 1=1 --")), "Injected session returned user");
            expect("Synthetic fixture MOTD".equals(db.getMOTD()), "MOTD mismatch");
            expect(db.getHNPairs(user).getArray()[0] == 1, "Handle list mismatch");
            expect(db.getHNPairs("' OR 1=1 --").getArray()[0] == 0, "Injected user returned handle");
            expect(!db.checkHandle(game.equals("00000002") ? "A1B2C3" : "D4E5F6"),
                    "Existing handle reported available");
            expect(db.checkHandle("ZZZZZZ"), "Nonexistent handle reported occupied");

            db.updateClientGame(user, 42);
            expect(db.getGameNumber(user) == 42, "Game number update did not persist");
            expect(state(user, game, "gamesess") == 42, "Direct game number check failed");
            db.updateClientOrigin(user, 3, 4, 5, 6);
            expect(state(user, game, "state") == 3, "State update failed");
            expect(state(user, game, "area") == 4, "Area update failed");
            expect(state(user, game, "room") == 5, "Room update failed");
            expect(state(user, game, "slot") == 6, "Slot update failed");

            Client client = new Client(null, user, session);
            String handle = game.equals("00000002") ? "Q1R2S3" : "Q4R5S6";
            client.setHNPair(new HNPair(handle, "勇気 O'Neil"));
            db.createNewHNPair(client);
            expect("勇気 O'Neil".equals(nickname(handle)), "SJIS->UTF8 insert mismatch");
            client.setHNPair(new HNPair(handle, "おはよう"));
            db.updateHNPair(client);
            expect("おはよう".equals(nickname(handle)), "SJIS->UTF8 update mismatch");

            // Simulate connection closure and verify the configured engine reconnects.
            Field conField = Database.class.getDeclaredField("con");
            conField.setAccessible(true);
            ((Connection) conField.get(db)).close();
            expect(user.equals(db.getUserid(session)), "Connection recovery failed");
        }

        System.out.println("PASS " + System.getenv().getOrDefault("DB_ENGINE", "mysql") +
                           " Java JDBC contract for " + game);
    }
}
