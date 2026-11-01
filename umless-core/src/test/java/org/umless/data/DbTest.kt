package org.umless.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.umless.coach.Metrics
import org.umless.core.TestEnv
import org.umless.core.Umless
import org.umless.core.UtteranceRecord
import java.io.File
import java.nio.file.Files

class DbTest {
    private fun tempDb(): File = File(Files.createTempDirectory("umless-db").toFile(), "umless.db")

    private fun desktopDbPy(): File? {
        val repo = System.getProperty("umless.repo") ?: return null
        return File(repo, "desktop/umless/db.py").takeIf { it.exists() }
    }

    private fun extract(py: String, name: String): String {
        val m = Regex("$name = \"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).find(py) ?: error("$name not found")
        return m.groupValues[1]
    }

    /** 表结构快照：每张表的列（名、类型、非空、默认值、主键）、索引、外键 */
    private fun schemaOf(sql: SqlDatabase): Map<String, List<String>> {
        val out = sortedMapOf<String, List<String>>()
        val tables = sql.query("SELECT name, type FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")
        for (t in tables) {
            val name = t.str("name")!!
            if (t.str("type") != "table") {
                out["${t.str("type")}:$name"] = listOf()
                continue
            }
            out["table:$name"] = sql.query("PRAGMA table_info($name)").map {
                "${it.str("name")} ${it.str("type")} notnull=${it.long("notnull")} dflt=${it.str("dflt_value")} pk=${it.long("pk")}"
            } + sql.query("PRAGMA index_list($name)").map { "index ${it.str("name")} unique=${it.long("unique")}" }.sorted() +
                sql.query("PRAGMA foreign_key_list($name)").map {
                    "fk ${it.str("from")} -> ${it.str("table")}.${it.str("to")} on_delete=${it.str("on_delete")}"
                }
        }
        return out
    }

    @Test
    fun schemaStringsIdenticalToDesktop() {
        val f = desktopDbPy()
        assumeTrue("desktop repo not found", f != null)
        val py = f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals(extract(py, "SCHEMA_V1"), Db.SCHEMA_V1)
        assertEquals(extract(py, "SCHEMA_V2"), Db.SCHEMA_V2)
        assertTrue(py.contains("SCHEMA_VERSION = ${Db.SCHEMA_VERSION}"))
    }

    @Test
    fun schemaMatchesDesktopMigration() {
        val f = desktopDbPy()
        assumeTrue("desktop repo not found", f != null)
        val py = f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
        // 参考库：按 db.py 的 _migrate 逐条执行 SCHEMA_V1 + SCHEMA_V2
        val ref = JdbcSqlDatabase(tempDb())
        Db.statements(extract(py, "SCHEMA_V1")).forEach { ref.exec(it) }
        Db.statements(extract(py, "SCHEMA_V2")).forEach { ref.exec(it) }
        val ours = JdbcSqlDatabase(tempDb())
        Db(ours)
        assertEquals(schemaOf(ref), schemaOf(ours))
        assertEquals(2L, ours.query("PRAGMA user_version")[0].long("user_version"))
        assertEquals(
            setOf("utterances", "metrics", "lexicon", "diagnoses", "habits", "practice", "kv"),
            schemaOf(ours).keys.filter { it.startsWith("table:") && it != "table:sqlite_sequence" }.map { it.removePrefix("table:") }.toSet(),
        )
    }

    @Test
    fun migratesV1DatabaseAndIsIdempotent() {
        val file = tempDb()
        val v1 = JdbcSqlDatabase(file)
        Db.statements(Db.SCHEMA_V1).forEach { v1.exec(it) }
        v1.exec("PRAGMA user_version = 1")
        v1.insert(
            "INSERT INTO utterances (created_at, duration_s, app, asr_engine, raw_text, output_text, asr_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf("2026-03-10T09:00:00", 1.5, "a", "sensevoice", "嗯你好", "你好", 10),
        )
        v1.close()
        val db = Db(JdbcSqlDatabase(file))
        val cols = db.query("PRAGMA table_info(utterances)").map { it.str("name") }
        assertTrue("scene" in cols && "intensity" in cols)
        assertEquals(1, db.query("SELECT * FROM utterances").size)
        db.close()
        Db(JdbcSqlDatabase(file)).close() // 再次打开不报错
    }

    @Test
    fun kvLexiconUtterancesAndCascade() {
        TestEnv.setup()
        val db = Umless.db
        assertNull(db.getKv("nope"))
        db.setKv("x", buildJsonObject { put("a", 1); put("b", "中文") })
        assertEquals("{\"a\": 1, \"b\": \"中文\"}", db.query("SELECT value FROM kv WHERE key = 'x'")[0].str("value"))
        assertEquals(buildJsonObject { put("a", 1); put("b", "中文") }, db.getKv("x"))
        db.setKv("x", null)
        assertNull(db.getKv("x"))

        val added = db.addWords(listOf("嗯", " 嗯 ", "", "一二三四五六七八九", "就是"), "builtin")
        assertEquals(listOf("嗯", "就是"), added)
        assertEquals(emptyList<String>(), db.addWords(listOf("嗯"), "user"))
        db.setWordEnabled("嗯", false)
        assertEquals(listOf("就是"), db.lexicon().map { it.str("word") })

        val uid = db.addUtterance(UtteranceRecord(1.234, "com.tencent.mm", "sensevoice", "嗯你好", 12, "你好", polishedText = "你好"))
        assertEquals(1.23, db.query("SELECT duration_s FROM utterances WHERE id = ?", uid)[0].double("duration_s")!!, 0.0)
        db.saveMetrics(uid, Metrics.analyze("嗯你好", "你好", 1.234, listOf("嗯")))
        val m = db.query("SELECT * FROM metrics WHERE utterance_id = ?", uid)[0]
        assertEquals("{\"嗯\": 1}", m.str("fillers"))
        assertEquals("[[\"嗯\", \"filler\"], [\"你好\", \"keep\"]]", m.str("highlights"))
        db.deleteUtterance(uid)
        assertTrue(db.query("SELECT * FROM metrics").isEmpty()) // 外键级联删除

        val export = db.exportAll()
        assertEquals(Db.EXPORT_TABLES, export.keys.toList())
        assertEquals(1, (export["lexicon"] as JsonArray).count { (it as JsonObject)["word"] == JsonPrimitive("嗯") })
        db.addWords(listOf("你知道吗"), "user")
        db.clearAll()
        assertEquals(setOf("builtin"), db.lexicon(false).map { it.str("source") }.toSet())
    }
}
