package org.umless.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.umless.core.UtteranceRecord
import org.umless.voice.Transcript

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {

    // ---------------------------------------------------------------- 假实现

    private class FakeHost : VoiceHost {
        override val editorInfo: EditorInfo? get() = null
        val commits = mutableListOf<String>()
        val replaces = mutableListOf<Pair<String, String>>()
        val statuses = mutableListOf<VoiceStatus>()
        var hides = 0
        var haptics = 0
        var actions: Set<CommitAction>? = null
        var onAction: ((CommitAction) -> Unit)? = null
        var commitOk = true
        var replaceOk = true

        override fun commit(text: String): Boolean {
            if (commitOk) commits += text
            return commitOk
        }

        override fun replaceLastCommit(committed: String, replacement: String): Boolean {
            replaces += committed to replacement
            return replaceOk
        }

        override fun showStatus(state: VoiceStatus) {
            statuses += state
        }

        override fun hideStatus() {
            hides++
            actions = null
            onAction = null
        }

        override fun showCommitActions(onAction: (CommitAction) -> Unit) = showCommitActions(CommitAction.entries.toSet(), onAction)

        override fun showCommitActions(actions: Set<CommitAction>, onAction: (CommitAction) -> Unit) {
            this.actions = actions
            this.onAction = onAction
        }

        override fun hapticFeedback() {
            haptics++
        }

        val lastStatus get() = statuses.lastOrNull()
        val messages get() = statuses.filterIsInstance<VoiceStatus.Message>().map { it.text }
    }

    private class FakeRecorder : VoiceRecorder {
        override val sampleRate = 16000
        var starts = 0
        var stops = 0
        var recording = false
        var startError: Throwable? = null
        var samples = FloatArray(16000) { 0.1f }

        override fun start() {
            starts++
            startError?.let { throw it }
            recording = true
        }

        override fun stop(): FloatArray {
            stops++
            val was = recording
            recording = false
            return if (was) samples else FloatArray(0)
        }

        override fun level() = 0.5f
    }

    private class FakeEnv : VoiceEnv {
        override var minHoldMs = 300L
        override var polishTimeoutMs = 20_000L
        override var llmEnabled = true
        override var llmModel: String? = "test-model"
        override var haptic = true
        override var recordHistory = true
        override var skipIncognito = true
        override var ownPackage: String? = "org.umless.ime"
        var excluded = setOf("com.bank")
        var sceneRules = mapOf("com.tencent.mm" to "chat", "com.google.android.gm" to "email")
        var intensities = mapOf("email" to "restructure", "chat" to "moderate", "default" to "moderate")
        var model = true
        var mic = true
        val logs = mutableListOf<String>()

        override fun sceneOf(packageName: String?) = sceneRules[packageName] ?: "default"
        override fun intensityOf(scene: String) = intensities[scene] ?: "moderate"
        override fun isExcluded(packageName: String?) = packageName in excluded
        override fun modelReady() = model
        override fun hasMicPermission() = mic
        override fun t(key: String) = key
        override fun log(message: String, error: Throwable?) {
            logs += message
        }
    }

    private class Saved(val record: UtteranceRecord, val mode: SaveMode)

    private inner class Harness(val test: TestScope) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(dispatcher + Job())
        val host = FakeHost()
        val recorder = FakeRecorder()
        val env = FakeEnv()
        var facts: EditorFacts? = EditorFacts("com.tencent.mm", InputType.TYPE_CLASS_TEXT, 0)
        var transcript = "嗯今天下午三点开会"
        var transcribeDelay = 300L
        var transcribeCalls = 0
        var transcribeError: Throwable? = null
        var polishResult: String? = "今天下午三点开会。"
        var polishDelay = 500L
        var polishError: Throwable? = null
        val polishCalls = mutableListOf<Triple<String, String, String>>()
        var polishFinished = 0
        val saved = mutableListOf<Saved>()

        val controller = VoiceController(
            host = host,
            recorder = recorder,
            transcriber = Transcriber { s, sr ->
                transcribeCalls++
                delay(transcribeDelay)
                transcribeError?.let { throw it }
                Transcript(transcript, 123, "sensevoice")
            },
            polisher = Polisher { raw, scene, intensity ->
                polishCalls += Triple(raw, scene, intensity)
                delay(polishDelay)
                polishError?.let { throw it }
                polishFinished++
                polishResult
            },
            records = RecordSink { r, mode, _, _ -> saved += Saved(r, mode) },
            env = env,
            clock = Clock { test.testScheduler.currentTime },
            scope = scope,
            work = dispatcher,
            editor = { facts },
        )

        fun run() = test.testScheduler.runCurrent()
        fun advance(ms: Long) {
            test.testScheduler.advanceTimeBy(ms)
            test.testScheduler.runCurrent()
        }

        /** 按住 [holdMs] 后松开，然后让流程跑完（不含 4 秒的撤销窗口） */
        fun speak(holdMs: Long = 1000) {
            assertTrue(controller.press())
            run()
            advance(holdMs)
            controller.release()
            advance(2_000) // 识别 300ms + 润色 500ms 后写入；撤销窗口（写入后 4 秒）尚未结束
        }

        fun finish() {
            controller.dispose()
            scope.cancel()
        }
    }

    private fun harness(block: Harness.() -> Unit) = runTest {
        val h = Harness(this)
        try {
            h.block()
        } finally {
            h.finish()
        }
    }

    // ---------------------------------------------------------------- 正常流程

    @Test
    fun normalFlowCommitsPolishedTextAndRecords() = harness {
        assertTrue(controller.press())
        run()
        assertEquals(1, recorder.starts)
        assertTrue(controller.state is VoiceController.State.Listening)
        advance(200)
        // 每 50ms 刷新一次音量与计时
        val listening = host.statuses.filterIsInstance<VoiceStatus.Listening>()
        assertTrue(listening.size >= 4)
        assertTrue(listening.last().elapsedMs >= 150)
        advance(800)
        controller.release()
        run()
        assertEquals(VoiceStatus.Transcribing, host.lastStatus)
        advance(300)
        assertEquals(VoiceController.State.Polishing, controller.state)
        assertEquals(VoiceStatus.Polishing, host.lastStatus)
        advance(500)
        assertEquals(listOf("今天下午三点开会。"), host.commits)
        assertEquals(Triple("嗯今天下午三点开会", "chat", "moderate"), polishCalls.single())
        assertEquals(setOf(CommitAction.UseRaw, CommitAction.Undo), host.actions)
        assertEquals("voice.inserted", (host.lastStatus as VoiceStatus.Message).text)
        val r = saved.single()
        assertEquals(SaveMode.History, r.mode)
        assertEquals("嗯今天下午三点开会", r.record.rawText)
        assertEquals("今天下午三点开会。", r.record.polishedText)
        assertEquals("今天下午三点开会。", r.record.outputText)
        assertEquals("chat", r.record.scene)
        assertEquals("moderate", r.record.intensity)
        assertEquals("test-model", r.record.llmModel)
        assertEquals(500L, r.record.llmMs)
        assertEquals(1.0, r.record.durationS, 1e-9)
        assertNull(r.record.error)
        assertEquals(2, host.haptics) // 开始、结束各一次
        assertEquals(1, recorder.stops)
        assertEquals("今天下午三点开会。", controller.lastResult?.output)
    }

    @Test
    fun commitActionsExpireAfterFourSeconds() = harness {
        speak()
        assertTrue(controller.state is VoiceController.State.Committed)
        val cb = host.onAction!!
        advance(4_000)
        assertEquals(VoiceController.State.Idle, controller.state)
        cb(CommitAction.Undo) // 过期后点击无效
        assertTrue(host.replaces.isEmpty())
    }

    @Test
    fun undoRemovesCommittedText() = harness {
        speak()
        host.onAction!!(CommitAction.Undo)
        assertEquals(listOf("今天下午三点开会。" to ""), host.replaces)
        assertEquals(VoiceController.State.Idle, controller.state)
    }

    @Test
    fun useRawReplacesPolishedWithRaw() = harness {
        speak()
        host.onAction!!(CommitAction.UseRaw)
        assertEquals(listOf("今天下午三点开会。" to "嗯今天下午三点开会"), host.replaces)
        assertEquals(VoiceController.State.Idle, controller.state)
    }

    @Test
    fun noUseRawWhenNothingPolished() = harness {
        env.llmEnabled = false
        speak()
        assertTrue(polishCalls.isEmpty())
        assertEquals(listOf("嗯今天下午三点开会"), host.commits)
        assertEquals(setOf(CommitAction.Undo), host.actions)
        host.onAction!!(CommitAction.UseRaw) // 不在可用操作里
        assertTrue(host.replaces.isEmpty())
        val r = saved.single().record
        assertNull(r.llmModel)
        assertNull(r.intensity)
        assertNull(r.llmMs)
    }

    // ---------------------------------------------------------------- 按住太短 / 上滑取消

    @Test
    fun shortHoldIsDiscardedSilently() = harness {
        assertTrue(controller.press())
        run()
        advance(100)
        controller.release()
        advance(10_000)
        assertEquals(0, transcribeCalls)
        assertTrue(host.commits.isEmpty())
        assertTrue(host.messages.isEmpty())
        assertEquals(1, recorder.stops)
        assertFalse(recorder.recording)
        assertEquals(VoiceController.State.Idle, controller.state)
        assertTrue(host.hides >= 1)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun slideUpArmsCancelAndSlidingBackDisarms() = harness {
        assertTrue(controller.press())
        run()
        advance(500)
        controller.drag(-30f, 60f)
        assertEquals(false, (controller.state as VoiceController.State.Listening).cancelArmed)
        controller.drag(-80f, 60f)
        assertEquals(true, (controller.state as VoiceController.State.Listening).cancelArmed)
        assertEquals(true, (host.lastStatus as VoiceStatus.Listening).cancelArmed)
        controller.drag(-10f, 60f)
        assertEquals(false, (controller.state as VoiceController.State.Listening).cancelArmed)
        controller.drag(-100f, 60f)
        advance(500)
        controller.release()
        advance(10_000)
        assertEquals(0, transcribeCalls)
        assertTrue(host.commits.isEmpty())
        assertEquals(1, recorder.stops)
        assertEquals(VoiceController.State.Idle, controller.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun releaseAfterSlidingBackStillCommits() = harness {
        assertTrue(controller.press())
        run()
        advance(500)
        controller.drag(-100f, 60f)
        controller.drag(0f, 60f)
        advance(500)
        controller.release()
        advance(5_000)
        assertEquals(1, host.commits.size)
    }

    // ---------------------------------------------------------------- 拦截

    @Test
    fun passwordFieldIsBlocked() = harness {
        facts = EditorFacts("com.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0)
        assertFalse(controller.press())
        run()
        assertEquals(0, recorder.starts)
        assertEquals(listOf("voice.password_field"), host.messages)
        assertTrue((host.lastStatus as VoiceStatus.Message).isError)

        facts = EditorFacts("com.app", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0)
        assertFalse(controller.press())
        facts = EditorFacts("com.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0)
        assertFalse(controller.press())
        facts = EditorFacts("com.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0)
        assertFalse(controller.press())
        run()
        assertEquals(0, recorder.starts)
    }

    @Test
    fun noEditorDoesNothing() = harness {
        facts = null
        assertFalse(controller.press())
        assertTrue(host.statuses.isEmpty())
    }

    @Test
    fun modelMissingShowsMessageWithoutRecording() = harness {
        env.model = false
        assertFalse(controller.press())
        run()
        assertEquals(0, recorder.starts)
        val m = host.lastStatus as VoiceStatus.Message
        assertEquals("voice.model_missing", m.text)
        assertTrue(m.openUmless)
    }

    @Test
    fun missingMicPermissionPointsToUmless() = harness {
        env.mic = false
        assertFalse(controller.press())
        run()
        assertEquals(0, recorder.starts)
        val m = host.lastStatus as VoiceStatus.Message
        assertEquals("voice.mic_permission", m.text)
        assertTrue(m.openUmless)
    }

    @Test
    fun micStartFailureShowsError() = harness {
        recorder.startError = IllegalStateException("busy")
        assertTrue(controller.press())
        run()
        run()
        assertEquals("voice.mic_failed", (host.lastStatus as VoiceStatus.Message).text)
        assertTrue(controller.state is VoiceController.State.Message)
        controller.release() // 已经不在录音，松开无效
        advance(5_000)
        assertEquals(0, transcribeCalls)
        // 下一次按下可以重试
        recorder.startError = null
        assertTrue(controller.press())
        run()
        assertEquals(2, recorder.starts)
    }

    @Test
    fun micSecurityExceptionMeansPermission() = harness {
        recorder.startError = SecurityException("denied")
        assertTrue(controller.press())
        run()
        run()
        assertEquals("voice.mic_permission", (host.lastStatus as VoiceStatus.Message).text)
    }

    // ---------------------------------------------------------------- 失败兜底

    @Test
    fun polishTimeoutCommitsRawAndIgnoresLateResult() = harness {
        polishDelay = 30_000
        assertTrue(controller.press())
        run()
        advance(1000)
        controller.release()
        advance(300) // 识别完成，开始润色
        assertEquals(VoiceController.State.Polishing, controller.state)
        advance(19_999)
        assertTrue(host.commits.isEmpty())
        advance(1)
        assertEquals(listOf("嗯今天下午三点开会"), host.commits)
        val m = host.statuses.filterIsInstance<VoiceStatus.Message>().last()
        assertEquals("voice.polish_timeout", m.text)
        assertTrue(m.isError)
        assertEquals(setOf(CommitAction.Undo), host.actions)
        // 迟到的润色结果不会再写入，也不会替换
        advance(60_000)
        assertEquals(0, polishFinished)
        assertEquals(1, host.commits.size)
        assertTrue(host.replaces.isEmpty())
        val r = saved.single().record
        assertNull(r.polishedText)
        assertEquals("嗯今天下午三点开会", r.outputText)
        assertTrue(r.error!!.contains("超时"))
    }

    @Test
    fun polishFailureCommitsRaw() = harness {
        polishError = RuntimeException("401")
        speak()
        assertEquals(listOf("嗯今天下午三点开会"), host.commits)
        assertTrue("voice.polish_failed" in host.messages)
        assertTrue(saved.single().record.error!!.contains("401"))
    }

    @Test
    fun polishReturningBlankFallsBackToRaw() = harness {
        polishResult = "  "
        speak()
        assertEquals(listOf("嗯今天下午三点开会"), host.commits)
        assertTrue("voice.inserted" in host.messages)
    }

    @Test
    fun emptyTranscriptShowsNoSpeech() = harness {
        transcript = "  "
        speak()
        assertTrue(host.commits.isEmpty())
        assertEquals("voice.no_speech", (host.lastStatus as VoiceStatus.Message).text)
        assertFalse((host.lastStatus as VoiceStatus.Message).isError)
        assertTrue(polishCalls.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun transcribeErrorShowsItsMessage() = harness {
        transcribeError = RuntimeException("识别模型未下载")
        speak()
        assertTrue(host.commits.isEmpty())
        assertEquals("识别模型未下载", (host.lastStatus as VoiceStatus.Message).text)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun commitFailureStillRecords() = harness {
        host.commitOk = false
        speak()
        assertEquals("voice.failed", (host.lastStatus as VoiceStatus.Message).text)
        assertNull(host.actions)
        assertEquals(1, saved.size)
    }

    // ---------------------------------------------------------------- 取消 / 换输入框

    @Test
    fun editorChangeCancelsTranscription() = harness {
        assertTrue(controller.press())
        run()
        advance(1000)
        controller.release()
        advance(100)
        assertEquals(VoiceController.State.Transcribing, controller.state)
        controller.onEditorChanged()
        assertEquals(VoiceController.State.Idle, controller.state)
        advance(10_000)
        assertTrue(host.commits.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun editorChangeCancelsPolishing() = harness {
        assertTrue(controller.press())
        run()
        advance(1000)
        controller.release()
        advance(400)
        assertEquals(VoiceController.State.Polishing, controller.state)
        controller.onEditorChanged()
        advance(60_000)
        assertTrue(host.commits.isEmpty())
        assertEquals(0, polishFinished)
    }

    @Test
    fun editorChangeDropsPendingUndo() = harness {
        speak()
        val cb = host.onAction!!
        controller.onEditorChanged()
        cb(CommitAction.Undo)
        assertTrue(host.replaces.isEmpty())
    }

    @Test
    fun editorChangeWhileListeningStopsMic() = harness {
        assertTrue(controller.press())
        run()
        advance(500)
        controller.onEditorChanged()
        run()
        assertEquals(1, recorder.stops)
        assertFalse(recorder.recording)
        advance(10_000)
        assertEquals(0, transcribeCalls)
    }

    @Test
    fun cancelBeforeMicOpenedStillClosesIt() = harness {
        assertTrue(controller.press())
        controller.cancel() // 打开麦克风的后台任务还没跑
        run()
        run()
        assertFalse(recorder.recording)
    }

    @Test
    fun pressWhileProcessingIsIgnored() = harness {
        assertTrue(controller.press())
        run()
        advance(1000)
        controller.release()
        run()
        assertFalse(controller.press())
        assertEquals(1, recorder.starts)
    }

    @Test
    fun newPressDismissesPreviousUndo() = harness {
        speak()
        val cb = host.onAction!!
        assertTrue(controller.press())
        cb(CommitAction.Undo)
        assertTrue(host.replaces.isEmpty())
    }

    @Test
    fun cancelIfListeningOnlyAffectsItsSource() = harness {
        assertTrue(controller.press(VoiceController.Source.Panel))
        run()
        controller.cancelIfListening(VoiceController.Source.Space)
        assertTrue(controller.state is VoiceController.State.Listening)
        controller.cancelIfListening(VoiceController.Source.Panel)
        assertEquals(VoiceController.State.Idle, controller.state)
    }

    // ---------------------------------------------------------------- 是否记录

    @Test
    fun recordDecisions() = harness {
        val normal = EditorFacts("com.tencent.mm", InputType.TYPE_CLASS_TEXT, 0)
        val incognito = EditorFacts("com.android.chrome", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val own = EditorFacts("org.umless.ime", InputType.TYPE_CLASS_TEXT, 0)
        assertEquals(SaveMode.History, saveMode(normal, env))
        assertEquals(SaveMode.Nothing, saveMode(EditorFacts("com.bank", InputType.TYPE_CLASS_TEXT, 0), env))
        assertEquals(SaveMode.Nothing, saveMode(incognito, env))
        assertEquals(SaveMode.LastOutputOnly, saveMode(own, env))
        env.skipIncognito = false
        assertEquals(SaveMode.History, saveMode(incognito, env))
        env.recordHistory = false
        assertEquals(SaveMode.Nothing, saveMode(normal, env))
        assertEquals(SaveMode.LastOutputOnly, saveMode(own, env))  // 自己界面里的「试一试」不受暂停影响
    }

    @Test
    fun incognitoUtteranceLeavesNoTrace() = harness {
        facts = EditorFacts("com.android.chrome", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        speak()
        assertEquals(1, host.commits.size)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun excludedAppLeavesNoTrace() = harness {
        facts = EditorFacts("com.bank", InputType.TYPE_CLASS_TEXT, 0)
        speak()
        assertTrue(saved.isEmpty())
    }

    // ---------------------------------------------------------------- 场景

    @Test
    fun searchFieldsUseFaithful() = harness {
        facts = EditorFacts("com.tencent.mm", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0)
        speak()
        assertEquals(Triple("嗯今天下午三点开会", "search", "faithful"), polishCalls.single())
        assertEquals("search", saved.single().record.scene)
        assertEquals("faithful", saved.single().record.intensity)
    }

    @Test
    fun sceneSelection() {
        val sceneOf = { p: String? -> mapOf("com.google.android.gm" to "email", "com.tencent.mm" to "chat")[p] ?: "default" }
        val intensityOf = { s: String -> if (s == "email") "restructure" else "moderate" }
        fun choose(pkg: String?, type: Int, ime: Int = 0) = chooseScene(EditorFacts(pkg, type, ime), sceneOf, intensityOf)
        val text = InputType.TYPE_CLASS_TEXT

        assertEquals(SceneChoice("search", "faithful"), choose("com.tencent.mm", text or InputType.TYPE_TEXT_VARIATION_URI))
        assertEquals(SceneChoice("search", "faithful"), choose("com.tencent.mm", text, EditorInfo.IME_ACTION_SEARCH))
        assertEquals(
            SceneChoice("search", "faithful"),
            choose("com.android.chrome", text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI),
        )
        assertEquals(SceneChoice("search", "faithful"), choose("com.app", text or InputType.TYPE_TEXT_VARIATION_FILTER))
        assertEquals(SceneChoice("search", "faithful"), choose("com.app", InputType.TYPE_CLASS_NUMBER))
        assertEquals(SceneChoice("email", "restructure"), choose("com.app", text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
        assertEquals(
            SceneChoice("email", "restructure"),
            choose("com.google.android.gm", text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        )
        assertEquals(SceneChoice("chat", "moderate"), choose("com.tencent.mm", text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND))
        assertEquals(SceneChoice("default", "moderate"), choose("com.unknown", text))
        assertEquals(SceneChoice("default", "moderate"), choose(null, text))
    }

    @Test
    fun editorFacts() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(EditorFacts("a", text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0).isPassword)
        assertTrue(EditorFacts("a", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0).isPassword)
        assertFalse(EditorFacts("a", text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).isPassword)
        assertFalse(EditorFacts("a", InputType.TYPE_CLASS_NUMBER, 0).isPassword)
        assertTrue(EditorFacts("a", text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE).noPersonalizedLearning)
        assertFalse(EditorFacts("a", text, EditorInfo.IME_ACTION_DONE).noPersonalizedLearning)
    }

    // ---------------------------------------------------------------- 状态通知

    @Test
    fun listenersSeeStateSequence() = harness {
        val seen = mutableListOf<String>()
        controller.addListener { seen += it.javaClass.simpleName }
        speak()
        assertEquals(listOf("Listening", "Transcribing", "Polishing", "Committed"), seen)
    }

    @Test
    fun longRecordingIsReleasedAutomatically() = harness {
        assertTrue(controller.press())
        run()
        advance(VoiceController.MAX_LISTEN_MS + 100)
        assertFalse(controller.state is VoiceController.State.Listening)
        assertEquals(1, recorder.stops)
        advance(2_000)
        assertEquals(1, host.commits.size)
    }
}
