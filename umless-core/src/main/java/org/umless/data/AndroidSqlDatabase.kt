package org.umless.data

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import java.io.File

/** SQLiteDatabase 实现：WAL、外键；参数按类型绑定（rawQuery 只能绑字符串，这里用 CursorFactory 绑定） */
class AndroidSqlDatabase(file: File) : SqlDatabase {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path, null,
        SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
    ).apply { setForeignKeyConstraintsEnabled(true) }

    private fun bind(p: SQLiteProgram, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> p.bindNull(idx)
                is Boolean -> p.bindLong(idx, if (v) 1 else 0)
                is Int -> p.bindLong(idx, v.toLong())
                is Long -> p.bindLong(idx, v)
                is Float -> p.bindDouble(idx, v.toDouble())
                is Double -> p.bindDouble(idx, v)
                is Number -> p.bindDouble(idx, v.toDouble())
                is ByteArray -> p.bindBlob(idx, v)
                else -> p.bindString(idx, v.toString())
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Row> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            bind(query, args)
            SQLiteCursor(driver, editTable, query)
        }
        db.rawQueryWithFactory(factory, sql, null, "").use { c ->
            val cols = c.columnNames.toList()
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) {
                val values = Array<Any?>(cols.size) { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                out.add(Row.build(cols, values))
            }
            return out
        }
    }

    override fun insert(sql: String, args: List<Any?>): Long =
        db.compileStatement(sql).use { st ->
            bind(st, args)
            st.executeInsert()
        }

    override fun update(sql: String, args: List<Any?>): Int =
        db.compileStatement(sql).use { st ->
            bind(st, args)
            st.executeUpdateDelete()
        }

    override fun exec(sql: String) = db.execSQL(sql)

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()
}
