package org.umless.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** 耗时任务，语义同桌面端 jobs.py：start 返回 {"job_id"}，前端轮询 job_status；key 相同且在运行中的任务不重复启动 */
object Jobs {
    private class State(
        var state: String = "running",
        var progress: Float? = null,
        var message: String,
        var result: JsonElement = JsonNull,
        var error: String? = null,
        val key: String?,
    )

    private val jobs = LinkedHashMap<String, State>()
    private val lock = Any()

    /** 任务运行的作用域（测试可替换为可控的调度器） */
    @Volatile
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * block(progress) 的返回值作为任务结果；progress(比例或 null, 说明)。
     * key 相同的任务还在运行时不再重复启动，直接返回它的 job_id：切换页面再回来重复点击诊断、下载时，
     * 不会再调一次 LLM 或让两个任务写同一个文件。
     */
    fun start(
        message: String? = null,
        key: String? = null,
        block: suspend (progress: (Float?, String?) -> Unit) -> JsonElement,
    ): JsonObject {
        val jobId = UUID.randomUUID().toString().replace("-", "").take(12)
        val state = State(message = message ?: I18n.t("job.processing"), key = key)
        synchronized(lock) {
            if (key != null) {
                for ((jid, s) in jobs) {
                    if (s.key == key && s.state == "running") return buildJsonObject { put("job_id", jid) }
                }
            }
            jobs[jobId] = state
        }
        val progress: (Float?, String?) -> Unit = { p, msg ->
            synchronized(lock) {
                state.progress = p
                if (!msg.isNullOrEmpty()) state.message = msg
            }
        }
        scope.launch {
            try {
                val result = block(progress)
                synchronized(lock) {
                    state.state = "done"
                    state.progress = 1.0f
                    state.result = result
                }
            } catch (e: Throwable) {
                UmlessLog.e("Jobs", "任务失败", e)
                synchronized(lock) {
                    state.state = "error"
                    state.error = e.message?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName
                }
            }
        }
        return buildJsonObject { put("job_id", jobId) }
    }

    fun status(jobId: String): JsonObject = synchronized(lock) {
        val s = jobs[jobId] ?: return buildJsonObject {
            put("state", "error"); put("progress", JsonNull); put("message", "")
            put("result", JsonNull); put("error", I18n.t("job.not_found"))
        }
        val snapshot = buildJsonObject {
            put("state", s.state)
            put("progress", s.progress?.let { JsonPrimitive(it) } ?: JsonNull)
            put("message", s.message)
            put("result", s.result)
            put("error", s.error)
        }
        if (s.state != "running") jobs.remove(jobId) // 结果只取一次
        snapshot
    }
}
