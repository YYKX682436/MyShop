package aikayun.muchen.shop

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.ui.*

class ShellAppearance {
    var gate by mutableStateOf(true)
    var bound by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var returning by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var toolbar by mutableStateOf(true)
    var menu by mutableStateOf(false)
    var title by mutableStateOf("艾卡云")
    var icon by mutableStateOf<android.graphics.Bitmap?>(null)
    var desktop by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var dark by mutableStateOf<Boolean?>(null)
}

@Composable
fun ShopShell(browser: WebView?, gate: LinearLayout, appearance: ShellAppearance, source: WebGlassSource, action: (Int) -> Unit) {
    val dark = appearance.dark ?: isSystemInDarkTheme()
    val expanded = appearance.menu
    val sampling = !appearance.gate && (appearance.toolbar || expanded)
    SideEffect { source.active(sampling, expanded) }
    GlasmioTheme(isDark = dark) {
        // The custom capture records WebView pixels, while AndroidView draws its hardware content.
        val onDraw: ContentDrawScope.() -> Unit = remember(source) {{
            source.frame?.let { frame ->
                drawImage(frame.bitmap.asImageBitmap(), dstOffset = IntOffset(frame.left, frame.top), dstSize = IntSize(frame.width, frame.height))
            }
        }}
        val backdrop = rememberLayerBackdrop(onDraw = onDraw)
        GlasmioBackdropProvider(backdrop) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { android.widget.FrameLayout(it) },
                    modifier = Modifier.fillMaxSize().drawWithContent { source.version.intValue; drawContent() }.glassBackdropSource(backdrop).trackBackdropPosition(backdrop, source.version),
                    update = { host ->
                        if (host.getChildAt(0) !== browser) {
                            host.removeAllViews()
                            browser?.let {
                                (it.parent as? ViewGroup)?.removeView(it)
                                host.addView(it, ViewGroup.LayoutParams(-1, -1))
                            }
                        }
                    },
                )
                AnimatedVisibility(
                    visible = !appearance.gate && (appearance.toolbar || appearance.menu),
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).padding(horizontal = 12.dp, vertical = 8.dp),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it },
                    exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it },
                ) {
                    GlassSurface(Modifier.fillMaxWidth().height(52.dp), shape = CircleShape, material = NavigationGlassMaterial.RegularSmall) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                val icon = appearance.icon
                                if (icon != null) Image(icon.asImageBitmap(), "商城图标", Modifier.size(30.dp).clip(CircleShape))
                                else Image(painterResource(R.drawable.ic_shop), "商城图标", Modifier.size(30.dp))
                            }
                            Text(appearance.title, Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val anchor = rememberGlasmioMenuAnchorState()
                            GlasmioMenu(
                                expanded = appearance.menu,
                                onExpandedChange = { appearance.menu = it },
                                itemCount = 7, anchorEnabled = appearance.ready, menuAnchorState = anchor, forceBelowAnchor = true,
                                anchor = { open ->
                                    GlasmioIconButton(onClick = open, sampleBackdrop = false, navigationSurfaceColor = Color.Transparent, modifier = Modifier.size(52.dp).glasmioMenuAnchor(anchor).semantics { contentDescription = "更多操作" }, size = 48.dp) {
                                        val color = LocalContentColor.current
                                        Canvas(Modifier.size(24.dp)) { for (i in 1..3) drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(size.width * i/4, size.height/2)) }
                                    }
                                },
                            ) { _, close ->
                                listOf("返回", "刷新", "回到主页", "分享当前网页", "在浏览器打开", "复制当前网址", "电脑模式").forEachIndexed { index, title ->
                                    GlasmioMenuItem(title = title, enabled = index != 0 || appearance.canBack,
                                        rowHeight = 48.dp, icon = { ActionIcon(index) },
                                        trailing = if (index == 6) {{ Text(if (appearance.desktop) "已开启" else "关闭") }} else null,
                                        onClick = { close(); action(index) })
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(appearance.gate, enter = fadeIn(tween(120)), exit = fadeOut(tween(if (appearance.returning) 120 else 360)) + scaleOut(tween(if (appearance.returning) 120 else 360), targetScale = 0.94f)) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(if (dark) listOf(Color(0xFF101622), Color(0xFF1B2435), Color(0xFF161B28)) else listOf(Color(0xFFEFF5FF), Color(0xFFF8F9FD), Color(0xFFE8ECFA)))))
                    Column(
                        Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { }
                            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(Modifier.widthIn(max = 400.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shadowElevation = 12.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if(dark) 0.12f else 0.85f))) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (!appearance.returning) {
                                    val icon = appearance.icon
                                    if (appearance.bound && icon != null) Image(icon.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
                                    else Image(painterResource(R.drawable.ic_shop), null, Modifier.size(56.dp))
                                    Spacer(Modifier.height(14.dp))
                                    Text(if (appearance.bound) appearance.title else "艾卡云", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(24.dp))
                                }
                                if (appearance.busy) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Spacer(Modifier.height(12.dp)) }
                                AndroidView(factory = {
                                    (gate.parent as? ViewGroup)?.removeView(gate)
                                    gate
                                }, modifier = Modifier.fillMaxWidth())
                                if (!appearance.bound) {
                                    Spacer(Modifier.height(14.dp))
                                    Text("成功绑定后，需清除应用数据才能更换商城", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Original vectors; no sample/Apple icon artwork. */
@Composable
private fun ActionIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.8f)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x,y),Offset(xx,yy),1.8f)
            when (index) {
                0 -> { line(15f,4f,7f,12f);line(7f,12f,15f,20f) }
                1 -> { drawArc(color,45f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke);line(20f,3f,20f,9f);line(20f,9f,14f,9f) }
                2 -> { drawPath(Path().apply { moveTo(3f,11f);lineTo(12f,3f);lineTo(21f,11f);lineTo(21f,21f);lineTo(3f,21f);close() },color,style=stroke) }
                3 -> { line(7f,12f,18f,5f);line(7f,12f,18f,19f);for ((x,y) in listOf(5f to 12f,19f to 4f,19f to 20f)) drawCircle(color,2.5f,Offset(x,y),style=stroke) }
                4 -> { drawRect(color,Offset(3f,7f),Size(14f,14f),style=stroke);line(10f,14f,21f,3f);line(14f,3f,21f,3f);line(21f,3f,21f,10f) }
                5 -> { drawRect(color,Offset(7f,7f),Size(14f,14f),style=stroke);line(3f,17f,3f,3f);line(3f,3f,17f,3f) }
                6 -> { drawRect(color,Offset(2f,3f),Size(20f,14f),style=stroke);line(12f,17f,12f,21f);line(7f,21f,17f,21f) }
            }
        }
    }
}
