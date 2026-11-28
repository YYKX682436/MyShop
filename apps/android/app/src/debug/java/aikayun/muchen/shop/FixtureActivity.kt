package aikayun.muchen.shop

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled on-device fixtures, excluded from the release APK. No production configuration is changed. */
class FixtureActivity : ShopActivity() {
    companion object {
        @Volatile var result: Authorization = Authorization.Allowed
        @Volatile var authWait: CountDownLatch? = null
        @Volatile var pageWait: CountDownLatch? = null
        @Volatile var failHost: String? = null
        @Volatile var externalEnabled = false
        val checks = CopyOnWriteArrayList<String>()
        val loads = CopyOnWriteArrayList<String>()
        fun reset() {
            result = Authorization.Allowed
            authWait?.countDown(); pageWait?.countDown()
            authWait = null; pageWait = null; failHost = null; externalEnabled = false
            checks.clear(); loads.clear()
        }
    }
    override fun authorityClient() = object : AuthorityClient() {
        override fun resolve(origin: String): Authorization {
            checks.add(origin)
            val snapshot = when {
                origin == "https://denied.fixture.test/" -> Authorization.Denied("not_authorized")
                origin == "https://external.fixture.test/" && !externalEnabled -> Authorization.Denied("not_authorized")
                else -> result
            }
            // Deliberately ignore cancellation to verify stale responses are discarded.
            val wait = authWait
            if (wait != null) {
                var complete = false
                while (!complete) try { wait.await(); complete = true } catch (_: InterruptedException) { }
            }
            return snapshot
        }
    }
    override fun interceptResource(request: WebResourceRequest): WebResourceResponse {
        if (request.isForMainFrame) {
            loads.add(request.url.toString())
            val wait = pageWait
            if (wait != null) try { wait.await() } catch (_: InterruptedException) { }
        }
        val failed = request.url.host == failHost || request.url.path == "/missing.png"
        val html = """<!doctype html><html><head><meta charset="utf-8"><title>Fixture</title></head><body><h1>Fixture</h1><a href="/second">Second</a><img src="/missing.png"><input id="draft" value="unchanged"></body></html>"""
        return WebResourceResponse("text/html", "UTF-8", if (failed) 503 else 200,
            if (failed) "Unavailable" else "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }
}
