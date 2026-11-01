package org.umless.core

import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.umless.data.AndroidSqlDatabase
import org.umless.data.SqlDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 与 Android 相关的能力。正式运行用 [AndroidPlatform]；JVM 单元测试注入自己的实现（临时目录、sqlite-jdbc、classpath 资源）。
 */
interface UmlessPlatform {
    /** 数据目录：config.json、umless.db、models/、logs/ */
    val home: File

    fun openDatabase(file: File): SqlDatabase

    /** 读取 assets 里的文件，例如 spec/prompts/polish_base.txt */
    fun openAsset(path: String): InputStream

    /** 系统语言：zh / en */
    fun systemLanguage(): String

    fun appVersion(): String

    /** 导出数据的目录（App 专属外部存储 exports/，用户可在文件管理器里找到） */
    fun exportsDir(): File

    /**
     * 把文件保存到「下载」目录（Android 10+ 用 MediaStore；更早的系统退回 [exportsDir]），
     * 返回显示给用户的路径。export_data 与主 App 的 export_logs 共用
     */
    fun saveToDownloads(name: String, mime: String, write: (OutputStream) -> Unit): String {
        val f = File(exportsDir(), name)
        f.outputStream().use(write)
        return f.path
    }

    fun copyText(text: String)

    /** 设备与应用信息（导出诊断日志用） */
    fun deviceInfo(): Map<String, String>

    /** 输出到 logcat；priority 为 android.util.Log 的级别 */
    fun logcat(priority: Int, tag: String, msg: String, e: Throwable?)
}

class AndroidPlatform(private val context: Context) : UmlessPlatform {
    override val home: File get() = File(context.filesDir, "umless").apply { mkdirs() }

    override fun openDatabase(file: File): SqlDatabase = AndroidSqlDatabase(file)

    override fun openAsset(path: String): InputStream = context.assets.open(path)

    override fun systemLanguage(): String {
        val locale = context.resources.configuration.let {
            if (Build.VERSION.SDK_INT >= 24) it.locales[0] else @Suppress("DEPRECATION") it.locale
        } ?: Locale.getDefault()
        return if (locale.language.lowercase(Locale.ROOT).startsWith("zh")) "zh" else "en"
    }

    override fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun exportsDir(): File =
        (context.getExternalFilesDir("exports") ?: File(context.filesDir, "exports")).apply { mkdirs() }

    override fun saveToDownloads(name: String, mime: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT < 29) return super.saveToDownloads(name, mime, write)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return super.saveToDownloads(name, mime, write)
        try {
            resolver.openOutputStream(uri)!!.use(write)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // 同名文件存在时系统会自动改名，以实际保存的名字为准
        val saved = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(dir, saved).path
    }

    override fun copyText(text: String) {
        val run = Runnable {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Umless", text))
        }
        // 剪贴板服务在部分系统上要求主线程调用
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else Handler(Looper.getMainLooper()).post(run)
    }

    override fun deviceInfo(): Map<String, String> = linkedMapOf(
        "app" to context.packageName,
        "version" to appVersion(),
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "abi" to Build.SUPPORTED_ABIS.joinToString(","),
        "locale" to Locale.getDefault().toLanguageTag(),
    )

    override fun logcat(priority: Int, tag: String, msg: String, e: Throwable?) {
        if (e != null) Log.println(priority, tag, msg + "\n" + Log.getStackTraceString(e))
        else Log.println(priority, tag, msg)
    }
}

/** 当前时间（测试可替换）。时间统一为本地时间 ISO 字符串 2026-10-12T10:57:44 */
object Clock {
    @Volatile
    var provider: () -> LocalDateTime = { LocalDateTime.now() }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    fun now(): LocalDateTime = provider()

    fun isoOf(t: LocalDateTime): String = t.truncatedTo(ChronoUnit.SECONDS).format(ISO)

    fun nowIso(): String = isoOf(now())

    fun today(): String = now().toLocalDate().toString()

    fun parse(iso: String): LocalDateTime = LocalDateTime.parse(iso)

    fun shift(day: String, days: Long): String = LocalDate.parse(day).plusDays(days).toString()
}

/** 共享规范 spec/ 里的文件：运行时从 assets 读取，单元测试（未初始化平台时）从 classpath 读取 */
object Spec {
    private val cache = ConcurrentHashMap<String, String>()

    fun text(path: String): String = cache.getOrPut(path) {
        open("spec/$path").use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun open(path: String): InputStream {
        Umless.platformOrNull?.let { return it.openAsset(path) }
        return Spec::class.java.classLoader?.getResourceAsStream(path)
            ?: throw IllegalStateException("missing asset: $path")
    }
}
