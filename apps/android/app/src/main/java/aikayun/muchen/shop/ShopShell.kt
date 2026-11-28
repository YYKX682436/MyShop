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
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.geometry.Offset
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
    var host by mutableStateOf("")
    var samplingAllowed by mutableStateOf(false)
    var icon by mutableStateOf<android.graphics.Bitmap?>(null)
    var desktop by mutableStateOf(false)
    var external by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var dark by mutableStateOf<Boolean?>(null)
}

@Composable
fun ShopShell(browser: WebView?, gate: LinearLayout, appearance: ShellAppearance, source: WebGlassSource, action: (Int) -> Unit) {
    val dark = appearance.dark ?: isSystemInDarkTheme()
    val toolbarTransition = remember { MutableTransitionState(false) }
    var menuAlpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(appearance.gate) { if(appearance.gate) menuAlpha=0f }
    val expanded = appearance.menu || menuAlpha > 0f
    toolbarTransition.targetState = !appearance.gate && (appearance.toolbar || expanded)
    val toolbarVisible = toolbarTransition.currentState || toolbarTransition.targetState || !toolbarTransition.isIdle
    val sampling = appearance.samplingAllowed && (toolbarVisible || expanded)
    SideEffect { source.active(sampling, expanded) }
    val toolbarHeight = ShellDimensions.toolbarDp(LocalConfiguration.current.fontScale).dp
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val safe = WindowInsets.safeDrawing
    // The library clamps against the whole viewport; reserve the anchor's space too,
    // so short windows scroll the rows instead of moving the menu over its trigger.
    val menuHeight = minOf((7*48+2*9+2*10).dp,
        windowHeight - with(density) { (safe.getTop(density)+safe.getBottom(density)).toDp() } - toolbarHeight - 16.dp).coerceAtLeast(1.dp)
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
                    factory = { WebViewHost(it) },
                    modifier = Modifier.fillMaxSize().drawWithContent { source.version.intValue; drawContent() }.glassBackdropSource(backdrop).trackBackdropPosition(backdrop, source.version),
                    update = { host ->
                        host.configureSafety(appearance.external || appearance.desktop, appearance.external)
                        if (host.getChildAt(0) !== browser) {
                            host.removeAllViews()
                            browser?.let {
                                (it.parent as? ViewGroup)?.removeView(it)
                                host.addView(it, ViewGroup.LayoutParams(-1, -1))
                                it.requestApplyInsets()
                            }
                        }
                    },
                )
                AnimatedVisibility(
                    visibleState = toolbarTransition,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).padding(horizontal = 12.dp, vertical = 8.dp),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it },
                    exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it },
                ) {
                    Row(Modifier.fillMaxWidth().height(toolbarHeight).onGloballyPositioned {
                        val p = it.positionInRoot()
                        source.toolbarBounds(Rect(p.x,p.y,p.x+it.size.width,p.y+it.size.height))
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NavigationGlass(Modifier.size(toolbarHeight), dark) {
                            val icon = appearance.icon
                            if (icon != null) Image(icon.asImageBitmap(), "商城图标", Modifier.size(44.dp).clip(CircleShape))
                            else Image(painterResource(R.drawable.ic_shop), "商城图标", Modifier.size(44.dp).clip(CircleShape))
                        }
                        NavigationGlass(Modifier.weight(1f).fillMaxHeight(), dark) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(appearance.title, fontSize=17.sp, lineHeight=20.sp, fontWeight=FontWeight.Medium, maxLines=1, overflow=TextOverflow.Ellipsis)
                                Text(appearance.host, fontSize=12.sp, lineHeight=15.sp, color=LocalContentColor.current.copy(alpha=0.65f), maxLines=1, overflow=TextOverflow.Ellipsis)
                            }
                        }
                        val anchor = rememberGlasmioMenuAnchorState()
                        val typography = MaterialTheme.typography
                        MaterialTheme(typography=typography.copy(bodyLarge=typography.bodyLarge.copy(fontSize=16.sp,lineHeight=22.sp))) {
                            GlasmioMenu(
                                expanded = appearance.menu,
                                onExpandedChange = { appearance.menu = it },
                                itemCount = 7,
                                menuWidth = 224.dp,
                                // Seven complete 48dp rows, two 9dp separators, library's 10dp edges.
                                heightOverride = menuHeight,
                                anchorEnabled = appearance.ready,
                                menuAnchorState = anchor,
                                morphFromAnchor = false, keepAnchorVisible = true, fullScreen = false, forceBelowAnchor = true,
                                onMenuAlphaChanged = { menuAlpha=if(appearance.gate) 0f else it },
                                onMenuBoundsChanged = { source.menuBounds(it) },
                                anchor = { open ->
                                    NavigationGlass(Modifier.size(toolbarHeight).glasmioMenuAnchor(anchor).semantics { contentDescription = "更多操作" }, dark, onClick=open) {
                                        val color = LocalContentColor.current
                                        Canvas(Modifier.size(24.dp)) { for (i in 1..3) drawCircle(color, radius=1.6.dp.toPx(), center=Offset(size.width*i/4,size.height/2)) }
                                    }
                                },
                            ) { _, close ->
                                listOf("返回", "刷新", "回到主页", "分享当前网页", "在浏览器打开", "复制当前网址", if(appearance.desktop) "切换手机模式" else "切换电脑模式").forEachIndexed { index, title ->
                                    if(index==3 || index==6) HorizontalDivider(Modifier.padding(horizontal=16.dp,vertical=4.dp),thickness=1.dp,color=LocalContentColor.current.copy(alpha=0.12f))
                                    GlasmioMenuItem(title=title, enabled=index!=0 || appearance.canBack, rowHeight=48.dp,
                                        icon={ ActionIcon(index, appearance.desktop) },
                                        onClick={ if(appearance.menu) { action(index);close() } })
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


/** Shared material for the three independent surfaces; content opacity remains intact. */
@Composable
private fun NavigationGlass(modifier: Modifier, dark: Boolean, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    GlassSurface(modifier, shape=CircleShape, material=NavigationGlassMaterial.RegularSmall,
        navigationSurfaceColor=(if(dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha=0.40f),
        navigationSurfaceAlphaMultiplier=1f, frostRadius=8.dp, refractionHeight=8.dp,
        refractionAmount=1.5.dp, opticalHighlightBoost=-0.25f, onClick=onClick, content=content)
}

/** Google Material Icons Outlined; only these eight vectors are packaged. */
@Composable
private fun ActionIcon(index: Int, desktop: Boolean) {
    val resource=when(index) {
        0 -> R.drawable.ic_action_arrow_back
        1 -> R.drawable.ic_action_refresh
        2 -> R.drawable.ic_action_home
        3 -> R.drawable.ic_action_share
        4 -> R.drawable.ic_action_open_in_new
        5 -> R.drawable.ic_action_content_copy
        else -> if(desktop) R.drawable.ic_action_smartphone else R.drawable.ic_action_desktop_windows
    }
    Icon(painterResource(resource),null,Modifier.size(20.dp),tint=LocalContentColor.current)
}

/** Modify insets on the parent so WebView retains its own internal insets listener. */
private class WebViewHost(context: android.content.Context) : android.widget.FrameLayout(context) {
    private var nativeSafe = false
    private var reserveToolbar = false
    init {
        setOnApplyWindowInsetsListener { _, insets ->
            val types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            val safe = if (nativeSafe) insets.getInsets(types) else android.graphics.Insets.NONE
            (getChildAt(0)?.layoutParams as? LayoutParams)?.let { params ->
                val bottom = if(reserveToolbar) safe.bottom else 0
                val top = safe.top + if (reserveToolbar) (ShellDimensions.topBarDp(resources.configuration.fontScale) * resources.displayMetrics.density).toInt() else 0
                if (params.leftMargin != safe.left || params.topMargin != top || params.rightMargin != safe.right || params.bottomMargin != bottom) {
                    params.setMargins(safe.left, top, safe.right, bottom)
                    getChildAt(0).layoutParams = params
                }
            }
            // Phone MyShop uses CSS env. Scaled desktop WebView needs physical margins;
            // handled top/side edges are zero; MyShop keeps bottom CSS safe area and IME.
            val remaining = if (nativeSafe) android.view.WindowInsets.Builder(insets).apply {
                for (type in listOf(android.view.WindowInsets.Type.statusBars(),android.view.WindowInsets.Type.navigationBars(),android.view.WindowInsets.Type.displayCutout())) {
                    val edge = insets.getInsets(type)
                    setInsets(type, android.graphics.Insets.of(0,0,0,if(reserveToolbar) 0 else edge.bottom))
                }
            }.build() else insets
            onApplyWindowInsets(remaining)
        }
    }
    fun configureSafety(value: Boolean, toolbar: Boolean) {
        if (nativeSafe != value || reserveToolbar != toolbar) {
            nativeSafe = value; reserveToolbar = toolbar
            requestApplyInsets()
        }
    }
}
