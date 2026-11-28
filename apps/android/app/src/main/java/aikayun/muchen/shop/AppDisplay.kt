package aikayun.muchen.shop

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Display-only, native -> exact authorized top frame. It exposes no native methods. */
class AppDisplay(private val view: WebView, private val origin: String, private val external: Boolean) {
    private val originalUa = view.settings.userAgentString
    private val metadataSupported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
    private val originalMetadata = if (metadataSupported) WebSettingsCompat.getUserAgentMetadata(view.settings) else null
    private var script: ScriptHandler? = null
    private var fitPending = false
    private var generation = 0
    private var closed = false
    var desktop = false
        private set
    private val wireOrigin = origin.trimEnd('/')

    fun configure(value: Boolean) {
        generation++
        fitPending = value
        desktop = value
        // WebView initialScale is physical-pixel percent (unlike CSS viewport scale).
        // Pin the first desktop presentation before its one reload; otherwise WebView may
        // retain the previous document's mobile zoom even with a 1280 layout viewport.
        val pixels = if (view.width > 0) view.width else view.resources.displayMetrics.widthPixels
        view.setInitialScale(if (value) kotlin.math.round(pixels * 100f / 1280f).toInt().coerceAtLeast(1) else 0)
        view.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(value)
            builtInZoomControls = value
            displayZoomControls = false
        }
        install()
        if (metadataSupported && originalMetadata != null) {
            WebSettingsCompat.setUserAgentMetadata(view.settings, if (value) UserAgentMetadata.Builder(originalMetadata)
                .setMobile(false).setPlatform("Linux").setPlatformVersion("").setArchitecture("x86").setBitness(64)
                .setModel("").build() else originalMetadata)
        }
        // Changing UA during loading can initiate a reload. Caller only switches a stable document.
        val ua = if (value) originalUa.replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace("; wv", "").replace("Version/4.0 ", "").replace(" Mobile", "") else originalUa
        view.settings.userAgentString = "$ua AikayunShop/${BuildConfig.VERSION_NAME}"
    }

    private fun state(): String = JSONObject().put("version", 1).put("origin", wireOrigin)
        .put("desktop", desktop).put("topBarDp", 68)
        .put("containerWidthDp", (if (view.width > 0) view.width / view.resources.displayMetrics.density else view.resources.configuration.screenWidthDp.toFloat()).coerceAtLeast(1f).toDouble()).toString()

    private fun javascript(): String = """
        (()=>{if(window!==top || location.origin!==${JSONObject.quote(wireOrigin)})return;
          window.__AIKAYUN_DISPLAY__=${state()};
          window.dispatchEvent(new Event('aikayun:display'));
          ${if (external) EXTERNAL else ""}
        })();
    """.trimIndent()

    fun update() {
        if (OriginPolicy.normalize(view.url ?: "") != origin) return
        install()
        view.evaluateJavascript(javascript(), null)
    }
    private fun install() {
        script?.remove(); script = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            script = WebViewCompat.addDocumentStartJavaScript(view, javascript(), setOf(wireOrigin))
        }
    }
    fun settleOverview(isCurrent: () -> Boolean) {
        if (!desktop || !fitPending || closed) return
        fitPending = false
        val expected = generation
        view.evaluateJavascript("window.visualViewport?.width || innerWidth") { result ->
            if (closed || expected != generation || !desktop || !isCurrent()) return@evaluateJavascript
            val width = result.toFloatOrNull() ?: return@evaluateJavascript
            val factor = width / 1280f
            // One display correction after the document is drawable, without another load.
            // Some WebViews preserve the old page zoom despite the new initial-scale meta.
            if (factor in 0.05f..20f && kotlin.math.abs(factor - 1f) > 0.03f) view.zoomBy(factor)
        }
    }
    fun close() { closed = true; generation++; script?.remove(); script = null }

    companion object {
        private val EXTERNAL = """
          if(!window.__aikayunExternalViewport){
            window.__aikayunExternalViewport=true;
            const originals=new Map();let scheduled=false;
            const reconcile=()=>{
              scheduled=false;const s=window.__AIKAYUN_DISPLAY__;
              if(!s||s.origin!==location.origin)return;
              const metas=Array.from(document.querySelectorAll('meta[name="viewport"]'));
              if(s.desktop){
                if(!metas.length&&document.head){const m=document.createElement('meta');m.name='viewport';m.dataset.aikayunCreated='1';document.head.appendChild(m);metas.push(m)}
                for(const m of metas){if(!originals.has(m))originals.set(m,m.content);if(m.content!=='width=1280, initial-scale='+Math.min(1,screen.width/1280)+', viewport-fit=cover')m.content='width=1280, initial-scale='+Math.min(1,screen.width/1280)+', viewport-fit=cover'}
              }else{for(const [m,c]of originals){if(m.dataset.aikayunCreated)m.remove();else m.content=c}originals.clear()}
            };
            const schedule=()=>{if(!scheduled){scheduled=true;requestAnimationFrame(reconcile)}};
            new MutationObserver(schedule).observe(document,{subtree:true,childList:true,attributes:true,attributeFilter:['content','name']});
            addEventListener('aikayun:display',schedule);addEventListener('resize',schedule);schedule();
          }
        """.trimIndent()
    }
}
