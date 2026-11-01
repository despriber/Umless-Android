package org.umless.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.umless.R
import org.umless.api.UmlessApi
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Umless 主 App（启动入口）：WebView 加载与桌面端相同的网页界面（概览 / 报告 / 练习 / 历史 / 设置 / 新手引导）。
 *
 * - 网页由 [WebViewAssetLoader] 从 assets/web/ 提供，地址 https://appassets.androidplatform.net/web/index.html；
 *   其他网络请求一律拒绝，外部链接交给浏览器
 * - js/android-shim.js 在页面脚本之前注入（document-start 脚本；旧 WebView 改为在 index.html 里插入 script 标签），
 *   它把 window.pywebview.api.<方法>() 转给 [UmlessBridge]
 * - 全面屏：内容画到系统栏下面，网页用 env(safe-area-inset-*) 避让；同时把安全区写入 CSS 变量 --sat/--sar/--sab/--sal。
 *   页面加载后检查 WebView 是否支持 env()，不支持时改由原生内边距避让（系统栏区域填页面背景色）
 * - 返回键：先给网页（window.__umless.back()），再按网页历史后退，最后退出
 * - 旋转、键盘等配置变化不重建（WebView 保留）；深浅色 / 语言变化会重建，用 saveState 恢复页面
 * - 深链接：intent extra [EXTRA_PAGE]（如 "settings/keyboard"）或键盘用的 [EXTRA_PAGE_KEYBOARD]
 *   （models → 设置「识别」，permission → 弹麦克风授权框）；未完成引导时打开 #onboarding
 */
class UmlessActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "page"

        /** 键盘（org.umless.glue.UmlessGlue.openUmless）使用的同义 extra：models / permission */
        const val EXTRA_PAGE_KEYBOARD = "org.umless.extra.PAGE"
        private const val TAG = "UmlessActivity"
        private const val INDEX_URL = "${BridgeCodec.ORIGIN}/web/index.html"
        private const val SHIM_ASSET = "web/js/android-shim.js"

        /** 返回结果早于此时间视为系统没有弹窗（已被永久拒绝） */
        private const val PERMISSION_INSTANT_MS = 500L

        /** 输入法选择框：等待窗口焦点、等待选择框出现的上限 */
        private const val FOCUS_WAIT_MS = 1500L
        private const val PICKER_SHOWN_MS = 1000L

        private var currentRef = WeakReference<UmlessActivity>(null)

        /** 当前在前台的主 App 窗口（平台接口用它申请权限、打开系统界面） */
        val current: UmlessActivity? get() = currentRef.get()

        /** WebView 不支持 env(safe-area-inset-*) 时由原生避让系统栏（进程内记住，重建后不再闪一下） */
        @Volatile
        private var nativeInsets = false

        fun intent(context: Context, page: String? = null): Intent =
            Intent(context, UmlessActivity::class.java).apply { if (page != null) putExtra(EXTRA_PAGE, page) }
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var bars: BarBackground
    private lateinit var init: Deferred<Unit>

    @Volatile
    private var pageUrl: String? = null
    private var pageLoaded = false
    private var destroyed = false

    /** 最近一次发给网页的安全区（CSS 像素），页面加载后用来判断 env() 是否可用 */
    private var cssInsets = floatArrayOf(0f, 0f, 0f, 0f)

    private var docRequest: CompletableDeferred<Uri?>? = null
    private val docLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        docRequest?.complete(uri)
        docRequest = null
    }

    private var micRequest: CompletableDeferred<Boolean>? = null
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRequest?.complete(granted)
        micRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        currentRef = WeakReference(this)
        init = UiInit.start(applicationContext)
        if (savedInstanceState == null) UiInit.runAutoDiagnosis(init)

        webView = try {
            WebView(this)
        } catch (e: Exception) {
            // 系统 WebView 缺失或正在更新
            UiLog.e(TAG, "无法创建 WebView", e)
            Toast.makeText(this, e.message ?: "WebView", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val bg = ContextCompat.getColor(this, R.color.umless_bg)
        bars = BarBackground(bg, ContextCompat.getColor(this, R.color.umless_bg_base))
        root = FrameLayout(this).apply {
            background = bars
            addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        webView.setBackgroundColor(bg)
        setContentView(root)
        setupWebView()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> applyInsets(insets) }
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            lifecycleScope.launch { webView.loadUrl(urlFor(initialPage(intent))) }
        }
        if (savedInstanceState == null) maybeRequestMic(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::webView.isInitialized || destroyed) return
        maybeRequestMic(intent)
        val page = BridgeCodec.routeFor(pageExtra(intent)) ?: return
        if (pageLoaded) webView.evaluateJavascript(WebScripts.navigateScript(page), null)
        else webView.loadUrl(urlFor(page))
    }

    private fun pageExtra(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_PAGE) ?: intent?.getStringExtra(EXTRA_PAGE_KEYBOARD)

    /** 键盘因为没有麦克风权限打开主 App：界面出来后直接弹授权框 */
    private fun maybeRequestMic(intent: Intent?) {
        if (pageExtra(intent)?.trim() != BridgeCodec.ACTION_PERMISSION) return
        lifecycleScope.launch {
            withResumed {}
            requestMicPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        // 每次回到主 App 都问一下；是否到期（24 小时）由 Updater 判断，未到期只读一次本地缓存
        if (::init.isInitialized) UiInit.runAutoUpdateCheck(init)
    }

    override fun onResume() {
        super.onResume()
        currentRef = WeakReference(this)
        if (::webView.isInitialized && !destroyed) {
            webView.onResume()
            // 从系统设置返回时网页靠 visibilitychange 刷新键盘状态；个别 WebView 不派发，这里补一次（重复刷新无害）
            webView.postDelayed({
                if (pageLoaded && !destroyed) webView.evaluateJavascript(WebScripts.VISIBLE, null)
            }, 300)
        }
    }

    override fun onPause() {
        if (::webView.isInitialized && !destroyed) webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized && !destroyed) webView.saveState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        if (currentRef.get() === this) currentRef = WeakReference(null)
        micRequest?.complete(false)
        micRequest = null
        docRequest?.complete(null)
        docRequest = null
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    /* ---------------------------------------------------------------- WebView */

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        // 深色由页面自己适配（light-dark() + prefers-color-scheme），关闭 WebView 的算法深色
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER // 网页自己的 .main 负责滚动

        val bridge = UmlessBridge(
            scope = lifecycleScope,
            ready = { init.await() },
            isTrusted = { BridgeCodec.isTrustedUrl(pageUrl) },
            deliver = { script -> if (!destroyed) webView.evaluateJavascript(script, null) },
            // 主题可能变了（设置页切换深浅色）：重新读取页面颜色
            onResult = { method -> if (method == "get_app_info" || method == "save_settings") probe(decideInsets = false) },
        )
        webView.addJavascriptInterface(bridge, "UmlessBridge")

        // 垫片：优先 document-start 脚本（只对我们的域名生效）；不支持时在 index.html 里插入
        var injected = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                val shim = assets.open(SHIM_ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
                WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf(BridgeCodec.ORIGIN))
                injected = true
            } catch (e: Exception) {
                UiLog.w(TAG, "注入 document-start 脚本失败，改为在 index.html 里插入", e)
            }
        }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(BridgeCodec.HOST)
            .addPathHandler("/web/", WebAssetHandler(assets, injectShim = !injected))
            .build()
        webView.webViewClient = Client(loader)
        webView.webChromeClient = Chrome()
    }

    private fun urlFor(page: String?) = if (page == null) INDEX_URL else "$INDEX_URL#$page"

    /** 深链接页面；没有时若尚未完成引导则直接打开引导页（网页自己也会跳转，这里省一次闪烁） */
    private suspend fun initialPage(intent: Intent?): String? {
        BridgeCodec.routeFor(pageExtra(intent))?.let { return it }
        val onboarded = withTimeoutOrNull(2000) {
            withContext(Dispatchers.IO) {
                try {
                    init.await()
                    val info = UmlessApi.call("get_app_info", JsonArray(emptyList())) as? JsonObject
                    (info?.get("onboarded") as? JsonPrimitive)?.booleanOrNull
                } catch (e: Exception) {
                    null
                }
            }
        }
        return if (onboarded == false) "onboarding" else null
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            UiLog.w(TAG, "没有可以打开链接的应用：$uri")
        }
    }

    private inner class Client(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.scheme == "https" && url.host == BridgeCodec.HOST) {
                return loader.shouldInterceptRequest(url) ?: WebAssetHandler.empty(404, "Not Found")
            }
            // 网页界面完全离线（大模型等网络请求都在原生端）；其他请求一律拒绝
            return WebAssetHandler.empty(403, "Forbidden")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            overrideUrl(request.url, request.isForMainFrame)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            overrideUrl(Uri.parse(url), true)

        private fun overrideUrl(url: Uri, mainFrame: Boolean): Boolean {
            if (url.scheme == "https" && url.host == BridgeCodec.HOST) return false
            if (mainFrame && url.scheme in setOf("http", "https", "mailto")) openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageUrl = url
            pageLoaded = false
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            pageUrl = url
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageUrl = url
            pageLoaded = true
            sendCssInsets()
            probe(decideInsets = true)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 渲染进程崩溃或被回收：重建界面，不让整个 App 崩溃
            UiLog.e(TAG, "WebView 渲染进程已退出")
            if (!destroyed) {
                destroyed = true // 之后不能再调用这个 WebView 的方法（destroy 除外，在 onDestroy 里）
                recreate()
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                UiLog.w(TAG, "网页错误：${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }
    }

    /* ---------------------------------------------------------------- 返回键 */

    private fun handleBack() {
        if (!pageLoaded || destroyed) {
            finish()
            return
        }
        webView.evaluateJavascript(WebScripts.BACK) { handled ->
            if (destroyed || handled == "true") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    /* ---------------------------------------------------------------- 系统栏与安全区 */

    private fun applyInsets(insets: WindowInsetsCompat): WindowInsetsCompat {
        val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = ime.bottom > 0 && ime.bottom > sys.bottom
        val d = resources.displayMetrics.density
        if (nativeInsets) {
            // 原生避让：WebView 不画到系统栏下面，网页的 env() 与 CSS 变量都为 0
            val bottom = max(sys.bottom, ime.bottom)
            root.setPadding(sys.left, sys.top, sys.right, bottom)
            bars.bottomHeight = if (imeVisible) 0 else sys.bottom
            root.invalidate()
            cssInsets = floatArrayOf(0f, 0f, 0f, 0f)
            sendCssInsets()
            return WindowInsetsCompat.CONSUMED
        }
        // 全面屏：只为输入法让出空间（API 30+ 全面屏下窗口不会自动缩小），其余交给网页避让
        root.setPadding(0, 0, 0, if (imeVisible) ime.bottom else 0)
        val bottom = if (imeVisible) 0 else sys.bottom
        cssInsets = floatArrayOf(sys.top / d, sys.right / d, bottom / d, sys.left / d)
        sendCssInsets()
        if (!imeVisible) return insets
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(sys.left, sys.top, sys.right, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .build()
    }

    private fun sendCssInsets() {
        if (!pageLoaded || destroyed) return
        val (t, r, b, l) = cssInsets
        webView.evaluateJavascript(WebScripts.insetsScript(t, r, b, l), null)
    }

    /**
     * 读取页面的深浅色与背景色，设置状态栏 / 导航栏图标颜色与原生避让时的底色；
     * [decideInsets] 为 true（页面刚加载完）时顺便判断 env(safe-area-inset-*) 是否可用
     */
    private fun probe(decideInsets: Boolean) {
        if (!pageLoaded || destroyed) return
        webView.evaluateJavascript(WebScripts.PROBE) { result ->
            if (destroyed) return@evaluateJavascript
            val p = WebScripts.parseProbe(result) ?: return@evaluateJavascript
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !p.dark
                isAppearanceLightNavigationBars = !p.dark
            }
            p.topColor?.let { bars.topColor = it }
            p.bottomColor?.let { bars.bottomColor = it }
            root.invalidate()
            if (decideInsets && !nativeInsets &&
                WebScripts.needsNativeInsets(cssInsets[0], cssInsets[2], p.envTop, p.envBottom)
            ) {
                UiLog.i(TAG, "WebView 不支持 env(safe-area-inset-*)，改由原生避让系统栏")
                nativeInsets = true
                ViewCompat.requestApplyInsets(root)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后安全区变化由 insets 回调处理；这里只刷新颜色
        probe(decideInsets = false)
    }

    /* ---------------------------------------------------------------- 麦克风权限 */

    /**
     * 申请录音权限并等待结果。系统没有弹窗就立即返回拒绝（之前选了「不再询问」）时，打开应用详情页让用户手动开启
     */
    /* ---------------------------------------------------------------- 输入法选择框 */

    private val focusWatchers = mutableListOf<(Boolean) -> Unit>()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusWatchers.toList().forEach { it(hasFocus) }
    }

    /** 等窗口焦点变为 [target]（主线程调用），超时返回 false */
    private suspend fun awaitWindowFocus(target: Boolean, timeoutMs: Long): Boolean {
        if (hasWindowFocus() == target) return true
        val done = CompletableDeferred<Unit>()
        val watcher: (Boolean) -> Unit = { if (it == target) done.complete(Unit) }
        focusWatchers += watcher
        return try {
            withTimeoutOrNull(timeoutMs) { done.await() } != null
        } finally {
            focusWatchers -= watcher
        }
    }

    /**
     * 弹出系统输入法选择框，返回是否真的弹出来了。
     * 系统只允许「当前有窗口焦点」的应用弹出：从系统设置返回后焦点可能还没回来，先等一下；
     * 弹出的选择框会拿走本窗口的焦点，据此判断是否成功（部分系统会静默忽略这个调用）。
     */
    suspend fun showImePicker(): Boolean {
        awaitWindowFocus(true, FOCUS_WAIT_MS)
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        val lost = CompletableDeferred<Unit>()
        val watcher: (Boolean) -> Unit = { if (!it) lost.complete(Unit) }
        focusWatchers += watcher
        return try {
            imm.showInputMethodPicker()
            withTimeoutOrNull(PICKER_SHOWN_MS) { lost.await() } != null
        } finally {
            focusWatchers -= watcher
        }
    }

    suspend fun requestMicPermission(): Boolean {
        val perm = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return true
        if (destroyed) return false
        val request = CompletableDeferred<Boolean>()
        micRequest?.complete(false)
        micRequest = request
        val t0 = SystemClock.elapsedRealtime()
        micLauncher.launch(perm)
        val granted = request.await()
        if (!granted && SystemClock.elapsedRealtime() - t0 < PERMISSION_INSTANT_MS &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
        ) {
            openAppDetails()
        }
        return granted
    }

    /** 系统文件选择器，返回选中的文件；取消时 null */
    suspend fun pickDocument(mimeTypes: Array<String>): Uri? {
        if (destroyed) return null
        val request = CompletableDeferred<Uri?>()
        docRequest?.complete(null)
        docRequest = request
        try {
            docLauncher.launch(mimeTypes)
        } catch (e: ActivityNotFoundException) {
            docRequest = null
            UiLog.w(TAG, "没有可用的文件选择器", e)
            return null
        }
        return request.await()
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            UiLog.w(TAG, "无法打开应用详情", e)
        }
    }
}
