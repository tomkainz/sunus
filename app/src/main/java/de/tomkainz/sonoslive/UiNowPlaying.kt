package de.tomkainz.sonoslive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The playing app's title with its controls. They steer that app, not the speaker,
 * and work without notification access; only the title and cover need it.
 */
@Composable
internal fun NowPlayingCard(info: NowPlayingInfo?, hasAccess: Boolean, musicActive: Boolean) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val playing = info?.playing ?: musicActive
    val title = info?.title ?: stringResource(if (hasAccess) R.string.now_nothing else R.string.now_enable_title)
    val subtitle = if (info != null) info.artist else if (hasAccess) null else stringResource(R.string.now_enable_hint)
    Surface(Modifier.fillMaxWidth(), shape = ROW, color = c.surfaceContainerLow, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !hasAccess) { NowPlaying.setAccess(context, true) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(info)
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Control(R.string.now_previous, info?.canPrevious ?: true, NowPlaying::previous) {
                SkipGlyph(it, forward = false)
            }
            Control(if (playing) R.string.now_pause else R.string.now_play, true, NowPlaying::playPause) {
                if (playing) PauseGlyph(it) else PlayGlyph(it)
            }
            Control(R.string.now_next, info?.canNext ?: true, NowPlaying::next) {
                SkipGlyph(it, forward = true)
            }
        }
    }
}

/** Stays in place when an app cannot skip, so the row does not jump between titles. */
@Composable
private fun Control(label: Int, enabled: Boolean, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val c = MaterialTheme.colorScheme
    val text = stringResource(label)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        glyph(if (enabled) c.onSurfaceVariant else c.onSurfaceVariant.copy(alpha = 0.3f))
    }
}

@Composable
private fun Cover(info: NowPlayingInfo?) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val art = info?.art
    if (art != null) {
        Image(
            art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).shadow(4.dp, shape).clip(shape)
        )
    } else {
        Box(Modifier.size(56.dp).background(c.surfaceContainer, shape), contentAlignment = Alignment.Center) {
            NoteGlyph(c.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoteGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.minDimension * 0.09f
        val r = size.minDimension * 0.13f
        val left = Offset(size.width * 0.3f, size.height * 0.76f)
        val right = Offset(size.width * 0.76f, size.height * 0.68f)
        drawCircle(color, r, left)
        drawCircle(color, r, right)
        val path = Path().apply {
            moveTo(left.x + r - w / 2, left.y)
            lineTo(left.x + r - w / 2, size.height * 0.24f)
            lineTo(right.x + r - w / 2, size.height * 0.14f)
            lineTo(right.x + r - w / 2, right.y)
        }
        drawPath(path, color, style = Stroke(w, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

@Composable
private fun PlayGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.26f, size.height * 0.16f)
            lineTo(size.width * 0.26f, size.height * 0.84f)
            lineTo(size.width * 0.84f, size.height * 0.5f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PauseGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val bar = Size(size.width * 0.2f, size.height * 0.64f)
        val radius = CornerRadius(bar.width * 0.3f, bar.width * 0.3f)
        val top = size.height * 0.18f
        drawRoundRect(color, Offset(size.width * 0.22f, top), bar, radius)
        drawRoundRect(color, Offset(size.width * 0.58f, top), bar, radius)
    }
}

@Composable
private fun SkipGlyph(color: Color, forward: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        scale(scaleX = if (forward) 1f else -1f, scaleY = 1f) {
            val path = Path().apply {
                moveTo(size.width * 0.2f, size.height * 0.2f)
                lineTo(size.width * 0.2f, size.height * 0.8f)
                lineTo(size.width * 0.68f, size.height * 0.5f)
                close()
            }
            drawPath(path, color)
            drawRect(color, Offset(size.width * 0.7f, size.height * 0.2f), Size(size.width * 0.1f, size.height * 0.6f))
        }
    }
}
