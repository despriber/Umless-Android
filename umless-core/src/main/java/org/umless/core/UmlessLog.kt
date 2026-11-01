package org.umless.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志：写入 home/logs/umless.log（滚动，上限约 1MB × 2），同时输出到 logcat。
 * 默认不记录用户说的原文（与桌面端一致：只记字数与耗时）；原文只允许用 [d] 写，且仅在 [debug] 打开时落盘。
 */
object UmlessLog {
    const val MAX_BYTES = 1L shl 20

    // android.util.Log 的级别
    private const val DEBUG = 3
    private const val INFO = 4
    private const val WARN = 5
    private const val ERROR = 6

    /** 调试模式：写入 d() 级别的日志（可能含原文），默认关闭 */
    @Volatile
    var debug = false

    @Volatile
    private var dir: File? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    val logFile: File? get() = dir?.let { File(it, "umless.log") }

    internal fun open(logDir: File) {
        logDir.mkdirs()
        dir = logDir
    }

    fun d(tag: String, msg: String) {
        if (debug) write(DEBUG, "D", tag, msg, null)
    }

    fun i(tag: String, msg: String) { write(INFO, "I", tag, msg, null) }
    fun w(tag: String, msg: String, e: Throwable? = null) { write(WARN, "W", tag, msg, e) }
    fun e(tag: String, msg: String, e: Throwable? = null) { write(ERROR, "E", tag, msg, e) }

    private fun write(priority: Int, level: String, tag: String, msg: String, e: Throwable?) {
        val p = Umless.platformOrNull
        try {
            if (p != null) p.logcat(priority, "Umless/$tag", msg, e)
        } catch (_: Throwable) {
            // logcat 不可用（JVM 单元测试）时忽略
        }
        val file = logFile ?: return
        val line = buildString {
            synchronized(timeFormat) { append(timeFormat.format(Date())) }
            append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            if (e != null) append(stackTrace(e))
        }
        synchronized(lock) {
            try {
                if (file.length() + line.length > MAX_BYTES) rotate(file)
                file.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    private fun rotate(file: File) {
        val old = File(file.path + ".1")
        old.delete()
        file.renameTo(old)
    }

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    /** 配置里的密钥一律打码 */
    fun maskSecrets(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            if (SECRET.containsMatchIn(k) && v is JsonPrimitive && v.isString)
                JsonPrimitive(if (v.content.isEmpty()) "" else "***")
            else maskSecrets(v)
        })
        is JsonArray -> JsonArray(e.map(::maskSecrets))
        else -> e
    }

    private val prettyJson = Json { prettyPrint = true }

    private val SECRET = Regex("(?i)(api_key|apikey|token|secret|password|authorization)")

    /** 打包日志与设备信息（不含配置里的 API Key、不含转写原文）到 [dest]，供「导出诊断日志」。dest 以 .zip 结尾时打 zip 包，否则写一个文本文件 */
    fun export(dest: File) {
        dest.parentFile?.mkdirs()
        val info = buildString {
            append("Umless diagnostic bundle\n")
            append("exported_at: ").append(Clock.nowIso()).append('\n')
            Umless.platformOrNull?.let { p ->
                runCatching { p.deviceInfo() }.getOrNull()?.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
            }
            append("language: ").append(runCatching { Umless.language() }.getOrDefault("?")).append('\n')
        }
        val config = runCatching {
            prettyJson.encodeToString(JsonElement.serializer(), maskSecrets(UmlessConfig.get()))
        }.getOrElse { "{}" }
        val logs = synchronized(lock) {
            val f = logFile
            listOfNotNull(f?.let { File(it.path + ".1") }, f).filter { it.exists() }.map { it.name to it.readBytes() }
        }
        if (dest.name.endsWith(".zip", ignoreCase = true)) {
            ZipOutputStream(dest.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("info.txt", info.toByteArray(Charsets.UTF_8))
                entry("config.json", config.toByteArray(Charsets.UTF_8))
                for ((name, bytes) in logs) entry(name, bytes)
            }
        } else {
            dest.outputStream().use { out ->
                out.write(info.toByteArray(Charsets.UTF_8))
                out.write("\n===== config.json =====\n".toByteArray(Charsets.UTF_8))
                out.write(config.toByteArray(Charsets.UTF_8))
                for ((name, bytes) in logs) {
                    out.write("\n\n===== $name =====\n".toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                }
            }
        }
    }
}
