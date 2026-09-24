package jtap.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class Database private constructor(val path: Path) {
    companion object {
        init { Class.forName("org.sqlite.JDBC") }

        fun open(file: Path): Database {
            Files.createDirectories(file.toAbsolutePath().parent)
            val db = Database(file)
            db.connect().use { Schema.migrate(it) }
            return db
        }

        /** Ephemeral in-memory database (each connection a fresh DB, so tests use a temp file). */
        fun tempFile(label: String = "jtap"): Path =
            Files.createTempFile("${label}-", ".db").also { it.toFile().deleteOnExit() }
    }

    fun connect(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:$path")
        c.createStatement().use {
            it.execute("PRAGMA busy_timeout=10000")
            it.execute("PRAGMA foreign_keys=ON")
        }
        return c
    }
}
