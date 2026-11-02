package org.umless.glue

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.ime.EditorFacts
import org.umless.ime.VoiceController
import org.umless.voice.ModelManager
import org.umless.voice.VoiceClient
import timber.log.Timber
import java.io.File

/** app 与 Umless 模块之间的入口：进程判断、初始化、共享的识别客户端、打开主 App */
object UmlessGlue {

    /** 打开 Umless 主 App 时附带的页面（主 App 可据此跳到对应页面） */
    const val EXTRA_PAGE = "org.umless.extra.PAGE"
    const val PAGE_MODELS = "models"
    const val PAGE_PERMISSION = "permission"

    /** 当前进程名（API 28+ 用 Application.getProcessName，更早的读 /proc/self/cmdline） */
    fun processName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        runCatching {
            File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private val voiceProcess: Boolean by lazy { processName()?.endsWith(":voice") == true }

    /** :voice 进程只运行 VoiceService，不需要上游的任何初始化 */
    fun isVoiceProcess(): Boolean = voiceProcess

    /** 主进程 Application.onCreate 调用；失败只记日志，不影响键盘本身 */
    fun init(app: Application) {
        try {
            Umless.init(app)
            ModelManager.registerHooks()
            UmlessThemes.logStartupState()
        } catch (t: Throwable) {
            Timber.e(t, "Umless init failed")
        }
    }

    /**
     * Umless 可用（已初始化；直接启动模式下设备未解锁时不可用）。
     * 只看 Umless 本身：Application 里初始化失败、之后打开主 App 重试成功时，键盘也能随之可用
     */
    val ready: Boolean get() = Umless.isInitialized

    private var _client: VoiceClient? = null

    /** 进程内共享的识别客户端（键盘视图重建时不必重新绑定 :voice 进程） */
    fun client(context: Context): VoiceClient =
        _client ?: synchronized(this) {
            _client ?: VoiceClient(context.applicationContext).also { _client = it }
        }

    /** 最近一次语音输入的结果（语音面板显示），键盘视图重建后仍保留 */
    @Volatile
    var lastResult: VoiceController.Result? = null

    private fun <T> safe(default: T, block: () -> T): T = if (!ready) default else try {
        block()
    } catch (t: Throwable) {
        default
    }

    /** voice.space_long_press */
    val spaceLongPressEnabled: Boolean get() = safe(false) { UmlessConfig.spaceLongPress }

    /** 工具栏麦克风按钮：voice.toolbar_button 开启且不是密码框 */
    fun toolbarButtonVisible(facts: EditorFacts?, password: Boolean): Boolean =
        safe(false) { UmlessConfig.toolbarButton } && !password && facts?.isPassword != true

    /** 打开 Umless 主 App（模型下载、麦克风授权） */
    fun openUmless(context: Context, page: String?) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (page != null) intent.putExtra(EXTRA_PAGE, page)
            context.startActivity(intent)
        } catch (t: Throwable) {
            Timber.w(t, "open Umless failed")
        }
    }
}
