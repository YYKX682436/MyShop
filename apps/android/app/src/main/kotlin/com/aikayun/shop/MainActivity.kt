package com.aikayun.shop

import aikayun.muchen.shop.R
import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ContentValues
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ActionMode
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var model: ShopViewModel
    private lateinit var shopWebView: WebView
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingChooserCallback: ValueCallback<Array<Uri>>? = null
    private var chooserGeneration = 0
    private var activeChooserGeneration = 0
    private var chooserBusy = false
    private var queuedChooser: QueuedChooser? = null
    private var captureFile: File? = null
    private var captureUri: Uri? = null
    private var captureWasVideo = false
    private var pendingGeolocation: PendingGeolocation? = null
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private var mediaFallbackState by mutableStateOf<MediaFallbackState?>(null)

    private val singleVisualPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        finishVisualChoice(uri?.let(::listOf).orEmpty())
    }
    private val multipleVisualPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        finishVisualChoice(uris)
    }
    private val singleDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        finishDocumentChoice(uri?.let(::listOf).orEmpty())
    }
    private val multipleDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        finishDocumentChoice(uris)
    }
    private val cameraCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = captureFile
        val uri = captureUri
        captureFile = null
        captureUri = null
        val valid = result.resultCode == RESULT_OK && file != null && uri != null && file.isFile
            && file.length() in 1..(50L * 1024L * 1024L) && validateCapture(file, captureWasVideo)
        if (!valid) file?.delete()
        finishVisualChoice(if (valid && readable(uri!!)) listOf(uri) else emptyList())
    }
    private val mediaPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestInitialLocationPermission()
        if (mediaFallbackState != null) loadMediaFallbackItems()
    }
    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pending = pendingGeolocation
        pendingGeolocation = null
        if (pending != null) {
            val granted = model.trustedOrigin()?.let { NativeOrigin.isSameOrigin(pending.origin, it) } == true && hasLocationPermission()
            pending.callback.invoke(pending.origin, granted, false)
        }
    }
    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                fullScreenView != null -> hideCustomView()
                mediaFallbackState != null -> closeMediaFallback()
                model.ui.value.imageActionUrl != null -> model.dismissImageActions()
                model.ui.value.aboutOpen -> model.setAbout(false)
                model.ui.value.menuOpen -> model.setMenu(false)
                model.canGoBack() -> model.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        model = ViewModelProvider(this)[ShopViewModel::class.java]
        model.attachActivity(this)
        shopWebView = model.getOrCreateWebView(this)
        onBackPressedDispatcher.addCallback(this, backHandler)
        setContent { AikayunApp(model, this) }
    }

    override fun onStart() {
        super.onStart()
        model.refreshAuthorizationIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        (model.currentWebView() ?: shopWebView).onResume()
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        model.setTextSelectionOpen(true)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        model.setTextSelectionOpen(false)
        super.onActionModeFinished(mode)
    }

    override fun onPause() {
        model.flushCookies()
        (model.currentWebView() ?: shopWebView).onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pendingChooserCallback?.onReceiveValue(null)
        pendingChooserCallback = null
        queuedChooser = null
        captureFile?.delete()
        captureFile = null
        captureUri = null
        mediaFallbackState = null
        chooserBusy = false
        pendingGeolocation?.callback?.invoke(pendingGeolocation?.origin.orEmpty(), false, false)
        pendingGeolocation = null
        downloadExecutor.shutdownNow()
        model.detachActivity(this)
        super.onDestroy()
    }

    fun openExternalUrl(url: String) {
        if (!url.startsWith("https://", true)) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
    }

    fun openPaymentClient(rawUrl: String): Boolean {
        if (rawUrl.length !in 1..2048 || rawUrl.any { it.isISOControl() || it.isWhitespace() }) {
            Toast.makeText(this, "支付链接无效，订单仍保留", Toast.LENGTH_SHORT).show()
            return true
        }
        val parsed = runCatching {
            if (rawUrl.startsWith("intent:", true)) Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl))
        }.getOrNull()
        val data = parsed?.data
        val scheme = data?.scheme?.lowercase(Locale.ROOT)
        val expectedPackage = when (scheme) {
            "weixin" -> "com.tencent.mm"
            "alipay", "alipays" -> "com.eg.android.AlipayGphone"
            else -> null
        }
        val safeTarget = if (parsed != null && expectedPackage != null
            && (parsed.action == null || parsed.action == Intent.ACTION_VIEW)
            && parsed.component == null && parsed.selector == null
            && (!rawUrl.startsWith("intent:", true) || parsed.`package` == expectedPackage)) {
            Intent(Intent.ACTION_VIEW, data).setPackage(expectedPackage)
        } else null
        if (safeTarget == null || safeTarget.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "无法打开支付应用，订单仍保留", Toast.LENGTH_SHORT).show()
            return true
        }
        return try {
            startActivity(safeTarget)
            true
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开支付应用，订单仍保留", Toast.LENGTH_SHORT).show()
            true
        }
    }

    fun shareCurrentPage() {
        val url = model.currentPageUrl()
        if (url.isBlank()) return
        val title = model.currentPageTitle().ifBlank { url }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(intent, "分享页面"))
    }

    fun openCurrentPageInBrowser() = openExternalUrl(model.currentPageUrl())

    fun downloadHttps(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val parsed = NativeOrigin.parse(url) ?: return
        val bound = model.trustedOrigin()
        if (bound != null && NativeOrigin.isSameOrigin(parsed.value, bound)) {
            downloadExecutor.execute { saveBoundDownload(url, userAgent, contentDisposition, mimeType, bound) }
            return
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeDownloadFilename(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)))
            .setDescription("正在下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeDownloadFilename(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)))
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        runCatching { (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request) }
            .onFailure { Toast.makeText(this, "下载未能开始", Toast.LENGTH_SHORT).show() }
    }

    private fun saveBoundDownload(rawUrl: String, userAgent: String?, contentDisposition: String?, mimeType: String?, boundOrigin: String) {
        var current = runCatching { URI(rawUrl) }.getOrNull() ?: return
        var connection: HttpURLConnection? = null
        var destination: Uri? = null
        var complete = false
        try {
            repeat(6) { hop ->
                val canonical = NativeOrigin.parse(current.toString()) ?: throw IllegalArgumentException("invalid_redirect")
                if (!current.scheme.equals("https", true) || current.rawUserInfo != null) throw IllegalArgumentException("unsafe_redirect")
                val request = URL(current.toASCIIString()).openConnection() as HttpURLConnection
                connection = request
                request.connectTimeout = 15_000
                request.readTimeout = 30_000
                request.instanceFollowRedirects = false
                request.requestMethod = "GET"
                userAgent?.takeIf { it.length <= 512 }?.let { request.setRequestProperty("User-Agent", it) }
                if (NativeOrigin.isSameOrigin(canonical.value, boundOrigin)) {
                    CookieManager.getInstance().getCookie(current.toASCIIString())?.takeIf { it.isNotBlank() }
                        ?.let { request.setRequestProperty("Cookie", it) }
                }
                val status = request.responseCode
                if (status in 300..399) {
                    if (hop == 5) throw IllegalStateException("too_many_redirects")
                    val location = request.getHeaderField("Location") ?: throw IllegalStateException("missing_redirect")
                    current = current.resolve(location)
                    request.disconnect()
                    connection = null
                    return@repeat
                }
                if (status !in 200..299) throw IllegalStateException("download_failed")
                if (request.contentLengthLong > MAX_DOWNLOAD_BYTES) throw IllegalStateException("download_too_large")
                val name = safeDownloadFilename(android.webkit.URLUtil.guessFileName(current.toString(), request.getHeaderField("Content-Disposition") ?: contentDisposition, request.contentType ?: mimeType))
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, request.contentType?.substringBefore(';') ?: mimeType ?: "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/艾卡云")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("cannot_create_download")
                var total = 0L
                val output = resolver.openOutputStream(destination!!, "w") ?: throw IllegalStateException("cannot_write_download")
                request.inputStream.use { input -> output.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOWNLOAD_BYTES) throw IllegalStateException("download_too_large")
                        stream.write(buffer, 0, count)
                    }
                } }
                resolver.update(destination!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                complete = true
                runOnUiThread { Toast.makeText(this, "已保存到下载目录", Toast.LENGTH_SHORT).show() }
                return
            }
        } catch (_: Exception) {
            runOnUiThread { Toast.makeText(this, "下载失败或文件过大", Toast.LENGTH_SHORT).show() }
        } finally {
            connection?.disconnect()
            if (!complete) destination?.let { contentResolver.delete(it, null, null) }
        }
    }

    private fun safeDownloadFilename(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        .trim().trim('.').take(120).ifBlank { "download" }

    fun notifyFileActionResult(saved: Boolean, share: Boolean, image: Boolean) {
        val message = when {
            !saved -> "文件处理失败"
            share -> "正在打开分享面板"
            image -> "图片已保存"
            else -> "文件已保存到下载目录"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val accept = normalizeAcceptTypes(params.acceptTypes.orEmpty())
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val mediaOnly = accept.isNotEmpty() && accept.all { it.startsWith("image/") || it.startsWith("video/") }
        val request = FileChoice(accept, multiple, params.isCaptureEnabled, mediaOnly)
        pendingChooserCallback?.onReceiveValue(null)
        pendingChooserCallback = callback
        wasMultipleChoice = multiple
        chooserGeneration += 1
        val generation = chooserGeneration
        if (chooserBusy) {
            queuedChooser = QueuedChooser(generation, request)
            return true
        }
        launchFileChoice(generation, request)
        return true
    }

    private fun launchFileChoice(generation: Int, request: FileChoice) {
        activeChooserGeneration = generation
        chooserBusy = true
        try {
            if (request.capture && request.mediaOnly && request.accept.map { it.substringBefore('/') }.distinct().size == 1) {
                if (launchCapture(generation, request)) return
            }
            if (request.mediaOnly) {
                val type = when {
                    request.accept.any { it.startsWith("image/") } && request.accept.any { it.startsWith("video/") } -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    request.accept.any { it.startsWith("video/") } -> ActivityResultContracts.PickVisualMedia.VideoOnly
                    else -> ActivityResultContracts.PickVisualMedia.ImageOnly
                }
                val pickerRequest = PickVisualMediaRequest(type)
                if (request.multiple) multipleVisualPicker.launch(pickerRequest) else singleVisualPicker.launch(pickerRequest)
            } else {
                launchDocuments(request)
            }
        } catch (_: ActivityNotFoundException) {
            if (request.mediaOnly) {
                try { launchDocuments(request) } catch (_: Exception) { showMediaFallback(generation, request) }
            } else finishChoice(emptyList(), generation)
        } catch (_: Exception) {
            if (request.mediaOnly) showMediaFallback(generation, request) else finishChoice(emptyList(), generation)
        }
    }

    private fun launchCapture(generation: Int, request: FileChoice): Boolean {
        val isVideo = request.accept.any { it.startsWith("video/") }
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "capture").apply { mkdirs() }
        val file = File.createTempFile(if (isVideo) "video-" else "image-", if (isVideo) ".mp4" else ".jpg", directory)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(if (isVideo) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("capture", uri)
        }
        if (intent.resolveActivity(packageManager) == null) {
            file.delete()
            return false
        }
        captureFile = file
        captureUri = uri
        captureWasVideo = isVideo
        cameraCapture.launch(intent)
        return true
    }

    private fun launchDocuments(request: FileChoice) {
        val accepted = request.accept.filter { it != "*/*" }.ifEmpty { listOf("*/*") }.distinct()
        val types = Array<String>(accepted.size) { index -> accepted[index].toString() }
        if (request.multiple) multipleDocumentPicker.launch(types) else singleDocumentPicker.launch(types)
    }

    private fun normalizeAcceptTypes(raw: Array<out String>): List<String> = raw.asSequence()
        .flatMap { it.split(',').asSequence() }
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .map { value ->
            when (value) {
                ".jpg", ".jpeg" -> "image/jpeg"
                ".png" -> "image/png"
                ".webp" -> "image/webp"
                ".gif" -> "image/gif"
                ".svg" -> "image/svg+xml"
                ".mp4", ".m4v" -> "video/mp4"
                ".mov" -> "video/quicktime"
                ".pdf" -> "application/pdf"
                ".zip" -> "application/zip"
                else -> value
            }
        }
        .filter { it.contains('/') }
        .distinct()
        .toList()

    private fun finishVisualChoice(uris: List<Uri>) = finishChoice(uris, activeChooserGeneration)
    private fun finishDocumentChoice(uris: List<Uri>) = finishChoice(uris, activeChooserGeneration)

    fun currentMediaFallback(): MediaFallbackState? = mediaFallbackState

    fun closeMediaFallback() {
        val generation = activeChooserGeneration
        mediaFallbackState = null
        finishChoice(emptyList(), generation)
    }

    fun openMediaFallbackDocuments() {
        val state = mediaFallbackState ?: return
        mediaFallbackState = null
        try {
            launchDocuments(FileChoice(state.accept, state.multiple, false, true))
        } catch (_: Exception) {
            mediaFallbackState = state
        }
    }

    fun requestMediaAccessForFallback() {
        val permissions = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= 34) permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        mediaPermissionRequest.launch(permissions.toTypedArray())
    }

    fun selectMediaFallbackItem(item: MediaFallbackItem) {
        val state = mediaFallbackState ?: return
        if (!state.multiple) {
            finishChoice(listOf(item.uri), activeChooserGeneration)
            return
        }
        val key = item.uri.toString()
        val next = if (key in state.selected) state.selected - key
            else if (state.selected.size < MAX_FALLBACK_SELECTIONS) state.selected + key else state.selected
        mediaFallbackState = state.copy(selected = next)
    }

    fun finishMediaFallbackSelection() {
        val state = mediaFallbackState ?: return
        val selected = state.items.filter { it.uri.toString() in state.selected }.map { it.uri }
        finishChoice(selected, activeChooserGeneration)
    }

    private fun showMediaFallback(generation: Int, request: FileChoice) {
        activeChooserGeneration = generation
        mediaFallbackState = MediaFallbackState(request.accept, request.multiple, emptyList(), emptySet(), loading = true)
        loadMediaFallbackItems()
    }

    private fun loadMediaFallbackItems() {
        val state = mediaFallbackState ?: return
        val generation = activeChooserGeneration
        downloadExecutor.execute {
            val items = queryMediaFallback(state.accept)
            runOnUiThread {
                val latest = mediaFallbackState ?: return@runOnUiThread
                if (generation == activeChooserGeneration) mediaFallbackState = latest.copy(items = items, loading = false)
            }
        }
    }

    private fun queryMediaFallback(accept: List<String>): List<MediaFallbackItem> {
        val hasAccess = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ).any { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!hasAccess) return emptyList()
        val wantsImages = accept.isEmpty() || accept.any { it.startsWith("image/") }
        val wantsVideos = accept.isEmpty() || accept.any { it.startsWith("video/") }
        val items = ArrayList<MediaFallbackItem>()
        if (wantsImages) queryMediaCollection(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            "image",
            items
        )
        if (wantsVideos) queryMediaCollection(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            "video",
            items
        )
        return items.asSequence()
            .filter { item -> accept.isEmpty() || accept.any { type -> type == "*/*" || type.endsWith("/*") && item.mime.startsWith(type.substringBefore('/')) || type == item.mime } }
            .sortedByDescending { it.dateAdded }
            .take(MAX_FALLBACK_ITEMS)
            .toList()
    }

    private fun queryMediaCollection(
        collection: Uri,
        idColumn: String,
        nameColumn: String,
        mimeColumn: String,
        kind: String,
        output: MutableList<MediaFallbackItem>
    ) {
        val projection = arrayOf(idColumn, nameColumn, mimeColumn, MediaStore.MediaColumns.DATE_ADDED)
        val cursor: Cursor = contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC") ?: return
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(idColumn)
            val nameIndex = it.getColumnIndexOrThrow(nameColumn)
            val mimeIndex = it.getColumnIndexOrThrow(mimeColumn)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (it.moveToNext() && output.size < MAX_FALLBACK_ITEMS) {
                val uri = ContentUris.withAppendedId(collection, it.getLong(idIndex))
                output += MediaFallbackItem(uri, it.getString(nameIndex).orEmpty(), it.getString(mimeIndex).orEmpty(), kind, it.getLong(dateIndex))
            }
        }
    }

    private fun validateCapture(file: File, isVideo: Boolean): Boolean {
        val header = ByteArray(16)
        val count = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (count < 8) return false
        if (isVideo) return String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
        val png = header.take(8).map { it.toInt() and 0xFF } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
        val jpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
        val webp = count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        val heic = count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
        return png || jpeg || webp || heic
    }

    private fun finishChoice(uris: List<Uri>, generation: Int) {
        chooserBusy = false
        mediaFallbackState = null
        if (generation != chooserGeneration) {
            val queued = queuedChooser
            queuedChooser = null
            if (queued != null) launchFileChoice(queued.generation, queued.request)
            return
        }
        val accepted = uris.asSequence().filter(::readable).distinct().toList()
        val limited = if (pendingChooserCallback == null || accepted.isEmpty()) null
            else if (queuedChooser == null && !wasMultipleChoice) accepted.take(1).toTypedArray()
            else accepted.toTypedArray()
        pendingChooserCallback?.onReceiveValue(limited)
        pendingChooserCallback = null
    }

    private var wasMultipleChoice = false

    private fun readable(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { it.read() >= -1 } == true
    }.getOrDefault(false)

    private fun openSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    fun openPermissionSettings() = openSettings()

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestLocationForWeb(origin: String, callback: GeolocationPermissions.Callback) {
        val bound = model.trustedOrigin()
        if (bound == null || !NativeOrigin.isSameOrigin(origin, bound)) {
            callback.invoke(origin, false, false)
            return
        }
        if (hasLocationPermission()) {
            callback.invoke(origin, true, false)
            return
        }
        pendingGeolocation?.let { it.callback.invoke(it.origin, false, false) }
        pendingGeolocation = PendingGeolocation(origin, callback)
        locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    fun requestFirstPermissionsIfNeeded() {
        val flags = getSharedPreferences("aikayun.permissions", MODE_PRIVATE)
        if (!flags.getBoolean("mediaRequested", false)) {
            flags.edit().putBoolean("mediaRequested", true).apply()
            val permissions = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            mediaPermissionRequest.launch(permissions.toTypedArray())
        } else requestInitialLocationPermission()
    }

    private fun requestInitialLocationPermission() {
        val flags = getSharedPreferences("aikayun.permissions", MODE_PRIVATE)
        if (flags.getBoolean("locationRequested", false)) return
        flags.edit().putBoolean("locationRequested", true).apply()
        locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullScreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        val decor = window.decorView as ViewGroup
        fullScreenView = view
        fullScreenCallback = callback
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        model.setFullScreen(true)
    }

    fun hideCustomView() {
        val view = fullScreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullScreenView = null
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        model.setFullScreen(false)
    }

    fun createPinnedShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "当前桌面不支持固定快捷方式", Toast.LENGTH_SHORT).show()
            return
        }
        val state = model.ui.value
        val label = state.binding?.siteName?.take(30)?.ifBlank { "商城" } ?: "商城"
        val icon = state.brandIcon?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(this, R.drawable.ic_aikayun_mark)
        val shortcut = ShortcutInfoCompat.Builder(this, "aikayun-current-store")
            .setShortLabel(label)
            .setIcon(icon)
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        if (!ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)) Toast.makeText(this, "无法添加快捷方式", Toast.LENGTH_SHORT).show()
    }

    private data class FileChoice(val accept: List<String>, val multiple: Boolean, val capture: Boolean, val mediaOnly: Boolean)
    private data class QueuedChooser(val generation: Int, val request: FileChoice)
    private data class PendingGeolocation(val origin: String, val callback: GeolocationPermissions.Callback)

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 1024L * 1024L * 1024L
        private const val MAX_FALLBACK_ITEMS = 100
        private const val MAX_FALLBACK_SELECTIONS = 50
    }
}

data class MediaFallbackItem(val uri: Uri, val name: String, val mime: String, val kind: String, val dateAdded: Long)

data class MediaFallbackState(
    val accept: List<String>,
    val multiple: Boolean,
    val items: List<MediaFallbackItem>,
    val selected: Set<String>,
    val loading: Boolean
)
