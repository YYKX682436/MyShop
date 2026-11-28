package aikayun.muchen.shop

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.neoruaa.glasmio.glass.render.Backdrop
import kotlin.math.roundToInt

/** Replay the existing bounded WebView source directly in the menu's separate window.
 * The library's stationary sampler handles the menu transform; no Activity layer export,
 * additional capture, or animation-driven bitmap allocation is needed here.
 */
internal class WebFrameBackdrop(private val source: WebGlassSource, private val host: View) : Backdrop {
    override val isCoordinatesDependent = true
    override fun observeSourceChanges(observer: () -> Unit): () -> Unit = source.observeFrames(observer)

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val target = coordinates?.takeIf { it.isAttached } ?: return
        val frame = source.frame ?: return
        val screen = IntArray(2); val window = IntArray(2)
        host.getLocationOnScreen(screen); host.getLocationInWindow(window)
        val origin = target.localToScreen(Offset.Zero)
        drawImage(frame.bitmap.asImageBitmap(),
            dstOffset=IntOffset((frame.left+screen[0]-window[0]-origin.x).roundToInt(), (frame.top+screen[1]-window[1]-origin.y).roundToInt()),
            dstSize=IntSize(frame.width,frame.height))
    }
}
