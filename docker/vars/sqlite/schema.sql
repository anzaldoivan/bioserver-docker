PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS users (
 userid TEXT COLLATE NOCASE NOT NULL PRIMARY KEY CHECK(length(userid)<=14),
 passwd TEXT NOT NULL CHECK(length(passwd)<=32)
);
CREATE TABLE IF NOT EXISTS sessions (
 userid TEXT COLLATE NOCASE NOT NULL CHECK(length(userid)<=14),
 ip TEXT NOT NULL, port INTEGER NOT NULL,
 sessid TEXT NOT NULL CHECK(length(sessid)=8),
 gamesess INTEGER NOT NULL DEFAULT 0, lastlogin TEXT DEFAULT NULL,
 area INTEGER DEFAULT -1, room INTEGER DEFAULT 0, slot INTEGER DEFAULT 0,
 state INTEGER DEFAULT 0, gameid TEXT NOT NULL CHECK(length(gameid)=8),
 UNIQUE(sessid, gameid)
);
CREATE INDEX IF NOT EXISTS idx_sessions_gamesess_gameid ON sessions(gamesess,gameid);
CREATE INDEX IF NOT EXISTS idx_sessions_userid ON sessions(userid);
CREATE TABLE IF NOT EXISTS hnpairs (
 userid TEXT COLLATE NOCASE NOT NULL CHECK(length(userid)<=14),
 handle TEXT COLLATE NOCASE NOT NULL CHECK(length(handle)<=6),
 nickname TEXT COLLATE BINARY NOT NULL CHECK(length(nickname)<=100)
);
CREATE INDEX IF NOT EXISTS idx_hnpairs_userid ON hnpairs(userid);
CREATE INDEX IF NOT EXISTS idx_hnpairs_handle ON hnpairs(handle);
CREATE TABLE IF NOT EXISTS motd (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 message TEXT NOT NULL CHECK(length(message)<=2000),
 active INTEGER NOT NULL DEFAULT 0
);
