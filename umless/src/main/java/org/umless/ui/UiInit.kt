package org.umless.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.umless.coach.Coach
import org.umless.core.Umless
import org.umless.update.AndroidUpdatePlatform
import org.umless.update.Updater
import org.umless.voice.ModelManager

/**
 * 主 App 打开时的后台初始化（进程内只做一次；失败后下次打开重试）：
 * Umless.init（配置、数据库、与平台无关的接口）→ 注册平台接口与更新接口。JS 桥的每次调用都先等它完成。
 */
internal object UiInit {
    private const val TAG = "UiInit"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var init: Deferred<Unit>? = null

    @Synchronized
    fun start(context: Context): Deferred<Unit> {
        init?.let { if (!it.isCancelled) return it }
        val app = context.applicationContext ?: context
        return scope.async<Unit> {
            try {
                Umless.init(app)
                ModelManager.registerHooks() // 幂等；Application 里通常已经调过
                // 必须在 Umless.init 之后：同名接口以平台实现为准
                PlatformApi.register { UmlessActivity.current }
                Updater.register(AndroidUpdatePlatform(app))
                // 上次更新成功后留下的安装包（已安装版本不低于它）在这里删除
                scope.launch { runCatching { Updater.cleanupAfterUpdate() }.onFailure { UiLog.w(TAG, "清理更新安装包失败", it) } }
            } catch (e: Throwable) {
                UiLog.e(TAG, "初始化失败", e)
                throw e
            }
        }.also { init = it }
    }

    /** 开启了自动检查更新且距上次检查超过 24 小时时，后台请求一次更新清单（结果缓存，网页自己显示提示条） */
    fun runAutoUpdateCheck(init: Deferred<Unit>) {
        scope.launch {
            try {
                init.await()
                Updater.autoCheckIfDue()
            } catch (e: Throwable) {
                UiLog.w(TAG, "自动检查更新失败", e)
            }
        }
    }

    /** 到期时自动生成昨天的表达报告（是否到期、是否开启由 Coach 判断） */
    fun runAutoDiagnosis(init: Deferred<Unit>) {
        scope.launch {
            try {
                init.await()
                Coach.runAutoDiagnosisIfDue()
            } catch (e: Throwable) {
                UiLog.w(TAG, "自动诊断失败", e)
            }
        }
    }
}
