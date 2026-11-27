package com.aikayun.shop

import android.app.Application
import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import aikayun.muchen.shop.BuildConfig
import aikayun.muchen.shop.R
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.UUID

enum class AppGate { INPUT, VERIFYING, FIRST_LOAD, ENTERING, CHECKING, READY, BLOCKED, TEMPORARY_ERROR, PAGE_ERROR }

data class SiteBinding(
    val origin: String,
    val siteType: String,
    val siteName: String,
    val faviconUrl: String
)

data class AppUiState(
    val gate: AppGate = AppGate.INPUT,
    val binding: SiteBinding? = null,
    val message: String? = null,
    val currentUrl: String = "",
    val pageTitle: String = "",
    val brandIcon: Bitmap? = null,
    val headerVisible: Boolean = true,
    val menuOpen: Boolean = false,
    val aboutOpen: Boolean = false,
    val desktopMode: Boolean = false,
    val firstEntry: Boolean = false,
    val fullScreen: Boolean = false,
    val webViewGeneration: Int = 0,
    val paymentDomain: String? = null,
    val paymentRecoveryUrl: String? = null,
    val imageActionUrl: String? = null,
    val nativeOverlayOpen: Boolean = false
)

class ShopViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("aikayun.binding", Context.MODE_PRIVATE)
    private val authority = AppShellAuthorityClient(BuildConfig.APP_AUTHORITY_ORIGIN)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityRef = WeakReference<MainActivity>(null)
    private var webView: WebView? = null
    private var installedBridgeOrigin: String? = null
    private var pendingFirstBinding: SiteBinding? = null
    private var lastTouchY = 0f
    private var lastTouchX = 0f
    private var dragStarted = false
    private var permissionPromptQueued = false
    private var activePaymentTarget: String? = null
    private var paymentTargetActivated = false
    private var paymentFlowStartedAt = 0L
    private var paymentReturnScript: androidx.webkit.ScriptHandler? = null
    private var firstLoadTimeout: Runnable? = null
    private var homeNavigationPending = false
    private var webOverlayOpen = false
    private var textSelectionOpen = false
    private val nativeTransfers = mutableMapOf<String, NativeTransfer>()
    private var defaultUserAgentMetadata: UserAgentMetadata? = null
    private val _ui = MutableStateFlow(loadInitialState(application))
    val ui: StateFlow<AppUiState> = _ui

    init {
        _ui.value.binding?.let { existing ->
            viewModelScope.launch { verifyExistingBinding(existing) }
        }
    }

    private fun loadInitialState(context: Context): AppUiState {
        val origin = prefs.getString(KEY_ORIGIN, null) ?: return AppUiState()
        val binding = SiteBinding(
            origin = origin,
            siteType = prefs.getString(KEY_TYPE, "myshop") ?: "myshop",
            siteName = prefs.getString(KEY_NAME, "") ?: "",
            faviconUrl = prefs.getString(KEY_FAVICON, "") ?: ""
        )
        val icon = runCatching { BitmapFactory.decodeFile(File(context.filesDir, BRAND_ICON_FILE).absolutePath) }.getOrNull()
        return AppUiState(
            gate = AppGate.CHECKING,
            binding = binding,
            brandIcon = icon,
            currentUrl = binding.origin,
            paymentRecoveryUrl = prefs.getString(KEY_PAYMENT_RECOVERY, null)
        )
    }

    fun attachActivity(activity: MainActivity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: MainActivity) {
        if (activityRef.get() === activity) activityRef.clear()
    }

    fun getOrCreateWebView(context: Context): WebView {
        webView?.let { return it }
        val themedContext = android.view.ContextThemeWrapper(getApplication(), R.style.Theme_Aikayun)
        val created = WebView(themedContext)
        created.setBackgroundColor(AndroidColor.TRANSPARENT)
        created.isVerticalScrollBarEnabled = false
        created.isHorizontalScrollBarEnabled = false
        created.overScrollMode = View.OVER_SCROLL_NEVER
        created.isFocusable = true
        created.isFocusableInTouchMode = true
        configureWebSettings(created)
        val desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        if (desktopMode) {
            val original = prefs.getString(KEY_MOBILE_UA, created.settings.userAgentString) ?: created.settings.userAgentString
            if (!prefs.contains(KEY_MOBILE_UA)) prefs.edit().putString(KEY_MOBILE_UA, original).apply()
            created.settings.userAgentString = toDesktopUserAgent(original)
            created.settings.loadWithOverviewMode = true
            applyUserAgentMetadata(created.settings, true)
            _ui.value = _ui.value.copy(desktopMode = true)
        }
        configureTouchObserver(created)
        configureClients(created)
        webView = created
        val binding = _ui.value.binding
        if (binding != null && _ui.value.gate == AppGate.READY) loadBoundHome(binding)
        return created
    }

    private fun configureWebSettings(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        WebView.setWebContentsDebuggingEnabled(false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            defaultUserAgentMetadata = WebSettingsCompat.getUserAgentMetadata(view.settings)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

    private fun configureTouchObserver(view: WebView) {
        val density = view.resources.displayMetrics.density
        view.setOnLongClickListener {
            val hit = view.hitTestResult
            if (hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val imageUrl = hit.extra
                val valid = imageUrl?.let { value ->
                    value.length <= 4096 && (value.startsWith("https://", true)
                        || value.startsWith("data:image/", true)
                        || value.startsWith("blob:", true))
                } == true
                if (valid) {
                    _ui.value = _ui.value.copy(imageActionUrl = imageUrl, headerVisible = true)
                    true
                } else false
            } else false
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.rawY
                    lastTouchX = event.rawX
                    dragStarted = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    if (!dragStarted && kotlin.math.abs(dy) > 10f * density && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f) {
                        dragStarted = true
                        showHeader()
                    }
                    if (dragStarted && kotlin.math.abs(dy) > 2f * density && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        if (dy < 0f) hideHeader() else showHeader()
                        lastTouchY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragStarted = false
            }
            false
        }
    }

    private fun configureClients(view: WebView) {
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val current = pendingFirstBinding ?: _ui.value.binding ?: return false
                val target = request.url.toString()
                if (NativeOrigin.isSameOrigin(target, current.origin)) {
                    if (paymentFlowStartedAt > 0L) {
                        _ui.value = _ui.value.copy(paymentDomain = null)
                    }
                    return false
                }
                val active = paymentFlowStartedAt > 0L && System.currentTimeMillis() - paymentFlowStartedAt < PAYMENT_FLOW_MAX_AGE_MS
                if (active && target.startsWith("https://", true) && request.method.equals("GET", true)) {
                    val requestedOrigin = NativeOrigin.parse(target)?.value
                    if (requestedOrigin != null && (NativeOrigin.isSameOrigin(requestedOrigin, activePaymentTarget.orEmpty()) || paymentTargetActivated)) {
                        paymentTargetActivated = true
                        _ui.value = _ui.value.copy(paymentDomain = request.url.host)
                        return false
                    }
                }
                if (active && target.substringBefore(':').lowercase() in setOf("weixin", "alipays", "alipay", "intent")) {
                    paymentTargetActivated = true
                    return activityRef.get()?.openPaymentClient(target) ?: true
                }
                if (pendingFirstBinding != null) {
                    cancelFirstLoadTimeout()
                    view.stopLoading()
                    pendingFirstBinding = null
                    _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "网站跳转到了 ${request.url.host ?: "其他域名"}，未更改绑定。请重新输入确认。")
                    return true
                }
                if (target.startsWith("https://", ignoreCase = true) && request.method.equals("GET", true)) {
                    activityRef.get()?.openExternalUrl(target)
                    return true
                }
                // Keep non-GET navigation intact; a POST must never be reconstructed as a GET.
                if (request.method.equals("GET", true)) return true
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                _ui.value = _ui.value.copy(currentUrl = url)
                val bound = _ui.value.binding?.origin
                if (bound != null && NativeOrigin.isSameOrigin(url, bound) && paymentFlowStartedAt > 0L) {
                    _ui.value = _ui.value.copy(paymentDomain = null)
                }
                val uri = runCatching { URI(url) }.getOrNull()
                if (uri?.scheme == "https" && bound != null && !NativeOrigin.isSameOrigin(url, bound) && paymentFlowStartedAt > 0L) {
                    paymentTargetActivated = true
                    _ui.value = _ui.value.copy(paymentDomain = uri.host)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                val requestId = System.nanoTime()
                view.postVisualStateCallback(requestId, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (pendingFirstBinding != null) {
                            if (!NativeOrigin.isSameOrigin(url, pendingFirstBinding!!.origin)) {
                                cancelFirstLoadTimeout()
                                pendingFirstBinding = null
                                _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "首页没有在绑定域名内打开。")
                                return
                            }
                            finishFirstPage(url)
                        } else if (_ui.value.binding != null && NativeOrigin.isSameOrigin(url, _ui.value.binding!!.origin)) {
                            if (paymentTargetActivated && paymentFlowStartedAt > 0L) clearPaymentFlow()
                            if (homeNavigationPending && URI(url).rawPath.orEmpty() in setOf("", "/")) {
                                view.clearHistory()
                                homeNavigationPending = false
                            }
                            _ui.value = _ui.value.copy(gate = AppGate.READY, currentUrl = url, message = null)
                        }
                    }
                })
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (pendingFirstBinding == null) _ui.value = _ui.value.copy(currentUrl = url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (pendingFirstBinding != null) {
                    cancelFirstLoadTimeout()
                    pendingFirstBinding = null
                    _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "无法打开该商城，请检查网址和网络后重试。")
                } else {
                    _ui.value = _ui.value.copy(gate = AppGate.PAGE_ERROR, message = "页面暂时无法连接。绑定和登录数据仍保留。")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500 && pendingFirstBinding != null) {
                    cancelFirstLoadTimeout()
                    pendingFirstBinding = null
                    _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "商城当前暂时无法访问，绑定尚未保存。")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                if (pendingFirstBinding != null) {
                    cancelFirstLoadTimeout()
                    pendingFirstBinding = null
                    _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "网站证书校验失败，绑定尚未保存。")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererError(view)
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                val binding = pendingFirstBinding ?: _ui.value.binding ?: return
                val resolved = title?.trim().orEmpty()
                if (resolved.isNotEmpty() && (binding.siteName.isBlank() || binding.siteType == "external")) {
                    updateBrandName(resolved)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                val binding = pendingFirstBinding ?: _ui.value.binding
                if (icon != null && binding != null && NativeOrigin.isSameOrigin(view.url.orEmpty(), binding.origin)) cacheBrandIcon(icon)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean = activityRef.get()?.showFileChooser(filePathCallback, fileChooserParams) ?: false

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val trusted = _ui.value.binding?.origin?.let { NativeOrigin.isSameOrigin(origin, it) } == true
                val activity = activityRef.get()
                if (trusted && activity != null) activity.requestLocationForWeb(origin, callback)
                else callback.invoke(origin, false, false)
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.deny()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                activityRef.get()?.showCustomView(view, callback) ?: callback.onCustomViewHidden()
            }

            override fun onHideCustomView() {
                activityRef.get()?.hideCustomView()
            }
        }

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("https://", ignoreCase = true)) {
                activityRef.get()?.downloadHttps(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    fun bindFromInput(raw: String) {
        if (_ui.value.gate == AppGate.VERIFYING || _ui.value.gate == AppGate.FIRST_LOAD) return
        val parsed = NativeOrigin.fromUserInput(raw)
        if (parsed == null) {
            _ui.value = _ui.value.copy(gate = AppGate.INPUT, message = "请输入有效的 HTTPS 商城网址。")
            return
        }
        _ui.value = _ui.value.copy(gate = AppGate.VERIFYING, message = null)
        viewModelScope.launch {
            when (val result = authority.resolve("${parsed.value}/")) {
                is AuthorityResult.Unavailable -> _ui.value = _ui.value.copy(gate = AppGate.INPUT, message = result.message)
                is AuthorityResult.Decision -> {
                    val decision = result.value
                    if (!decision.allowed || decision.canonicalOrigin != "${parsed.value}/" || decision.siteType == null) {
                        _ui.value = _ui.value.copy(gate = AppGate.INPUT, message = when (decision.reason) {
                            "site_unavailable" -> "该 MyShop 站点不存在或已停用。"
                            "unavailable" -> "App 授权服务当前不可用。"
                            else -> "此域名未获 App 授权。"
                        })
                    } else {
                        val brand = decision.brand
                        val pending = SiteBinding(
                            origin = parsed.value,
                            siteType = decision.siteType,
                            siteName = brand?.name?.trim().orEmpty(),
                            faviconUrl = brand?.faviconUrl.orEmpty()
                        )
                        pendingFirstBinding = pending
                        configureBridge(pending)
                        _ui.value = _ui.value.copy(gate = AppGate.FIRST_LOAD, message = null, binding = pending, firstEntry = true)
                        val target = webView
                        if (target == null) {
                            pendingFirstBinding = null
                            _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "网页视图尚未就绪，请重试。")
                        } else {
                            armFirstLoadTimeout(pending, target)
                            target.loadUrl("${pending.origin}/")
                        }
                    }
                }
            }
        }
    }

    private suspend fun verifyExistingBinding(binding: SiteBinding) {
        val lastAllowed = prefs.getBoolean(KEY_ALLOWED, false)
        _ui.value = _ui.value.copy(gate = AppGate.CHECKING, message = null)
        when (val result = authority.resolve("${binding.origin}/")) {
            is AuthorityResult.Unavailable -> {
                if (lastAllowed) bootExisting(binding, keepTrustedResult = true)
                else _ui.value = _ui.value.copy(gate = AppGate.TEMPORARY_ERROR, message = result.message)
            }
            is AuthorityResult.Decision -> {
                if (result.value.allowed && result.value.canonicalOrigin == "${binding.origin}/") {
                    prefs.edit().putBoolean(KEY_ALLOWED, true).putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
                    val brand = result.value.brand
                    val updated = if (brand != null && binding.siteType == "myshop") binding.copy(
                        siteName = brand.name.ifBlank { binding.siteName },
                        faviconUrl = brand.faviconUrl.ifBlank { binding.faviconUrl }
                    ) else binding
                    saveBinding(updated)
                    bootExisting(updated, keepTrustedResult = false)
                } else {
                    prefs.edit().putBoolean(KEY_ALLOWED, false).putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
                    _ui.value = _ui.value.copy(gate = AppGate.BLOCKED, binding = binding, message = "此商城的 App 授权已停用。重新启用后可恢复原绑定。")
                }
            }
        }
    }

    private fun bootExisting(binding: SiteBinding, keepTrustedResult: Boolean) {
        val view = webView
        _ui.value = _ui.value.copy(gate = AppGate.READY, binding = binding, message = null, headerVisible = true)
        if (view != null) {
            configureBridge(binding)
            if (view.url == null || view.url == "about:blank") view.loadUrl("${binding.origin}/")
        }
        if (keepTrustedResult) prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
    }

    fun refreshAuthorizationIfNeeded() {
        val binding = _ui.value.binding ?: return
        if (_ui.value.gate == AppGate.CHECKING || _ui.value.gate == AppGate.BLOCKED) return
        val lastCheck = prefs.getLong(KEY_CHECKED_AT, 0)
        val lastAllowed = prefs.getBoolean(KEY_ALLOWED, false)
        if (lastAllowed && System.currentTimeMillis() - lastCheck < AUTH_CACHE_MS) return
        viewModelScope.launch { verifyExistingBinding(binding) }
    }

    fun retryAuthorization() {
        val binding = _ui.value.binding ?: return
        viewModelScope.launch { verifyExistingBinding(binding) }
    }

    private fun finishFirstPage(url: String) {
        val binding = pendingFirstBinding ?: return
        cancelFirstLoadTimeout()
        val view = webView
        val pageTitle = view?.title?.trim().orEmpty()
        val persisted = binding.copy(siteName = binding.siteName.ifBlank { pageTitle.ifBlank { binding.origin.removePrefix("https://") } })
        pendingFirstBinding = null
        saveBinding(persisted)
        prefs.edit().putBoolean(KEY_ALLOWED, true).putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
        _ui.value = _ui.value.copy(
            gate = AppGate.ENTERING,
            binding = persisted,
            currentUrl = url,
            pageTitle = pageTitle,
            message = null,
            headerVisible = true,
            firstEntry = true
        )
    }

    fun onEntryAnimationFinished() {
        if (_ui.value.gate != AppGate.ENTERING) return
        _ui.value = _ui.value.copy(gate = AppGate.READY, firstEntry = false, headerVisible = true)
        permissionPromptQueued = true
        activityRef.get()?.requestFirstPermissionsIfNeeded()
    }

    fun takePermissionPromptRequest(): Boolean {
        if (!permissionPromptQueued) return false
        permissionPromptQueued = false
        return true
    }

    private fun saveBinding(binding: SiteBinding) {
        prefs.edit()
            .putString(KEY_ORIGIN, binding.origin)
            .putString(KEY_TYPE, binding.siteType)
            .putString(KEY_NAME, binding.siteName)
            .putString(KEY_FAVICON, binding.faviconUrl)
            .apply()
    }

    private fun armFirstLoadTimeout(binding: SiteBinding, view: WebView) {
        cancelFirstLoadTimeout()
        val timeout = Runnable {
            if (pendingFirstBinding === binding) {
                view.stopLoading()
                pendingFirstBinding = null
                _ui.value = _ui.value.copy(gate = AppGate.INPUT, binding = null, message = "商城首页加载超时，绑定尚未保存。请检查网络后重试。")
            }
        }
        firstLoadTimeout = timeout
        mainHandler.postDelayed(timeout, FIRST_LOAD_TIMEOUT_MS)
    }

    private fun cancelFirstLoadTimeout() {
        firstLoadTimeout?.let(mainHandler::removeCallbacks)
        firstLoadTimeout = null
    }

    fun updateBrandName(name: String) {
        val current = _ui.value.binding ?: pendingFirstBinding ?: return
        val binding = current.copy(siteName = name.take(120))
        pendingFirstBinding = if (pendingFirstBinding != null) binding else null
        if (_ui.value.binding != null) saveBinding(binding)
        _ui.value = _ui.value.copy(binding = if (_ui.value.binding != null) binding else _ui.value.binding, pageTitle = name.take(120))
    }

    fun cacheBrandIcon(bitmap: Bitmap) {
        runCatching {
            val file = File(getApplication<Application>().filesDir, BRAND_ICON_FILE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        _ui.value = _ui.value.copy(brandIcon = bitmap)
    }

    private fun configureBridge(binding: SiteBinding) {
        val view = webView ?: return
        if (installedBridgeOrigin == binding.origin) return
        if (installedBridgeOrigin != null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            _ui.value = _ui.value.copy(message = "当前系统 WebView 不支持安全网页通道。请更新 Android System WebView 后重试。")
            return
        }
        val exactRule = setOf(binding.origin)
        WebViewCompat.addWebMessageListener(view, JS_BRIDGE_NAME, exactRule) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, replyProxy ->
            val expected = _ui.value.binding?.origin ?: pendingFirstBinding?.origin
            val source = sourceOrigin.toString()
            if (!isMainFrame || expected == null || !NativeOrigin.isSameOrigin(source, expected)) return@addWebMessageListener
            val rawMessage = message.data
            if (rawMessage.isNullOrEmpty() || rawMessage.length > MAX_BRIDGE_MESSAGE_CHARS) return@addWebMessageListener
            val request = runCatching { JSONObject(rawMessage) }.getOrNull()
            val requestId = request?.optString("requestId").orEmpty()
            val type = request?.optString("type").orEmpty()
            if (requestId.isEmpty() || requestId.length > 80) return@addWebMessageListener
            if (type == "saveImageUrl") {
                val payload = request?.optJSONObject("payload")
                val imageUrl = payload?.optString("url").orEmpty()
                val share = payload?.optBoolean("share", false) ?: false
                viewModelScope.launch {
                    val downloaded = withContext(Dispatchers.IO) { downloadImageForUser(imageUrl, expected) }
                    val response = if (downloaded == null) {
                        transferError("image_download_failed")
                    } else {
                        val result = withContext(Dispatchers.Main) {
                            val saved = if (share) shareImageFile(downloaded.file, downloaded.filename, downloaded.mime)
                                else publishImageFile(downloaded.file, downloaded.filename, downloaded.mime)
                            downloaded.file.delete()
                            activityRef.get()?.notifyFileActionResult(saved, share, true)
                            saved
                        }
                        JSONObject().put("ok", result).put("error", if (result) JSONObject.NULL else "file_action_failed")
                    }
                    response.put("requestId", requestId)
                    replyProxy.postMessage(response.toString())
                }
                return@addWebMessageListener
            }
            val response = when (type) {
                "beginPayment" -> handleBeginPayment(request, expected)
                "getPlatform" -> JSONObject().put("ok", true).put("platform", "android").put("mobile", true)
                "saveBegin" -> beginNativeTransfer(request)
                "saveChunk" -> writeNativeTransferChunk(request)
                "saveFinish" -> finishNativeTransfer(request)
                "saveAbort" -> abortNativeTransfer(request)
                "setDesktopMode" -> {
                    val enabled = request?.optJSONObject("payload")?.optBoolean("enabled", false) ?: false
                    setDesktopMode(enabled)
                    JSONObject().put("ok", true)
                }
                "setOverlayOpen" -> {
                    val open = request?.optJSONObject("payload")?.optBoolean("open", false) ?: false
                    setWebOverlayOpen(open)
                    JSONObject().put("ok", true)
                }
                else -> JSONObject().put("ok", false).put("error", "unsupported_operation")
            }
            response.put("requestId", requestId)
            replyProxy.postMessage(response.toString())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val initialDesktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false)
            WebViewCompat.addDocumentStartJavaScript(
                view,
                JS_BRIDGE_SCRIPT.replace("__AIKAYUN_DESKTOP_MODE__", initialDesktopMode.toString()),
                exactRule
            )
        }
        installedBridgeOrigin = binding.origin
    }

    private fun handleBeginPayment(request: JSONObject?, boundOrigin: String): JSONObject {
        val binding = _ui.value.binding ?: pendingFirstBinding
        if (binding?.siteType != "myshop") return JSONObject().put("ok", false).put("error", "external_site_unsupported")
        val payload = request?.optJSONObject("payload") ?: return JSONObject().put("ok", false).put("error", "invalid_request")
        val requestId = request.optString("requestId")
        val target = payload.optString("targetUrl")
        val kind = payload.optString("kind")
        val recovery = payload.optString("recoveryUrl")
        if (target.length !in 1..2048 || kind !in setOf("redirect", "form_post", "open_in_client", "deep_link")) {
            return JSONObject().put("ok", false).put("error", "invalid_payment_request")
        }
        val uri = runCatching { URI(target) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "invalid_payment_target")
        if (uri.scheme?.lowercase() !in setOf("https", "weixin", "alipays", "alipay", "intent")
            || uri.rawUserInfo != null || !isAllowedPaymentTarget(target, uri)) {
            return JSONObject().put("ok", false).put("error", "blocked_payment_target")
        }
        val recoveryUri = runCatching { URI(recovery) }.getOrNull()
        if (recoveryUri == null || recoveryUri.scheme != "https" || !NativeOrigin.isSameOrigin(recovery, boundOrigin)) {
            return JSONObject().put("ok", false).put("error", "invalid_recovery_url")
        }
        activePaymentTarget = if (uri.scheme.equals("https", true)) NativeOrigin.parse(target)?.value
            else if (uri.scheme.equals("intent", true)) runCatching {
                android.content.Intent.parseUri(target, android.content.Intent.URI_INTENT_SCHEME).data?.scheme
            }.getOrNull()
            else uri.scheme?.lowercase()
        paymentTargetActivated = false
        paymentFlowStartedAt = System.currentTimeMillis()
        _ui.value = _ui.value.copy(paymentRecoveryUrl = recovery, paymentDomain = null)
        prefs.edit().putString(KEY_PAYMENT_REQUEST, requestId).putString(KEY_PAYMENT_RECOVERY, recovery).apply()
        val view = webView
        if (view != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            paymentReturnScript?.remove()
            val expectedOrigin = JSONObject.quote(boundOrigin)
            val recoveryPath = JSONObject.quote(recoveryUri.rawPath?.ifBlank { "/" } ?: "/")
            val script = """(() => { try { const u = new URL(location.href); if (u.origin === $expectedOrigin && u.pathname === $recoveryPath) { u.searchParams.set('paymentReturn','1'); history.replaceState(history.state, '', u.pathname + u.search + u.hash); } } catch (_) {} })();"""
            paymentReturnScript = WebViewCompat.addDocumentStartJavaScript(view, script, setOf(boundOrigin))
        }
        return JSONObject().put("ok", true).put("requestId", requestId)
    }

    private fun isAllowedPaymentTarget(target: String, uri: URI): Boolean {
        if (uri.scheme.equals("https", true)) return NativeOrigin.parse(target) != null
        if (uri.scheme.lowercase() in setOf("weixin", "alipay", "alipays")) return true
        if (!uri.scheme.equals("intent", true)) return false
        val parsed = runCatching {
            android.content.Intent.parseUri(target, android.content.Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return false
        val expectedPackage = when (parsed.data?.scheme?.lowercase()) {
            "weixin" -> "com.tencent.mm"
            "alipay", "alipays" -> "com.eg.android.AlipayGphone"
            else -> return false
        }
        return parsed.`package` == expectedPackage && parsed.component == null && parsed.selector == null
            && (parsed.action == null || parsed.action == android.content.Intent.ACTION_VIEW)
    }

    private fun beginNativeTransfer(request: JSONObject?): JSONObject {
        val binding = _ui.value.binding ?: pendingFirstBinding
        if (binding?.siteType != "myshop") return transferError("external_site_unsupported")
        val payload = request?.optJSONObject("payload") ?: return transferError("invalid_request")
        val id = payload.optString("transferId")
        val operation = payload.optString("operation")
        val mime = payload.optString("mime").substringBefore(';').trim().lowercase()
        val filename = normalizeTransferFilename(payload.optString("filename"), mime)
        val size = payload.optLong("size", -1)
        val limit = when (operation) {
            "saveText" -> MAX_TEXT_TRANSFER_BYTES
            "shareImage" -> MAX_IMAGE_TRANSFER_BYTES
            "saveBlob" -> MAX_BLOB_TRANSFER_BYTES
            else -> return transferError("unsupported_operation")
        }
        if (!id.matches(Regex("[A-Za-z0-9-]{1,80}")) || nativeTransfers.size >= 1 || size !in 0..limit
            || !isAllowedTransferMime(operation, mime)) return transferError("invalid_file")
        return try {
            val directory = File(getApplication<Application>().cacheDir, "native-transfers").apply { mkdirs() }
            val output = File(directory, "$id.part")
            nativeTransfers[id] = NativeTransfer(id, operation, filename, mime, size, output, FileOutputStream(output, false))
            JSONObject().put("ok", true).put("transferId", id)
        } catch (_: Exception) {
            nativeTransfers.remove(id)?.closeAndDelete()
            transferError("cannot_create_file")
        }
    }

    private fun writeNativeTransferChunk(request: JSONObject?): JSONObject {
        val payload = request?.optJSONObject("payload") ?: return transferError("invalid_request")
        val transfer = nativeTransfers[payload.optString("transferId")] ?: return transferError("transfer_not_found")
        val index = payload.optInt("index", -1)
        val encoded = payload.optString("data")
        if (index != transfer.nextIndex || encoded.length > MAX_ENCODED_CHUNK_CHARS) return failTransfer(transfer, "invalid_chunk")
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size > MAX_TRANSFER_CHUNK_BYTES || transfer.written + bytes.size > transfer.expectedSize) {
                return failTransfer(transfer, "invalid_chunk_size")
            }
            transfer.output.write(bytes)
            transfer.written += bytes.size
            transfer.nextIndex += 1
            JSONObject().put("ok", true).put("index", index)
        } catch (_: Exception) {
            failTransfer(transfer, "invalid_chunk")
        }
    }

    private fun finishNativeTransfer(request: JSONObject?): JSONObject {
        val payload = request?.optJSONObject("payload") ?: return transferError("invalid_request")
        val transfer = nativeTransfers.remove(payload.optString("transferId")) ?: return transferError("transfer_not_found")
        return try {
            transfer.output.flush()
            transfer.output.close()
            if (!transfer.file.isFile || transfer.file.length() != transfer.expectedSize
                || !validTransferContent(transfer.file, transfer.mime)) {
                transfer.file.delete()
                return transferError("file_validation_failed")
            }
            val saved = if (transfer.operation == "shareImage") shareNativeImage(transfer) else publishNativeFile(transfer)
            transfer.file.delete()
            activityRef.get()?.notifyFileActionResult(saved, transfer.operation == "shareImage", transfer.mime.startsWith("image/"))
            if (saved) JSONObject().put("ok", true) else transferError("file_action_failed")
        } catch (_: Exception) {
            transfer.closeAndDelete()
            transferError("file_action_failed")
        }
    }

    private fun abortNativeTransfer(request: JSONObject?): JSONObject {
        val id = request?.optJSONObject("payload")?.optString("transferId").orEmpty()
        nativeTransfers.remove(id)?.closeAndDelete()
        return JSONObject().put("ok", true)
    }

    private fun failTransfer(transfer: NativeTransfer, reason: String): JSONObject {
        nativeTransfers.remove(transfer.id)
        transfer.closeAndDelete()
        return transferError(reason)
    }

    private fun transferError(reason: String) = JSONObject().put("ok", false).put("error", reason)

    private fun isAllowedTransferMime(operation: String, mime: String): Boolean = when (operation) {
        "saveText" -> mime == "text/plain"
        "shareImage" -> mime in IMAGE_MIMES
        "saveBlob" -> mime == "text/plain" || mime == "application/pdf" || mime == "image/svg+xml" || mime in IMAGE_MIMES
        else -> false
    }

    private fun normalizeTransferFilename(value: String, mime: String? = null): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\\\"<>|\\u0000-\\u001f]"), "_").trim().take(100)
        val base = cleaned.ifBlank { "download" }
        if (Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(base)) return base
        val extension = when (mime?.substringBefore(';')?.lowercase()) {
            "text/plain" -> "txt"
            "application/pdf" -> "pdf"
            "image/svg+xml" -> "svg"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> null
        }
        return if (extension == null) base else "$base.$extension"
    }

    private fun validTransferContent(file: File, mime: String): Boolean = when {
        mime == "image/svg+xml" -> svgHeaderMatches(file)
        mime.startsWith("image/") -> imageSignatureMatches(file, mime)
        mime == "application/pdf" -> FileInputStream(file).use { input ->
            val header = ByteArray(5)
            input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-"
        }
        else -> true
    }

    private fun svgHeaderMatches(file: File): Boolean {
        val sample = ByteArray(4096)
        val count = FileInputStream(file).use { it.read(sample) }
        if (count <= 0) return false
        val header = String(sample, 0, count, Charsets.UTF_8).removePrefix("\uFEFF").trimStart().lowercase()
        val svgAt = header.indexOf("<svg")
        return svgAt in 0..512 && (header.startsWith("<svg") || header.startsWith("<?xml") || header.startsWith("<!--"))
    }

    private fun imageSignatureMatches(file: File, mime: String): Boolean {
        val header = ByteArray(16)
        val count = FileInputStream(file).use { it.read(header) }
        if (count < 4) return false
        return when (mime) {
            "image/jpeg" -> header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
            "image/png" -> header.take(8).map { it.toInt() and 0xFF } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
            "image/gif" -> count >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            "image/webp" -> count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            "image/avif" -> count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                && String(header, 8, 4, Charsets.US_ASCII) in setOf("avif", "avis", "mif1", "msf1")
            "image/heic", "image/heif" -> count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                && String(header, 8, 4, Charsets.US_ASCII) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
            else -> false
        }
    }

    private fun downloadImageForUser(rawUrl: String, boundOrigin: String): DownloadedImage? {
        if (rawUrl.length !in 1..4096) return null
        var current = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        var connection: HttpURLConnection? = null
        var outputFile: File? = null
        var keepImage = false
        try {
            repeat(6) { hop ->
                val canonical = NativeOrigin.parse(current.toString()) ?: return null
                if (!current.scheme.equals("https", true) || current.rawUserInfo != null || !isPublicNetworkHost(canonical.host)) return null
                connection = (URL(current.toASCIIString()).openConnection() as? HttpURLConnection) ?: return null
                val request = connection!!
                request.connectTimeout = 12_000
                request.readTimeout = 15_000
                request.instanceFollowRedirects = false
                request.requestMethod = "GET"
                request.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                if (NativeOrigin.isSameOrigin(canonical.value, boundOrigin)) {
                    CookieManager.getInstance().getCookie(current.toASCIIString())?.takeIf { it.isNotBlank() }
                        ?.let { request.setRequestProperty("Cookie", it) }
                }
                val status = request.responseCode
                if (status in 300..399) {
                    if (hop == 5) return null
                    val location = request.getHeaderField("Location") ?: return null
                    current = current.resolve(location)
                    request.disconnect()
                    connection = null
                    return@repeat
                }
                if (status !in 200..299) return null
                val mime = request.contentType?.substringBefore(';')?.trim()?.lowercase()
                    ?.takeIf { it in IMAGE_MIMES } ?: return null
                if (request.contentLengthLong > MAX_IMAGE_TRANSFER_BYTES) return null
                val filename = normalizeTransferFilename(current.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image", mime)
                val directory = File(getApplication<Application>().cacheDir, "native-image-downloads").apply { mkdirs() }
                outputFile = File(directory, "${UUID.randomUUID()}.part")
                var total = 0L
                request.inputStream.use { input ->
                    FileOutputStream(outputFile!!).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_IMAGE_TRANSFER_BYTES) throw IllegalStateException("image_too_large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (total == 0L || !imageSignatureMatches(outputFile!!, mime)) return null
                keepImage = true
                return DownloadedImage(outputFile!!, filename, mime)
            }
        } catch (_: Exception) {
            outputFile?.delete()
            return null
        } finally {
            connection?.disconnect()
            if (!keepImage) outputFile?.delete()
        }
        outputFile?.delete()
        return null
    }

    private fun isPublicNetworkHost(host: String): Boolean = runCatching {
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all { address ->
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
                || address.isSiteLocalAddress || address.isMulticastAddress) return@all false
            val bytes = address.address.map { it.toInt() and 0xFF }
            if (bytes.size == 4) {
                val first = bytes[0]
                val second = bytes[1]
                !(first == 0 || first >= 224 || (first == 100 && second in 64..127)
                    || (first == 198 && second in 18..19) || (first == 192 && second == 0))
            } else true
        }
    }.getOrDefault(false)

    private fun publishNativeFile(transfer: NativeTransfer): Boolean {
        return publishFile(transfer.file, transfer.filename, transfer.mime)
    }

    private fun publishImageFile(file: File, filename: String, mime: String): Boolean = publishFile(file, filename, mime)

    private fun publishFile(file: File, filename: String, mime: String): Boolean {
        val image = mime in IMAGE_MIMES
        val collection = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val path = if (image) "${Environment.DIRECTORY_PICTURES}/艾卡云" else "${Environment.DIRECTORY_DOWNLOADS}/艾卡云"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = getApplication<Application>().contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("No destination stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun shareNativeImage(transfer: NativeTransfer): Boolean {
        return shareImageFile(transfer.file, transfer.filename, transfer.mime)
    }

    private fun shareImageFile(file: File, filename: String, mime: String): Boolean {
        val activity = activityRef.get() ?: return false
        val directory = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
        val shared = File(directory, filename)
        file.copyTo(shared, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", shared)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("image", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(android.content.Intent.createChooser(intent, "分享图片"))
        return true
    }

    private fun loadBoundHome(binding: SiteBinding) {
        configureBridge(binding)
        if (webView?.url == null || webView?.url == "about:blank") webView?.loadUrl("${binding.origin}/")
    }

    fun reload() { webView?.reload() }
    fun setWebOverlayOpen(open: Boolean) { webOverlayOpen = open; updateNativeOverlayState() }
    fun setTextSelectionOpen(open: Boolean) { textSelectionOpen = open; updateNativeOverlayState() }
    private fun updateNativeOverlayState() {
        val open = webOverlayOpen || textSelectionOpen
        _ui.value = _ui.value.copy(nativeOverlayOpen = open, headerVisible = !open)
    }
    fun dismissImageActions() { _ui.value = _ui.value.copy(imageActionUrl = null) }
    fun savePageImage(url: String, share: Boolean) {
        val view = webView ?: return
        val encoded = JSONObject.quote(url)
        view.evaluateJavascript("window.__AIKAYUN_APP__ && window.__AIKAYUN_APP__.saveImageFromUrl($encoded,$share).catch(()=>{});", null)
        dismissImageActions()
    }
    fun goHome() {
        val binding = _ui.value.binding ?: return
        clearPaymentFlow()
        homeNavigationPending = true
        webView?.loadUrl("${binding.origin}/")
    }
    fun returnToPaymentRecovery() {
        val recovery = prefs.getString(KEY_PAYMENT_RECOVERY, null) ?: _ui.value.paymentRecoveryUrl ?: return
        val uri = Uri.parse(recovery)
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filterNot { it == "paymentReturn" }.forEach { name ->
            uri.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
        }
        val target = builder.appendQueryParameter("paymentReturn", "1").build().toString()
        clearPaymentFlow()
        _ui.value = _ui.value.copy(menuOpen = false)
        webView?.loadUrl(target)
    }

    private fun clearPaymentFlow() {
        paymentTargetActivated = false
        paymentFlowStartedAt = 0L
        activePaymentTarget = null
        paymentReturnScript?.remove()
        paymentReturnScript = null
        prefs.edit().remove(KEY_PAYMENT_REQUEST).remove(KEY_PAYMENT_RECOVERY).apply()
        _ui.value = _ui.value.copy(paymentDomain = null, paymentRecoveryUrl = null)
    }
    fun goBack() { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun canGoBack(): Boolean = webView?.canGoBack() == true
    fun flushCookies() { CookieManager.getInstance().flush() }

    fun setDesktopMode(enabled: Boolean) {
        val view = webView ?: return
        val original = prefs.getString(KEY_MOBILE_UA, view.settings.userAgentString) ?: view.settings.userAgentString
        if (!prefs.contains(KEY_MOBILE_UA)) prefs.edit().putString(KEY_MOBILE_UA, original).apply()
        view.settings.userAgentString = if (enabled) toDesktopUserAgent(original) else original
        view.settings.loadWithOverviewMode = enabled
        prefs.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
        applyUserAgentMetadata(view.settings, enabled)
        _ui.value = _ui.value.copy(desktopMode = enabled)
        view.evaluateJavascript("window.__AIKAYUN_APP__ && window.__AIKAYUN_APP__.applyViewportMode($enabled);", null)
        view.reload()
    }

    fun updateInsets(top: Float, bottom: Float, ime: Float) {
        val view = webView ?: return
        val safeTop = top.coerceIn(0f, 200f)
        val safeBottom = bottom.coerceIn(0f, 200f)
        val keyboard = ime.coerceIn(0f, 1200f)
        view.evaluateJavascript(
            "window.__AIKAYUN_APP__ && window.__AIKAYUN_APP__.applyInsets({top:$safeTop,bottom:$safeBottom,ime:$keyboard});",
            null
        )
    }

    private fun toDesktopUserAgent(original: String): String = original
        .replace(Regex("\\(Linux; Android [^)]*\\)"), "(Windows NT 10.0; Win64; x64)")
        .replace(Regex("; wv\\)"), ")")
        .replace(" Mobile", "")

    private fun applyUserAgentMetadata(settings: WebSettings, desktop: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val default = defaultUserAgentMetadata ?: return
        val builder = UserAgentMetadata.Builder(default)
            .setMobile(!desktop)
            .setPlatform(if (desktop) "Windows" else "Android")
            .setPlatformVersion(if (desktop) "10.0" else Build.VERSION.RELEASE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            builder.setFormFactors(listOf(if (desktop) UserAgentMetadata.FORM_FACTOR_DESKTOP else UserAgentMetadata.FORM_FACTOR_MOBILE))
        }
        WebSettingsCompat.setUserAgentMetadata(settings, builder.build())
    }

    fun setMenu(open: Boolean) {
        _ui.value = _ui.value.copy(menuOpen = open, headerVisible = true)
    }
    fun setAbout(open: Boolean) { _ui.value = _ui.value.copy(aboutOpen = open, menuOpen = false, headerVisible = true) }
    fun hideHeader() { if (!_ui.value.menuOpen && !_ui.value.aboutOpen && !_ui.value.nativeOverlayOpen) _ui.value = _ui.value.copy(headerVisible = false) }
    fun showHeader() { if (!_ui.value.nativeOverlayOpen) _ui.value = _ui.value.copy(headerVisible = true) }
    fun setFullScreen(isFullScreen: Boolean) { _ui.value = _ui.value.copy(fullScreen = isFullScreen) }

    fun rendererError(view: WebView) {
        if (webView === view) webView = null
        installedBridgeOrigin = null
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading(); view.destroy() }
        _ui.value = _ui.value.copy(gate = AppGate.PAGE_ERROR, message = "网页进程已结束。绑定和登录信息仍保留，可重新加载。", webViewGeneration = _ui.value.webViewGeneration + 1)
    }

    fun retryPage() {
        val binding = _ui.value.binding
        if (binding != null) {
            val target = webView ?: activityRef.get()?.let { getOrCreateWebView(it) }
            if (target != null) {
                _ui.value = _ui.value.copy(gate = AppGate.READY, message = null)
                loadBoundHome(binding)
            }
        } else _ui.value = _ui.value.copy(gate = AppGate.INPUT, message = null)
    }

    fun currentPageUrl(): String = webView?.url ?: _ui.value.currentUrl
    fun currentPageTitle(): String = webView?.title?.takeIf { it.isNotBlank() } ?: _ui.value.binding?.siteName.orEmpty()
    fun trustedOrigin(): String? = _ui.value.binding?.origin ?: pendingFirstBinding?.origin
    fun currentWebView(): WebView? = webView
    fun paymentTarget(): String? = activePaymentTarget

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        nativeTransfers.values.forEach { it.closeAndDelete() }
        nativeTransfers.clear()
        webView?.apply {
            stopLoading()
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        webView = null
        super.onCleared()
    }

    companion object {
        const val JS_BRIDGE_NAME = "aikayunNative"
        private const val AUTH_CACHE_MS = 120_000L
        private const val BRAND_ICON_FILE = "brand-icon.png"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_TYPE = "type"
        private const val KEY_NAME = "siteName"
        private const val KEY_FAVICON = "favicon"
        private const val KEY_ALLOWED = "authorityAllowed"
        private const val KEY_CHECKED_AT = "authorityCheckedAt"
        private const val KEY_PAYMENT_REQUEST = "paymentRequestId"
        private const val KEY_PAYMENT_RECOVERY = "paymentRecoveryUrl"
        private const val KEY_MOBILE_UA = "mobileUserAgent"
        private const val KEY_DESKTOP_MODE = "desktopMode"
        private const val MAX_TEXT_TRANSFER_BYTES = 5L * 1024L * 1024L
        private const val MAX_IMAGE_TRANSFER_BYTES = 10L * 1024L * 1024L
        private const val MAX_BLOB_TRANSFER_BYTES = 16L * 1024L * 1024L
        private const val MAX_TRANSFER_CHUNK_BYTES = 128 * 1024
        private const val MAX_ENCODED_CHUNK_CHARS = 180_000
        private const val MAX_BRIDGE_MESSAGE_CHARS = 200_000
        private const val PAYMENT_FLOW_MAX_AGE_MS = 20 * 60 * 1000L
        private const val FIRST_LOAD_TIMEOUT_MS = 30_000L
        private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heic", "image/heif")

        private val JS_BRIDGE_SCRIPT = """
            (() => {
              const native = window.aikayunNative;
              if (!native || typeof native.postMessage !== 'function') return;
              const initialDesktopMode = __AIKAYUN_DESKTOP_MODE__;
              const pending = new Map();
              const transferChunkSize = 128 * 1024;
              const ensureRoot = () => {
                const root = document.documentElement;
                if (!root) return false;
                root.setAttribute('data-aikayun-app', 'android');
                return true;
              };
              if (!ensureRoot()) {
                const observer = new MutationObserver(() => { if (ensureRoot()) observer.disconnect(); });
                observer.observe(document, { childList: true, subtree: true });
              }
              native.addEventListener('message', event => {
                try {
                  const data = JSON.parse(event.data);
                  const waiter = pending.get(data.requestId);
                  if (!waiter) return;
                  pending.delete(data.requestId);
                  clearTimeout(waiter.timer);
                  waiter.resolve(data);
                } catch (_) {}
              });
              const send = (type, payload = {}) => new Promise((resolve, reject) => {
                if (!['beginPayment', 'getPlatform', 'saveBegin', 'saveChunk', 'saveFinish', 'saveAbort', 'saveImageUrl', 'setDesktopMode', 'setOverlayOpen'].includes(type)) {
                  reject(new Error('Unsupported native operation'));
                  return;
                }
                if (pending.size >= 8) { reject(new Error('Too many native requests')); return; }
                const id = (globalThis.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
                const timer = setTimeout(() => { pending.delete(id); reject(new Error('Native request timed out')); }, 10000);
                pending.set(id, { resolve, reject, timer });
                native.postMessage(JSON.stringify({ requestId: id, type, payload }));
              });
              const encodeBase64 = bytes => {
                let binary = '';
                for (let offset = 0; offset < bytes.length; offset += 8192) {
                  binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 8192, bytes.length)));
                }
                return btoa(binary);
              };
              const transferBlob = async (operation, filename, blob) => {
                if (!(blob instanceof Blob) || blob.size > 16 * 1024 * 1024) throw new Error('File is too large');
                const transferId = (globalThis.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
                const started = await send('saveBegin', { transferId, operation, filename, mime: blob.type, size: blob.size });
                if (!started.ok) throw new Error(started.error || 'Unable to save file');
                try {
                  for (let offset = 0, index = 0; offset < blob.size; offset += transferChunkSize, index += 1) {
                    const bytes = new Uint8Array(await blob.slice(offset, Math.min(offset + transferChunkSize, blob.size)).arrayBuffer());
                    const result = await send('saveChunk', { transferId, index, data: encodeBase64(bytes) });
                    if (!result.ok) throw new Error(result.error || 'Unable to transfer file');
                  }
                  const finished = await send('saveFinish', { transferId });
                  if (!finished.ok) throw new Error(finished.error || 'Unable to finish file');
                  return finished;
                } catch (error) {
                  void send('saveAbort', { transferId }).catch(() => {});
                  throw error;
                }
              };
              const applyViewportMode = enabled => {
                const apply = () => {
                  if (!document.head) return false;
                  let meta = document.querySelector('meta[name="viewport"]');
                  if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; document.head.appendChild(meta); }
                  meta.content = enabled ? 'width=1280, initial-scale=1' : 'width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes';
                  document.documentElement.setAttribute('data-aikayun-desktop', enabled ? 'true' : 'false');
                  return true;
                };
                if (!apply()) document.addEventListener('DOMContentLoaded', apply, { once: true });
              };
              const applyInsets = insets => {
                const apply = () => {
                  if (!document.documentElement) return false;
                  ensureRoot();
                  document.documentElement.style.setProperty('--aikayun-safe-top', Math.max(0, Number(insets.top) || 0) + 'px');
                  document.documentElement.style.setProperty('--aikayun-safe-bottom', Math.max(0, Number(insets.bottom) || 0) + 'px');
                  document.documentElement.style.setProperty('--aikayun-ime-bottom', Math.max(0, Number(insets.ime) || 0) + 'px');
                  return true;
                };
                if (!apply()) document.addEventListener('DOMContentLoaded', apply, { once: true });
              };
              const saveImageFromUrl = async (url, share) => {
                const target = new URL(url, location.href);
                if (!['https:', 'blob:', 'data:'].includes(target.protocol)) throw new Error('Unsupported image URL');
                if (target.protocol === 'blob:' && target.origin !== location.origin) throw new Error('Image origin mismatch');
                if (target.protocol === 'https:') {
                  const result = await send('saveImageUrl', { url: target.href, share: !!share });
                  if (!result.ok) throw new Error(result.error || 'Image download failed');
                  return result;
                }
                const response = await fetch(target.href, { credentials: target.origin === location.origin ? 'include' : 'omit', cache: 'no-store' });
                if (!response.ok) throw new Error('Image download failed');
                const pathName = target.pathname.split('/').pop() || '';
                const filename = pathName.replace(/[\\/:*?"<>|]/g, '_').slice(0, 90) || 'image';
                return transferBlob(share ? 'shareImage' : 'saveBlob', filename, await response.blob());
              };
              Object.defineProperty(window, '__AIKAYUN_APP__', {
                configurable: false,
                value: Object.freeze({
                  platform: 'android', mobile: true, version: 1, send,
                  get desktopMode() { return document.documentElement?.dataset.aikayunDesktop === 'true'; },
                  beginPayment: payload => send('beginPayment', payload),
                  setDesktopMode: enabled => send('setDesktopMode', { enabled: !!enabled }),
                  setOverlayOpen: open => send('setOverlayOpen', { open: !!open }),
                  applyViewportMode, applyInsets, saveImageFromUrl
                })
              });
              applyViewportMode(initialDesktopMode);
            })();
        """.trimIndent()
    }

    private data class NativeTransfer(
        val id: String,
        val operation: String,
        val filename: String,
        val mime: String,
        val expectedSize: Long,
        val file: File,
        val output: FileOutputStream,
        var written: Long = 0,
        var nextIndex: Int = 0
    ) {
        fun closeAndDelete() {
            runCatching { output.close() }
            runCatching { file.delete() }
        }
    }

    private data class DownloadedImage(val file: File, val filename: String, val mime: String)
}
