package aikayun.muchen.shop

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled on-device fixtures, excluded from the release APK. No production configuration is changed. */
class FixtureActivity : ShopActivity() {
    companion object {
        @Volatile var result: Authorization = Authorization.Allowed()
        @Volatile var authWait: CountDownLatch? = null
        @Volatile var pageWait: CountDownLatch? = null
        @Volatile var failHost: String? = null
        @Volatile var externalEnabled = false
        val checks = CopyOnWriteArrayList<String>()
        val loads = CopyOnWriteArrayList<String>()
        fun reset() {
            result = Authorization.Allowed()
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
        if (request.url.host == "redirect.fixture.test" && request.url.path == "/") return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("""<!doctype html><script>location.replace('/landing')</script>""".toByteArray()))
        if (request.url.path == "/icon.svg") return WebResourceResponse("image/svg+xml", "UTF-8", ByteArrayInputStream("""<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" rx="8" fill="#2563eb"/></svg>""".toByteArray()))
        val failed = request.url.host == failHost || request.url.path == "/missing.png"
        val html = """<!doctype html><html><head><meta charset="utf-8"><title>Fixture</title><meta property="og:site_name" content="Fixture Store"><link rel="icon" href="/icon.svg"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><meta name="theme-color" content="#ffffff"><style>body{margin:0;font:20px sans-serif}h1{padding-top:120px}.color{height:160px;padding:20px;font-size:30px}.internal{height:400px;overflow:auto}input{font-size:20px}</style><script>${assets.open("viewport.js").bufferedReader().use { it.readText() }}</script></head><body><h1>Fixture</h1><a href="/second">Second</a><img src="/missing.png"><input id="draft" value="unchanged"><div class="internal"><div style="height:1200px;background:linear-gradient(#fb7185,#60a5fa,#facc15)">Internal scrolling container</div></div><script>for(let i=0;i<16;i++){const e=document.createElement('div');e.className='color';e.style.background=['#f87171','#60a5fa','#facc15','#34d399'][i%4];e.textContent='WebView color block '+i;document.body.append(e)}</script></body></html>"""
        return WebResourceResponse("text/html", "UTF-8", if (failed) 503 else 200,
            if (failed) "Unavailable" else "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }
}
