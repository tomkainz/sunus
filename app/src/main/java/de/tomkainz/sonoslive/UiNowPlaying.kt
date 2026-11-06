package de.tomkainz.sonoslive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The playing app's title with its controls. Tapping controls that app, not the speaker. */
@Composable
internal fun NowPlayingCard(info: NowPlayingInfo) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = ROW, color = c.surfaceContainer) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            info.art?.let {
                Image(
                    it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(info.title, style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                info.artist?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (info.canPrevious) {
                val label = stringResource(R.string.now_previous)
                IconSpot(onClick = NowPlaying::previous, Modifier.semantics { contentDescription = label }) {
                    SkipGlyph(c.onSurfaceVariant, forward = false)
                }
            }
            val label = stringResource(if (info.playing) R.string.now_pause else R.string.now_play)
            IconSpot(onClick = NowPlaying::playPause, Modifier.semantics { contentDescription = label }) {
                if (info.playing) PauseGlyph(c.primary) else PlayGlyph(c.primary)
            }
            if (info.canNext) {
                val next = stringResource(R.string.now_next)
                IconSpot(onClick = NowPlaying::next, Modifier.semantics { contentDescription = next }) {
                    SkipGlyph(c.onSurfaceVariant, forward = true)
                }
            }
        }
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
