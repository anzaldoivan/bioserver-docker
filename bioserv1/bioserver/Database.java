package bioserver;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC persistence for both Outbreak game servers.
 *
 * MySQL remains the default. SQLite is opt-in via DB_ENGINE=sqlite and
 * DB_SQLITE_PATH; the file must have been initialized before startup.
 * All SQL inputs are bound, especially leading-zero game IDs and SJIS names.
 */
public class Database implements AutoCloseable {
    private final String url;
    private final String user;
    private final String password;
    private final boolean sqlite;
    private Connection con;

    public Database(String dbUser, String dbPassword, String dbHost, String dbParams, String dbDatabase) {
        String engine = System.getenv().getOrDefault("DB_ENGINE", "mysql");
        if ("sqlite".equalsIgnoreCase(engine)) {
            sqlite = true;
            String path = System.getenv().getOrDefault("DB_SQLITE_PATH", "/var/lib/bioserver/bioserver.sqlite");
            if (!Path.of(path).isAbsolute() || !Files.isRegularFile(Path.of(path))) {
                throw new IllegalStateException("SQLite DB_SQLITE_PATH must point to an initialized absolute-path file: " + path);
            }
            url = "jdbc:sqlite:" + path;
            user = "";
            password = "";
        } else if ("mysql".equalsIgnoreCase(engine)) {
            sqlite = false;
            url = String.format(
                "jdbc:mysql://%s:3306/%s?useUnicode=true&characterEncoding=UTF-8%s",
                dbHost, dbDatabase, dbParams == null ? "" : dbParams
            );
            user = dbUser;
            password = dbPassword;
        } else {
            throw new IllegalArgumentException("Unsupported DB_ENGINE: " + engine);
        }

        con = openConnection();
        setupDBrestart();
    }

    private Connection openConnection() {
        try {
            Connection opened = sqlite ? DriverManager.getConnection(url)
                                       : DriverManager.getConnection(url, user, password);
            try {
                if (sqlite) {
                    try (Statement stmt = opened.createStatement()) {
                        stmt.execute("PRAGMA busy_timeout=10000");
                        stmt.execute("PRAGMA foreign_keys=ON");
                        try (ResultSet mode = stmt.executeQuery("PRAGMA journal_mode")) {
                            if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
                                throw new SQLException("SQLite must use WAL mode (initialize database first)");
                            }
                        }
                    }
                }
                // Validate the complete schema on every new/recovered connection.
                try (Statement stmt = opened.createStatement()) {
                    for (String table : new String[] {"users", "sessions", "hnpairs", "motd"}) {
                        try (ResultSet ignored = stmt.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
                            // A missing table fails fast rather than silently creating a partial DB.
                        }
                    }
                }
                return opened;
            } catch (SQLException | RuntimeException failure) {
                opened.close();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not open or validate " + (sqlite ? "SQLite" : "MySQL") + " database", failure);
        }
    }

    private void testConnection() {
        try {
            if (con != null && !con.isClosed()) {
                try (Statement stmt = con.createStatement();
                     ResultSet ignored = stmt.executeQuery("SELECT 1")) {
                    return;
                }
            }
        } catch (SQLException failure) {
            // Reopen a broken connection below.
        }
        try {
            if (con != null) con.close();
        } catch (SQLException ignored) {
        }
        con = openConnection();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    private PreparedStatement prepare(String sql, Binder bind) throws SQLException {
        testConnection();
        PreparedStatement stmt = con.prepareStatement(sql);
        try {
            bind.bind(stmt);
            return stmt;
        } catch (SQLException | RuntimeException failure) {
            stmt.close();
            throw failure;
        }
    }

    private void execute(String sql, Binder bind) {
        try (PreparedStatement stmt = prepare(sql, bind)) {
            stmt.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Database update failed", failure);
        }
    }

    public String getUserid(String sessid) {
        try (PreparedStatement stmt = prepare(
                "SELECT userid FROM sessions WHERE sessid=? AND gameid=?",
                q -> { q.setString(1, sessid); q.setString(2, ServerMain.GAMEID); });
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException failure) {
            throw new IllegalStateException("Session lookup failed", failure);
        }
    }

    public HNPairs getHNPairs(String userid) {
        HNPairs pairs = new HNPairs();
        try (PreparedStatement stmt = prepare(
                "SELECT handle,nickname FROM hnpairs WHERE userid=? LIMIT 3",
                q -> q.setString(1, userid));
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                pairs.add(new HNPair(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Handle lookup failed", failure);
        }
        return pairs;
    }

    /** Returns true when the handle is available. */
    public boolean checkHandle(String handle) {
        try (PreparedStatement stmt = prepare(
                "SELECT COUNT(*) FROM hnpairs WHERE handle=?",
                q -> q.setString(1, handle));
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() && rs.getInt(1) == 0;
        } catch (SQLException failure) {
            throw new IllegalStateException("Handle availability lookup failed", failure);
        }
    }

    private static String nickname(Client client) {
        return new String(client.getHNPair().getNickname(), Charset.forName("Shift_JIS"));
    }

    private static String handle(Client client) {
        return new String(client.getHNPair().getHandle(), StandardCharsets.US_ASCII);
    }

    public void createNewHNPair(Client client) {
        execute("INSERT INTO hnpairs (userid,handle,nickname) VALUES (?,?,?)",
                q -> {
                    q.setString(1, client.getUserID());
                    q.setString(2, handle(client));
                    q.setString(3, nickname(client));
                });
    }

    public void updateHNPair(Client client) {
        execute("UPDATE hnpairs SET nickname=? WHERE userid=? AND handle=?",
                q -> {
                    q.setString(1, nickname(client));
                    q.setString(2, client.getUserID());
                    q.setString(3, handle(client));
                });
    }

    private void setupDBrestart() {
        execute("UPDATE sessions SET area=-1,room=0,slot=0,gamesess=0,state=0 WHERE gameid=?",
                q -> q.setString(1, ServerMain.GAMEID));
    }

    public void updateClientOrigin(String userid, int state, int area, int room, int slot) {
        execute("UPDATE sessions SET state=?,area=?,room=?,slot=? WHERE userid=? AND gameid=?",
                q -> {
                    q.setInt(1, state);
                    q.setInt(2, area);
                    q.setInt(3, room);
                    q.setInt(4, slot);
                    q.setString(5, userid);
                    q.setString(6, ServerMain.GAMEID);
                });
    }

    public void updateClientGame(String userid, int gamenumber) {
        execute("UPDATE sessions SET gamesess=? WHERE userid=? AND gameid=?",
                q -> {
                    q.setInt(1, gamenumber);
                    q.setString(2, userid);
                    q.setString(3, ServerMain.GAMEID);
                });
    }

    public int getGameNumber(String userid) {
        try (PreparedStatement stmt = prepare(
                "SELECT gamesess FROM sessions WHERE userid=? AND gameid=?",
                q -> {
                    q.setString(1, userid);
                    q.setString(2, ServerMain.GAMEID);
                });
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException failure) {
            throw new IllegalStateException("Game lookup failed", failure);
        }
    }

    public String getMOTD() {
        try (PreparedStatement stmt = prepare(
                "SELECT message FROM motd WHERE active=1 ORDER BY id DESC LIMIT 1",
                q -> {});
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException failure) {
            throw new IllegalStateException("MOTD lookup failed", failure);
        }
    }

    @Override
    public void close() {
        try {
            if (con != null) con.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("Database close failed", failure);
        }
    }
}
