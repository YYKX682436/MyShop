package aikayun.muchen.shop

import android.app.Activity
import android.content.Context
import android.test.InstrumentationTestCase
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class PhaseOneTest : InstrumentationTestCase() {
    private var current: ShopActivity? = null
    private val context get() = instrumentation.targetContext
    private fun ui(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun waitFor(message: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            var done = false
            ui { done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        fail(message)
    }
    private fun launch(): ShopActivity {
        // MIUI blocks app-originated background starts even for legacy instrumentation.
        val monitor = instrumentation.addMonitor(FixtureActivity::class.java.name, null, false)
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/${FixtureActivity::class.java.name}").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val activity = monitor.waitForActivityWithTimeout(5_000) as? ShopActivity
        instrumentation.removeMonitor(monitor)
        checkNotNull(activity) { "Fixture Activity did not start" }
        return activity.also { current = it }
    }
    private fun binding() = context.getSharedPreferences("binding", Context.MODE_PRIVATE).getString("origin", null)
    private fun submit(activity: ShopActivity, text: String) = ui {
        activity.findViewById<EditText>(R.id.origin_input).setText(text)
        activity.findViewById<Button>(R.id.confirm).performClick()
    }
    private fun visible(activity: Activity) = activity.findViewById<WebView>(R.id.browser)?.visibility == View.VISIBLE
    private fun status(activity: Activity) = activity.findViewById<TextView>(R.id.status)?.text?.toString() ?: ""
    private fun script(activity: Activity, js: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        ui { activity.findViewById<WebView>(R.id.browser).evaluateJavascript(js) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
    override fun setUp() {
        super.setUp()
        instrumentation.uiAutomation
        FixtureActivity.reset()
        context.getSharedPreferences("binding", Context.MODE_PRIVATE).edit().clear().commit()
    }
    override fun tearDown() {
        FixtureActivity.authWait?.countDown(); FixtureActivity.pageWait?.countDown()
        current?.let { ui { it.finish() } }
        instrumentation.waitForIdleSync()
        current = null
        super.tearDown()
    }
    fun testOriginAndStrictProtocol() {
        assertEquals("https://example.com/", OriginPolicy.normalize("EXAMPLE.com:443/a?q=1#x"))
        assertEquals("https://xn--bcher-kva.example/", OriginPolicy.normalize("https://bücher.example/a"))
        assertEquals("https://xn--fa-hia.example/", OriginPolicy.normalize("faß.example"))
        listOf("http://example.com", "https://u:p@example.com", "example.com:8443", "127.0.0.1", "0x7f.1", "localhost", "single", "https://a..example", "https://example.com\\@evil.test", "https://example.com%2eevil.test", "https://[::1]").forEach {
            assertNull("Must reject $it", OriginPolicy.normalize(it))
        }
        val origin = "https://allowed.fixture.test/"
        fun parse(data: String) = ResolveProtocol.parse("""{"code":0,"data":$data}""", origin)
        assertEquals(Authorization.Denied("unavailable"), parse("""{"schema_version":1,"allowed":false,"canonical_origin":"$origin","reason":"unavailable"}"""))
        assertEquals(Authorization.Allowed("external"), parse("""{"schema_version":1,"allowed":true,"canonical_origin":"$origin","site_type":"external"}"""))
        val invalid = listOf(
            """{"schema_version":2,"allowed":true,"canonical_origin":"$origin","site_type":"myshop"}""",
            """{"schema_version":1,"allowed":"true","canonical_origin":"$origin","site_type":"myshop"}""",
            """{"schema_version":1,"allowed":true,"canonical_origin":"https://evil.test/","site_type":"myshop"}""",
            """{"schema_version":1,"allowed":true,"canonical_origin":"$origin"}""",
            """{"schema_version":1.0,"allowed":true,"canonical_origin":"$origin","site_type":"myshop"}""",
            """{"schema_version":1,"allowed":true,"canonical_origin":"$origin","site_type":"unknown"}"""
        )
        invalid.forEach { body ->
            try { parse(body); fail("Malformed response allowed: $body") } catch (_: IllegalArgumentException) { }
        }
    }
    fun testFailedCandidateThenSuccessfulSiteAndRestart() {
        val activity = launch()
        FixtureActivity.failHost = "a.fixture.test"
        submit(activity, "a.fixture.test")
        waitFor("A must fail") { status(activity).contains("HTTP 503") }
        assertNull(binding())
        submit(activity, "b.fixture.test/some/path")
        waitFor("B must bind despite a failed image") { binding() == "https://b.fixture.test/" && visible(activity) }
        ui { activity.finish() }
        instrumentation.waitForIdleSync()
        val next = launch()
        waitFor("Restart must use B") { visible(next) }
        assertEquals("https://b.fixture.test/", FixtureActivity.checks.last())
        assertFalse(FixtureActivity.loads.last().contains("a.fixture.test"))
    }
    fun testExternalRuleCanChangeWithoutApkChange() {
        val activity = launch()
        submit(activity, "external.fixture.test")
        waitFor("External must initially be denied") { status(activity).contains("未获授权") }
        assertNull(binding())
        FixtureActivity.externalEnabled = true
        submit(activity, "external.fixture.test")
        waitFor("External should bind after central rule enables") { binding() == "https://external.fixture.test/" }
    }
    fun testRecheckPreservesDocumentHistoryAndStorage() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        script(activity, "document.getElementById('draft').value='retained'; localStorage.setItem('phase1','saved'); document.cookie='phase1_session=saved; max-age=3600; secure; samesite=lax'; 'ok'")
        ui { activity.findViewById<WebView>(R.id.browser).loadUrl("https://b.fixture.test/second") }
        waitFor("second") {
            val view = activity.findViewById<WebView>(R.id.browser)
            view.url?.endsWith("/second") == true && view.progress == 100 && FixtureActivity.loads.last().endsWith("/second")
        }
        script(activity, "document.getElementById('draft').value='retained'")
        assertEquals("\"retained\"", script(activity, "document.getElementById('draft').value"))
        val loadCount = FixtureActivity.loads.size
        val checks = FixtureActivity.checks.size
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        waitFor("resume") { visible(activity) && FixtureActivity.checks.size == checks + 1 }
        assertEquals(loadCount, FixtureActivity.loads.size)
        assertEquals("\"retained\"", script(activity, "document.getElementById('draft').value"))
        assertEquals("\"saved\"", script(activity, "localStorage.getItem('phase1')"))
        assertTrue(script(activity, "document.cookie").contains("phase1_session=saved"))
        ui { activity.onBackPressed() }
        waitFor("goBack root") { activity.findViewById<WebView>(R.id.browser).url == "https://b.fixture.test/" }
        assertEquals("https://b.fixture.test/", binding())
        ui { CookieManager.getInstance().flush(); activity.finish() }
        instrumentation.waitForIdleSync()
        val restarted = launch()
        waitFor("restart") { visible(restarted) }
        assertEquals("\"saved\"", script(restarted, "localStorage.getItem('phase1')"))
        assertTrue(script(restarted, "document.cookie").contains("phase1_session=saved"))
    }
    fun testDenialNetworkFailureAndRealRetry() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        FixtureActivity.result = Authorization.Denied("unavailable")
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        waitFor("revoke") { status(activity).contains("已停用") }
        assertNull(activity.findViewById<WebView>(R.id.browser))
        assertEquals("https://b.fixture.test/", binding())
        FixtureActivity.result = Authorization.Failed("无法连接授权服务，请检查网络后重试")
        ui { activity.findViewById<Button>(R.id.confirm).performClick() }
        waitFor("network distinct") { status(activity).contains("无法连接") }
        assertFalse(visible(activity))
        FixtureActivity.result = Authorization.Allowed()
        ui { activity.findViewById<Button>(R.id.confirm).performClick() }
        waitFor("recover") { visible(activity) }
        assertEquals("https://b.fixture.test/", binding())
    }
    fun testUnboundBackgroundAndLateAuthorizationCannotBind() {
        val activity = launch()
        val latch = CountDownLatch(1)
        FixtureActivity.authWait = latch
        submit(activity, "a.fixture.test")
        waitFor("request began") { FixtureActivity.checks.isNotEmpty() }
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        assertNull(binding())
        FixtureActivity.authWait = null
        submit(activity, "b.fixture.test")
        latch.countDown()
        waitFor("new B wins") { binding() == "https://b.fixture.test/" }
        assertEquals("https://b.fixture.test/", binding())
        assertTrue(FixtureActivity.loads.none { it.startsWith("https://a.fixture.test") })
    }
    fun testUnboundLoadingStopDoesNotSaveAndNoDuplicateWebView() {
        val activity = launch()
        val latch = CountDownLatch(1)
        FixtureActivity.pageWait = latch
        submit(activity, "a.fixture.test")
        waitFor("load began") { FixtureActivity.loads.isNotEmpty() }
        assertNull(binding())
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        assertNull(binding())
        assertNull(activity.findViewById<WebView>(R.id.browser))
        FixtureActivity.pageWait = null
        latch.countDown()
        submit(activity, "b.fixture.test")
        waitFor("B wins after page cancellation") { binding() == "https://b.fixture.test/" && visible(activity) }
    }

    private fun request(url: String, method: String = "GET", main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = true
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    fun testFirstCrossOriginRedirectAndLateCallbacksCannotBind() {
        val activity = launch()
        val latch = CountDownLatch(1)
        FixtureActivity.pageWait = latch
        submit(activity, "a.fixture.test")
        waitFor("load starts") { activity.findViewById<WebView>(R.id.browser) != null }
        ui {
            val old = activity.findViewById<WebView>(R.id.browser)
            val client = old.webViewClient
            assertTrue(client.shouldOverrideUrlLoading(old, request("https://external.fixture.test/")))
            client.onPageCommitVisible(old, "https://a.fixture.test/")
            client.onPageFinished(old, "https://a.fixture.test/")
        }
        assertNull(binding())
        assertTrue(status(activity).contains("首页跳转"))
        FixtureActivity.pageWait = null
        latch.countDown()
        submit(activity, "b.fixture.test")
        waitFor("B still binds") { binding() == "https://b.fixture.test/" }
    }
    fun testSameOriginSubresourcesAndCrossOriginPostPolicy() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        val loads = FixtureActivity.loads.size
        ui {
            val view = activity.findViewById<WebView>(R.id.browser)
            val client = view.webViewClient
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://b.fixture.test/second")))
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://cdn.external.test/image.png", main = false)))
            assertTrue(client.shouldOverrideUrlLoading(view, request("https://external.fixture.test/post", method = "POST")))
            assertTrue(client.shouldOverrideUrlLoading(view, request("weixin://test")))
        }
        assertEquals(loads, FixtureActivity.loads.size)
        assertTrue(visible(activity))
    }
    fun testRotationDuringFirstLoadDoesNotWriteBinding() {
        val activity = launch()
        val latch = CountDownLatch(1)
        FixtureActivity.pageWait = latch
        submit(activity, "a.fixture.test")
        waitFor("load started") { FixtureActivity.loads.isNotEmpty() }
        var first: WebView? = null
        ui {
            first = activity.findViewById(R.id.browser)
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        waitFor("rotated") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        assertNull(binding())
        ui { assertSame(first, activity.findViewById(R.id.browser)) }
        FixtureActivity.pageWait = null
        latch.countDown()
        waitFor("bind only after document finishes") { binding() == "https://a.fixture.test/" }
        ui { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    fun testDeniedStateCannotBeReleasedByOldPageCallbacksOrBack() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        var old: WebView? = null
        var client: android.webkit.WebViewClient? = null
        ui { old = activity.findViewById(R.id.browser); client = old!!.webViewClient }
        FixtureActivity.result = Authorization.Denied("unavailable")
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        waitFor("denied") { status(activity).contains("已停用") }
        ui {
            client!!.onPageCommitVisible(old!!, "https://b.fixture.test/")
            client!!.onPageFinished(old!!, "https://b.fixture.test/")
        }
        assertFalse(visible(activity))
        assertTrue(status(activity).contains("已停用"))
        ui { activity.onBackPressed() }
        waitFor("back leaves Activity") { activity.isFinishing }
        assertEquals("https://b.fixture.test/", binding())
    }

    fun testInputAndFailedUnboundPageRemainUsableAfterBackground() {
        val activity = launch()
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        ui { assertTrue(activity.findViewById<Button>(R.id.confirm).isEnabled) }
        submit(activity, "denied.fixture.test")
        waitFor("denied candidate") { status(activity).contains("未获授权") }
        ui { instrumentation.callActivityOnStop(activity); instrumentation.callActivityOnStart(activity) }
        ui { assertTrue(activity.findViewById<Button>(R.id.confirm).isEnabled) }
        submit(activity, "b.fixture.test")
        waitFor("can still bind B") { binding() == "https://b.fixture.test/" }
    }
    fun testLiveCentralAuthorityContract() {
        val client = AuthorityClient()
        assertTrue(client.resolve("https://mc.mc520.top/") is Authorization.Allowed)
        assertTrue(client.resolve("https://520.mc520.top/") is Authorization.Allowed)
        assertEquals(Authorization.Denied("not_authorized"), client.resolve("https://mc520.top.evil.example/"))
        assertEquals(Authorization.Denied("not_authorized"), client.resolve("https://example.com/"))
        assertEquals(Authorization.Denied("site_unavailable"), client.resolve("https://unregistered-phase1.mc520.top/"))
    }

    fun testFirstLoadDeadlineSurvivesFurtherNavigationAndRejectsLateCommit() {
        val activity = launch()
        val latch = CountDownLatch(1)
        FixtureActivity.pageWait = latch
        submit(activity, "a.fixture.test")
        waitFor("load started") { FixtureActivity.loads.isNotEmpty() }
        var old: WebView? = null
        var client: android.webkit.WebViewClient? = null
        ui { old = activity.findViewById(R.id.browser); client = old!!.webViewClient }
        Thread.sleep(15_000)
        ui { client!!.onPageStarted(old!!, "https://a.fixture.test/redirected", null) }
        waitFor("first navigation cannot renew binding deadline", 20_000) { status(activity).contains("超时") }
        assertNull(binding())
        ui {
            client!!.onPageCommitVisible(old!!, "https://a.fixture.test/redirected")
            client!!.onPageFinished(old!!, "https://a.fixture.test/redirected")
        }
        assertNull(binding())
        FixtureActivity.pageWait = null
        latch.countDown()
        submit(activity, "b.fixture.test")
        waitFor("retry B after timeout") { binding() == "https://b.fixture.test/" }
    }
    fun testGlassCaptureMatchesLiveWebViewAndStopsWhenStill() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        val field = ShopActivity::class.java.getDeclaredField("glassSource").apply { isAccessible = true }
        val source = field.get(activity) as WebGlassSource
        script(activity, "window.scrollTo(0,document.querySelector('.color').offsetTop+50); 'ok'")
        Thread.sleep(1000)
        ui {
            val bmp = source.frame?.bitmap
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("probe", "captures=${source.captures} size=${bmp?.width}x${bmp?.height} pixel=${bmp?.getPixel(10,10)} scroll=${activity.findViewById<WebView>(R.id.browser).scrollY}") })
            bmp?.let { java.io.File(context.getExternalFilesDir(null), "glass-source.png").outputStream().use { out -> it.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out) } }
        }
        waitFor("capture must contain actual red WebView pixels") {
            source.frame?.bitmap?.let { android.graphics.Color.red(it.getPixel(10,10)) > 210 && android.graphics.Color.green(it.getPixel(10,10)) in 60..170 } == true
        }
        Thread.sleep(500)
        val before = source.captures
        Thread.sleep(1000)
        assertTrue("Static page must not drive a snapshot loop: ${source.captures - before}", source.captures - before <= 1)
        val appearanceField = ShopActivity::class.java.getDeclaredField("appearance").apply { isAccessible = true }
        val appearance = appearanceField.get(activity) as ShellAppearance
        ui { appearance.menu = true }
        waitFor("menu must expand the sampled strip") { (source.frame?.height ?: 0) > 1000 }
        Thread.sleep(500)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(context.getExternalFilesDir(null), "glass-probe.png")
        file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        source.frame!!.bitmap.let { bmp -> java.io.File(context.getExternalFilesDir(null), "glass-source.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        ui { appearance.menu = false }
    }

    fun testInvalidInputIsVisibleAndEditable() {
        val activity = launch()
        submit(activity, "http://invalid.example")
        waitFor("visible invalid error") { activity.findViewById<TextView>(R.id.status)?.visibility == View.VISIBLE && status(activity).contains("HTTPS") }
        ui { assertTrue(activity.findViewById<EditText>(R.id.origin_input).isEnabled); assertTrue(activity.findViewById<Button>(R.id.confirm).isEnabled) }
        assertNull(binding())
        submit(activity, "b.fixture.test")
        waitFor("valid retry") { visible(activity) && binding() == "https://b.fixture.test/" }
    }

    fun testPersistedBindingWinsBeforeVisualCommitAndOnStart() {
        val activity = launch()
        val latch = CountDownLatch(1)
        FixtureActivity.pageWait = latch
        submit(activity, "a.fixture.test")
        waitFor("candidate A loads") { FixtureActivity.loads.isNotEmpty() }
        context.getSharedPreferences("binding", Context.MODE_PRIVATE).edit().putString("origin", "https://b.fixture.test/").commit()
        FixtureActivity.pageWait = null; latch.countDown()
        waitFor("persisted B must win") { visible(activity) && activity.findViewById<WebView>(R.id.browser)?.url == "https://b.fixture.test/" }
        assertEquals("https://b.fixture.test/", binding())
        ui { instrumentation.callActivityOnStop(activity) }
        context.getSharedPreferences("binding", Context.MODE_PRIVATE).edit().putString("origin", "https://c.fixture.test/").commit()
        ui { instrumentation.callActivityOnStart(activity) }
        waitFor("onStart must synchronize saved C") { visible(activity) && activity.findViewById<WebView>(R.id.browser)?.url == "https://c.fixture.test/" }
        assertEquals("https://c.fixture.test/", binding())
    }

    private fun action(activity: ShopActivity, item: Int) {
        waitFor("native menu becomes available") { ShopActivity::class.java.getDeclaredField("pageReady").apply { isAccessible=true }.getBoolean(activity) }
        ui { ShopActivity::class.java.getDeclaredMethod("menuAction", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, item) }
    }

    fun testDesktopViewportRoundTripHistoryStorageAndClipboard() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        script(activity, "localStorage.setItem('mode','preserved');document.cookie='mode=preserved;secure;samesite=lax';document.querySelector('a').href='/second?q=ok#anchor'")
        Thread.sleep(500) // wait for the first-entry transition's blocking overlay to leave
        val rect = org.json.JSONArray(script(activity, "(()=>{const r=document.querySelector('a').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()"))
        var x = 0f; var y = 0f
        ui {
            val view = activity.findViewById<WebView>(R.id.browser)
            val location = IntArray(2); view.getLocationOnScreen(location)
            val scale = view.width / rect.getDouble(2)
            x = (location[0] + rect.getDouble(0) * scale).toFloat(); y = (location[1] + rect.getDouble(1) * scale).toFloat()
        }
        val time = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,x,y,0))
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time,time+80,android.view.MotionEvent.ACTION_UP,x,y,0))
        waitFor("GET second") { activity.findViewById<WebView>(R.id.browser)?.url?.contains("/second?q=ok#anchor") == true && activity.findViewById<WebView>(R.id.browser).progress == 100 }
        Thread.sleep(300)
        val before = FixtureActivity.loads.size
        var browser: WebView? = null
        ui { browser = activity.findViewById(R.id.browser) }
        action(activity, 6)
        waitFor("one reload") { FixtureActivity.loads.size == before + 1 && browser!!.progress == 100 }
        assertTrue(script(activity, "document.documentElement.clientWidth").toInt() in 1264..1296)
        waitFor("desktop visual commit") { ShopActivity::class.java.getDeclaredField("pageReady").apply{isAccessible=true}.getBoolean(activity) }
        Thread.sleep(300)
        val desktopGeometry=script(activity,"JSON.stringify({client:document.documentElement.clientWidth,width:visualViewport.width,scale:visualViewport.scale,meta:document.querySelector('meta[name=viewport]').content})")
        instrumentation.sendStatus(0,android.os.Bundle().apply{putString("desktopGeometry",desktopGeometry)})
        assertTrue("desktop must actually fit, not merely report a wide layout: $desktopGeometry", script(activity, "visualViewport.width").toDouble() in 1240.0..1320.0)
        assertEquals("true", script(activity, "matchMedia('(min-width: 1024px)').matches"))
        assertEquals("false", script(activity, "navigator.userAgentData.mobile"))
        assertEquals("true", script(activity, "navigator.userAgent.includes('AikayunShop/') && !navigator.userAgent.includes(' Mobile')"))
        assertTrue(script(activity, "document.cookie").contains("mode=preserved"))
        assertEquals("\"preserved\"", script(activity, "localStorage.getItem('mode')"))
        waitFor("history becomes available after reload commits") { browser!!.canGoBack() }
        ui { assertSame(browser, activity.findViewById(R.id.browser)); assertTrue("current URL preserved: ${browser!!.url}", browser!!.url!!.endsWith("/second?q=ok#anchor")); assertTrue("history preserved: ${browser!!.copyBackForwardList().size}", browser!!.canGoBack()) }
        action(activity, 5)
        ui { assertEquals("https://b.fixture.test/second?q=ok#anchor", (activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString()) }
        action(activity, 6)
        waitFor("mobile reload") { FixtureActivity.loads.size == before + 2 && browser!!.progress == 100 }
        assertTrue(script(activity, "document.documentElement.clientWidth").toInt() < 600)
        assertEquals("true", script(activity, "navigator.userAgentData.mobile"))
        assertEquals("true", script(activity, "window.__AIKAYUN_DISPLAY__.desktop===false"))
        action(activity, 2)
        waitFor("homepage returns and clears earlier history only on success") { browser!!.url == "https://b.fixture.test/" && browser!!.progress == 100 && !browser!!.canGoBack() }
        val count = FixtureActivity.loads.size
        action(activity, 2)
        Thread.sleep(200)
        assertEquals(count, FixtureActivity.loads.size)
    }

    fun testOptionalBrandCannotInvalidateValidAuthorization() {
        val origin = "https://b.fixture.test/"
        val malformed = ResolveProtocol.parse("""{"code":0,"data":{"schema_version":1,"allowed":true,"canonical_origin":"$origin","site_type":"myshop","brand":{"site_name":42,"favicon_url":[]}}}""", origin)
        assertEquals(Authorization.Allowed("myshop", SiteBrand()), malformed)
        val activity = launch()
        FixtureActivity.result = Authorization.Allowed("external")
        submit(activity, "b.fixture.test")
        waitFor("external brand fallback") { visible(activity) }
        val field = ShopActivity::class.java.getDeclaredField("appearance").apply { isAccessible = true }
        val state = field.get(activity) as ShellAppearance
        waitFor("homepage title and SVG favicon") { state.title == "Fixture Store" && state.icon != null }
        script(activity, "location.href='/second'")
        waitFor("second") { activity.findViewById<WebView>(R.id.browser)?.url?.endsWith("/second") == true }
        script(activity, "document.title='Product name'")
        assertEquals("Fixture Store", state.title)
    }

    fun testPostModeSwitchDoesNotResendAndDesktopPersistsAcrossRestartRotation() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        ui { activity.findViewById<WebView>(R.id.browser).postUrl("https://b.fixture.test/posted", "draft=retained".toByteArray()) }
        waitFor("POST document ready") { activity.findViewById<WebView>(R.id.browser)?.url?.endsWith("/posted") == true && activity.findViewById<WebView>(R.id.browser).progress == 100 }
        Thread.sleep(200)
        val count = FixtureActivity.loads.size
        action(activity, 6)
        Thread.sleep(300)
        assertEquals(count, FixtureActivity.loads.size)
        assertFalse(context.getSharedPreferences("binding", Context.MODE_PRIVATE).getBoolean("desktop:https://b.fixture.test/", false))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui { activity.findViewById<WebView>(R.id.browser).loadUrl("https://b.fixture.test/second") }
        waitFor("GET ready") { activity.findViewById<WebView>(R.id.browser)?.url?.endsWith("/second") == true && activity.findViewById<WebView>(R.id.browser).progress == 100 }
        Thread.sleep(200)
        action(activity, 6)
        waitFor("desktop saved") { context.getSharedPreferences("binding", Context.MODE_PRIVATE).getBoolean("desktop:https://b.fixture.test/", false) }
        var browser: WebView? = null
        ui { browser = activity.findViewById(R.id.browser); activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor("landscape") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        Thread.sleep(400)
        assertTrue("desktop layout survives rotation",script(activity,"document.documentElement.clientWidth").toInt() in 1264..1296)
        assertTrue("desktop overview survives rotation",script(activity,"visualViewport.width").toDouble() in 1240.0..1320.0)
        ui { assertSame(browser, activity.findViewById(R.id.browser)); activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor("portrait") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        ui { activity.finish() }
        instrumentation.waitForIdleSync()
        val restarted = launch()
        waitFor("desktop restart") { visible(restarted) }
        assertTrue(script(restarted, "document.documentElement.clientWidth").toInt() in 1264..1296)
        assertTrue(script(restarted, "navigator.userAgent").contains("AikayunShop/"))
    }

    fun testLivePngAndDefaultSvgDecodeAreBounded() {
        val brands = SiteBrands(context.getSharedPreferences("decode-test", Context.MODE_PRIVATE))
        val svg = brands.icon("https://mc.mc520.top/", "/default-icon.svg")
        val png = brands.icon("https://mc.mc520.top/", "/images/telegram.png")
        assertNotNull("production default SVG", svg)
        assertNotNull("production PNG", png)
        assertEquals(96, svg!!.width); assertEquals(96, png!!.width)
        assertNull(brands.icon("https://mc.mc520.top/", "/missing-phase2-icon.png"))
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }

    fun testNativeInputRealImePreservesTextAndInsets() {
        val activity = launch()
        waitFor("input mounted") { activity.findViewById<EditText>(R.id.origin_input) != null }
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        Thread.sleep(600) // allow the system splash and first Compose frame to finish
        screenshot(if(dark) "input-dark.png" else "input-light.png")
        ui {
            val input = activity.findViewById<EditText>(R.id.origin_input)
            input.setText("unfinished.example");input.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        waitFor("real input method visible") { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        Thread.sleep(300)
        screenshot(if(dark) "input-keyboard-dark.png" else "input-keyboard.png")
        ui {
            val button = activity.findViewById<Button>(R.id.confirm)
            val rect = android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(rect))
            val bottom = activity.window.decorView.height - activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            assertTrue("confirm above keyboard", rect.bottom <= bottom + 1)
            val input = activity.findViewById<EditText>(R.id.origin_input)
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        }
        waitFor("IME closes") { !activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
        ui { assertEquals("unfinished.example", activity.findViewById<EditText>(R.id.origin_input).text.toString()) }
    }

    private fun gesture(x: Float, from: Float, to: Float) {
        val start = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(start,start,android.view.MotionEvent.ACTION_DOWN,x,from,0))
        for(i in 1..10) {
            Thread.sleep(25)
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,x,from+(to-from)*i/10,0))
        }
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,to,0))
    }

    fun testInternalScrollHidesAndRecallsWithoutReloadAndProgrammaticScrollIsIgnored() {
        val activity = launch()
        submit(activity, "b.fixture.test")
        waitFor("bind") { visible(activity) }
        Thread.sleep(500)
        val state = ShopActivity::class.java.getDeclaredField("appearance").apply { isAccessible=true }.get(activity) as ShellAppearance
        var browser: WebView?=null;var width=0f
        ui { browser=activity.findViewById(R.id.browser); width=browser!!.width.toFloat() }
        script(activity,"document.activeElement?.blur();document.querySelector('.internal').scrollIntoView({block:'center'})")
        Thread.sleep(200)
        val r=org.json.JSONArray(script(activity,"(()=>{const r=document.querySelector('.internal').getBoundingClientRect();return [r.top,r.bottom,innerWidth,visualViewport.height,visualViewport.offsetTop]})()"))
        val scale=width/r.getDouble(2)
        val bottom=minOf(r.getDouble(1)-30,r.getDouble(3)+r.getDouble(4)-40)
        val top=maxOf(r.getDouble(0)+30,bottom-180)
        val x=width/2;val from=(bottom*scale).toFloat();val to=(top*scale).toFloat()
        script(activity,"window.touchProbe=[];addEventListener('touchstart',e=>touchProbe.push({y:e.touches[0].clientY,x:e.touches[0].clientX,target:e.target.outerHTML.slice(0,120)}),{passive:true})")
        val beforeGeometry=script(activity,"JSON.stringify({r:document.querySelector('.internal').getBoundingClientRect().toJSON(),height:document.querySelector('.internal').scrollHeight,client:document.querySelector('.internal').clientHeight,vw:visualViewport.width,vh:visualViewport.height,scale:visualViewport.scale,scrollY:scrollY})")
        instrumentation.sendStatus(0,android.os.Bundle().apply{putString("geometry","$beforeGeometry from=$from to=$to")})
        gesture(x,from,to)
        waitFor("internal user scroll hides toolbar") { !state.toolbar }
        Thread.sleep(300) // compositor scroll updates follow the observed input events
        val innerScroll=script(activity,"document.querySelector('.internal').scrollTop").toDouble()
        instrumentation.sendStatus(0,android.os.Bundle().apply{putString("scroll","inner=$innerScroll main="+script(activity,"scrollY")+" touches="+script(activity,"JSON.stringify(touchProbe)"))})
        assertTrue("inner container actually scrolled",innerScroll>0)
        gesture(x,to,from)
        waitFor("reverse gesture recalls toolbar") { state.toolbar }
        val count=FixtureActivity.loads.size
        script(activity,"scrollTo(0,document.querySelector('.color').offsetTop+50)")
        Thread.sleep(200)
        assertTrue(state.toolbar)
        repeat(3) { ui{state.menu=true};Thread.sleep(180);ui{state.menu=false};Thread.sleep(180) }
        ui { assertSame(browser,activity.findViewById(R.id.browser)) }
        assertEquals(count,FixtureActivity.loads.size)
    }

    fun testExternalHomepageMetadataFollowsSameOriginLanding() {
        val activity = launch()
        FixtureActivity.result = Authorization.Allowed("external")
        submit(activity, "redirect.fixture.test")
        waitFor("redirected homepage binds") { visible(activity) && binding() == "https://redirect.fixture.test/" }
        val state = ShopActivity::class.java.getDeclaredField("appearance").apply { isAccessible=true }.get(activity) as ShellAppearance
        waitFor("brand comes from the actual landing homepage") { state.title == "Fixture Store" }
        ui { assertTrue(activity.findViewById<WebView>(R.id.browser).url!!.endsWith("/landing")) }
    }

    fun testHorizontalLongPressAndRealTwoPointerZoomDoNotHideToolbar() {
        val activity = launch()
        submit(activity,"b.fixture.test")
        waitFor("bind") { visible(activity) }
        Thread.sleep(500)
        val state=ShopActivity::class.java.getDeclaredField("appearance").apply{isAccessible=true}.get(activity) as ShellAppearance
        fun event(start: Long, action: Int, points: List<Pair<Float,Float>>) {
            val properties=Array(points.size){ i->android.view.MotionEvent.PointerProperties().apply{id=i;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER} }
            val coords=Array(points.size){ i->android.view.MotionEvent.PointerCoords().apply{x=points[i].first;y=points[i].second;pressure=1f;size=1f} }
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),action,points.size,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0))
        }
        var start=android.os.SystemClock.uptimeMillis()
        event(start,0,listOf(300f to 1500f))
        for(i in 1..8){Thread.sleep(25);event(start,2,listOf(300f+i*70 to 1500f))}
        event(start,1,listOf(860f to 1500f))
        assertTrue(state.toolbar)
        script(activity,"document.querySelector('.color').scrollIntoView()")
        Thread.sleep(200)
        val r=org.json.JSONArray(script(activity,"(()=>{const r=document.querySelectorAll('.color')[1].getBoundingClientRect();return [100,r.top+35,innerWidth]})()"))
        val scale=1220/r.getDouble(2)
        val textPoint=(r.getDouble(0)*scale).toFloat() to (r.getDouble(1)*scale).toFloat()
        start=android.os.SystemClock.uptimeMillis();event(start,0,listOf(textPoint));Thread.sleep(650);event(start,1,listOf(textPoint))
        assertTrue("long press must not hide toolbar",state.toolbar)
        assertTrue("WebView still receives text long press",script(activity,"getSelection().toString().length").toInt()>0)
        script(activity,"getSelection().removeAllRanges()")
        action(activity,6)
        Thread.sleep(500)
        assertTrue(script(activity,"document.documentElement.clientWidth").toInt() in 1264..1296)
        val before=script(activity,"visualViewport.scale").toDouble()
        start=android.os.SystemClock.uptimeMillis()
        event(start,0,listOf(550f to 1400f))
        event(start,android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(550f to 1400f,670f to 1400f))
        for(i in 1..8){Thread.sleep(30);event(start,2,listOf((550f-i*28) to 1400f,(670f+i*28) to 1400f))}
        event(start,android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(326f to 1400f,894f to 1400f))
        event(start,1,listOf(326f to 1400f))
        Thread.sleep(400)
        assertTrue("real two-pointer gesture zooms desktop content",script(activity,"visualViewport.scale").toDouble()>before+0.05)
        assertTrue("two-pointer gesture must not hide toolbar",state.toolbar)
        action(activity,6);Thread.sleep(500)
        assertTrue(script(activity,"document.documentElement.clientWidth").toInt()<600)
        assertEquals("true",script(activity,"navigator.userAgentData.mobile"))
    }

}
