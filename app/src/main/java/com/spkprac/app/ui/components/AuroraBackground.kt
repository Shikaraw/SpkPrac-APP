package com.spkprac.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.LocalUiPrefs
import com.spkprac.app.ui.theme.isDarkScheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Soft, slowly drifting colour field painted behind a screen.
 *
 * The animation phase is read inside the draw lambda so a running aurora only invalidates
 * drawing — it never recomposes the content on top. When the user turns animations off the
 * phase is frozen at 0 and nothing is scheduled.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isDarkScheme()
    val motion = LocalUiPrefs.current.animations && animated
    val backdrop = Brand.backdrop(dark)
    val accentA = if (dark) Brand.Teal else Brand.Mint
    val accentB = if (dark) Brand.Aqua else Brand.Sky
    val accentC = if (dark) Brand.Violet else Brand.Amber

    val phase: State<Float> = if (motion) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
            label = "auroraPhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(backdrop))
        )
        Canvas(Modifier.matchParentSize()) {
            val p = phase.value
            val tau = p * 6.2831855f
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.62f

            fun blob(cx: Float, cy: Float, color: Color, alpha: Float) {
                val center = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = r
                    ),
                    radius = r,
                    center = center
                )
            }

            blob(
                cx = w * (0.22f + 0.10f * sin(tau)),
                cy = h * (0.16f + 0.05f * cos(tau * 0.8f)),
                color = accentA,
                alpha = if (dark) 0.30f else 0.55f
            )
            blob(
                cx = w * (0.86f + 0.10f * cos(tau * 0.7f)),
                cy = h * (0.30f + 0.06f * sin(tau * 1.1f)),
                color = accentB,
                alpha = if (dark) 0.22f else 0.38f
            )
            blob(
                cx = w * (0.55f + 0.12f * sin(tau * 0.9f + 2f)),
                cy = h * (0.82f + 0.05f * cos(tau * 1.2f)),
                color = accentC,
                alpha = if (dark) 0.16f else 0.26f
            )
        }
        content()
    }
}
