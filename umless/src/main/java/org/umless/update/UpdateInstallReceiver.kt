package org.umless.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat
import org.umless.core.I18n
import org.umless.ui.UiLog
import org.umless.ui.UmlessActivity

/**
 * PackageInstaller 会话的结果（AndroidUpdatePlatform.install 提交时的 PendingIntent，只接收本应用的显式广播）：
 * - STATUS_PENDING_USER_ACTION：打开系统的安装确认界面（EXTRA_INTENT）
 * - STATUS_SUCCESS：覆盖安装成功（通常收不到——安装时本进程会被结束）
 * - 其他：记日志并提示失败原因；用户在确认界面点了取消（STATUS_FAILURE_ABORTED）时不提示
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    UiLog.w(TAG, "安装需要确认，但系统没有给出确认界面")
                    return
                }
                openConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> UiLog.i(TAG, "更新安装成功")
            PackageInstaller.STATUS_FAILURE_ABORTED -> UiLog.i(TAG, "用户取消了安装（$message）")
            else -> {
                UiLog.w(TAG, "更新安装失败：status=$status，$message")
                val reason = message?.takeIf { it.isNotBlank() } ?: "status $status"
                Toast.makeText(context, I18n.t("update.install_failed", "error" to reason), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 优先从主 App 窗口打开（在前台，不受后台启动 Activity 的限制），否则用 NEW_TASK */
    private fun openConfirm(context: Context, confirm: Intent) {
        try {
            val act = UmlessActivity.current?.takeUnless { it.isFinishing || it.isDestroyed }
            if (act != null) act.startActivity(confirm)
            else context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            UiLog.e(TAG, "无法打开安装确认界面", e)
        }
    }

    companion object {
        const val ACTION = "org.umless.update.INSTALL_STATUS"
        private const val TAG = "Update"
    }
}
