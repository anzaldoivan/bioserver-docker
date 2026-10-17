import bioserver.Database;
import bioserver.ServerMain;

/** Concurrent multi-process SQLite writer for CI. */
public final class DatabaseLoad {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !ServerMain.GAMEID.equals(args[0])) {
            throw new IllegalArgumentException("game, userid, iterations required");
        }
        String user = args[1];
        int iterations = Integer.parseInt(args[2]);
        try (Database db = new Database("bioserver", "bioserver", "127.0.0.1", "", "bioserver")) {
            for (int i = 0; i < iterations; i++) {
                db.updateClientGame(user, i);
                db.updateClientOrigin(user, i % 3, i % 8, i % 5, i % 4);
                Thread.sleep(1);
            }
            if (db.getGameNumber(user) != iterations - 1) {
                throw new AssertionError("Lost Java update: " + user);
            }
        }
        System.out.println("Concurrent writer success: " + ServerMain.GAMEID);
    }
}
