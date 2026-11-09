package de.tomkainz.sonoslive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

// ---- Hand-drawn icons --------------------------------------------------

@Composable
internal fun CheckGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(22.dp)) {
        val w = size.minDimension * 0.11f
        drawLine(c.primary, Offset(size.width * 0.18f, size.height * 0.52f),
            Offset(size.width * 0.42f, size.height * 0.74f), w, StrokeCap.Round)
        drawLine(c.primary, Offset(size.width * 0.42f, size.height * 0.74f),
            Offset(size.width * 0.82f, size.height * 0.28f), w, StrokeCap.Round)
    }
}

/** The photo of the speaker's model, drawn only if there is no picture of it. */
@Composable
internal fun SpeakerPicture(speaker: Speaker) {
    val context = LocalContext.current
    val image by produceState(SpeakerImages.cached(speaker.model), speaker.model) {
        value = SpeakerImages.load(context, speaker)
    }
    // The photos show black speakers, which would vanish into a dark card without some light behind them.
    val light = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.18f) else Color.Transparent
    Box(
        Modifier
            .size(width = 72.dp, height = 48.dp)
            .drawBehind {
                // Fades out inside the card, or the card's edge would cut it off.
                val glow = size.height * 0.72f
                drawCircle(Brush.radialGradient(listOf(light, Color.Transparent), center, glow), glow, center)
            },
        contentAlignment = Alignment.Center
    ) {
        val picture = image
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            DrawnSpeaker(SpeakerKind.of(speaker.model))
        }
    }
}

/** Soundbar, speaker, sub or small box. */
@Composable
private fun DrawnSpeaker(kind: SpeakerKind) {
    val dark = isSystemInDarkTheme()
    val top = if (dark) Color(0xFF9A9CA3) else Color(0xFFB3B0A8)
    val bottom = if (dark) Color(0xFF55575D) else Color(0xFF6F6D67)
    val light = if (dark) Color(0xFFB9BBC2) else Color(0xFFCFCCC4)
    val dot = Color.Black.copy(alpha = 0.28f)
    Canvas(Modifier.size(width = 64.dp, height = 40.dp)) {
        val w = size.width
        val h = size.height
        fun shadowUnder(x: Float, y: Float, bw: Float) {
            drawOval(Color.Black.copy(alpha = 0.08f), Offset(x - bw * 0.02f, y - h * 0.03f), Size(bw * 1.04f, h * 0.12f))
            drawOval(Color.Black.copy(alpha = 0.10f), Offset(x + bw * 0.03f, y - h * 0.01f), Size(bw * 0.94f, h * 0.07f))
        }
        // The front, with the top catching the light above it.
        fun body(x: Float, y: Float, bw: Float, bh: Float, radius: Float) {
            shadowUnder(x, y + bh, bw)
            drawRoundRect(light, Offset(x, y - h * 0.035f), Size(bw, bh), CornerRadius(radius))
            drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = y, endY = y + bh),
                Offset(x, y), Size(bw, bh), cornerRadius = CornerRadius(radius))
        }
        when (kind) {
            SpeakerKind.BAR -> {
                // Seen slightly from above and from the left, so its depth shows.
                val depth = h * 0.2f
                val y = h * 0.42f
                val frontH = h * 0.32f
                shadowUnder(0f, y + frontH, w)
                val topFace = Path().apply {
                    moveTo(w * 0.03f, y + h * 0.03f)
                    lineTo(w * 0.15f, y - depth)
                    lineTo(w * 0.985f, y - depth)
                    lineTo(w * 0.975f, y + h * 0.03f)
                    close()
                }
                drawPath(topFace, light)
                drawPath(topFace, light, style = Stroke(h * 0.05f, join = StrokeJoin.Round))
                drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = y, endY = y + frontH),
                    Offset(0f, y), Size(w, frontH), cornerRadius = CornerRadius(frontH * 0.42f))
                repeat(24) { i ->
                    drawCircle(dot, h * 0.016f, Offset(w * (0.08f + i * 0.036f), y + frontH * 0.5f))
                }
            }
            SpeakerKind.TOWER -> {
                val bw = h * 0.72f
                val x = (w - bw) / 2
                body(x, h * 0.06f, bw, h * 0.84f, bw * 0.2f)
                repeat(5) { row ->
                    repeat(5) { col ->
                        drawCircle(dot, h * 0.016f, Offset(x + bw * (0.24f + col * 0.13f), h * (0.26f + row * 0.11f)))
                    }
                }
            }
            SpeakerKind.SUB -> {
                val bw = h * 0.86f
                val x = (w - bw) / 2
                body(x, h * 0.08f, bw, h * 0.82f, bw * 0.18f)
                drawRoundRect(dot, Offset(x + bw * 0.42f, h * 0.2f), Size(bw * 0.16f, h * 0.58f),
                    cornerRadius = CornerRadius(bw * 0.08f))
            }
            SpeakerKind.HOMEPOD -> {
                // A tall rounded cylinder in mesh, with the glowing touch surface on top.
                val bw = h * 0.7f
                val x = (w - bw) / 2
                val y = h * 0.1f
                val bh = h * 0.8f
                shadowUnder(x, y + bh, bw)
                drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = y, endY = y + bh),
                    Offset(x, y), Size(bw, bh), cornerRadius = CornerRadius(bw * 0.32f))
                repeat(6) { row ->
                    drawLine(dot, Offset(x + bw * 0.1f, y + bh * (0.3f + row * 0.11f)),
                        Offset(x + bw * 0.9f, y + bh * (0.3f + row * 0.11f)), h * 0.012f)
                }
                drawOval(light, Offset(x + bw * 0.08f, y + h * 0.015f), Size(bw * 0.84f, h * 0.12f))
                drawOval(Color(0xFF8E7CF0).copy(alpha = 0.55f), Offset(x + bw * 0.3f, y + h * 0.035f), Size(bw * 0.4f, h * 0.07f))
            }
            SpeakerKind.HOMEPOD_MINI -> {
                // A small ball, flattened on top, with the glowing touch surface.
                val d = h * 0.74f
                val x = (w - d) / 2
                val y = h * 0.16f
                shadowUnder(x + d * 0.1f, y + d, d * 0.8f)
                drawOval(Brush.verticalGradient(listOf(top, bottom), startY = y, endY = y + d), Offset(x, y), Size(d, d))
                repeat(4) { row ->
                    val ry = y + d * (0.4f + row * 0.13f)
                    val half = d * (0.42f - row * 0.06f)
                    drawLine(dot, Offset(w / 2 - half, ry), Offset(w / 2 + half, ry), h * 0.012f)
                }
                drawOval(light, Offset(x + d * 0.22f, y + d * 0.04f), Size(d * 0.56f, d * 0.16f))
                drawOval(Color(0xFF8E7CF0).copy(alpha = 0.55f), Offset(x + d * 0.36f, y + d * 0.07f), Size(d * 0.28f, d * 0.09f))
            }
            SpeakerKind.BOX -> {
                val bw = w * 0.56f
                val x = (w - bw) / 2
                body(x, h * 0.4f, bw, h * 0.34f, h * 0.08f)
                drawCircle(light, h * 0.03f, Offset(x + bw * 0.14f, h * 0.57f))
            }
        }
    }
}

@Composable
internal fun VolumeGlyph(muted: Boolean, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(Modifier.size(22.dp)) {
        val col = color
        val w = size.minDimension * 0.1f
        val body = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.38f)
            lineTo(size.width * 0.32f, size.height * 0.38f)
            lineTo(size.width * 0.54f, size.height * 0.18f)
            lineTo(size.width * 0.54f, size.height * 0.82f)
            lineTo(size.width * 0.32f, size.height * 0.62f)
            lineTo(size.width * 0.12f, size.height * 0.62f)
            close()
        }
        drawPath(body, col)
        if (muted) {
            drawLine(col, Offset(size.width * 0.66f, size.height * 0.37f),
                Offset(size.width * 0.9f, size.height * 0.63f), w, StrokeCap.Round)
            drawLine(col, Offset(size.width * 0.9f, size.height * 0.37f),
                Offset(size.width * 0.66f, size.height * 0.63f), w, StrokeCap.Round)
        } else {
            val r = size.minDimension * 0.24f
            drawArc(col, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(size.width * 0.5f - r, center.y - r), size = Size(r * 2, r * 2),
                style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** Three sliders (equalizer). */
@Composable
internal fun EqGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(26.dp)) {
        val col = c.onSurfaceVariant
        val w = size.minDimension * 0.09f
        listOf(0.22f to 0.64f, 0.5f to 0.32f, 0.78f to 0.56f).forEach { (x, knob) ->
            val cx = size.width * x
            drawLine(col, Offset(cx, size.height * 0.1f), Offset(cx, size.height * 0.9f), w, StrokeCap.Round)
            drawCircle(col, radius = size.minDimension * 0.12f, center = Offset(cx, size.height * knob))
        }
    }
}

@Composable
internal fun ChevronGlyph(modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier.size(20.dp)) {
        val col = c.onSurfaceVariant
        val w = size.minDimension * 0.1f
        val tip = Offset(center.x, size.height * 0.64f)
        drawLine(col, Offset(size.width * 0.24f, size.height * 0.38f), tip, w, StrokeCap.Round)
        drawLine(col, tip, Offset(size.width * 0.76f, size.height * 0.38f), w, StrokeCap.Round)
    }
}

/** Bars that move with the level while audio goes to this speaker. */
@Composable
internal fun LiveBars(audioFlowing: Boolean, level: Float) {
    val c = MaterialTheme.colorScheme
    val col = if (audioFlowing) c.primary else c.onSurfaceVariant
    val lvl by animateFloatAsState(if (audioFlowing) level.coerceIn(0f, 1f) else 0f, tween(140), label = "bars")
    val phase by rememberInfiniteTransition(label = "bars").animateFloat(
        0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase"
    )
    Canvas(Modifier.size(22.dp)) {
        val bars = 4
        val w = size.width / (bars * 2 - 1)
        repeat(bars) { i ->
            // Each bar swings with its own phase; the level sets how far.
            val swing = 0.55f + 0.45f * sin(phase + i * 1.7f)
            // Never flatter than this, so silent bars do not look like "⋯".
            val h = size.height * (0.36f + 0.64f * lvl * swing)
            drawRoundRect(col, topLeft = Offset(i * 2 * w, (size.height - h) / 2),
                size = Size(w, h), cornerRadius = CornerRadius(w / 2))
        }
    }
}

@Composable
internal fun PowerGlyph(color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.minDimension * 0.11f
        val r = size.minDimension * 0.33f
        drawArc(color, startAngle = -62f, sweepAngle = 304f, useCenter = false,
            topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
            style = Stroke(width = w, cap = StrokeCap.Round))
        drawLine(color, Offset(center.x, center.y - r - w * 0.3f), Offset(center.x, center.y - r * 0.15f),
            w, StrokeCap.Round)
    }
}

@Composable
internal fun StopGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension * 0.56f
        drawRoundRect(color, topLeft = Offset(center.x - s / 2, center.y - s / 2),
            size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f, s * 0.22f))
    }
}

/** Magnifying glass (search). */
@Composable
internal fun SearchGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val col = c.onSurfaceVariant
        val w = size.minDimension * 0.11f
        val r = size.minDimension * 0.27f
        val lens = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(col, radius = r, center = lens, style = Stroke(width = w))
        drawLine(col, Offset(lens.x + r * 0.72f, lens.y + r * 0.72f),
            Offset(size.width * 0.84f, size.height * 0.84f), w, StrokeCap.Round)
    }
}

/** Three dots (more). */
@Composable
internal fun MoreGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val r = size.minDimension * 0.08f
        listOf(0.22f, 0.5f, 0.78f).forEach { x ->
            drawCircle(c.onSurfaceVariant, radius = r, center = Offset(size.width * x, center.y))
        }
    }
}
