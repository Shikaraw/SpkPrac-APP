package com.spkprac.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.LocalUiPrefs
import com.spkprac.app.ui.theme.isDarkScheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Visual state of the conversation, driving how the orb animates. */
enum class OrbState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * The "voice presence" indicator: a hand-drawn orb whose motion mirrors what the app is doing.
 *
 * Everything is done with Canvas primitives so the app keeps its tiny APK — no animation library.
 * Both the animation phase and the smoothed mic level are read inside the draw scope, so a running
 * orb only invalidates drawing instead of recomposing its parents.
 */
@Composable
fun VoiceOrb(
    state: OrbState,
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp
) {
    val animations = LocalUiPrefs.current.animations
    val dark = isDarkScheme()
    val accent = Brand.accent(dark)
    val warm = Brand.warm(dark)
    val errorColor = MaterialTheme.colorScheme.error
    val surface = MaterialTheme.colorScheme.surface

    val phase: State<Float> = if (animations) {
        rememberInfiniteTransition(label = "orb").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(
                    durationMillis = when (state) {
                        OrbState.THINKING -> 2600
                        OrbState.CONNECTING -> 1400
                        else -> 4200
                    },
                    easing = LinearEasing
                )
            ),
            label = "orbPhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    // Smooth the 20 Hz level updates so the pulse never looks steppy.
    val smoothLevel = animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
        label = "orbLevel"
    )

    Canvas(modifier.size(size)) {
        val p = phase.value
        val lv = smoothLevel.value
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val base = minOf(w, h) / 2f * 0.52f
        val twoPi = (2 * PI).toFloat()

        val activeColor = when (state) {
            OrbState.ERROR -> errorColor
            OrbState.THINKING -> warm.first()
            OrbState.SPEAKING -> accent.first()
            else -> accent.last()
        }
        val breath = when (state) {
            OrbState.LISTENING -> base * (0.02f + 0.10f * lv)
            OrbState.SPEAKING -> base * (0.05f + 0.16f * lv)
            OrbState.THINKING -> base * (0.04f + 0.03f * sin(p * twoPi))
            OrbState.CONNECTING -> base * 0.05f
            OrbState.ERROR -> base * 0.02f
            OrbState.IDLE -> base * (0.02f + 0.02f * sin(p * twoPi))
        }
        val coreR = base + breath

        // Outer halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(activeColor.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(cx, cy),
                radius = coreR * 1.9f
            ),
            radius = coreR * 1.9f,
            center = Offset(cx, cy)
        )

        // Expanding ripples while speaking / listening to the user.
        if (state == OrbState.SPEAKING || state == OrbState.LISTENING) {
            val rippleCount = 2
            for (i in 0 until rippleCount) {
                val t = (p + i * 0.5f) % 1f
                val r = coreR * (1.05f + t * 0.75f)
                val alpha = (1f - t) * if (state == OrbState.SPEAKING) 0.38f else 0.22f
                drawCircle(
                    color = activeColor.copy(alpha = alpha),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.4f + 2.2f * (1f - t))
                )
            }
        }

        // Rotating dashed ring for thinking / connecting.
        if (state == OrbState.THINKING || state == OrbState.CONNECTING) {
            rotate(degrees = p * 360f, pivot = Offset(cx, cy)) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(activeColor.copy(alpha = 0.05f), activeColor.copy(alpha = 0.85f))
                    ),
                    startAngle = 0f,
                    sweepAngle = if (state == OrbState.CONNECTING) 200f else 300f,
                    useCenter = false,
                    topLeft = Offset(cx - coreR * 1.30f, cy - coreR * 1.30f),
                    size = Size(coreR * 2.6f, coreR * 2.6f),
                    style = Stroke(
                        width = 3.5f,
                        pathEffect = if (state == OrbState.THINKING) {
                            PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)
                        } else null
                    )
                )
            }
        }

        // Static track ring keeps the orb anchored.
        drawCircle(
            color = activeColor.copy(alpha = 0.16f),
            radius = coreR * 1.30f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )

        // Glowing core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.30f else 0.55f),
                    activeColor.copy(alpha = 0.95f),
                    activeColor
                ),
                center = Offset(cx - coreR * 0.18f, cy - coreR * 0.22f),
                radius = coreR * 1.15f
            ),
            radius = coreR,
            center = Offset(cx, cy)
        )

        // Specular highlight
        drawCircle(
            color = surface.copy(alpha = if (dark) 0.14f else 0.55f),
            radius = coreR * 0.30f,
            center = Offset(cx - coreR * 0.32f, cy - coreR * 0.36f)
        )

        // A ring of little bars rides the core edge while the assistant speaks.
        if (state == OrbState.SPEAKING) {
            val bars = 36
            val ringR = coreR * 1.16f
            for (i in 0 until bars) {
                val angle = (i.toFloat() / bars) * twoPi
                val wave = 0.5f + 0.5f * sin(angle * 3f + p * twoPi * 2f)
                val len = (3f + 9f * wave) * (0.35f + 0.65f * lv)
                val r1 = ringR
                val r2 = ringR + len
                val sx = cx + cos(angle) * r1
                val sy = cy + sin(angle) * r1
                val ex = cx + cos(angle) * r2
                val ey = cy + sin(angle) * r2
                drawLine(
                    color = activeColor.copy(alpha = 0.20f + 0.55f * wave),
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = 3f
                )
            }
        }
    }
}

/** Convenience mapper so screens do not have to repeat the when-expression. */
fun orbStateOf(
    status: com.spkprac.app.core.SessionViewModel.Status,
    agent: com.spkprac.app.core.SessionViewModel.AgentState
): OrbState = when (status) {
    com.spkprac.app.core.SessionViewModel.Status.IDLE -> OrbState.IDLE
    com.spkprac.app.core.SessionViewModel.Status.CONNECTING -> OrbState.CONNECTING
    com.spkprac.app.core.SessionViewModel.Status.RECONNECTING -> OrbState.CONNECTING
    com.spkprac.app.core.SessionViewModel.Status.ENDED -> OrbState.IDLE
    com.spkprac.app.core.SessionViewModel.Status.FAILED -> OrbState.ERROR
    com.spkprac.app.core.SessionViewModel.Status.CONNECTED -> when (agent) {
        com.spkprac.app.core.SessionViewModel.AgentState.LISTENING -> OrbState.LISTENING
        com.spkprac.app.core.SessionViewModel.AgentState.THINKING -> OrbState.THINKING
        com.spkprac.app.core.SessionViewModel.AgentState.SPEAKING -> OrbState.SPEAKING
    }
}

/** Small breathing dots used as a "thinking" affordance. */
@Composable
fun ThinkingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 7.dp
) {
    val animations = LocalUiPrefs.current.animations
    val phase: State<Float> = if (animations) {
        rememberInfiniteTransition(label = "dots").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "dotsPhase"
        )
    } else {
        remember { mutableFloatStateOf(0.55f) }
    }
    Canvas(modifier.size(dotSize * 5, dotSize)) {
        val p = phase.value
        val gap = dotSize.toPx() * 1.9f
        val r = dotSize.toPx() / 2f
        val cy = size.height / 2f
        for (i in 0 until 3) {
            val t = ((p + i * 0.18f) % 1f)
            val bump = sin(t * PI.toFloat()).coerceAtLeast(0f)
            drawCircle(
                color = color.copy(alpha = 0.30f + 0.70f * bump),
                radius = r * (0.72f + 0.28f * bump),
                center = Offset(r + i * gap, cy - bump * r * 0.9f)
            )
        }
    }
}

/** Vertical level meter driven by the microphone RMS. */
@Composable
fun LevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    bars: Int = 26,
    active: Boolean = true
) {
    val animations = LocalUiPrefs.current.animations
    val dark = isDarkScheme()
    val accent = Brand.accent(dark)
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val smooth = animateFloatAsState(
        targetValue = if (active) level.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.75f),
        label = "meterLevel"
    )
    val phase: State<Float> = if (animations) {
        rememberInfiniteTransition(label = "meter").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "meterPhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(modifier) {
        val lv = smooth.value
        val p = phase.value
        val gap = size.width / bars
        val barW = gap * 0.46f
        for (i in 0 until bars) {
            val centerBias = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val wobble = 0.55f + 0.45f * sin((i * 0.55f) + p * 6.2831855f)
            val h = size.height * (0.10f + 0.90f * lv * centerBias * wobble).coerceIn(0.06f, 1f)
            val x = i * gap + (gap - barW) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(accent.first().copy(alpha = 0.95f), accent.last().copy(alpha = 0.35f)),
                    startY = size.height - h,
                    endY = size.height
                ),
                topLeft = Offset(x, size.height - h),
                size = Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
            )
        }
        // baseline
        drawLine(
            color = idleColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.5f
        )
    }
}
