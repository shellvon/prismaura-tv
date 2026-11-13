package tv.prism

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Show the intro once per process, including across activity recreation. */
internal object PrismLaunch {
    var played = false
}

/** 冷启动的系统启动画面（launch_frame）就是下面这一帧：三角形画完、光还没散开。
    动画从这个进度接着走，静态 logo 不会重画一遍再消失。 */
private const val LAUNCH_FRAME = .30f

/** Vector intro driven by a single animation clock. */
@Composable
fun PrismIntro(motion: Boolean, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val c = LocalPrismColors.current
    val t = remember { Animatable(if (motion) LAUNCH_FRAME else .86f) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        /* A TV can take seconds to present the first frame. Until the window has focus the
           animation is playing to a buffer nobody sees, so the clock waits for it. */
        withTimeoutOrNull(2000) { while (!view.hasWindowFocus()) withFrameNanos { } }
        /* 用自己的时基，不用 tween：系统「动画缩放」为 0 时（不少电视出厂/省电模式就是这样）
           Compose 的 tween 会在一帧内跳到终点，开屏就等于没动画。这里逐帧算进度，
           动画只受应用内「减少动效」控制。 */
        if (!motion) {
            t.snapTo(.86f)
            delay(700)
        }
        val from = if (motion) LAUNCH_FRAME else .86f
        val span = if (motion) 900f else 200f
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val k = ((now - start) / 1_000_000.0).toFloat().div(span).coerceIn(0f, 1f)
            t.snapTo(from + (1f - from) * k)
            if (k >= 1f) break
        }
        onDone()
    }
    // Read the clock during drawing to avoid recomposing on every frame.
    Box(
        modifier.fillMaxSize().background(c.background).graphicsLayer {
            val p = t.value
            val out = ((p - .86f) / .14f).coerceIn(0f, 1f)
            alpha = 1f - out
            val s = 1f + .04f * out
            scaleX = s
            scaleY = s
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            fun ease(v: Float) = v * v * (3f - 2f * v)
            fun at(from: Float, to: Float) = ease(((p - from) / (to - from)).coerceIn(0f, 1f))

            val cx = size.width * .5f
            val cy = size.height * .44f
            val s = size.minDimension * .24f
            val stroke = (s * .014f).coerceAtLeast(1.4.dp.toPx())
            val ink = Brush.linearGradient(
                listOf(c.accent, c.glow),
                start = Offset(cx - s * .5f, cy + s * .4f), end = Offset(cx + s * .5f, cy - s * .5f),
            )
            val apex = Offset(cx, cy - s * .52f)
            val left = Offset(cx - s * .52f, cy + s * .40f)
            val right = Offset(cx + s * .52f, cy + s * .40f)
            val exit = Offset(cx + s * .286f, cy)

            val halo = at(.34f, .80f)
            if (halo > 0f) {
                val r = s * (1.1f + .35f * halo)
                drawCircle(
                    Brush.radialGradient(
                        listOf(c.accent.copy(alpha = .10f * halo), Color.Transparent),
                        center = Offset(cx, cy), radius = r,
                    ),
                    radius = r, center = Offset(cx, cy),
                )
            }

            val floor = at(.34f, .74f)
            if (floor > 0f) {
                drawOval(
                    Brush.radialGradient(
                        listOf(c.accent.copy(alpha = .14f * floor), Color.Transparent),
                        center = Offset(cx, cy + s * .60f), radius = s * .54f,
                    ),
                    topLeft = Offset(cx - s * .54f, cy + s * .46f),
                    size = Size(s * 1.08f, s * .28f),
                )
            }

            listOf(
                -13f to c.glow.copy(alpha = .75f),
                2f to c.accent.copy(alpha = .95f),
                18f to c.foreground.copy(alpha = .55f),
            ).forEachIndexed { i, (deg, color) ->
                val f = at(.34f + i * .05f, .74f + i * .05f)
                if (f <= 0f) return@forEachIndexed
                val rad = Math.toRadians(deg.toDouble())
                val reach = s * .95f * f
                val tip = Offset(exit.x + (reach * Math.cos(rad)).toFloat(), exit.y + (reach * Math.sin(rad)).toFloat())
                drawLine(
                    Brush.linearGradient(listOf(color, color.copy(alpha = .08f)), start = exit, end = tip),
                    start = exit, end = tip, strokeWidth = stroke * .78f, cap = StrokeCap.Round,
                )
            }

            val spark = at(.34f, .40f) * (1f - at(.46f, .62f))
            if (spark > 0f) drawCircle(c.foreground.copy(alpha = .7f * spark), radius = s * .025f, center = exit)

            // 三角最后画：光线看起来是从边里透出来的，交叠处被描边压住。
            listOf(apex, left, right, apex).zipWithNext().forEach { (a, b) ->
                drawLine(ink, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }

        Column(
            Modifier.padding(top = 152.dp).graphicsLayer {
                val n = ((t.value - .44f) / .30f).coerceIn(0f, 1f)
                alpha = n
                translationY = 10.dp.toPx() * (1f - n)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PrismAura", color = c.foreground, fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.6.sp)
            Spacer(Modifier.height(10.dp))
            Text("极 光 音 乐", color = c.muted.copy(alpha = .8f), fontSize = 11.sp, letterSpacing = 4.sp)
        }
    }
}
