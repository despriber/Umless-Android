package org.umless.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 主 App 网页界面调用的后端接口，方法名与参数和桌面端完全一致（见 Umless 主仓库 docs/ui-api.md）。
 *
 * 各部分在初始化时注册自己的方法：
 * - org.umless.coach / data：概览、报告、练习评测、历史、设置读写、口癖词表、导出等（与平台无关）
 * - org.umless.ui：输入法状态、麦克风权限、导出日志、文件选择等（需要 Activity / Context）
 * - org.umless.voice：模型下载、麦克风测试、练习录音
 *
 * 失败时抛出 [ApiException]（message 已按界面语言翻译），网页端收到的是 reject(message)。
 */
typealias ApiHandler = suspend (args: JsonArray) -> JsonElement

class ApiException(message: String) : RuntimeException(message)

object UmlessApi {
    private val handlers = mutableMapOf<String, ApiHandler>()

    @Synchronized
    fun register(name: String, handler: ApiHandler) {
        handlers[name] = handler
    }

    @Synchronized
    fun has(name: String) = name in handlers

    @Synchronized
    fun names(): Set<String> = handlers.keys.toSet()

    suspend fun call(name: String, args: JsonArray): JsonElement {
        val handler = synchronized(this) { handlers[name] } ?: throw ApiException("unknown method: $name")
        return handler(args)
    }
}
