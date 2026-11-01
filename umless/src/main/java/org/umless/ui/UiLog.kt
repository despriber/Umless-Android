package org.umless.ui

import android.util.Log
import org.umless.core.UmlessLog

/** 写诊断日志；Umless 尚未初始化（或日志本身出错）时退回 logcat，日志永远不影响主流程 */
internal object UiLog {
    fun i(tag: String, msg: String) {
        try {
            UmlessLog.i(tag, msg)
        } catch (_: Throwable) {
            Log.i(tag, msg)
        }
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        try {
            UmlessLog.w(tag, msg, e)
        } catch (_: Throwable) {
            Log.w(tag, msg, e)
        }
    }

    fun e(tag: String, msg: String, e: Throwable? = null) {
        try {
            UmlessLog.e(tag, msg, e)
        } catch (_: Throwable) {
            Log.e(tag, msg, e)
        }
    }
}
