package gsb.store

import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = 2

/** SQLite access with WAL, crash recovery and explicit migrations. */
class Db(path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA foreign_keys=ON") }
    }

    init { migrate(); recover() }

    private fun migrate() {
        val v = userVersion()
        if (v < 1) {
            tx { c ->
                c.createStatement().use { s ->
                    s.execute("""CREATE TABLE IF NOT EXISTS artifacts(
                        id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
                        version INTEGER NOT NULL, status TEXT NOT NULL,
                        content TEXT NOT NULL, created_at TEXT NOT NULL,
                        UNIQUE(kind,name,version))""")
                    s.execute("""CREATE TABLE IF NOT EXISTS pointers(
                        kind TEXT NOT NULL, name TEXT NOT NULL, version INTEGER NOT NULL,
                        PRIMARY KEY(kind,name))""")
                    s.execute("""CREATE TABLE IF NOT EXISTS artifact_deps(
                        artifact_id TEXT NOT NULL, depends_on TEXT NOT NULL,
                        PRIMARY KEY(artifact_id,depends_on))""")
                    s.execute("""CREATE TABLE IF NOT EXISTS events(
                        seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL,
                        kind TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)""")
                    s.execute("""CREATE TABLE IF NOT EXISTS branches(
                        name TEXT PRIMARY KEY, base_event_seq INTEGER NOT NULL,
                        head_event_seq INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1)""")
                }
                setUserVersion(c, 1)
            }
        }
        if (v < 2) {
            // v2: events gain a branch column so conflicting decisions stay separable
            tx { c ->
                c.createStatement().use {
                    it.execute("ALTER TABLE events ADD COLUMN branch TEXT NOT NULL DEFAULT 'main'")
                }
                setUserVersion(c, 2)
            }
        }
    }

    /** Interrupted publishes leave 'building' rows; a restart must never expose them. */
    fun recover() {
        conn.createStatement().use { it.execute("DELETE FROM artifacts WHERE status='building'") }
    }

    fun userVersion(): Int =
        conn.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun setUserVersion(c: Connection, v: Int) =
        c.createStatement().use { it.execute("PRAGMA user_version=$v") }

    fun <T> tx(block: (Connection) -> T): T {
        val old = conn.autoCommit
        conn.autoCommit = false
        try { val r = block(conn); conn.commit(); return r }
        catch (e: Throwable) { conn.rollback(); throw e }
        finally { conn.autoCommit = old }
    }

    fun close() = conn.close()
}
