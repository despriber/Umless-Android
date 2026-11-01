package org.umless.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umless.ui.UiLog
import org.umless.ui.UmlessActivity
import java.io.File
import java.security.MessageDigest

/**
 * [UpdatePlatform] 的 Android 实现：版本与签名来自 PackageManager，安装走 PackageInstaller 会话
 * （结果由 [UpdateInstallReceiver] 处理：需要用户确认时打开系统的安装确认界面）。
 */
class AndroidUpdatePlatform(context: Context) : UpdatePlatform {
    private val context: Context = context.applicationContext ?: context

    override val packageName: String get() = context.packageName

    override val updatesDir: File get() = File(context.cacheDir, "updates")

    private val pm: PackageManager get() = context.packageManager

    override fun installedVersionCode(): Long = PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(packageName, 0))

    override fun installedVersionName(): String = pm.getPackageInfo(packageName, 0).versionName ?: ""

    override fun supportedAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    override fun inspectArchive(apk: File): ArchiveInfo? {
        val info = archiveInfo(apk, SIGNING_FLAGS) ?: return null
        var certs = certificatesOf(info)
        // 部分系统版本读安装包时 GET_SIGNING_CERTIFICATES 拿不到 signingInfo，退回 GET_SIGNATURES 再读一次
        if (certs.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            certs = certificatesOf(archiveInfo(apk, PackageManager.GET_SIGNATURES))
        }
        return ArchiveInfo(info.packageName, PackageInfoCompat.getLongVersionCode(info), certs)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File, flags: Int): PackageInfo? = try {
        pm.getPackageArchiveInfo(apk.path, flags)
    } catch (e: Exception) {
        UiLog.w(TAG, "无法读取安装包 ${apk.name}", e)
        null
    }

    @Suppress("DEPRECATION")
    override fun installedCertificates(): Set<String> = try {
        certificatesOf(pm.getPackageInfo(packageName, SIGNING_FLAGS))
    } catch (e: PackageManager.NameNotFoundException) {
        emptySet()
    }

    override fun canRequestInstall(): Boolean =
        // Android 8 以下没有按应用的「安装未知应用」开关，由系统安装界面自己提示
        Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()

    override suspend fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        withContext(Dispatchers.Main) {
            val withPackage = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            // 个别系统没有按应用的设置页时退回总列表
            if (!start(withPackage)) start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
    }

    /** 优先用当前主 App 窗口启动（同一任务里，返回时回到 Umless），没有时用 Application + NEW_TASK */
    private fun start(intent: Intent): Boolean = try {
        val act = UmlessActivity.current?.takeUnless { it.isFinishing }
        if (act != null) act.startActivity(intent)
        else context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        UiLog.w(TAG, "无法打开 ${intent.action}", e)
        false
    } catch (e: SecurityException) {
        UiLog.w(TAG, "无法打开 ${intent.action}", e)
        false
    }

    override fun install(apk: File) {
        val installer = pm.packageInstaller
        // 上次没完成的会话（用户关掉了确认界面等）先放弃，免得占着暂存空间
        for (s in installer.mySessions) {
            try {
                installer.abandonSession(s.sessionId)
            } catch (e: Exception) {
                UiLog.w(TAG, "放弃旧的安装会话失败", e)
            }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
            // 只是「请求」免确认：系统只在本应用是安装来源且有 UPDATE_PACKAGES_WITHOUT_USER_ACTION 时才生效，
            // 否则照常回调 STATUS_PENDING_USER_ACTION，由用户在确认界面点「更新」
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            // 系统往 Intent 里填结果（EXTRA_STATUS 等），Android 12+ 必须 FLAG_MUTABLE；显式组件，满足 Android 14 对可变 PendingIntent 的限制
            val intent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION)
                .setPackage(packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
            UiLog.i(TAG, "安装会话已提交：$sessionId（${apk.length()} 字节）")
        } catch (e: Exception) {
            try {
                session.abandon()
            } catch (_: Exception) {
            }
            throw e
        } finally {
            session.close()
        }
    }

    companion object {
        private const val TAG = "Update"

        @Suppress("DEPRECATION")
        private val SIGNING_FLAGS: Int =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        /** 当前签名证书（不含密钥轮换前的旧证书）的 SHA-256 */
        internal fun certificatesOf(info: PackageInfo?): Set<String> {
            if (info == null) return emptySet()
            val signingInfo = if (Build.VERSION.SDK_INT >= 28) info.signingInfo else null
            val signatures: Array<Signature>? = if (signingInfo != null) {
                signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
