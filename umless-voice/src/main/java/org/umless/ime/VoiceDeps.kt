package org.umless.ime

import org.umless.core.UtteranceRecord
import org.umless.voice.Transcript

/*
 * VoiceController 的依赖，全部可替换：生产实现见 AndroidVoice.kt，单元测试注入假的。
 */

/** 录音（生产实现包装 org.umless.voice.AudioRecorder）。start / stop 在后台线程调用，level 在主线程调用 */
interface VoiceRecorder {
    val sampleRate: Int

    /** 打开麦克风；没有权限抛 SecurityException，其他失败抛别的异常 */
    fun start()

    /** 停止并返回录到的音频；未在录音时返回空数组 */
    fun stop(): FloatArray

    /** 最近的音量 0–1 */
    fun level(): Float
}

fun interface Transcriber {
    /** 失败抛出异常（message 已翻译） */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int): Transcript
}

fun interface Polisher {
    /** 返回润色稿；失败抛出异常 */
    suspend fun polish(raw: String, scene: String, intensity: String): String?
}

fun interface RecordSink {
    /** 落库（在后台线程调用，可以阻塞）。mode 为 [SaveMode.Nothing] 时不会被调用 */
    fun save(record: UtteranceRecord, mode: SaveMode, samples: FloatArray, sampleRate: Int)
}

/** 一次语音输入留下什么，规则同桌面端 app._handle */
enum class SaveMode {
    /** 写历史（含逐条指标）和最近一次输出 */
    History,

    /** 只写最近一次输出：在 Umless 自己的界面里说的（引导页「试一试」读取） */
    LastOutputOnly,

    /** 什么都不留：暂停记录、黑名单应用、无痕输入框 */
    Nothing,
}

fun interface Clock {
    /** 单调时钟，毫秒 */
    fun nowMs(): Long
}

/** 语音流程读取的配置与平台状态。每次使用时读取，配置修改立即生效 */
interface VoiceEnv {
    /** 按住时间短于此值的录音直接丢弃（voice.min_hold_ms） */
    val minHoldMs: Long

    /** 润色超时（llm.timeout_s） */
    val polishTimeoutMs: Long

    val llmEnabled: Boolean

    /** 写入记录的模型名（Polish.model） */
    val llmModel: String?

    val haptic: Boolean
    val recordHistory: Boolean
    val skipIncognito: Boolean

    /** 本应用包名：在 Umless 自己的界面里（引导页「试一试」）照常输出，但不写历史 */
    val ownPackage: String?

    fun sceneOf(packageName: String?): String
    fun intensityOf(scene: String): String
    fun isExcluded(packageName: String?): Boolean

    /** 当前识别引擎可用（本地模型已下载，或已配置云端接口） */
    fun modelReady(): Boolean

    fun hasMicPermission(): Boolean

    /** 界面文字（I18n.t） */
    fun t(key: String): String

    fun log(message: String, error: Throwable? = null)
}
