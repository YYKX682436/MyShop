package aikayun.muchen.shop

import android.annotation.SuppressLint
import android.app.Activity
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
open class ShopActivity : Activity() {
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
    private var web: WebView? = null
    private var documentUrl: String? = null
    private var documentId = 0L
    private var committed = false
    private var finished = false
    private var pageReady = false
    private var deadline: Runnable? = null
    private lateinit var root: FrameLayout
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
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        gate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        input = EditText(this).apply {
            id = R.id.origin_input
            hint = "商城网址"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(savedInstanceState?.getString("input") ?: "")
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        status = TextView(this).apply { id = R.id.status; textSize = 15f; setPadding(0, dp(12), 0, dp(12)) }
        confirm = Button(this).apply { id = R.id.confirm; text = "确认"; setOnClickListener { submit() } }
        gate.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        gate.addView(status, LinearLayout.LayoutParams(-1, -2))
        gate.addView(confirm, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(gate, FrameLayout.LayoutParams(-1, -1))
        showGate(if (bound == null) "" else "正在验证授权…", false)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { navigateBack() }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        // Only here, never also in onCreate/onResume. An unbound attempt is not a saved binding.
        bound?.let { checkAuthorization(it) }
    }

    override fun onStop() {
        foreground = false
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
        if (origin == null) { status.text = "请输入有效的 HTTPS 商城网址"; return }
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
                    Authorization.Allowed -> {
                        authorized = true
                        val existing = web
                        if (existing != null && bound != null) {
                            existing.onResume()
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
        pageReady = false
        val browser = WebView(this)
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
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                unsupported()
                return true
            }
        }
        browser.setDownloadListener { _, _, _, _, _ -> unsupported() }
        fun live(view: WebView) = token == viewId && web === view
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = interceptResource(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (!live(view) || !authorized || !foreground) return true
                val target = request.url.toString()
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
                documentUrl = url
                committed = false
                finished = false
                pageReady = false
                scheduleTimeout(token, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!live(view) || documentUrl != url || OriginPolicy.normalize(url) != origin) return
                committed = true
                finishIfReady(view, token, origin)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!live(view) || documentUrl != url || OriginPolicy.normalize(url) != origin) return
                finished = true
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
        root.addView(browser, 0, FrameLayout.LayoutParams(-1, -1))
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
                    if (!preferences.edit().putString("origin", origin).commit()) {
                        failPage("无法保存绑定，请重试", origin)
                        return
                    }
                    bound = origin
                    candidate = null
                }
                if (foreground && authorized) {
                    retryUrl = null
                    CookieManager.getInstance().flush()
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
        cancelTimeout()
        viewId++
        pageReady = false
        documentUrl = null
        web?.let {
            web = null
            root.removeView(it)
            it.stopLoading()
            it.onPause()
            it.destroy()
        }
    }

    private fun showGate(message: String, retry: Boolean) {
        gate.visibility = View.VISIBLE
        gate.bringToFront()
        web?.visibility = View.INVISIBLE
        input.visibility = if (bound == null) View.VISIBLE else View.GONE
        input.isEnabled = !querying && retry || (!querying && message.isEmpty())
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        confirm.text = if (bound != null || retry) "重试" else "确认"
        confirm.isEnabled = retry || message.isEmpty()
    }

    private fun revealWeb() {
        if (!foreground || !authorized) return
        gate.visibility = View.GONE
        web?.visibility = View.VISIBLE
    }

    private fun unsupported() { Toast.makeText(this, "本阶段暂不支持此操作", Toast.LENGTH_SHORT).show() }

    private fun navigateBack() {
        val browser = web
        if (authorized && gate.visibility != View.VISIBLE && browser != null && browser.canGoBack()) browser.goBack()
        else finish()
    }

    // API 33+ uses the platform dispatcher registered in onCreate; older devices use this callback.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy system back", ReplaceWith("navigateBack()"))
    override fun onBackPressed() { navigateBack() }

    override fun onDestroy() {
        foreground = false
        invalidateRequest()
        destroyWeb()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
