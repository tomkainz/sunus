package de.tomkainz.sonoslive

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ---- Sound (EQ) ---------------------------------------------------------

private class Preset(val name: Int, val bass: Int, val treble: Int)

private val PRESETS = listOf(
    Preset(R.string.eq_flat, 0, 0),
    Preset(R.string.eq_preset_bass, 4, 0),
    Preset(R.string.eq_preset_warm, 2, -2),
    Preset(R.string.eq_preset_vocal, -2, 3)
)

/**
 * The speaker's sound: presets, bass, treble, loudness, and on home theater
 * speakers night mode and speech enhancement. Folded until tapped; folded,
 * it shows a one-line summary.
 */
@Composable
internal fun EqCard(eq: Eq) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val preset = PRESETS.firstOrNull { it.bass == eq.bass && it.treble == eq.treble }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = ROW, color = c.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EqGlyph()
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.eq_title), style = MaterialTheme.typography.titleMedium,
                        color = c.onSurface)
                    Text(eqSummary(eq, preset), style = MaterialTheme.typography.labelSmall,
                        color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChevronGlyph(Modifier.rotate(turn))
            }
            if (open) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESETS.forEach { p ->
                            Chip(stringResource(p.name), p == preset, Modifier.weight(1f)) {
                                AppState.setEq(eq.copy(bass = p.bass, treble = p.treble))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    EqSlider(stringResource(R.string.eq_bass), eq.bass) { AppState.setEq(eq.copy(bass = it)) }
                    EqSlider(stringResource(R.string.eq_treble), eq.treble) { AppState.setEq(eq.copy(treble = it)) }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.outlineVariant)
                    SwitchRow(stringResource(R.string.eq_loudness), stringResource(R.string.eq_loudness_hint),
                        eq.loudness) { AppState.setEq(eq.copy(loudness = it)) }
                    eq.night?.let { on ->
                        SwitchRow(stringResource(R.string.eq_night), stringResource(R.string.eq_night_hint), on) {
                            AppState.setEq(eq.copy(night = it))
                        }
                    }
                    eq.speech?.let { on ->
                        SwitchRow(stringResource(R.string.eq_speech), stringResource(R.string.eq_speech_hint), on) {
                            AppState.setEq(eq.copy(speech = it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun eqSummary(eq: Eq, preset: Preset?): String {
    val parts = mutableListOf(
        preset?.let { stringResource(it.name) }
            ?: stringResource(R.string.eq_summary, signed(eq.bass), signed(eq.treble))
    )
    if (eq.loudness) parts += stringResource(R.string.eq_loudness)
    if (eq.night == true) parts += stringResource(R.string.eq_night_short)
    if (eq.speech == true) parts += stringResource(R.string.eq_speech_short)
    return parts.joinToString(" · ")
}

/** With a real minus sign, so +3 and −3 are the same width (with [TABULAR]). */
private fun signed(v: Int): String = when {
    v > 0 -> "+$v"
    v < 0 -> "−${-v}"
    else -> "0"
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) c.primary else c.secondaryContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (selected) c.onPrimary else c.onSecondaryContainer)
    }
}

/** The whole row switches, not just the switch. */
@Composable
internal fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary)
        )
    }
}

@Composable
private fun EqSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            modifier = Modifier.width(56.dp))
        CenterSlider(value, label, onChange, Modifier.weight(1f))
        Text(signed(value), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR),
            color = if (value == 0) c.onSurfaceVariant else c.primary,
            modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

/** Digits of equal width, so the values do not jitter while dragging. */
internal const val TABULAR = "tnum"

/** Space left and right of the track, so the thumb fits at the ends. */
private val SLIDER_EDGE = 12.dp

private fun stepAt(x: Float, width: Float, edge: Float): Int =
    ((x - edge) / (width - 2 * edge) * 20f - 10f).roundToInt().coerceIn(-10, 10)

/**
 * Slider from -10 to +10 that fills from the middle, so raised and lowered
 * values are told apart at a glance. One haptic tick per step.
 */
@Composable
private fun CenterSlider(value: Int, label: String, onChange: (Int) -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val report by rememberUpdatedState(onChange)
    val shown by animateFloatAsState(value.toFloat(), tween(90), label = "eq")
    fun set(v: Int) {
        if (v == current) return
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        report(v)
    }
    Canvas(
        modifier
            .height(40.dp)
            .pointerInput(Unit) {
                val edge = SLIDER_EDGE.toPx()
                detectTapGestures { set(stepAt(it.x, size.width.toFloat(), edge)) }
            }
            .pointerInput(Unit) {
                val edge = SLIDER_EDGE.toPx()
                detectHorizontalDragGestures(onDragStart = { set(stepAt(it.x, size.width.toFloat(), edge)) }) { change, _ ->
                    change.consume()
                    set(stepAt(change.position.x, size.width.toFloat(), edge))
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = signed(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), -10f..10f, 19)
                setProgress { set(it.roundToInt().coerceIn(-10, 10)); true }
            }
    ) {
        val edge = SLIDER_EDGE.toPx()
        val trackH = 14.dp.toPx()
        val top = center.y - trackH / 2
        val left = edge
        val right = size.width - edge
        val mid = (left + right) / 2
        val x = left + (shown + 10f) / 20f * (right - left)

        val track = Path().apply {
            addRoundRect(RoundRect(left, top, right, top + trackH, CornerRadius(trackH / 2)))
        }
        drawPath(track, c.secondaryContainer)
        clipPath(track) {
            drawRect(c.primary, topLeft = Offset(min(mid, x), top), size = Size(abs(x - mid), trackH))
        }
        // Zero mark, then the thumb with a gap in the card's color around it.
        drawLine(c.onSurfaceVariant.copy(alpha = 0.6f), Offset(mid, top - 4.dp.toPx()),
            Offset(mid, top + trackH + 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        val gap = 5.dp.toPx()
        val thumbH = 30.dp.toPx()
        drawRoundRect(c.surfaceContainer, topLeft = Offset(x - gap, center.y - thumbH / 2 - 2.dp.toPx()),
            size = Size(gap * 2, thumbH + 4.dp.toPx()), cornerRadius = CornerRadius(gap))
        drawRoundRect(c.primary, topLeft = Offset(x - 2.dp.toPx(), center.y - thumbH / 2),
            size = Size(4.dp.toPx(), thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
    }
}
