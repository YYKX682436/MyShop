package aikayun.muchen.shop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.ceil

/** Only WebView content invalidations schedule capture; Compose/glass draws never schedule it. */
class ObservedWebView(context: Context) : WebView(context) {
    var changed: (() -> Unit)? = null
    var direction: ((Boolean) -> Unit)? = null
    var interacted: (() -> Unit)? = null
    var beforeHardwareDraw: (() -> Unit)? = null
    var debugInsets: String? = null
        private set
    override fun dispatchApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        if(BuildConfig.DEBUG) debugInsets = insets.toString()
        return super.dispatchApplyWindowInsets(insets)
    }
    var sampling = false
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var accumulated = 0f
    private var eligible = false
    private var vertical = false
    private var downAt = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onDraw(canvas: Canvas) {
        // A software snapshot must be followed by the real hardware draw in this same
        // View traversal. Capturing between traversals can replace Chromium's nested
        // scrolling hit-test data with the small software clip.
        if (canvas.isHardwareAccelerated && !sampling) beforeHardwareDraw?.invoke()
        super.onDraw(canvas)
    }
    override fun invalidate() { super.invalidate(); if (!sampling) changed?.invoke() }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!sampling) changed?.invoke()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; lastY = event.y
                accumulated = 0f; eligible = true; vertical = false; downAt = event.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> eligible = false
            MotionEvent.ACTION_MOVE -> if (eligible && event.pointerCount == 1) {
                val dx = event.x - startX
                val dy = event.y - startY
                if (!vertical && event.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()) eligible = false
                if (!vertical && abs(dx) > slop && abs(dx) > abs(dy)) eligible = false
                if (eligible && (vertical || abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.4f)) {
                    vertical = true
                    val step = event.y - lastY
                    if (step * accumulated < 0) accumulated = 0f
                    accumulated += step
                    if (abs(accumulated) > slop * 3) {
                        direction?.invoke(accumulated > 0)
                        accumulated = 0f
                    }
                    changed?.invoke()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> eligible = false
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) interacted?.invoke()
        return handled
    }
}

data class GlassFrame(val bitmap: Bitmap, val width: Int, val height: Int, val left: Int, val top: Int)

/** Fresh, never rewritten bitmaps. Capture only the visible top strip, excluding all native UI. */
class WebGlassSource {
    var frame by mutableStateOf<GlassFrame?>(null)
        private set
    val version = androidx.compose.runtime.mutableIntStateOf(0)
    var captures = 0
        private set
    private var browser: ObservedWebView? = null
    private var enabled = false
    private var permitted = false
    private var visible = false
    private var toolbarRect: androidx.compose.ui.geometry.Rect? = null
    private var menuRect: androidx.compose.ui.geometry.Rect? = null
    private var menu = false
    private var pending = false
    private var ready = false
    private var dirtyAt = 0L
    private val timings = ArrayList<Pair<Double, Double>>()
    fun debugMetrics(): org.json.JSONObject {
        check(BuildConfig.DEBUG)
        return org.json.JSONObject().put("strategy", "next-draw").put("captures", captures)
            .put("samples", org.json.JSONArray(timings.map { org.json.JSONArray(listOf(it.first, it.second)) }))
    }
    private val capture = Runnable {
        pending = false
        val view = browser
        if (enabled && ready && view != null) {
            // Delayed invalidate itself must not re-arm sampling.
            view.sampling = true
            try { view.invalidate() } finally { view.sampling = false }
        }
    }
    fun toolbarBounds(rect: androidx.compose.ui.geometry.Rect) { toolbarRect = rect; regionChanged() }
    fun menuBounds(rect: androidx.compose.ui.geometry.Rect) { menuRect = rect; regionChanged() }
    private fun regionChanged() {
        val view = browser ?: return
        val region = region(view)
        val previous = frame
        if (previous == null || region.left < previous.left || region.top < previous.top || region.right > previous.left + previous.width || region.bottom > previous.top + previous.height) dirty()
    }
    private fun region(view: WebView): android.graphics.Rect {
        val position = IntArray(2); view.getLocationInWindow(position)
        val density = view.resources.displayMetrics.density
        val bounds = toolbarRect ?: androidx.compose.ui.geometry.Rect(position[0].toFloat(),position[1].toFloat(),(position[0]+view.width).toFloat(),position[1]+156*density)
        val expanded = if(menu) menuRect ?: androidx.compose.ui.geometry.Rect(bounds.left,bounds.top,bounds.right,bounds.top+560*density) else null
        val margin = 32*density
        return android.graphics.Rect(
            kotlin.math.floor(minOf(bounds.left,expanded?.left ?: bounds.left)-margin).toInt().coerceAtLeast(position[0]),
            kotlin.math.floor(minOf(bounds.top,expanded?.top ?: bounds.top)-margin).toInt().coerceAtLeast(position[1]),
            ceil(maxOf(bounds.right,expanded?.right ?: bounds.right)+margin).toInt().coerceAtMost(position[0]+view.width),
            ceil(maxOf(bounds.bottom,expanded?.bottom ?: bounds.bottom)+margin).toInt().coerceAtMost(position[1]+view.height))
    }
    private fun captureBeforeHardwareDraw() {
        if (!ready) return
        ready = false
        val view = browser ?: return
        if (!enabled || view.width <= 0 || view.height <= 0) return
        val started = SystemClock.elapsedRealtimeNanos()
        val region = region(view)
        if (region.isEmpty) return
        val position = IntArray(2); view.getLocationInWindow(position)
        val width = region.width(); val height = region.height()
        val scale = 0.4f
        val bitmap = Bitmap.createBitmap(ceil(width * scale).toInt(), ceil(height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-(view.scrollX+region.left-position[0]).toFloat(), -(view.scrollY+region.top-position[1]).toFloat())
        view.sampling = true
        try { view.draw(canvas) } finally { view.sampling = false }
        frame = GlassFrame(bitmap, width, height, region.left, region.top)
        version.intValue++
        captures++
        if (BuildConfig.DEBUG && timings.size < 4000) timings.add((SystemClock.elapsedRealtimeNanos() - started) / 1e6 to (SystemClock.elapsedRealtimeNanos() - dirtyAt) / 1e6)
    }
    fun attach(view: ObservedWebView?) {
        browser?.removeCallbacks(capture)
        pending = false; ready = false
        toolbarRect = null; menuRect = null
        browser?.changed = null
        browser?.beforeHardwareDraw = null
        browser = view
        invalidateFrame()
        view?.changed = { dirty() }
        view?.beforeHardwareDraw = { captureBeforeHardwareDraw() }
    }
    fun invalidateFrame() { frame = null; version.intValue++; dirty() }
    /** Activity owns this hard gate; a stale Compose side effect cannot reopen it. */
    fun allow(value: Boolean) { permitted = value; reconcile() }
    fun active(value: Boolean, expanded: Boolean) {
        val changed = menu != expanded
        visible = value; menu = expanded
        reconcile()
        if(enabled && changed) dirty()
    }
    private fun reconcile() {
        val next = permitted && visible
        val changed = enabled != next
        enabled = next
        if (!next) { browser?.removeCallbacks(capture);pending=false;ready=false }
        else if(changed) invalidateFrame()
    }
    fun dirty() {
        val view = browser ?: return
        if (!enabled) return
        if(!ready) dirtyAt = SystemClock.elapsedRealtimeNanos()
        ready = true
        if(!pending) { pending=true;view.postOnAnimation(capture) }
    }
    fun close() { allow(false);active(false, false);attach(null) }
}
