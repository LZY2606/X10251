package jtag.store

import java.sql.Connection
import java.sql.DriverManager

class Database(path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    companion object { const val SCHEMA = 2 }

    init {
        migrate()
        recover()
    }

    fun exec(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.execute()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            val rs = ps.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += map(rs)
            return out
        }
    }

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block(); conn.commit(); return r
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun migrate() {
        exec("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)")
        var v = query("SELECT version FROM schema_version") { it.getInt(1) }.firstOrNull()
        if (v == null) { exec("INSERT INTO schema_version(version) VALUES(0)"); v = 0 }
        if (v < 1) {
            exec("CREATE TABLE IF NOT EXISTS version(id TEXT PRIMARY KEY, base_id TEXT, seq INTEGER, status TEXT, note TEXT, created_at TEXT)")
            exec("CREATE TABLE IF NOT EXISTS source_item(id TEXT, version_id TEXT, kind TEXT, item_key TEXT, payload TEXT, PRIMARY KEY(version_id, item_key))")
            exec("CREATE TABLE IF NOT EXISTS derived_item(id TEXT, version_id TEXT, kind TEXT, item_key TEXT, payload TEXT, deps TEXT, PRIMARY KEY(version_id, item_key))")
            exec("CREATE TABLE IF NOT EXISTS event(id TEXT, version_id TEXT, seq INTEGER, type TEXT, payload TEXT, PRIMARY KEY(version_id, seq))")
            exec("CREATE TABLE IF NOT EXISTS current_pointer(id INTEGER PRIMARY KEY CHECK(id=1), version_id TEXT)")
            v = 1
        }
        if (v < 2) {
            exec("CREATE TABLE IF NOT EXISTS derivation_run(id TEXT, version_id TEXT, node_kind TEXT, action TEXT, reason TEXT)")
            exec("CREATE TABLE IF NOT EXISTS branch(id TEXT PRIMARY KEY, base_version TEXT, head_version TEXT, status TEXT, resolution_event TEXT)")
            v = 2
        }
        exec("UPDATE schema_version SET version=$v")
    }

    /** 崩溃恢复：写入中断留下的 building 版本只可能是不完整的，标记 aborted，当前指针绝不可见它。 */
    private fun recover() {
        exec("UPDATE version SET status='aborted' WHERE status='building'")
    }
}
