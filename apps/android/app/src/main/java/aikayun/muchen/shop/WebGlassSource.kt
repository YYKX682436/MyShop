package aikayun.muchen.shop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
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
    var sampling = false
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var accumulated = 0f
    private var eligible = false
    private var vertical = false
    private var downAt = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

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
    private val handler = Handler(Looper.getMainLooper())
    private var browser: ObservedWebView? = null
    private var enabled = false
    private var menu = false
    private var pending = false
    private var last = 0L
    private val capture = Runnable {
        pending = false
        val view = browser
        if (enabled && view != null && view.width > 0 && view.height > 0) {
            val density = view.resources.displayMetrics.density
            val height = minOf(view.height, ceil(density * (if (menu) 560 else 156)).toInt())
            val scale = 0.4f
            val bitmap = Bitmap.createBitmap(ceil(view.width * scale).toInt(), ceil(height * scale).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.sampling = true
            try { view.draw(canvas) } finally { view.sampling = false }
            frame = GlassFrame(bitmap, view.width, height, view.left, view.top)
            version.intValue++
            captures++
            last = SystemClock.uptimeMillis()
        }
    }
    fun attach(view: ObservedWebView?) {
        browser?.changed = null
        browser = view
        invalidateFrame()
        view?.changed = { dirty() }
    }
    fun invalidateFrame() { frame = null; dirty() }
    fun active(value: Boolean, expanded: Boolean) {
        val changed = enabled != value || menu != expanded
        enabled = value; menu = expanded
        if (!enabled) { handler.removeCallbacks(capture); pending = false }
        else if (changed) dirty()
    }
    fun dirty() {
        if (!enabled || pending) return
        pending = true
        handler.postDelayed(capture, (67 - (SystemClock.uptimeMillis() - last)).coerceAtLeast(0))
    }
    fun close() { active(false, false); attach(null) }
}
