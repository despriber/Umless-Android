package org.umless.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** 单元测试用的 sqlite-jdbc 实现（与 Android 实现相同的语义：WAL、外键、按类型绑定） */
class JdbcSqlDatabase(file: File) : SqlDatabase {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private var depth = 0

    init {
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
        }
    }

    private fun prepare(sql: String, args: List<Any?>): PreparedStatement {
        val st = conn.prepareStatement(sql)
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> st.setNull(idx, Types.NULL)
                is Boolean -> st.setLong(idx, if (v) 1 else 0)
                is Int -> st.setLong(idx, v.toLong())
                is Long -> st.setLong(idx, v)
                is Float -> st.setDouble(idx, v.toDouble())
                is Double -> st.setDouble(idx, v)
                is Number -> st.setDouble(idx, v.toDouble())
                is ByteArray -> st.setBytes(idx, v)
                else -> st.setString(idx, v.toString())
            }
        }
        return st
    }

    @Synchronized
    override fun query(sql: String, args: List<Any?>): List<Row> = prepare(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val md = rs.metaData
            val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
            val out = ArrayList<Row>()
            while (rs.next()) {
                val values = Array<Any?>(cols.size) { i ->
                    when (val v = rs.getObject(i + 1)) {
                        is Int -> v.toLong()
                        is Short -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v
                    }
                }
                out.add(Row.build(cols, values))
            }
            out
        }
    }

    @Synchronized
    override fun insert(sql: String, args: List<Any?>): Long {
        prepare(sql, args).use { it.executeUpdate() }
        return conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    @Synchronized
    override fun update(sql: String, args: List<Any?>): Int = prepare(sql, args).use { it.executeUpdate() }

    @Synchronized
    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) {
            depth++
            try { return block() } finally { depth-- }
        }
        conn.autoCommit = false
        depth = 1
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            depth = 0
            conn.autoCommit = true
        }
    }

    @Synchronized
    override fun close() = conn.close()
}
