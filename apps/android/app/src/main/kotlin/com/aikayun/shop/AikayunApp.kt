package com.aikayun.shop

import aikayun.muchen.shop.R
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.util.Size
import android.view.accessibility.AccessibilityManager
import android.view.Window
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidCapsule
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val BrandBlue = Color(0xFF2563EB)
private val LightBackdrop = Color(0xFFF4F7FE)
private val DarkBackdrop = Color(0xFF0E131B)

@Composable
fun AikayunApp(model: ShopViewModel, activity: MainActivity) {
    val ui by model.ui.collectAsState()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val background = if (dark) DarkBackdrop else LightBackdrop
    val content = if (dark) Color(0xFFF5F7FA) else Color(0xFF151C2A)
    val backdrop = rememberLayerBackdrop()
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val statusInset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val density = LocalDensity.current
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    val keyboardInset = with(density) { WindowInsets.ime.getBottom(this).toDp() }

    SideEffect { setTransparentSystemBars(activity.window, dark) }

    LaunchedEffect(ui.binding?.origin, ui.currentUrl, statusInset, safeBottomInset, keyboardInset) {
        if (ui.binding != null) model.updateInsets(statusInset.value, safeBottomInset.value, keyboardInset.value)
    }

    LaunchedEffect(ui.headerVisible, ui.menuOpen, ui.aboutOpen, ui.nativeOverlayOpen, ui.gate, ui.fullScreen, accessibility?.isTouchExplorationEnabled) {
        if (ui.gate == AppGate.READY && ui.headerVisible && !ui.menuOpen && !ui.aboutOpen
            && !ui.nativeOverlayOpen && !ui.fullScreen && accessibility?.isTouchExplorationEnabled != true) {
            delay(2_500)
            model.hideHeader()
        }
    }

    Box(Modifier.fillMaxSize().background(background)) {
        keyWebView(ui.webViewGeneration) {
            AndroidView(
                factory = { model.currentWebView() ?: model.getOrCreateWebView(activity) },
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                update = { /* The ViewModel owns this WebView; recomposition never reloads it. */ }
            )
        }

        MorphBrandCard(
            state = ui,
            backdrop = backdrop,
            statusInset = statusInset,
            colors = AppColors(dark, content),
            activity = activity,
            model = model
        )

        when (ui.gate) {
            AppGate.CHECKING -> StartupOverlay(ui, colors = AppColors(dark, content), statusInset = statusInset, model = model)
            AppGate.BLOCKED -> GateOverlay(ui.message ?: "App 授权暂不可用。", action = { model.retryAuthorization() }, actionLabel = "重新检查", backdrop = backdrop, colors = AppColors(dark, content))
            AppGate.TEMPORARY_ERROR -> GateOverlay(ui.message ?: "授权服务暂时无法连接。", action = { model.retryAuthorization() }, actionLabel = "重试", backdrop = backdrop, colors = AppColors(dark, content))
            AppGate.PAGE_ERROR -> if (ui.binding != null) GateOverlay(ui.message ?: "页面暂时无法打开。", action = { model.retryPage() }, actionLabel = "重新加载", backdrop = backdrop, colors = AppColors(dark, content))
            else -> Unit
        }

        if (ui.menuOpen && !ui.fullScreen) MenuOverlay(backdrop, statusInset, ui, model, activity, AppColors(dark, content))
        if (ui.aboutOpen && !ui.fullScreen) AboutOverlay(backdrop, ui, model, activity, AppColors(dark, content))
        ui.imageActionUrl?.takeIf { !ui.fullScreen }?.let { imageUrl ->
            ImageActionsOverlay(backdrop, safeBottomInset, imageUrl, model, AppColors(dark, content))
        }
        activity.currentMediaFallback()?.let { state ->
            MediaFallbackOverlay(backdrop, safeBottomInset, state, activity, AppColors(dark, content))
        }
    }

    LaunchedEffect(ui.gate) {
        if (ui.gate == AppGate.ENTERING) {
            val duration = if (ValueAnimator.areAnimatorsEnabled()) 520 else 0
            delay(duration.toLong())
            model.onEntryAnimationFinished()
        }
    }
}

@Composable
private fun keyWebView(key: Int, content: @Composable () -> Unit) {
    androidx.compose.runtime.key(key) { content() }
}

private data class AppColors(val dark: Boolean, val content: Color) {
    val card = if (dark) Color(0xFF171D28).copy(alpha = 0.54f) else Color.White.copy(alpha = 0.58f)
    val secondary = if (dark) Color(0xFFB9C2D0) else Color(0xFF586477)
    val border = if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFF43536C).copy(alpha = 0.10f)
}

@Composable
private fun MorphBrandCard(
    state: AppUiState,
    backdrop: LayerBackdrop,
    statusInset: androidx.compose.ui.unit.Dp,
    colors: AppColors,
    activity: MainActivity,
    model: ShopViewModel
) {
    var bindUrl by rememberSaveable { mutableStateOf("") }
    val isEnteringForm = state.binding == null || state.gate in setOf(AppGate.INPUT, AppGate.VERIFYING, AppGate.FIRST_LOAD)
    val compactTarget = if (isEnteringForm) 0f else 1f
    val progress by animateFloatAsState(
        targetValue = compactTarget,
        animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 520 else 0),
        label = "binding-card-morph"
    )
    val density = LocalDensity.current
    val topInset = statusInset + 8.dp
    val visible = isEnteringForm || state.headerVisible || state.gate == AppGate.ENTERING
    val keyboardInset = with(density) { WindowInsets.ime.getBottom(this).toDp() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = (maxWidth - 24.dp).coerceAtMost(520.dp)
        val cardHeight = lerp(330.dp, 56.dp, progress)
        val centerY = (maxHeight - 330.dp) / 2 - keyboardInset / 2
        val cardY = lerp(centerY, topInset, progress)
        val barOffset = if (visible) 0.dp else -(topInset + 68.dp)
        val yOffset = cardY + barOffset
        val radius = lerp(30.dp, 28.dp, progress)
        val cardModifier = Modifier
            .offset { IntOffset(0, with(density) { yOffset.toPx().roundToInt() }) }
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .clip(RoundedCornerShape(radius))

        if (progress >= 0.999f) {
            LiquidCapsule(backdrop, cardModifier) {
                CompactBrandRow(state, colors, model, activity, Modifier.fillMaxSize().padding(horizontal = 10.dp))
            }
        } else {
            Box(
                cardModifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(radius) },
                    effects = {
                        vibrancy()
                        blur(10.dp.toPx())
                        lens(22.dp.toPx(), 32.dp.toPx())
                    },
                    onDrawSurface = { drawRect(colors.card) }
                )
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
                    CompactBrandRow(state, colors, model, activity, Modifier.height(44.dp).fillMaxWidth(), showMenu = false)
                    Column(
                        Modifier.weight(1f).fillMaxWidth().alpha(1f - progress).padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        BasicText("连接你的商城", style = TextStyle(colors.content, 21.sp, FontWeight.SemiBold))
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier.fillMaxWidth().height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (colors.dark) Color.White.copy(0.07f) else Color.White.copy(0.7f))
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = bindUrl,
                                onValueChange = { bindUrl = it.take(2048) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                cursorBrush = SolidColor(BrandBlue),
                                textStyle = TextStyle(colors.content, 15.sp),
                                decorationBox = { inner ->
                                    Box {
                                        if (bindUrl.isEmpty()) BasicText("输入商城网址", style = TextStyle(colors.secondary, 15.sp))
                                        inner()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (!state.message.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            BasicText(state.message, style = TextStyle(Color(0xFFCC3F47), 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(18.dp))
                        LiquidButton(
                            onClick = { if (state.gate != AppGate.VERIFYING && state.gate != AppGate.FIRST_LOAD) model.bindFromInput(bindUrl) },
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth(),
                            isInteractive = true,
                            tint = Color.Unspecified,
                            surfaceColor = BrandBlue
                        ) {
                            BasicText(
                                when (state.gate) {
                                    AppGate.VERIFYING -> "正在验证授权…"
                                    AppGate.FIRST_LOAD -> "正在连接商城…"
                                    else -> "确认并继续"
                                },
                                style = TextStyle(Color.White, 15.sp, FontWeight.SemiBold)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        BasicText("1.0.1（1）", style = TextStyle(colors.secondary.copy(alpha = 0.68f), 11.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactBrandRow(
    state: AppUiState,
    colors: AppColors,
    model: ShopViewModel,
    activity: MainActivity,
    modifier: Modifier,
    showMenu: Boolean = true
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark(state, Modifier.size(34.dp))
        BasicText(
            text = state.paymentDomain?.let { "正在付款 · $it" }
                ?: state.binding?.siteName?.ifBlank { "艾卡云" } ?: "艾卡云",
            modifier = Modifier.weight(1f),
            style = TextStyle(colors.content, 15.sp, FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showMenu && state.gate == AppGate.READY) {
            Box(
                Modifier.size(40.dp).clip(Capsule()).clickable { model.setMenu(true) },
                contentAlignment = Alignment.Center
            ) {
                BasicText("⋯", style = TextStyle(colors.content, 24.sp, FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun BrandMark(state: AppUiState, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(BrandBlue), contentAlignment = Alignment.Center) {
        val icon = state.brandIcon
        if (icon != null) {
            androidx.compose.foundation.Image(icon.asImageBitmap(), contentDescription = "", modifier = Modifier.fillMaxSize().padding(3.dp))
        } else {
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_aikayun_mark), contentDescription = "艾卡云", modifier = Modifier.fillMaxSize().padding(7.dp))
        }
    }
}

@Composable
private fun StartupOverlay(state: AppUiState, colors: AppColors, statusInset: androidx.compose.ui.unit.Dp, model: ShopViewModel) {
    Box(Modifier.fillMaxSize().background(if (colors.dark) DarkBackdrop.copy(0.90f) else LightBackdrop.copy(0.90f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(state, Modifier.size(62.dp))
            BasicText(state.binding?.siteName?.ifBlank { "艾卡云" } ?: "艾卡云", style = TextStyle(colors.content, 18.sp, FontWeight.Medium))
            BasicText("正在检查商城授权…", style = TextStyle(colors.secondary, 13.sp))
        }
    }
}

@Composable
private fun GateOverlay(message: String, action: () -> Unit, actionLabel: String, backdrop: LayerBackdrop, colors: AppColors) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)).clickable(indication = null, interactionSource = null) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp).clip(RoundedCornerShape(24.dp))
                .background(colors.card).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(message, style = TextStyle(colors.content, 15.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            BasicText(actionLabel, Modifier.clip(Capsule()).background(BrandBlue).clickable(onClick = action).padding(horizontal = 22.dp, vertical = 12.dp), TextStyle(Color.White, 14.sp, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun MenuOverlay(
    backdrop: LayerBackdrop,
    statusInset: androidx.compose.ui.unit.Dp,
    state: AppUiState,
    model: ShopViewModel,
    activity: MainActivity,
    colors: AppColors
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)).clickable(indication = null, interactionSource = null) { model.setMenu(false) })
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = statusInset + 70.dp, end = 12.dp).width(238.dp)
                .clip(RoundedCornerShape(24.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(24.dp) },
                    effects = { vibrancy(); blur(12.dp.toPx()); lens(16.dp.toPx(), 24.dp.toPx()) },
                    onDrawSurface = { drawRect(colors.card.copy(alpha = 0.96f)) }
                ).padding(vertical = 8.dp)
        ) {
            if (state.paymentRecoveryUrl != null) {
                MenuItem("↩", "返回订单", colors) { model.returnToPaymentRecovery() }
            }
            MenuItem("‹", "返回", colors) { model.setMenu(false); model.goBack() }
            MenuItem("↻", "刷新", colors) { model.setMenu(false); model.reload() }
            MenuItem("⌂", "回到主页", colors) { model.setMenu(false); model.goHome() }
            MenuItem("▤", if (state.desktopMode) "切换手机布局" else "电脑模式", colors) { model.setMenu(false); model.setDesktopMode(!state.desktopMode) }
            MenuItem("↗", "分享当前页", colors) { model.setMenu(false); activity.shareCurrentPage() }
            MenuItem("◎", "在浏览器打开", colors) { model.setMenu(false); activity.openCurrentPageInBrowser() }
            MenuItem("ⓘ", "关于", colors) { model.setAbout(true) }
        }
    }
}

@Composable
private fun ImageActionsOverlay(
    backdrop: LayerBackdrop,
    bottomInset: androidx.compose.ui.unit.Dp,
    imageUrl: String,
    model: ShopViewModel,
    colors: AppColors
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)).clickable(indication = null, interactionSource = null) { model.dismissImageActions() })
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 14.dp)
                .fillMaxWidth().widthIn(max = 520.dp).clip(RoundedCornerShape(24.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(24.dp) },
                    effects = { vibrancy(); blur(14.dp.toPx()); lens(18.dp.toPx(), 26.dp.toPx()) },
                    onDrawSurface = { drawRect(colors.card.copy(alpha = 0.97f)) }
                ).padding(10.dp)
        ) {
            BasicText("图片", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), TextStyle(colors.secondary, 12.sp))
            MenuItem("↓", "保存图片", colors) { model.savePageImage(imageUrl, false) }
            MenuItem("↗", "分享图片", colors) { model.savePageImage(imageUrl, true) }
        }
    }
}

@Composable
private fun MediaFallbackOverlay(
    backdrop: LayerBackdrop,
    bottomInset: androidx.compose.ui.unit.Dp,
    state: MediaFallbackState,
    activity: MainActivity,
    colors: AppColors
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 8.dp)
                .fillMaxWidth().widthIn(max = 560.dp).height(420.dp).clip(RoundedCornerShape(26.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(26.dp) },
                    effects = { vibrancy(); blur(14.dp.toPx()); lens(18.dp.toPx(), 26.dp.toPx()) },
                    onDrawSurface = { drawRect(colors.card.copy(alpha = 0.98f)) }
                ).padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("兼容相册", style = TextStyle(colors.content, 17.sp, FontWeight.SemiBold))
                Spacer(Modifier.weight(1f))
                BasicText("取消", Modifier.clickable { activity.closeMediaFallback() }.padding(8.dp), TextStyle(colors.secondary, 14.sp))
            }
            if (state.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText("正在读取媒体…", style = TextStyle(colors.secondary, 14.sp))
                }
            } else if (state.items.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    BasicText("没有可访问的媒体", style = TextStyle(colors.secondary, 14.sp))
                    Spacer(Modifier.height(12.dp))
                    BasicText("允许访问相册", Modifier.clickable { activity.requestMediaAccessForFallback() }.padding(8.dp), TextStyle(BrandBlue, 14.sp, FontWeight.Medium))
                    BasicText("选择其他位置", Modifier.clickable { activity.openMediaFallbackDocuments() }.padding(8.dp), TextStyle(colors.secondary, 14.sp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(state.items, key = { it.uri.toString() }) { item ->
                        val selected = item.uri.toString() in state.selected
                        val thumbnail by produceState<Bitmap?>(null, item.uri) {
                            value = runCatching {
                                withContext(Dispatchers.IO) {
                                    activity.contentResolver.loadThumbnail(item.uri, Size(180, 180), null)
                                }
                            }.getOrNull()
                        }
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (colors.dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                                .clickable { activity.selectMediaFallbackItem(item) }
                        ) {
                            if (thumbnail != null) {
                                androidx.compose.foundation.Image(thumbnail!!.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    BasicText(if (item.kind == "video") "▶" else "▧", style = TextStyle(colors.secondary, 28.sp))
                                }
                            }
                            if (selected) {
                                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(Capsule()).background(BrandBlue), contentAlignment = Alignment.Center) {
                                    BasicText("✓", style = TextStyle(Color.White, 13.sp, FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }
            if (state.multiple) {
                BasicText(
                    if (state.selected.isEmpty()) "完成" else "完成（${state.selected.size}）",
                    Modifier.align(Alignment.End).padding(top = 8.dp).clip(Capsule()).background(BrandBlue)
                        .clickable { activity.finishMediaFallbackSelection() }.padding(horizontal = 20.dp, vertical = 10.dp),
                    TextStyle(Color.White, 14.sp, FontWeight.Medium)
                )
            }
        }
    }
}

@Composable
private fun MenuItem(symbol: String, label: String, colors: AppColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BasicText(symbol, style = TextStyle(colors.secondary, 18.sp, FontWeight.Medium))
        BasicText(label, style = TextStyle(colors.content, 14.sp))
    }
}

@Composable
private fun AboutOverlay(backdrop: LayerBackdrop, state: AppUiState, model: ShopViewModel, activity: MainActivity, colors: AppColors) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)).clickable(indication = null, interactionSource = null) { model.setAbout(false) })
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = 460.dp)
                .clip(RoundedCornerShape(28.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = { vibrancy(); blur(14.dp.toPx()); lens(20.dp.toPx(), 30.dp.toPx()) },
                    onDrawSurface = { drawRect(colors.card.copy(alpha = 0.97f)) }
                ).padding(22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(state, Modifier.size(42.dp))
                Column {
                    BasicText("艾卡云", style = TextStyle(colors.content, 18.sp, FontWeight.SemiBold))
                    BasicText("版本 1.0.1（1）", style = TextStyle(colors.secondary, 12.sp))
                }
                Spacer(Modifier.weight(1f))
                BasicText("×", Modifier.clickable { model.setAbout(false) }.padding(6.dp), TextStyle(colors.secondary, 22.sp))
            }
            BasicText("当前商城", style = TextStyle(colors.secondary, 12.sp))
            BasicText(state.binding?.origin?.removePrefix("https://")?.trimEnd('/') ?: "—", style = TextStyle(colors.content, 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            AboutAction("权限设置", colors) { model.setAbout(false); activity.openPermissionSettings() }
            AboutAction("添加商城到桌面", colors) { activity.createPinnedShortcut() }
            BasicText("开源许可", style = TextStyle(colors.content, 14.sp, FontWeight.SemiBold))
            val license = remember {
                runCatching { activity.resources.openRawResource(R.raw.android_liquid_glass_license).bufferedReader().use { it.readText() } }
                    .getOrDefault("AndroidLiquidGlass · Apache License 2.0")
            }
            BasicText(license, style = TextStyle(colors.secondary, 10.sp), maxLines = Int.MAX_VALUE)
            BasicText("AndroidLiquidGlass · eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5", style = TextStyle(colors.secondary.copy(alpha = 0.72f), 10.sp))
        }
    }
}

@Composable
private fun AboutAction(label: String, colors: AppColors, onClick: () -> Unit) {
    BasicText(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClick).padding(13.dp), TextStyle(colors.content, 14.sp))
}

private fun setTransparentSystemBars(window: Window, dark: Boolean) {
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}
