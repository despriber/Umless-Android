package org.umless.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** 与用户文件打交道：保存到「下载」目录、把用户选的文件复制进应用私有目录 */
internal object UserFiles {

    /**
     * 把 [file] 复制到「下载」目录，返回显示给用户的路径（docs/ui-api.md 7.3 / 7.4）。
     * Android 10+ 走 MediaStore（不需要存储权限）；更早的系统没有权限写公共目录，存到应用专属外部目录 Download/
     */
    fun saveToDownloads(context: Context, file: File, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("MediaStore open failed")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            // 重名时系统会改名（如 xxx (1).zip），以实际文件名为准
            val name = displayName(context, uri) ?: file.name
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory().absolutePath
            return "$root/${Environment.DIRECTORY_DOWNLOADS}/$name"
        }
        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Download"))
            .apply { mkdirs() }
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** 文件名只保留安全字符，并保证以 .wav 结尾 */
    fun safeWavName(name: String?): String {
        val raw = (name ?: "").substringAfterLast('/')
        val stem = if (raw.contains('.')) raw.substringBeforeLast('.') else raw
        val base = stem
            .replace(Regex("[^\\p{L}\\p{N}_. -]"), "_").trim().trim('.').take(60)
        return (base.ifEmpty { "sound" }) + ".wav"
    }

    /** 把用户选的音频复制到 home/sounds/，返回绝对路径（同名覆盖） */
    fun importSound(context: Context, uri: Uri, home: File): String {
        val dir = File(home, "sounds").apply { mkdirs() }
        val dest = File(dir, safeWavName(displayName(context, uri)))
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot open $uri")
        return dest.absolutePath
    }
}
