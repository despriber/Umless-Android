package org.umless.core

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.umless.api.CoreApi
import org.umless.coach.Coach
import org.umless.data.Db
import java.io.File

/**
 * Umless 的全局入口：在 Application.onCreate（主进程）与主 App Activity 中调用 [init]。
 * :voice 进程不调用（它只做识别）。
 *
 * 数据目录 [home] = filesDir/umless：config.json、umless.db、models/、logs/。
 */
@SuppressLint("StaticFieldLeak") // 只保存 applicationContext
object Umless {
    lateinit var context: Context
        private set

    @Volatile
    private var platform: UmlessPlatform? = null

    @Volatile
    private var database: Db? = null

    /** 后台任务（补算指标、耗时接口）使用的作用域 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val platformOrNull: UmlessPlatform? get() = platform

    val env: UmlessPlatform get() = platform ?: throw IllegalStateException("Umless.init() has not been called")

    val home: File get() = env.home.apply { mkdirs() }
    val modelsDir: File get() = File(home, "models").apply { mkdirs() }

    /** 数据库也已打开才算初始化完成（setup 进行中 platform 已赋值、数据库还没打开） */
    val isInitialized: Boolean get() = platform != null && database != null

    /** 本地数据库（与桌面端同一 schema） */
    val db: Db get() = database ?: throw IllegalStateException("Umless.init() has not been called")

    /** 幂等；会初始化日志、配置、数据库，并在后台补算指标、刷新习惯卡 */
    fun init(context: Context) {
        synchronized(this) {
            if (isInitialized) return
            this.context = context.applicationContext ?: context
            setup(AndroidPlatform(this.context), warmup = true)
        }
    }

    /**
     * 用指定平台初始化（JVM 单元测试用）。与 [init] 不同，每次调用都会重新打开配置与数据库，
     * 便于测试之间互不影响；[warmup] 为 false 时不在后台补算指标。
     */
    fun init(platform: UmlessPlatform, warmup: Boolean = false) {
        synchronized(this) {
            database?.let { runCatching { it.close() } }
            database = null
            setup(platform, warmup)
        }
    }

    private fun setup(p: UmlessPlatform, warmup: Boolean) {
        platform = p
        try {
            p.home.mkdirs()
            UmlessLog.open(File(p.home, "logs"))
            UmlessConfig.load(File(p.home, "config.json"))
            database = Db(p.openDatabase(File(p.home, "umless.db")))
        } catch (t: Throwable) {
            // 打开数据库失败（磁盘满、库损坏等）：回到未初始化状态，下次 init（如打开主 App）会重试；
            // 否则 platform 已赋值，之后的 init 直接返回，Umless 在进程结束前一直不可用
            platform = null
            database = null
            throw t
        }
        CoreApi.registerAll()
        UmlessLog.i("Umless", "初始化完成：${p.appVersion()}，界面语言 ${language()}")
        if (warmup) recomputeLater()
    }

    /** 后台补算指标、刷新习惯卡（口癖词表变化后也调用） */
    fun recomputeLater() {
        scope.launch {
            try {
                Coach.backfillMetrics()
                Coach.refreshHabits()
            } catch (e: Exception) {
                UmlessLog.e("Umless", "补算指标失败", e)
            }
        }
    }

    /** 当前界面语言：zh / en（配置 app.language 为 auto 时跟随系统） */
    fun language(): String {
        val setting = if (UmlessConfig.isLoaded) UmlessConfig.str("app.language") else null
        return resolveLanguage(setting)
    }

    /** 配置值（auto / zh / en）→ 实际界面语言；未知值按 auto 处理 */
    fun resolveLanguage(setting: String?): String {
        if (setting == "zh" || setting == "en") return setting
        return platform?.let { runCatching { it.systemLanguage() }.getOrNull() }
            ?: if (java.util.Locale.getDefault().language.startsWith("zh")) "zh" else "en"
    }
}
