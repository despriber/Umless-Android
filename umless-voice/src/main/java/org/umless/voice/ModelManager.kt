package org.umless.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.umless.core.CoreHooks
import org.umless.core.DownloadSources
import org.umless.core.I18n
import org.umless.core.Umless
import org.umless.core.UmlessLog
import org.umless.ime.VoiceTexts
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 本地识别模型：下载（下载源见 DownloadSources：直连 GitHub / 镜像，sha256 校验，tar.bz2 解压）、是否已下载、路径。
 * 模型规格与桌面端 models.py 一致；手机端只提供 sensevoice。
 *
 * 下载到 models/.<archive>.part，校验后解压到临时目录，最后整体改名为正式目录（原子操作）：
 * 中途被杀或取消不会留下「看起来已下载」的半成品，下次下载前清理残留。
 */
object ModelManager {

    data class Spec(val dirname: String, val sha256: String, val sizeMb: Int) {
        val archive: String get() = "$dirname.tar.bz2"
    }

    private const val RELEASE = DownloadSources.MODEL_RELEASE

    val MODELS: Map<String, Spec> = mapOf(
        "sensevoice" to Spec(
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63",
            166,
        ),
    )

    /** 识别时必须存在的文件 */
    private val REQUIRED = mapOf("sensevoice" to listOf("model.int8.onnx", "tokens.txt"))

    private val mutex = Mutex()

    private fun spec(name: String): Spec =
        MODELS[name] ?: throw IllegalArgumentException("unknown model: $name")

    /** 模型目录（不保证存在） */
    fun modelDir(name: String): File = File(Umless.modelsDir, spec(name).dirname)

    fun isDownloaded(name: String): Boolean {
        val dir = try {
            modelDir(name)
        } catch (e: Throwable) {
            return false
        }
        return dir.isDirectory && REQUIRED[name].orEmpty().all { File(dir, it).isFile }
    }

    fun sizeMb(name: String): Int = spec(name).sizeMb

    /** 官方下载地址（GitHub）；实际按 DownloadSources 的配置决定直连还是走镜像 */
    fun url(name: String): String = "$RELEASE/${spec(name).archive}"

    /** 设置页显示的模型信息（CoreHooks.modelsInfo）。在主进程 Umless.init 之后调用一次 */
    fun registerHooks() {
        CoreHooks.modelsInfo = {
            buildJsonObject {
                putJsonObject("sensevoice") {
                    put("downloaded", isDownloaded("sensevoice"))
                    put("size_mb", sizeMb("sensevoice"))
                    put("name", "SenseVoice-Small")
                }
            }
        }
    }

    /** 下载并解压；progress(比例或 null, 说明)。失败抛出异常（message 已翻译）；取消协程即取消下载 */
    suspend fun download(name: String, progress: (Float?, String?) -> Unit) {
        val spec = spec(name)
        mutex.withLock {
            withContext(Dispatchers.IO) { downloadLocked(name, spec, progress) }
        }
    }

    private suspend fun downloadLocked(name: String, spec: Spec, progress: (Float?, String?) -> Unit) {
        val modelsDir = Umless.modelsDir
        val target = File(modelsDir, spec.dirname)
        if (isDownloaded(name)) {
            progress(1f, tr("models.exists", "path" to target.path))
            return
        }
        cleanup(modelsDir, spec)
        if (target.exists()) target.deleteRecursively() // 不完整的旧目录
        val need = spec.sizeMb * 1024L * 1024 * 5 / 2 // 压缩包 + 解压后约 2.5 倍
        if (modelsDir.usableSpace in 1 until need) {
            throw IOException(
                VoiceTexts.pick(
                    "存储空间不足：需要约 ${need shr 20} MB",
                    "Not enough storage: about ${need shr 20} MB needed",
                )
            )
        }

        val part = File(modelsDir, ".${spec.archive}.part")
        for ((source, url) in DownloadSources.candidates(url(name))) {
            progress(0f, tr("models.downloading_from", "url" to url, "size" to spec.sizeMb))
            val sha = try {
                fetch(url, part, progress)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                UmlessLogSafe.w("下载失败：$url", e)
                progress(null, tr("models.source_failed", "error" to (e.message ?: e.javaClass.simpleName)))
                part.delete()
                continue
            }
            if (sha != spec.sha256) {
                UmlessLogSafe.w("模型校验失败：$url sha256=$sha")
                progress(null, tr("models.checksum_failed"))
                part.delete()
                continue
            }
            DownloadSources.succeeded(source) // 文件完整：auto 模式下次先试这个源
            progress(0.96f, tr("models.extracting"))
            val tmp = File(modelsDir, ".extract-${spec.dirname}")
            try {
                tmp.deleteRecursively()
                extract(part, tmp, progress)
                val extracted = File(tmp, spec.dirname)
                if (!extracted.isDirectory || REQUIRED[name].orEmpty().any { !File(extracted, it).isFile }) {
                    throw IOException("archive layout unexpected")
                }
                if (!extracted.renameTo(target)) throw IOException("rename failed: $extracted → $target")
            } finally {
                tmp.deleteRecursively()
                part.delete()
            }
            progress(1f, tr("models.done", "path" to target.path))
            UmlessLogSafe.i("模型 $name 已下载：$target")
            return
        }
        throw IOException(tr("models.failed", "name" to name))
    }

    /** 清理上次中断留下的临时文件 */
    private fun cleanup(modelsDir: File, spec: Spec) {
        File(modelsDir, ".${spec.archive}.part").delete()
        File(modelsDir, spec.archive).delete()
        File(modelsDir, ".extract-${spec.dirname}").deleteRecursively()
    }

    /** 流式下载到 [dest]，边下边算 sha256；取消时断开连接 */
    private suspend fun fetch(url: String, dest: File, progress: (Float?, String?) -> Unit): String {
        val job = currentCoroutineContext()[Job]
        var current = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Umless-Android")
            }
            val code = conn.responseCode
            if (code in 300..399) { // 手动跟随跳转（GitHub → objects.githubusercontent.com，可能跨域名）
                val loc = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                conn.disconnect()
                if (++redirects > 5) throw IOException("too many redirects")
                current = URL(current, loc)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            break
        }
        // 取消时立即断开连接，打断阻塞中的读
        @OptIn(InternalCoroutinesApi::class)
        val handle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { conn.disconnect() }
        try {
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport >= (1 shl 20)) {
                            lastReport = done
                            progress(
                                done.toFloat() / total * 0.95f,
                                tr("models.progress", "done" to (done shr 20), "total" to (total shr 20)),
                            )
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("incomplete download: $done / $total")
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            handle?.dispose()
            conn.disconnect()
        }
    }

    /** 解压 tar.bz2 到 [destDir]；拒绝越出目标目录的条目，只解出普通文件与目录 */
    private suspend fun extract(archive: File, destDir: File, progress: (Float?, String?) -> Unit) {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        val size = archive.length().coerceAtLeast(1)
        val counting = CountingStream(archive.inputStream().buffered(1 shl 16))
        TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw IOException("bad archive entry: ${entry.name}")
                }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                if (counting.count - lastReport >= (4 shl 20)) {
                                    currentCoroutineContext().ensureActive()
                                    lastReport = counting.count
                                    progress(0.96f + 0.039f * counting.count / size, tr("models.extracting"))
                                }
                            }
                        }
                    }
                    else -> {} // 链接等其他类型不需要
                }
            }
        }
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private fun tr(key: String, vararg args: Pair<String, Any?>): String =
        runCatching { I18n.t(key, *args) }.getOrNull()?.takeIf { it.isNotBlank() && it != key } ?: key
}

/** 日志：Umless 未初始化或日志不可用时只写 logcat */
internal object UmlessLogSafe {
    private const val TAG = "UmlessVoice"

    fun i(msg: String) {
        try {
            UmlessLog.i(TAG, msg)
        } catch (_: Throwable) {
            android.util.Log.i(TAG, msg)
        }
    }

    fun w(msg: String, e: Throwable? = null) {
        try {
            UmlessLog.w(TAG, msg, e)
        } catch (_: Throwable) {
            android.util.Log.w(TAG, msg, e)
        }
    }
}
