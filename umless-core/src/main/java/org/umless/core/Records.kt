package org.umless.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.coach.Coach

/** 一次语音输入的结果 */
data class UtteranceRecord(
    val durationS: Double,
    val app: String?,          // 包名
    val asrEngine: String,
    val rawText: String,
    val asrMs: Long,
    val outputText: String,
    val llmModel: String? = null,
    val polishedText: String? = null,
    val llmMs: Long? = null,
    val error: String? = null,
    val scene: String? = null,
    val intensity: String? = null,
)

/** 语音输入落库：写 utterances、计算并保存逐条指标、更新 last_output（引导页「试一试」读取） */
object Records {
    private const val TAG = "Records"

    /**
     * 返回记录 id；已按隐私设置决定是否写历史（调用方传 record=false 时什么都不写）。
     *
     * 与桌面端 app._handle 一致：record=true 时写 utterances、metrics 与 last_output（这里再核对一次
     * privacy.record_history 与黑名单，不满足时同样什么都不写，返回 null）；record=false（暂停记录、黑名单、
     * 无痕输入框等）什么都不写，last_output 也不更新。在 Umless 自己的界面里试说用 [saveLastOutputOnly]。原文为空时不记录。
     */
    fun save(u: UtteranceRecord, record: Boolean): Long? {
        if (u.rawText.isBlank()) return null
        if (!record || !UmlessConfig.recordHistory || UmlessConfig.isExcluded(u.app)) {
            UmlessLog.i(TAG, "%.1fs [%s] 未记录（暂停记录、黑名单或无痕输入框）".format(java.util.Locale.ROOT, u.durationS, u.app))
            return null
        }
        saveLastOutput(u.rawText, u.polishedText, u.outputText)
        val db = Umless.db
        val uid = db.addUtterance(u)
        try {
            Coach.computeMetrics(uid, u.rawText, u.polishedText, u.durationS)
        } catch (e: Exception) {
            UmlessLog.e(TAG, "计算指标失败", e)
        }
        // 日志默认只记字数与耗时；原文 / 输出属于个人语音内容，仅在调试模式写入
        UmlessLog.i(
            TAG,
            "#$uid %.1fs [%s/%s/%s] ASR %dms, LLM %s, 原文 %d 字 → 输出 %d 字".format(
                java.util.Locale.ROOT, u.durationS, u.app, u.scene, u.intensity ?: "-", u.asrMs,
                u.llmMs?.let { "${it}ms" } ?: "未润色",
                Py.len(u.rawText), Py.len(u.outputText),
            ),
        )
        UmlessLog.d(TAG, "#$uid\n  原文：${u.rawText}\n  输出：${u.outputText}")
        return uid
    }

    /**
     * 在 Umless 自己的界面里说的话（新手引导「试一试」）：只更新 last_output，不写历史、不算场景
     * （桌面端 own=True 的情况）。text 为实际输出（润色稿或原文）
     */
    fun saveLastOutputOnly(raw: String, polished: String?, text: String) {
        if (raw.isBlank()) return
        saveLastOutput(raw, polished, text)
        UmlessLog.i(TAG, "Umless 界面内的输入（原文 ${Py.len(raw)} 字），不记录")
    }

    /** 只存原文与润色稿；原文标注（口癖高亮）由 get_last_output 读取时按当前词表计算 */
    private fun saveLastOutput(raw: String, polished: String?, text: String) {
        try {
            Umless.db.setKv("last_output", buildJsonObject {
                put("text", text)
                put("raw", raw)
                put("polished", polished)
                put("at", Clock.nowIso())
            })
        } catch (e: Exception) {
            UmlessLog.e(TAG, "写入最近一次输出失败", e)
        }
    }
}
