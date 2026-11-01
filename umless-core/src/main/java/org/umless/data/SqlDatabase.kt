package org.umless.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 最小的 SQL 接口：Android 用 [AndroidSqlDatabase]（SQLiteDatabase），JVM 单元测试用 sqlite-jdbc 实现。
 * 参数按类型绑定（Long / Int / Double / String / Boolean / null）。实现在打开时启用 WAL 与外键。
 */
interface SqlDatabase {
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>

    /** 执行 INSERT，返回 last_insert_rowid */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    /** 执行 UPDATE / DELETE，返回影响的行数 */
    fun update(sql: String, args: List<Any?> = emptyList()): Int

    /** 执行不返回结果、没有参数的单条语句（DDL、PRAGMA 赋值、VACUUM） */
    fun exec(sql: String)

    fun <T> transaction(block: () -> T): T

    fun close()
}

/** 查询结果的一行：列名 → 值（Long / Double / String / ByteArray / null） */
class Row(private val index: Map<String, Int>, private val values: Array<Any?>) {
    val columns: Set<String> get() = index.keys

    fun has(name: String) = name in index

    operator fun get(name: String): Any? = index[name]?.let { values[it] }

    operator fun get(i: Int): Any? = values[i]

    fun str(name: String): String? = get(name)?.toString()

    fun long(name: String): Long? = when (val v = get(name)) {
        null -> null
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    fun int(name: String): Int? = long(name)?.toInt()

    fun double(name: String): Double? = when (val v = get(name)) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    /** SQLite 的 0/1 → 布尔；null 为 false（Python 的真值语义） */
    fun bool(name: String): Boolean = when (val v = get(name)) {
        null -> false
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    fun toJson(): JsonObject = JsonObject(index.entries.sortedBy { it.value }.associate { (k, i) -> k to valueJson(values[i]) })

    companion object {
        fun valueJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Long -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v.toLong())
            is Double -> JsonPrimitive(v)
            is Float -> JsonPrimitive(v.toDouble())
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is ByteArray -> JsonPrimitive(String(v, Charsets.UTF_8))
            else -> JsonPrimitive(v.toString())
        }

        fun build(columns: List<String>, values: Array<Any?>): Row {
            val index = LinkedHashMap<String, Int>()
            columns.forEachIndexed { i, c -> if (c !in index) index[c] = i }
            return Row(index, values)
        }
    }
}
