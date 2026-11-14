package tv.prism

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path

@Composable fun PlatformIcon(platform: MusicPlatform, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(when (platform) {
        MusicPlatform.NETEASE -> Color(0xFFD43C33)
        MusicPlatform.BILIBILI -> Color(0xFF00A1D6)
        else -> c.panel
    }).semantics { contentDescription = platform.label }, contentAlignment = Alignment.Center) {
        if (platform.icon != null) Image(painterResource(platform.icon), null, Modifier.fillMaxSize(when (platform) {
            MusicPlatform.NETEASE, MusicPlatform.BILIBILI -> .72f
            MusicPlatform.SPOTIFY, MusicPlatform.YOUTUBE -> .86f
            else -> 1f
        }))
        else Canvas(Modifier.fillMaxSize(.62f)) {
            val stroke = 2.dp.toPx()
            if (platform == MusicPlatform.WEBDAV) {
                drawCircle(c.accent, radius = size.minDimension * .22f, center = Offset(size.width * .42f, size.height * .43f), style = Stroke(stroke))
                drawLine(c.accent, Offset(size.width * .16f, size.height * .72f), Offset(size.width * .86f, size.height * .72f), stroke)
                drawLine(c.accent, Offset(size.width * .67f, size.height * .43f), Offset(size.width * .86f, size.height * .72f), stroke)
            } else if (platform == MusicPlatform.OTHER) {
                drawLine(c.accent, Offset(size.width * .62f, size.height * .16f), Offset(size.width * .62f, size.height * .68f), stroke, StrokeCap.Round)
                drawLine(c.accent, Offset(size.width * .62f, size.height * .16f), Offset(size.width * .86f, size.height * .10f), stroke, StrokeCap.Round)
                drawCircle(c.accent, radius = size.minDimension * .16f, center = Offset(size.width * .46f, size.height * .72f))
            } else {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .5f, 0f); lineTo(size.width, size.height * .5f)
                    lineTo(size.width * .5f, size.height); lineTo(0f, size.height * .5f); close()
                }
                drawPath(path, c.accent, style = Stroke(stroke))
            }
        }
    }
}

@Composable fun PlatformAction(platform: MusicPlatform, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    val currentClick by rememberUpdatedState(onClick)
    Button(onClick = onClick, modifier = modifier.pointerInput(Unit) { detectTapGestures { currentClick() } }, colors = ButtonDefaults.colors(containerColor = c.panel, contentColor = c.foreground,
        focusedContainerColor = c.accent, focusedContentColor = c.onAccent), shape = ButtonDefaults.shape(RoundedCornerShape(50))) {
        PlatformIcon(platform, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
internal fun Glyph(kind: String, modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = 1.5.dp.toPx()
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        when (kind) {
            "back" -> drawPath(path { moveTo(s * .60f, s * .22f); lineTo(s * .38f, s * .5f); lineTo(s * .60f, s * .78f) },
                color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            "play" -> drawPath(path { moveTo(s * .34f, s * .18f); lineTo(s * .82f, s * .5f); lineTo(s * .34f, s * .82f); close() }, color)
            "pause" -> {
                drawRoundRect(color, topLeft = p(.33f, .20f), size = Size(s * .11f, s * .60f), cornerRadius = CornerRadius(s * .02f))
                drawRoundRect(color, topLeft = p(.56f, .20f), size = Size(s * .11f, s * .60f), cornerRadius = CornerRadius(s * .02f))
            }
            "prev" -> {
                drawRoundRect(color, topLeft = p(.22f, .20f), size = Size(s * .07f, s * .60f), cornerRadius = CornerRadius(s * .02f))
                drawPath(path { moveTo(s * .80f, s * .20f); lineTo(s * .36f, s * .5f); lineTo(s * .80f, s * .80f); close() }, color)
            }
            "next" -> {
                drawRoundRect(color, topLeft = p(.71f, .20f), size = Size(s * .07f, s * .60f), cornerRadius = CornerRadius(s * .02f))
                drawPath(path { moveTo(s * .20f, s * .20f); lineTo(s * .64f, s * .5f); lineTo(s * .20f, s * .80f); close() }, color)
            }
            "heart", "heartFilled" -> {
                val h = path {
                    moveTo(s * .5f, s * .86f)
                    cubicTo(s * .42f, s * .78f, s * .12f, s * .58f, s * .12f, s * .34f)
                    cubicTo(s * .12f, s * .14f, s * .36f, s * .10f, s * .5f, s * .28f)
                    cubicTo(s * .64f, s * .10f, s * .88f, s * .14f, s * .88f, s * .34f)
                    cubicTo(s * .88f, s * .58f, s * .58f, s * .78f, s * .5f, s * .86f)
                    close()
                }
                if (kind == "heartFilled") drawPath(h, color)
                else drawPath(h, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "aurora" -> {
                val upper = path {
                    moveTo(s * .08f, s * .60f)
                    cubicTo(s * .30f, s * .16f, s * .46f, s * .86f, s * .92f, s * .36f)
                }
                val lower = path {
                    moveTo(s * .08f, s * .78f)
                    cubicTo(s * .30f, s * .34f, s * .46f, s * .98f, s * .92f, s * .54f)
                }
                drawPath(upper, color, style = Stroke(w, cap = StrokeCap.Round))
                drawPath(lower, color.copy(alpha = .45f), style = Stroke(w, cap = StrokeCap.Round))
            }
            "radio" -> {
                drawLine(color, p(.27f, .26f), p(.72f, .10f), w, StrokeCap.Round)
                drawRoundRect(color, p(.15f, .30f), Size(s * .70f, s * .53f), CornerRadius(s * .08f), style = Stroke(w))
                drawCircle(color, s * .12f, p(.36f, .57f), style = Stroke(w))
                drawLine(color, p(.60f, .49f), p(.73f, .49f), w, StrokeCap.Round)
                drawLine(color, p(.60f, .65f), p(.73f, .65f), w, StrokeCap.Round)
            }
            "keyboard" -> {
                drawRoundRect(color, p(.08f, .24f), Size(s * .84f, s * .52f), CornerRadius(s * .05f), style = Stroke(w))
                for (x in listOf(.25f, .42f, .59f, .76f)) for (y in listOf(.39f, .53f))
                    drawCircle(color, s * .025f, p(x, y))
                drawLine(color, p(.34f, .66f), p(.66f, .66f), w, StrokeCap.Round)
            }
            "list" -> for (y in listOf(.28f, .5f, .72f)) drawLine(color, p(.22f, y), p(.78f, y), w, StrokeCap.Round)
            "add", "plus" -> {
                drawLine(color, p(.5f, .2f), p(.5f, .8f), w, StrokeCap.Round)
                drawLine(color, p(.2f, .5f), p(.8f, .5f), w, StrokeCap.Round)
            }
            "shuffle" -> {
                drawLine(color, p(.15f, .30f), p(.32f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.32f, .30f), p(.68f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.68f, .70f), p(.85f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.15f, .70f), p(.32f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.32f, .70f), p(.68f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.68f, .30f), p(.85f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.74f, .20f), p(.85f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.85f, .30f), p(.74f, .40f), w, StrokeCap.Round)
                drawLine(color, p(.74f, .60f), p(.85f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.85f, .70f), p(.74f, .80f), w, StrokeCap.Round)
            }
            "repeat", "repeatOne" -> {
                drawLine(color, p(.2f, .32f), p(.8f, .32f), w, StrokeCap.Round)
                drawLine(color, p(.65f, .17f), p(.8f, .32f), w, StrokeCap.Round)
                drawLine(color, p(.8f, .32f), p(.65f, .47f), w, StrokeCap.Round)
                drawLine(color, p(.8f, .68f), p(.2f, .68f), w, StrokeCap.Round)
                drawLine(color, p(.35f, .53f), p(.2f, .68f), w, StrokeCap.Round)
                drawLine(color, p(.2f, .68f), p(.35f, .83f), w, StrokeCap.Round)
                if (kind == "repeatOne") drawLine(color, p(.5f, .42f), p(.5f, .58f), w * 1.5f, StrokeCap.Round)
            }
            "diamond" -> drawPath(path {
                moveTo(s * .5f, s * .10f); lineTo(s * .90f, s * .5f); lineTo(s * .5f, s * .90f); lineTo(s * .10f, s * .5f); close()
            }, color, style = Stroke(w * .8f, join = StrokeJoin.Round))
            /* 音质不是信号强度：递增的柱子只会被读成 Wi-Fi 信号格。改成推子——三根暗轨各挂
               一个滑块，滑块高低错开，读作「在调声音的那一档」。轨道压到 w*.55 且不做端点，
               滑块才占主导，整块形状是横竖交织的，不会退化成柱状图。 */
            "fader" -> {
                val knobs = listOf(.60f, .30f, .47f)
                listOf(.24f, .50f, .76f).forEachIndexed { i, x ->
                    drawLine(color.copy(alpha = .34f), p(x, .13f), p(x, .87f), w * .55f, StrokeCap.Round)
                    drawRoundRect(
                        color, topLeft = Offset(s * (x - .095f), s * (knobs[i] - .05f)),
                        size = Size(s * .19f, s * .10f), cornerRadius = CornerRadius(s * .05f),
                    )
                }
            }
            "search" -> {
                drawCircle(color, radius = s * .27f, center = p(.44f, .44f), style = Stroke(w))
                drawLine(color, p(.64f, .64f), p(.84f, .84f), w, StrokeCap.Round)
            }
            "history" -> {
                drawCircle(color, radius = s * .30f, center = p(.5f, .52f), style = Stroke(w))
                drawLine(color, p(.5f, .52f), p(.5f, .34f), w, StrokeCap.Round)
                drawLine(color, p(.5f, .52f), p(.66f, .60f), w, StrokeCap.Round)
            }
            "undo" -> {
                drawArc(color, startAngle = -90f, sweepAngle = -300f, useCenter = false,
                    topLeft = p(.24f, .26f), size = Size(s * .52f, s * .52f), style = Stroke(w, cap = StrokeCap.Round))
                val tip = p(.5f, .26f)
                drawLine(color, tip, Offset(tip.x + s * .13f, tip.y - s * .06f), w, StrokeCap.Round)
                drawLine(color, tip, Offset(tip.x + s * .11f, tip.y + s * .10f), w, StrokeCap.Round)
            }
            "qr" -> {
                for (corner in listOf(p(.18f, .18f), p(.56f, .18f), p(.18f, .56f))) {
                    drawRoundRect(color, topLeft = corner, size = Size(s * .26f, s * .26f),
                        cornerRadius = CornerRadius(s * .04f), style = Stroke(w * .9f))
                }
                drawRoundRect(color, topLeft = p(.62f, .62f), size = Size(s * .08f, s * .08f))
                drawRoundRect(color, topLeft = p(.76f, .62f), size = Size(s * .08f, s * .08f))
                drawRoundRect(color, topLeft = p(.62f, .76f), size = Size(s * .08f, s * .08f))
            }
            "mv" -> {
                drawRoundRect(color, topLeft = p(.14f, .24f), size = Size(s * .72f, s * .52f),
                    cornerRadius = CornerRadius(s * .06f), style = Stroke(w))
                drawPath(path { moveTo(s * .42f, s * .38f); lineTo(s * .66f, s * .5f); lineTo(s * .42f, s * .62f); close() }, color)
            }
            "artist" -> {
                drawCircle(color, radius = s * .14f, center = p(.5f, .34f), style = Stroke(w))
                drawPath(path {
                    moveTo(s * .22f, s * .80f)
                    cubicTo(s * .24f, s * .58f, s * .38f, s * .53f, s * .5f, s * .53f)
                    cubicTo(s * .62f, s * .53f, s * .76f, s * .58f, s * .78f, s * .80f)
                }, color, style = Stroke(w, cap = StrokeCap.Round))
            }
            /* A microphone reads as "the singer" faster than a bust at 22dp. */
            "mic" -> {
                drawRoundRect(color, topLeft = p(.385f, .12f), size = Size(s * .23f, s * .36f),
                    cornerRadius = CornerRadius(s * .115f), style = Stroke(w))
                drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = p(.30f, .30f), size = Size(s * .40f, s * .34f), style = Stroke(w, cap = StrokeCap.Round))
                drawLine(color, p(.5f, .64f), p(.5f, .80f), w, StrokeCap.Round)
                drawLine(color, p(.34f, .82f), p(.66f, .82f), w, StrokeCap.Round)
            }
            "album" -> {
                drawCircle(color, radius = s * .30f, center = p(.5f, .5f), style = Stroke(w))
                drawCircle(color, radius = s * .20f, center = p(.5f, .5f), style = Stroke(w * .7f))
                drawCircle(color, radius = s * .075f, center = p(.5f, .5f))
            }
            "trash" -> {
                drawLine(color, p(.2f, .30f), p(.8f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.4f, .22f), p(.6f, .22f), w, StrokeCap.Round)
                drawPath(path {
                    moveTo(s * .3f, s * .3f); lineTo(s * .34f, s * .82f); lineTo(s * .66f, s * .82f); lineTo(s * .7f, s * .3f)
                }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(color, p(.44f, .42f), p(.45f, .7f), w * .8f, StrokeCap.Round)
                drawLine(color, p(.56f, .42f), p(.55f, .7f), w * .8f, StrokeCap.Round)
            }
            "close" -> {
                drawLine(color, p(.28f, .28f), p(.72f, .72f), w, StrokeCap.Round)
                drawLine(color, p(.72f, .28f), p(.28f, .72f), w, StrokeCap.Round)
            }
            /* One stroke, no frame: the selection control already draws the box around it. */
            "check" -> drawPath(path {
                moveTo(s * .26f, s * .53f); lineTo(s * .43f, s * .71f); lineTo(s * .75f, s * .29f)
            }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            /* Four corner brackets: "give this the whole screen". */
            "expand" -> {
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(color, p(x, y), p(x + dx, y), w, StrokeCap.Round)
                    drawLine(color, p(x, y), p(x, y + dy), w, StrokeCap.Round)
                }
                corner(.18f, .18f, .16f, .16f)
                corner(.82f, .18f, -.16f, .16f)
                corner(.18f, .82f, .16f, -.16f)
                corner(.82f, .82f, -.16f, -.16f)
            }
            "bulbOff" -> {
                drawPath(path {
                    moveTo(s * .34f, s * .62f)
                    cubicTo(s * .34f, s * .51f, s * .22f, s * .47f, s * .22f, s * .34f)
                    cubicTo(s * .22f, s * .19f, s * .35f, s * .10f, s * .50f, s * .10f)
                    cubicTo(s * .65f, s * .10f, s * .78f, s * .19f, s * .78f, s * .34f)
                    cubicTo(s * .78f, s * .47f, s * .66f, s * .51f, s * .66f, s * .62f)
                }, color, style = Stroke(w, cap = StrokeCap.Round))
                drawLine(color, p(.34f, .72f), p(.66f, .72f), w, StrokeCap.Round)
                drawLine(color, p(.40f, .84f), p(.60f, .84f), w, StrokeCap.Round)
                drawLine(color, p(.14f, .14f), p(.86f, .86f), w, StrokeCap.Round)
            }
            /* A sung note over two text lines: the word being sung, not just text. */
            "lyric" -> {
                drawCircle(color, radius = s * .10f, center = p(.30f, .28f), style = Stroke(w))
                drawLine(color, p(.38f, .26f), p(.38f, .46f), w, StrokeCap.Round)
                drawLine(color, p(.38f, .46f), p(.55f, .41f), w, StrokeCap.Round)
                drawLine(color, p(.58f, .63f), p(.84f, .63f), w, StrokeCap.Round)
                drawLine(color, p(.50f, .82f), p(.84f, .82f), w, StrokeCap.Round)
            }
            "palette" -> {
                drawCircle(color, radius = s * .40f, center = p(.5f, .5f), style = Stroke(w))
                for (dot in listOf(p(.32f, .52f), p(.41f, .33f), p(.60f, .31f))) drawCircle(color, s * .05f, dot)
                drawCircle(color, radius = s * .07f, center = p(.62f, .63f), style = Stroke(w * .8f))
            }
            "compass" -> {
                drawCircle(color, radius = s * .42f, center = p(.5f, .5f), style = Stroke(w))
                drawPath(path {
                    moveTo(s * .66f, s * .34f); lineTo(s * .57f, s * .57f); lineTo(s * .34f, s * .66f); lineTo(s * .43f, s * .43f); close()
                }, color, style = Stroke(w * .9f, join = StrokeJoin.Round))
            }
            "layers" -> {
                drawPath(path {
                    moveTo(s * .5f, s * .14f); lineTo(s * .86f, s * .34f); lineTo(s * .5f, s * .54f); lineTo(s * .14f, s * .34f); close()
                }, color, style = Stroke(w, join = StrokeJoin.Round))
                drawPath(path { moveTo(s * .14f, s * .52f); lineTo(s * .5f, s * .72f); lineTo(s * .86f, s * .52f) },
                    color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path { moveTo(s * .14f, s * .68f); lineTo(s * .5f, s * .88f); lineTo(s * .86f, s * .68f) },
                    color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "info" -> {
                drawCircle(color, radius = s * .42f, center = p(.5f, .5f), style = Stroke(w))
                drawLine(color, p(.5f, .46f), p(.5f, .68f), w, StrokeCap.Round)
                drawCircle(color, s * .045f, p(.5f, .32f))
            }
            "chev" -> drawPath(path {
                moveTo(s * .26f, s * .38f); lineTo(s * .5f, s * .62f); lineTo(s * .74f, s * .38f)
            }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            /* 进页面的箭头朝右：⌄ 读作「向下展开」，而这个胶囊按下去是进入播放页。 */
            "chevR" -> drawPath(path {
                moveTo(s * .38f, s * .26f); lineTo(s * .62f, s * .5f); lineTo(s * .38f, s * .74f)
            }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            "refresh" -> {
                drawArc(color, startAngle = -90f, sweepAngle = -285f, useCenter = false,
                    topLeft = p(.18f, .18f), size = Size(s * .64f, s * .64f), style = Stroke(w, cap = StrokeCap.Round))
                val tip = p(.82f, .41f)
                drawLine(color, tip, Offset(tip.x - s * .13f, tip.y - s * .12f), w, StrokeCap.Round)
                drawLine(color, tip, Offset(tip.x - s * .12f, tip.y + s * .12f), w, StrokeCap.Round)
            }
            /* 链条：两节互扣的环节，斜 45°。之前是两段反向圆弧，缩到 18dp 就只剩
               一个循环箭头——那是「回收」，不是「链接」。 */
            "link" -> {
                rotate(-45f) {
                    drawRoundRect(color, topLeft = p(.14f, .41f), size = Size(s * .44f, s * .18f),
                        cornerRadius = CornerRadius(s * .09f), style = Stroke(w))
                    drawRoundRect(color, topLeft = p(.44f, .41f), size = Size(s * .44f, s * .18f),
                        cornerRadius = CornerRadius(s * .09f), style = Stroke(w))
                }
            }
            /* 导入：托盘 + 落进去的箭头。动作是「把歌单收进来」，不是「连一个地址」。 */
            "import" -> {
                drawLine(color, p(.5f, .12f), p(.5f, .56f), w, StrokeCap.Round)
                drawLine(color, p(.34f, .42f), p(.5f, .57f), w, StrokeCap.Round)
                drawLine(color, p(.66f, .42f), p(.5f, .57f), w, StrokeCap.Round)
                drawPath(path {
                    moveTo(s * .20f, s * .56f); lineTo(s * .20f, s * .84f)
                    lineTo(s * .80f, s * .84f); lineTo(s * .80f, s * .56f)
                }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "remote" -> {
                drawRoundRect(color, topLeft = p(.30f, .08f), size = Size(s * .40f, s * .84f),
                    cornerRadius = CornerRadius(s * .09f), style = Stroke(w))
                drawRoundRect(color, topLeft = p(.35f, .20f), size = Size(s * .30f, s * .50f),
                    cornerRadius = CornerRadius(s * .03f), style = Stroke(w * .8f))
                drawLine(color, p(.44f, .80f), p(.56f, .80f), w, StrokeCap.Round)
            }
        }
    }
}

/* An icon is only legible when it is the only thing in the row; that is the point of replacing
   搜索 and 手机输入 with a magnifier and a QR mark. The ring carries focus so the glyph itself
   never has to change colour. */
@Composable fun AuraIconButton(icon: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                               primary: Boolean = false, size: Dp = 38.dp) {
    val c = LocalPrismColors.current
    Box(modifier.size(size).auraFocus(CircleShape, 1.06f, ring = if (primary) c.foreground else null)
        .clip(CircleShape).background(if (primary) c.accent else c.panel)
        .clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Glyph(icon, Modifier.size(size * .6f), if (primary) c.onAccent else c.foreground.copy(alpha = .82f))
    }
}

/** A labelled icon tile for a dialog row: one glyph, one short label. */
@Composable fun PrismIconAction(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.width(112.dp).height(104.dp)
            .auraFocus(shape, 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) c.accent.copy(alpha = .17f) else c.background.copy(alpha = .5f))
            .border(1.dp, if (focused) c.accent else c.line.copy(alpha = .6f), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape)
            .background(if (active) c.accent else c.foreground.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            Glyph(icon, Modifier.size(26.dp), if (active) c.onAccent else c.foreground.copy(alpha = .88f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = if (focused || active) c.accent else c.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
