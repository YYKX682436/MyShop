package aikayun.muchen.shop

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Activity owns exactly one WebView; request/view identities make abandoned callbacks inert. */
open class ShopActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var request: Future<*>? = null
    private var requestId = 0L
    private var viewId = 0L
    private var foreground = false
    private var authorized = false
    private var querying = false
    private var candidate: String? = null
    private var bound: String? = null
    private var retryUrl: String? = null
    private var web by mutableStateOf<WebView?>(null)
    private val appearance = ShellAppearance()
    private val glassSource = WebGlassSource()
    private var display: AppDisplay? = null
    private var siteType = "myshop"
    private var homeClearPending = false
    private var homeDocument = -1L
    private var mainMethod = "GET"
    private var brandGeneration = 0L
    private val brandExecutor = Executors.newSingleThreadExecutor()
    private val brands by lazy { SiteBrands(getSharedPreferences("brands", MODE_PRIVATE)) }
    private var activeBrand: SiteBrand? = null
    private var brandOrigin: String? = null
    private var discoveryDocument = -1L
    private var homeMetadataPending = false
    private var brandHomeUrl: String? = null
    private var appearanceCheck: Runnable? = null
    private var documentUrl: String? = null
    private var documentId = 0L
    private var committed = false
    private var finished = false
    private var pageReady = false
    private var deadline: Runnable? = null
    private lateinit var gate: LinearLayout
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private val preferences by lazy { getSharedPreferences("binding", MODE_PRIVATE) }

    protected open fun authorityClient(): AuthorityClient = AuthorityClient()
    // Default never intercepts content. Debug fixtures override this in a debug-only Activity.
    protected open fun interceptResource(request: WebResourceRequest): WebResourceResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bound = preferences.getString("origin", null)
        restoreBrand(bound)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        gate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        input = EditText(this).apply {
            id = R.id.origin_input
            hint = "商城网址"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(savedInstanceState?.getString("input") ?: "")
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            textSize = 16f
            setPadding(dp(16), 0, dp(16), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, event ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) { submit(); true } else false
            }
        }
        status = TextView(this).apply { id = R.id.status; textSize = 15f; setPadding(0, dp(12), 0, dp(12)) }
        confirm = Button(this).apply { id = R.id.confirm; text = "进入商城"; isAllCaps = false; textSize = 16f; setOnClickListener { submit() } }
        gate.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        gate.addView(status, LinearLayout.LayoutParams(-1, -2))
        gate.addView(confirm, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        styleInput()
        setContentView(ComposeView(this).apply {
            consumeWindowInsets = false
            setContent { ShopShell(web, gate, appearance, glassSource, ::menuAction) }
        })
        showGate(if (bound == null) "" else "正在验证授权…", false)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { navigateBack() }
        })
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        val persisted = preferences.getString("origin", null)
        if (persisted != bound) {
            invalidateRequest()
            candidate = null
            retryUrl = null
            authorized = false
            destroyWeb()
            bound = persisted
            restoreBrand(bound)
        }
        // Only here, never also in onCreate/onResume. An unbound attempt is not a saved binding.
        bound?.let { checkAuthorization(it) }
    }

    override fun onStop() {
        foreground = false
        homeClearPending = false; homeDocument = -1L
        invalidateRequest()
        authorized = false
        if (bound == null) {
            if (candidate != null) {
                candidate = null
                destroyWeb()
                showGate("已暂停，请重新确认", true)
            }
        } else {
            web?.onPause()
            showGate("正在验证授权…", false)
        }
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("input", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun submit() {
        if (querying || !foreground) return
        val origin = bound ?: OriginPolicy.normalize(input.text.toString())
        if (origin == null) { showGate("请输入有效的 HTTPS 商城网址", true); return }
        if (bound == null) {
            candidate = origin
            destroyWeb()
            input.setText(origin)
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        checkAuthorization(origin)
    }

    private fun invalidateRequest() {
        requestId++
        request?.cancel(true)
        request = null
        querying = false
    }

    private fun checkAuthorization(origin: String) {
        invalidateRequest()
        val id = requestId
        authorized = false
        querying = true
        web?.onPause()
        showGate("正在验证授权…", false)
        val client = authorityClient()
        request = executor.submit {
            val result = client.resolve(origin)
            handler.post {
                if (!foreground || id != requestId || (bound ?: candidate) != origin) return@post
                querying = false
                request = null
                when (result) {
                    is Authorization.Allowed -> {
                        authorized = true
                        siteType = result.siteType
                        appearance.external = siteType == "external"
                        applyAuthorizedBrand(origin, result.brand)
                        val existing = web
                        if (existing != null && bound != null) {
                            existing.onResume()
                            display?.update()
                            if (pageReady) refreshDocumentAppearance(existing, viewId, origin, documentId)
                            if (pageReady) revealWeb() else showGate("正在打开商城…", false)
                        } else openPage(origin, retryUrl ?: origin)
                    }
                    is Authorization.Denied -> {
                        // Explicit denial isolates the old document and its JS/history entirely.
                        destroyWeb()
                        candidate = null
                        val reason = when (result.reason) {
                            "unavailable" -> "商城客户端访问已停用"
                            "site_unavailable" -> "商城不存在或已停用"
                            else -> "该网址未获授权"
                        }
                        showGate(reason, true)
                    }
                    is Authorization.Failed -> {
                        if (bound == null) { candidate = null; destroyWeb() }
                        showGate(result.message, true)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPage(origin: String, url: String) {
        destroyWeb()
        val token = viewId
        homeMetadataPending = url == origin
        brandHomeUrl = null
        val mainRequests = java.util.concurrent.ConcurrentHashMap<String, String>()
        pageReady = false
        val browser = ObservedWebView(this)
        glassSource.attach(browser)
        browser.direction = { show ->
            if (!appearance.gate && !appearance.menu) {
                appearance.toolbar = show
                updateSampling()
            }
        }
        web = browser
        browser.id = R.id.browser
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        appearance.desktop = preferences.getBoolean("desktop:$origin", false)
        display = AppDisplay(browser, origin, siteType == "external").also { it.configure(appearance.desktop) }
        browser.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r-l != oldR-oldL || b-t != oldB-oldT) {
                glassSource.invalidateFrame()
                if (authorized && web === browser) display?.update()
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap) {
                if (web === view && authorized && OriginPolicy.normalize(view.url ?: "") == origin && view.url == origin && activeBrand?.iconUrl.isNullOrBlank()) {
                    appearance.icon = android.graphics.Bitmap.createScaledBitmap(icon,192,192,true)
                    cacheBrand(origin)
                }
            }
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                unsupported()
                return true
            }
        }
        browser.setDownloadListener { _, _, _, _, _ -> unsupported() }
        fun live(view: WebView) = token == viewId && web === view
        browser.interacted = {
            appearanceCheck?.let(handler::removeCallbacks)
            val document = documentId
            appearanceCheck = Runnable {
                if (live(browser) && foreground && authorized && pageReady && document == documentId) refreshDocumentAppearance(browser, token, origin, document)
            }.also { handler.postDelayed(it, 180) }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) {
                    val target = request.url.toString()
                    mainRequests[target.substringBefore('#')] = request.method
                    handler.post {
                        if (live(view) && OriginPolicy.normalize(target) == origin && documentUrl?.substringBefore('#') == target.substringBefore('#')) mainMethod = request.method
                    }
                }
                return interceptResource(request)
            }

            override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
                dontResend.sendToTarget()
                Toast.makeText(this@ShopActivity, "已取消表单重新提交，请返回原页面操作", Toast.LENGTH_SHORT).show()
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                if (live(view) && authorized) { display?.update(); glassSource.invalidateFrame() }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (live(view)) {
                    appearance.toolbar = true
                    appearance.menu = false
                    updateHost(view, url, token, origin)
                    appearance.canBack = view.canGoBack()
                    if (authorized && pageReady) refreshDocumentAppearance(view, token, origin, documentId)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (!live(view) || !authorized || !foreground) return true
                val target = request.url.toString()
                if (homeClearPending && (request.hasGesture() || request.method != "GET")) { homeClearPending=false;homeDocument=-1L }
                if (OriginPolicy.normalize(target) == origin) return false
                if (bound == null) {
                    failPage("首页跳转到其他网址，请修改后重试", origin)
                    return true
                }
                if (request.method == "GET" && request.url.scheme == "https" && OriginPolicy.normalize(target) != null) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: android.content.ActivityNotFoundException) { unsupported() }
                } else unsupported()
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!live(view)) return
                if (OriginPolicy.normalize(url) != origin) {
                    failPage("不支持此页面跳转", origin)
                    return
                }
                documentId++
                updateHost(view, url, token, origin)
                glassSource.invalidateFrame()
                appearance.toolbar = true
                appearance.menu = false
                mainMethod = mainRequests[url.substringBefore('#')] ?: "UNKNOWN"
                if (homeClearPending) { homeDocument = documentId; homeMetadataPending = true }
                documentUrl = url
                committed = false
                finished = false
                pageReady = false
                appearance.ready = false
                updateSampling()
                // First binding has one deadline across redirects, rather than a fresh budget per hop.
                if (bound != null) scheduleTimeout(token, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!live(view) || documentUrl != url || OriginPolicy.normalize(url) != origin) return
                committed = true
                finishIfReady(view, token, origin)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!live(view) || documentUrl != url || OriginPolicy.normalize(url) != origin) return
                finished = true
                display?.update()
                refreshDocumentAppearance(view, token, origin, documentId)
                finishIfReady(view, token, origin)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (live(view) && request.isForMainFrame) failPage("网页连接失败，请重试", request.url.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (live(view) && request.isForMainFrame) failPage("网页请求失败（HTTP ${response.statusCode}）", request.url.toString())
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                // TLS errors do not expose isForMainFrame. A subresource failure must not unbind the site.
                if (live(view) && (error.url == documentUrl || error.url == view.url)) failPage("网页证书无效，已停止访问", error.url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (live(view)) failPage("网页已停止，请重试", documentUrl ?: origin)
                return true
            }
        }

        showGate("正在打开商城…", false)
        scheduleTimeout(token, url)
        browser.loadUrl(url)
    }

    private fun finishIfReady(view: WebView, token: Long, origin: String) {
        if (!committed || !finished) return
        val navigation = documentId
        view.postVisualStateCallback(navigation, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (token != viewId || web !== view || navigation != documentId || !committed || !finished) return
                pageReady = true
                cancelTimeout()
                // This is the only persistent binding write in the application.
                if (foreground && authorized && bound == null && candidate == origin) {
                    val persisted = preferences.getString("origin", null)
                    if (persisted != null && persisted != origin) {
                        invalidateRequest()
                        candidate = null
                        retryUrl = null
                        authorized = false
                        destroyWeb()
                        bound = persisted
                        checkAuthorization(persisted)
                        return
                    }
                    if (!preferences.edit().putString("origin", origin).commit()) {
                        failPage("无法保存绑定，请重试", origin)
                        return
                    }
                    bound = origin
                    candidate = null
                    cacheBrand(origin)
                }
                if (homeClearPending && homeDocument == navigation && OriginPolicy.normalize(view.url ?: "") == origin && foreground && authorized) {
                    view.clearHistory()
                    homeClearPending = false; homeDocument = -1L
                }
                if (foreground && authorized) {
                    retryUrl = null
                    CookieManager.getInstance().flush()
                    display?.settleOverview { token == viewId && web === view && navigation == documentId && foreground && authorized }
                    revealWeb()
                }
            }
        })
    }

    private fun scheduleTimeout(token: Long, url: String) {
        cancelTimeout()
        deadline = Runnable { if (token == viewId && !pageReady) failPage("网页打开超时，请重试", url) }
        handler.postDelayed(deadline!!, 30_000)
    }

    private fun cancelTimeout() { deadline?.let(handler::removeCallbacks); deadline = null }

    private fun failPage(message: String, url: String) {
        invalidateRequest()
        authorized = false
        retryUrl = if (bound != null && OriginPolicy.normalize(url) == bound) url else null
        candidate = null
        destroyWeb()
        showGate(message, true)
    }

    private fun destroyWeb() {
        appearance.samplingAllowed=false
        glassSource.allow(false)
        appearanceCheck?.let(handler::removeCallbacks); appearanceCheck = null
        display?.close(); display = null
        homeClearPending = false; homeDocument = -1L
        brandGeneration++
        glassSource.attach(null)
        cancelTimeout()
        viewId++
        pageReady = false
        documentUrl = null
        web?.let {
            web = null
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.stopLoading()
            it.onPause()
            it.destroy()
        }
    }

    private fun showGate(message: String, retry: Boolean) {
        gate.visibility = View.VISIBLE
        appearance.returning = bound != null && web != null && pageReady
        appearance.bound = bound != null
        appearance.busy = !retry && message.isNotEmpty()
        appearance.ready = false
        appearance.gate = true
        appearance.menu = false
        updateSampling()
        web?.visibility = View.INVISIBLE
        input.visibility = if (bound == null) View.VISIBLE else View.GONE
        input.isEnabled = !querying && retry || (!querying && message.isEmpty())
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        confirm.text = if (appearance.busy) "正在进入…" else if (bound != null) "重试" else "进入商城"
        confirm.isEnabled = retry || message.isEmpty()
        status.setTextColor(if (retry) Color.rgb(200, 55, 65) else if (isDark()) Color.LTGRAY else Color.DKGRAY)
    }

    private fun revealWeb() {
        if (!foreground || !authorized) return
        gate.visibility = View.GONE
        appearance.ready = true
        appearance.gate = false
        appearance.toolbar = true
        appearance.canBack = web?.canGoBack() == true
        appearance.bound = bound != null
        web?.visibility = View.VISIBLE
        updateSampling()
    }

    private fun updateSampling() {
        val allowed = foreground && authorized && appearance.ready && !appearance.gate
        appearance.samplingAllowed = allowed
        glassSource.allow(allowed)
    }

    private fun menuAction(index: Int) {
        appearance.menu = false
        if (!foreground || !authorized || appearance.gate || !pageReady) return
        val browser = web ?: return
        val url = browser.url?.takeIf { OriginPolicy.normalize(it) == bound } ?: return
        if(index==0 || index==1 || index==6) { homeClearPending=false;homeDocument=-1L }
        when (index) {
            0 -> if (browser.canGoBack()) browser.goBack()
            1 -> if (mainMethod == "GET") browser.reload() else formNotice()
            2 -> bound?.let { home ->
                if (url != home) { homeClearPending = true; browser.loadUrl(home) }
                else appearance.toolbar = true
            }
            3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${browser.title.orEmpty()}\n$url")
            }, "分享当前网页"))
            4 -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
            5 -> {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("当前网址", url))
                Toast.makeText(this, "网址已复制", Toast.LENGTH_SHORT).show()
            }
            6 -> {
                if (mainMethod != "GET" || browser.progress < 100) { formNotice(); return }
                appearance.ready = false
                updateSampling()
                appearance.desktop = !appearance.desktop
                bound?.let { preferences.edit().putBoolean("desktop:$it", appearance.desktop).apply() }
                display?.configure(appearance.desktop)
                browser.requestApplyInsets()
                browser.reload()
            }
        }
    }

    private fun formNotice() { android.app.AlertDialog.Builder(this).setMessage("此页面包含表单或仍在加载，请返回稳定页面后再操作。不会重新提交表单。").setPositiveButton("知道了", null).show() }

    private fun restoreBrand(origin: String?) {
        brandGeneration++
        brandOrigin = origin
        val cached = origin?.let(brands::cached)
        appearance.host = origin?.let { Uri.parse(it).host }.orEmpty()
        appearance.title = cached?.first ?: origin?.let { Uri.parse(it).host } ?: "艾卡云"
        appearance.icon = cached?.second
        appearance.bound = bound != null
    }

    private fun applyAuthorizedBrand(origin: String, brand: SiteBrand?) {
        if (brandOrigin != origin) restoreBrand(origin)
        activeBrand = brand
        discoveryDocument = -1L
        brand?.name?.let { appearance.title = it }
    }

    private fun cacheBrand(origin: String) {
        if (authorized && bound == origin && preferences.getString("origin", null) == origin && brandOrigin == origin) {
            brands.save(origin, appearance.title, appearance.icon)
        }
    }

    private fun updateHost(view: WebView, url: String, token: Long, origin: String) {
        if (token == viewId && web === view && authorized && OriginPolicy.normalize(url) == origin) {
            appearance.host = Uri.parse(url).host.orEmpty()
        }
    }

    private fun refreshDocumentAppearance(view: WebView, token: Long, origin: String, document: Long) {
        // Theme may follow each document; the site's name/icon may only come from its homepage.
        view.evaluateJavascript("""(()=>({dark:document.documentElement.classList.contains('dark')||document.documentElement.dataset.theme==='dark',color:Array.from(document.querySelectorAll('meta[name="theme-color"]')).find(m=>!m.media||matchMedia(m.media).matches)?.content||'',name:document.querySelector('meta[property="og:site_name"]')?.content||document.querySelector('meta[name="application-name"]')?.content||document.title,icon:document.querySelector('link[rel~="icon"]')?.href||''}))()""") { json ->
            if (token != viewId || web !== view || documentId != document || !authorized) return@evaluateJavascript
            val value = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@evaluateJavascript
            val color = runCatching { Color.parseColor(value.optString("color")) }.getOrNull()
            val dark = if (value.optBoolean("dark")) true else color?.let { androidx.core.graphics.ColorUtils.calculateLuminance(it) < 0.45 }
            appearance.dark = dark
            val resolvedDark = dark ?: isDark()
            val background = color?.takeIf { Color.alpha(it) == 255 && (androidx.core.graphics.ColorUtils.calculateLuminance(it) < 0.45) == resolvedDark }
                ?: if (resolvedDark) Color.rgb(16,22,34) else Color.rgb(248,249,251)
            window.decorView.setBackgroundColor(background)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                val light = !(dark ?: (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES))
                isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
            }
            if ((!homeMetadataPending && view.url != origin && view.url != brandHomeUrl) || discoveryDocument == document) return@evaluateJavascript
            brandHomeUrl = view.url
            homeMetadataPending = false
            discoveryDocument = document
            if (siteType == "external" && activeBrand?.name.isNullOrBlank()) value.optString("name").trim().takeIf { it.length in 1..160 }?.let { appearance.title = it }
            val iconUrl = activeBrand?.iconUrl ?: value.optString("icon").takeIf { it.isNotBlank() }
            cacheBrand(origin)
            if (iconUrl != null) {
                val generation = ++brandGeneration
                brandExecutor.submit {
                    val icon = brands.icon(origin, iconUrl)
                    handler.post {
                        if (generation == brandGeneration && token == viewId && web === view && document == documentId && authorized && brandOrigin == origin) {
                            if (icon != null) appearance.icon = icon
                            cacheBrand(origin)
                        }
                    }
                }
            }
        }
    }

    private fun unsupported() { Toast.makeText(this, "本阶段暂不支持此操作", Toast.LENGTH_SHORT).show() }

    private fun navigateBack() {
        if (appearance.menu) { appearance.menu = false; updateSampling(); return }
        val browser = web
        if (authorized && gate.visibility != View.VISIBLE && browser != null && browser.canGoBack()) browser.goBack()
        else finish()
    }

    override fun onDestroy() {
        foreground = false
        invalidateRequest()
        destroyWeb()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        brandExecutor.shutdownNow()
        glassSource.close()
        super.onDestroy()
    }

    private fun isDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun styleInput() {
        input.setTextColor(if (isDark()) Color.WHITE else Color.rgb(24,35,55))
        input.setHintTextColor(if (isDark()) Color.LTGRAY else Color.GRAY)
        input.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (isDark()) Color.rgb(37,46,61) else Color.rgb(239,243,249))
            setStroke(dp(1), if (isDark()) Color.rgb(65,77,98) else Color.rgb(222,229,240))
        }
        confirm.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(48,103,232),Color.rgb(83,84,220))).apply { cornerRadius = dp(16).toFloat() }
        confirm.setTextColor(Color.WHITE)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
