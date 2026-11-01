package org.umless.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.umless.coach.Analysis
import org.umless.core.Clock
import org.umless.core.Py
import org.umless.core.UtteranceRecord
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本地 SQLite 数据层，移植自桌面端 db.py。schema 与桌面端完全一致（SCHEMA_V1 + SCHEMA_V2，user_version = 2），
 * 两端导出的库可以互相读取。原始逐字稿（raw_text）是表达诊断的数据基础，必须保留。
 */
class Db(private val sql: SqlDatabase) {
    private val lock = ReentrantLock()

    init {
        lock.withLock { migrate() }
    }

    companion object {
        const val SCHEMA_VERSION = 2

        val SCHEMA_V1 = """
CREATE TABLE IF NOT EXISTS utterances (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT    NOT NULL,          -- 本地时间 ISO 格式
    duration_s    REAL    NOT NULL,          -- 录音时长
    app           TEXT,                      -- 前台应用进程名
    asr_engine    TEXT    NOT NULL,
    raw_text      TEXT    NOT NULL,          -- ASR 逐字稿（保留口癖）
    llm_model     TEXT,
    polished_text TEXT,                      -- 润色结果；LLM 关闭或失败时为空
    output_text   TEXT    NOT NULL,          -- 实际输出给用户的文字
    asr_ms        INTEGER NOT NULL,
    llm_ms        INTEGER,
    error         TEXT
);
CREATE INDEX IF NOT EXISTS idx_utterances_created ON utterances(created_at);
"""

        val SCHEMA_V2 = """
-- 场景：chat / document / code / email / default；润色强度：faithful / moderate / restructure
ALTER TABLE utterances ADD COLUMN scene TEXT;
ALTER TABLE utterances ADD COLUMN intensity TEXT;

CREATE TABLE IF NOT EXISTS metrics (
    utterance_id   INTEGER PRIMARY KEY REFERENCES utterances(id) ON DELETE CASCADE,
    chars          INTEGER NOT NULL,      -- 原文有效字数（不含标点空白）
    fillers        TEXT    NOT NULL,      -- {"就是": 2, "嗯": 1}
    filler_count   INTEGER NOT NULL,
    repeat_count   INTEGER NOT NULL,
    correction_count INTEGER NOT NULL,
    rewrite_ratio  REAL,                  -- 改写幅度 0~1，无润色稿时为空
    cpm            REAL,                  -- 语速：字 / 分钟
    fluency        REAL,                  -- 0~100
    concision      REAL,                  -- 0~100
    scoreable      INTEGER NOT NULL,      -- 是否参与评分（足够长）
    highlights     TEXT    NOT NULL,      -- 原文分段标注 [[文本, 类型], ...]
    version        INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS lexicon (
    word       TEXT PRIMARY KEY,
    source     TEXT NOT NULL,             -- builtin / llm / user
    enabled    INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS diagnoses (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    date       TEXT NOT NULL,             -- 诊断覆盖的日期 YYYY-MM-DD
    created_at TEXT NOT NULL,
    model      TEXT,
    n          INTEGER NOT NULL,          -- 样本条数
    logic      REAL,                      -- LLM 1~5 分 × 20，即 0~100
    clarity    REAL,
    fluency    REAL,                      -- 当天规则均值 0~100
    concision  REAL,
    overall    REAL,
    data       TEXT NOT NULL              -- LLM 返回的完整 JSON
);
CREATE INDEX IF NOT EXISTS idx_diagnoses_date ON diagnoses(date);

CREATE TABLE IF NOT EXISTS habits (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    key         TEXT NOT NULL UNIQUE,     -- 例如 word:就是 / repeat / logic
    title       TEXT NOT NULL,
    kind        TEXT NOT NULL,            -- word / repeat / correction / logic / clarity
    word        TEXT,
    advice      TEXT,
    evidence    TEXT,                     -- JSON 列表
    baseline    REAL,
    target      REAL,
    current     REAL,
    status      TEXT NOT NULL,            -- active / achieved / archived
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    achieved_at TEXT
);

CREATE TABLE IF NOT EXISTS practice (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL,
    topic      TEXT NOT NULL,
    category   TEXT,
    duration_s REAL NOT NULL,
    raw_text   TEXT NOT NULL,
    overall    REAL,
    data       TEXT NOT NULL              -- 评分与反馈 JSON
);

CREATE TABLE IF NOT EXISTS kv (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

        val EXPORT_TABLES = listOf("utterances", "metrics", "lexicon", "diagnoses", "habits", "practice")

        private val ALTER_RE = Regex("""^ALTER TABLE utterances ADD COLUMN (\w+)""")

        /** 按 ";" 切分脚本并去掉整行注释（与 db.py 的 _migrate 相同） */
        fun statements(script: String): List<String> = script.split(";").mapNotNull { stmt ->
            stmt.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").trim().ifEmpty { null }
        }

        fun nowIso(): String = Clock.nowIso()
    }

    // ---- 基础 ----

    private fun migrate() {
        val version = sql.query("PRAGMA user_version").firstOrNull()?.get(0)?.let { (it as Number).toInt() } ?: 0
        sql.transaction {
            if (version < 1) statements(SCHEMA_V1).forEach { sql.exec(it) }
            if (version < 2) {
                val cols = sql.query("PRAGMA table_info(utterances)").map { it.str("name") }.toSet()
                for (stmt in statements(SCHEMA_V2)) {
                    val m = ALTER_RE.find(stmt)
                    if (m != null && m.groupValues[1] in cols) continue
                    sql.exec(stmt)
                }
            }
        }
        sql.exec("PRAGMA user_version = $SCHEMA_VERSION")
    }

    fun query(q: String, vararg args: Any?): List<Row> = lock.withLock { sql.query(q, args.toList()) }

    /** 返回 last_insert_rowid（与 db.py 的 execute 一致） */
    fun insert(q: String, vararg args: Any?): Long = lock.withLock { sql.insert(q, args.toList()) }

    fun update(q: String, vararg args: Any?): Int = lock.withLock { sql.update(q, args.toList()) }

    fun <T> transaction(block: () -> T): T = lock.withLock { sql.transaction(block) }

    fun close() = lock.withLock { sql.close() }

    // ---- 键值（调度状态、缓存等） ----

    /** JSON 值；不存在或值为 null 时返回 null */
    fun getKv(key: String): JsonElement? {
        val rows = query("SELECT value FROM kv WHERE key = ?", key)
        if (rows.isEmpty()) return null
        val v = Json.parseToJsonElement(rows[0].str("value") ?: return null)
        return if (v is JsonNull) null else v
    }

    fun setKv(key: String, value: JsonElement?) {
        insert(
            "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            key, Py.dumps(value ?: JsonNull),
        )
    }

    // ---- 语音记录 ----

    fun addUtterance(u: UtteranceRecord): Long = insert(
        "INSERT INTO utterances (created_at, duration_s, app, asr_engine, raw_text, llm_model, " +
            "polished_text, output_text, asr_ms, llm_ms, error, scene, intensity) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        nowIso(), Py.round(u.durationS, 2), u.app, u.asrEngine, u.rawText, u.llmModel,
        u.polishedText, u.outputText, u.asrMs, u.llmMs, u.error, u.scene, u.intensity,
    )

    fun recent(limit: Int = 10): List<Row> = query("SELECT * FROM utterances ORDER BY id DESC LIMIT ?", limit)

    /** [start, end) 区间内的记录及其指标，按时间正序 */
    fun utterancesBetween(start: String, end: String): List<Row> = query(
        "SELECT u.*, m.chars, m.fillers, m.filler_count, m.repeat_count, m.correction_count, " +
            "m.rewrite_ratio, m.cpm, m.fluency, m.concision, m.scoreable, m.highlights " +
            "FROM utterances u LEFT JOIN metrics m ON m.utterance_id = u.id " +
            "WHERE u.created_at >= ? AND u.created_at < ? ORDER BY u.id",
        start, end,
    )

    fun deleteUtterance(uid: Long) {
        update("DELETE FROM utterances WHERE id = ?", uid)
    }

    fun purgeOlderThan(days: Int): Int {
        val cutoff = Clock.isoOf(Clock.now().minusDays(days.toLong()))
        return update("DELETE FROM utterances WHERE created_at < ?", cutoff)
    }

    // ---- 指标 ----

    fun saveMetrics(uid: Long, m: Analysis) {
        insert(
            "INSERT OR REPLACE INTO metrics (utterance_id, chars, fillers, filler_count, repeat_count, " +
                "correction_count, rewrite_ratio, cpm, fluency, concision, scoreable, highlights, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            uid, m.chars, Py.dumps(m.fillersJson()), m.fillerCount, m.repeatCount, m.correctionCount,
            m.rewriteRatio, m.cpm, m.fluency, m.concision, if (m.scoreable) 1 else 0,
            Py.dumps(m.highlightsJson()), m.version,
        )
    }

    fun utterancesMissingMetrics(version: Int): List<Row> = query(
        "SELECT u.* FROM utterances u LEFT JOIN metrics m ON m.utterance_id = u.id " +
            "WHERE m.utterance_id IS NULL OR m.version < ?",
        version,
    )

    // ---- 口癖词表 ----

    fun lexicon(enabledOnly: Boolean = true): List<Row> =
        query("SELECT * FROM lexicon" + (if (enabledOnly) " WHERE enabled = 1" else "") + " ORDER BY source, word")

    /** 加入新词，返回真正新增的词（已存在的不覆盖来源与开关）。整批一个事务 */
    fun addWords(words: List<String>, source: String): List<String> = transaction {
        val added = ArrayList<String>()
        for (raw in words) {
            val w = Py.strip(raw)
            if (w.isEmpty() || Py.len(w) > 8) continue
            val n = sql.update(
                "INSERT OR IGNORE INTO lexicon (word, source, enabled, created_at) VALUES (?, ?, 1, ?)",
                listOf(w, source, nowIso()),
            )
            if (n > 0) added.add(w)
        }
        added
    }

    fun setWordEnabled(word: String, enabled: Boolean) {
        update("UPDATE lexicon SET enabled = ? WHERE word = ?", if (enabled) 1 else 0, word)
    }

    fun removeWord(word: String) {
        update("DELETE FROM lexicon WHERE word = ?", word)
    }

    // ---- 导出 / 清空 ----

    fun exportAll(): JsonObject = JsonObject(
        EXPORT_TABLES.associateWith { table -> JsonArray(query("SELECT * FROM $table").map { it.toJson() }) }
    )

    fun clearAll() {
        lock.withLock {
            sql.transaction {
                for (table in listOf("metrics", "utterances", "diagnoses", "habits", "practice", "kv")) {
                    sql.update("DELETE FROM $table")
                }
                sql.update("DELETE FROM lexicon WHERE source != 'builtin'")
            }
            sql.exec("VACUUM")
        }
    }
}
