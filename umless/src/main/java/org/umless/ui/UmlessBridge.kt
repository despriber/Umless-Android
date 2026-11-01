package org.umless.ui

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umless.api.ApiException
import org.umless.api.UmlessApi

/**
 * 通过 addJavascriptInterface 以 `UmlessBridge` 暴露给网页（js/android-shim.js 调用）。
 *
 * - [call] 在 WebView 的 JavaBridge 线程上被调用，立即返回；接口在 [scope]（随 Activity 销毁取消）的 IO 线程执行
 * - 结果通过 [deliver] 在主线程 evaluateJavascript：window.__umlessResolve(id, ok, resultJson)
 * - [isTrusted] 检查当前页面是否来自我们的资源域名，其他来源的调用直接丢弃
 */
class UmlessBridge(
    private val scope: CoroutineScope,
    private val ready: suspend () -> Unit,
    private val isTrusted: () -> Boolean,
    private val deliver: (script: String) -> Unit,
    private val onResult: (method: String) -> Unit = {},
) {
    /** [callId] 为垫片里的递增整数（docs/ui-api.md 7.2） */
    @JavascriptInterface
    fun call(callId: Int, method: String?, argsJson: String?) {
        val id = callId.toString()
        if (!isTrusted()) {
            UiLog.w(TAG, "拒绝来自非本地页面的调用：$method")
            return
        }
        val name = method.orEmpty()
        scope.launch(Dispatchers.IO) {
            val script = try {
                ready()
                val result = UmlessApi.call(name, BridgeCodec.parseArgs(argsJson))
                BridgeCodec.okScript(id, result)
            } catch (e: CancellationException) {
                if (!isActive) throw e // Activity 已销毁，页面也不在了
                BridgeCodec.errorScript(id, errorMessage(e)) // 接口内部的超时等
            } catch (e: ApiException) {
                BridgeCodec.errorScript(id, e.message ?: UiText.get("failed"))
            } catch (e: Throwable) {
                UiLog.e(TAG, "接口 $name 出错", e)
                BridgeCodec.errorScript(id, errorMessage(e))
            }
            withContext(Dispatchers.Main) {
                deliver(script)
                onResult(name)
            }
        }
    }

    companion object {
        private const val TAG = "UmlessBridge"

        /** 非 ApiException：通用的「操作失败」，附上简短原因便于反馈 */
        internal fun errorMessage(e: Throwable): String {
            val generic = UiText.get("failed")
            val detail = e.message?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: return generic
            return if (UiText.lang() == "zh") "$generic：$detail" else "$generic: $detail"
        }
    }
}
