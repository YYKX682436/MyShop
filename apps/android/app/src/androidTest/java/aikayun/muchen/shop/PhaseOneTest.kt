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
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
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
    private fun status(activity: Activity) = activity.findViewById<TextView>(R.id.status).text.toString()
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
        assertEquals(Authorization.Allowed, parse("""{"schema_version":1,"allowed":true,"canonical_origin":"$origin","site_type":"external"}"""))
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
        FixtureActivity.result = Authorization.Allowed
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
        assertEquals(Authorization.Allowed, client.resolve("https://mc.mc520.top/"))
        assertEquals(Authorization.Allowed, client.resolve("https://520.mc520.top/"))
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
}
