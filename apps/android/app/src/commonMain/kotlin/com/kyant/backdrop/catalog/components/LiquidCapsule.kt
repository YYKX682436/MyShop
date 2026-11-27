// Capsule material extracted from LiquidBottomTabs.kt at AndroidLiquidGlass eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5.
package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

@Composable
fun LiquidCapsule(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val surfaceColor = if (isSystemInDarkTheme()) {
        Color(0xFF121212).copy(alpha = 0.34f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.38f)
    }
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(8f.dp.toPx())
                lens(24f.dp.toPx(), 24f.dp.toPx())
            },
            onDrawSurface = { drawRect(surfaceColor) }
        )
    ) {
        content()
    }
}
