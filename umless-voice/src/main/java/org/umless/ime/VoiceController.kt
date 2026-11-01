package org.umless.ime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.umless.core.UtteranceRecord
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * 语音输入流程：按住 → 录音 → 识别 → 润色 → 写入 → 落库，以及撤销 / 原话、失败兜底、密码框 / 无痕 / 黑名单。
 *
 * 状态：Idle → Listening → Transcribing → Polishing → Committed（短暂显示「原话 / 撤销」）/ Message。
 * 公开方法都在主线程调用（[scope] 应是主线程作用域）；录音启停、润色、落库在 [work] 上执行，不阻塞主线程。
 * 每次按下是一个 session：取消或换输入框后 session 递增，旧 session 的异步结果一律丢弃。
 */
class VoiceController(
    private val host: VoiceHost,
    private val recorder: VoiceRecorder,
    private val transcriber: Transcriber,
    private val polisher: Polisher,
    private val records: RecordSink,
    private val env: VoiceEnv,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val work: CoroutineContext = Dispatchers.IO,
    private val editor: () -> EditorFacts? = { EditorFacts.from(host.editorInfo) },
) {
    enum class Source { Space, Panel }

    /** 一次语音输入的结果（语音面板显示原话与润色稿） */
    data class Result(val raw: String, val polished: String?, val output: String, val error: String?)

    sealed interface State {
        data object Idle : State
        data class Listening(val source: Source, val cancelArmed: Boolean) : State
        data object Transcribing : State
        data object Polishing : State
        data class Committed(val result: Result, val actions: Set<CommitAction>) : State
        data class Message(val text: String, val isError: Boolean) : State
    }

    var state: State = State.Idle
        private set

    var lastResult: Result? = null
        private set

    val isBusy: Boolean
        get() = state.let { it is State.Listening || it is State.Transcribing || it is State.Polishing }

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    /** 润色在独立作用域里跑：超时后直接取消，迟到的结果不会再被使用 */
    private val polishScope = CoroutineScope(SupervisorJob() + work)

    /** 录音启停与落库：不随取消 / 销毁而中断（保证麦克风一定被关闭、已写入的记录一定落库） */
    private val bgScope = CoroutineScope(SupervisorJob() + work)

    private var session = 0
    private var pressedAt = 0L
    private var facts: EditorFacts? = null
    private var startJob: Deferred<Throwable?>? = null
    private var tickJob: Job? = null
    private var processJob: Job? = null
    private var actionsJob: Job? = null

    // ---------------------------------------------------------------- 按住 / 滑动 / 松开

    /** 按下（长按空格触发或按下面板大按钮）。返回是否开始录音 */
    fun press(source: Source = Source.Space): Boolean {
        if (isBusy) return false
        dismissCommitted()
        val f = editor() ?: return false
        if (f.isPassword) {
            message("voice.password_field", isError = true)
            return false
        }
        if (!env.modelReady()) {
            message("voice.model_missing", isError = true, openUmless = true)
            return false
        }
        if (!env.hasMicPermission()) {
            message("voice.mic_permission", isError = true, openUmless = true)
            return false
        }
        val id = ++session
        facts = f
        pressedAt = clock.nowMs()
        setState(State.Listening(source, cancelArmed = false))

        // 打开麦克风可能要 100ms 以上（冷启动更久），放到后台；界面先进入「正在聆听」，
        // 麦克风真正开始录音后再震动：感到震动再开口，开头不会丢字（同桌面端的开始提示音）
        val sj = bgScope.async {
            try {
                recorder.start()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }
        }
        startJob = sj
        scope.launch {
            val err = sj.await()
            if (err == null) {
                if (id == session && state is State.Listening && env.haptic) host.hapticFeedback()
                return@launch
            }
            if (id == session && state is State.Listening) {
                env.log("打开麦克风失败", err)
                tickJob?.cancel()
                startJob = null
                micError(err)
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                val st = state as? State.Listening ?: break
                val elapsed = clock.nowMs() - pressedAt
                host.showStatus(VoiceStatus.Listening(recorder.level().coerceIn(0f, 1f), elapsed, st.cancelArmed))
                if (elapsed >= MAX_LISTEN_MS) {
                    release()
                    break
                }
                delay(LEVEL_INTERVAL_MS)
            }
        }
        return true
    }

    /** 手指相对按下位置的纵向偏移（向上为负）。上滑超过 [threshold] 进入「松开取消」，滑回来解除 */
    fun drag(offsetY: Float, threshold: Float) {
        val st = state as? State.Listening ?: return
        val armed = offsetY < -threshold
        if (armed == st.cancelArmed) return
        setState(st.copy(cancelArmed = armed))
        if (armed && env.haptic) host.hapticFeedback()
        host.showStatus(VoiceStatus.Listening(recorder.level().coerceIn(0f, 1f), clock.nowMs() - pressedAt, armed))
    }

    /** 松开：上滑状态下取消；按住太短直接丢弃；否则识别 → 润色 → 写入 */
    fun release() {
        val st = state as? State.Listening ?: return
        if (st.cancelArmed) {
            cancel()
            return
        }
        tickJob?.cancel()
        val id = session
        val f = facts ?: return cancel()
        val held = clock.nowMs() - pressedAt
        val stopping = stopRecording()
        if (env.haptic) host.hapticFeedback()
        if (held < env.minHoldMs) {
            host.hideStatus()
            setState(State.Idle)
            return
        }
        setState(State.Transcribing)
        host.showStatus(VoiceStatus.Transcribing)
        processJob = scope.launch {
            val samples = stopping.await()
            if (id != session) return@launch
            process(id, f, samples)
        }
    }

    /** 取消一切进行中的工作（录音、识别、润色）与待撤销操作，隐藏状态条 */
    fun cancel() {
        val st = state
        session++
        tickJob?.cancel()
        processJob?.cancel()
        actionsJob?.cancel()
        tickJob = null
        processJob = null
        actionsJob = null
        if (startJob != null) stopRecording()
        if (st !is State.Idle) host.hideStatus()
        setState(State.Idle)
    }

    /** 只在由 [source] 发起、仍在录音时取消（面板关闭、空格的触摸被系统取消） */
    fun cancelIfListening(source: Source) {
        if ((state as? State.Listening)?.source == source) cancel()
    }

    /** 换了输入框 / 键盘隐藏：取消进行中的工作，撤销也不再可用 */
    fun onEditorChanged() = cancel()

    fun dispose() {
        cancel()
        listeners.clear()
        polishScope.cancel()
    }

    // ---------------------------------------------------------------- 流程

    private suspend fun process(id: Int, f: EditorFacts, samples: FloatArray?) {
        if (samples == null) { // 麦克风没打开
            micError(null)
            return
        }
        val sr = recorder.sampleRate
        if (samples.isEmpty()) {
            message("voice.no_speech", isError = false)
            return
        }
        val tr = try {
            transcriber.transcribe(samples, sr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            env.log("识别失败", e)
            if (id == session) showMessage(e.message?.takeIf { it.isNotBlank() } ?: env.t("voice.failed"), true)
            return
        }
        if (id != session) return
        val raw = tr.text.trim()
        if (raw.isEmpty()) {
            env.log("未识别到内容（${samples.size / sr.coerceAtLeast(1)}s）")
            message("voice.no_speech", isError = false)
            return
        }
        val choice = chooseScene(f, env::sceneOf, env::intensityOf)

        var polished: String? = null
        var error: String? = null
        var notice: String? = null
        var llmMs: Long? = null
        val llm = env.llmEnabled
        if (llm) {
            setState(State.Polishing)
            host.showStatus(VoiceStatus.Polishing)
            val t0 = clock.nowMs()
            val job = polishScope.async { polisher.polish(raw, choice.scene, choice.intensity) }
            try {
                polished = withTimeout(env.polishTimeoutMs) { job.await() }?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: TimeoutCancellationException) {
                job.cancel()
                error = "LLM 润色超时（${env.polishTimeoutMs / 1000}s）"
                notice = "voice.polish_timeout"
            } catch (e: CancellationException) {
                job.cancel()
                throw e
            } catch (e: Throwable) {
                error = "LLM 润色失败：${e.message}"
                notice = "voice.polish_failed"
            }
            llmMs = clock.nowMs() - t0
            if (error != null) env.log(error)
        }
        if (id != session) return

        val output = polished ?: raw
        val ok = host.commit(output)
        val result = Result(raw, polished, output, error)
        lastResult = result

        val record = UtteranceRecord(
            durationS = samples.size.toDouble() / sr,
            app = f.packageName,
            asrEngine = tr.engine,
            rawText = raw,
            asrMs = tr.asrMs,
            outputText = output,
            llmModel = if (llm) env.llmModel else null,
            polishedText = polished,
            llmMs = llmMs,
            error = error,
            scene = choice.scene,
            intensity = if (llm) choice.intensity else null,
        )
        val mode = saveMode(f, env)
        if (mode != SaveMode.Nothing) {
            bgScope.launch {
                try {
                    records.save(record, mode, samples, sr)
                } catch (e: Throwable) {
                    env.log("保存记录失败", e)
                }
            }
        }

        if (!ok) {
            message("voice.failed", isError = true)
            return
        }
        val actions = if (polished != null && polished != raw) setOf(CommitAction.UseRaw, CommitAction.Undo)
        else setOf(CommitAction.Undo)
        setState(State.Committed(result, actions))
        host.showStatus(VoiceStatus.Message(env.t(notice ?: "voice.inserted"), isError = notice != null))
        host.showCommitActions(actions) { onCommitAction(id, it) }
        actionsJob = scope.launch {
            delay(COMMIT_ACTIONS_MS)
            if (id == session && state is State.Committed) {
                host.hideStatus()
                setState(State.Idle)
            }
        }
    }

    private fun onCommitAction(id: Int, action: CommitAction) {
        val st = state as? State.Committed ?: return
        if (id != session || action !in st.actions) return
        actionsJob?.cancel()
        actionsJob = null
        val r = st.result
        val ok = when (action) {
            CommitAction.UseRaw -> host.replaceLastCommit(r.output, r.raw)
            CommitAction.Undo -> host.replaceLastCommit(r.output, "")
        }
        if (!ok) env.log("${action.name} 未执行：光标前的文字已经变化")
        host.hideStatus()
        setState(State.Idle)
    }

    // ---------------------------------------------------------------- 辅助

    /** 停止录音（等打开完成后再停，保证麦克风一定被关闭）。打开失败时结果为 null */
    private fun stopRecording(): Deferred<FloatArray?> {
        val sj = startJob
        startJob = null
        return bgScope.async {
            val err = try {
                sj?.await()
            } catch (e: CancellationException) {
                null
            }
            val samples = try {
                recorder.stop()
            } catch (e: Throwable) {
                env.log("停止录音失败", e)
                FloatArray(0)
            }
            if (sj == null || err != null) null else samples
        }
    }

    /** 新的一次按下前，收起上一次的「原话 / 撤销」 */
    private fun dismissCommitted() {
        if (state is State.Committed || state is State.Message) {
            actionsJob?.cancel()
            actionsJob = null
            session++
            host.hideStatus()
            setState(State.Idle)
        }
    }

    private fun micError(e: Throwable?) {
        if (e is SecurityException) message("voice.mic_permission", isError = true, openUmless = true)
        else message("voice.mic_failed", isError = true)
    }

    private fun message(key: String, isError: Boolean, openUmless: Boolean = false) =
        showMessage(env.t(key), isError, openUmless)

    private fun showMessage(text: String, isError: Boolean, openUmless: Boolean = false) {
        host.showStatus(VoiceStatus.Message(text, isError, openUmless))
        setState(State.Message(text, isError))
    }

    private fun setState(s: State) {
        if (s == state) return
        state = s
        listeners.forEach { it(s) }
    }

    companion object {
        const val LEVEL_INTERVAL_MS = 50L
        const val COMMIT_ACTIONS_MS = 4000L
        const val MAX_LISTEN_MS = 5 * 60_000L
    }
}

/**
 * 这次输入留下什么（同桌面端）：Umless 自己的界面里只写最近一次输出（不受暂停记录影响）；
 * 暂停记录、黑名单应用、（开启「无痕不记录」时）无痕输入框什么都不留；其余写历史。
 */
fun saveMode(f: EditorFacts, env: VoiceEnv): SaveMode = when {
    env.ownPackage != null && f.packageName == env.ownPackage -> SaveMode.LastOutputOnly
    !env.recordHistory || env.isExcluded(f.packageName) ||
            (env.skipIncognito && f.noPersonalizedLearning) -> SaveMode.Nothing
    else -> SaveMode.History
}
