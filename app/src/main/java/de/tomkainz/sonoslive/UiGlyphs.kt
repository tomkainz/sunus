package de.tomkainz.sonoslive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
internal fun SpeakerGlyph(selected: Boolean) {
    val c = MaterialTheme.colorScheme
    val col = if (selected) c.primary else c.onSurfaceVariant
    Canvas(Modifier.size(26.dp)) {
        val w = size.minDimension * 0.11f
        drawRoundRect(col, topLeft = Offset(size.width * 0.2f, size.height * 0.06f),
            size = Size(size.width * 0.6f, size.height * 0.88f),
            cornerRadius = CornerRadius(size.width * 0.16f), style = Stroke(w))
        drawCircle(col, radius = size.minDimension * 0.15f, center = Offset(center.x, size.height * 0.6f), style = Stroke(w))
        drawCircle(col, radius = size.minDimension * 0.035f, center = Offset(center.x, size.height * 0.24f))
    }
}

@Composable
internal fun VolumeGlyph(muted: Boolean) {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(22.dp)) {
        val col = c.onSurfaceVariant
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
            drawRoundRect(c.primary, topLeft = Offset(i * 2 * w, (size.height - h) / 2),
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
