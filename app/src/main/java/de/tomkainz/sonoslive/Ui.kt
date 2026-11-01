package de.tomkainz.sonoslive

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val ROW = RoundedCornerShape(20.dp)

@Composable
fun SonosLiveApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDiscover: () -> Unit,
    onSelect: (Speaker) -> Unit,
    onAddManual: (String) -> Unit,
    onSetVolume: (Int) -> Unit,
    onStartPairing: (Int?) -> Unit
) {
    val speakers by AppState.speakers.collectAsStateWithLifecycle()
    val selected by AppState.selected.collectAsStateWithLifecycle()
    val searching by AppState.searching.collectAsStateWithLifecycle()
    val mode by AppState.mode.collectAsStateWithLifecycle()
    val running by AppState.running.collectAsStateWithLifecycle()
    val status by AppState.status.collectAsStateWithLifecycle()
    val message by AppState.message.collectAsStateWithLifecycle()
    val audioFlowing by AppState.audioFlowing.collectAsStateWithLifecycle()
    val level by AppState.level.collectAsStateWithLifecycle()
    val volume by AppState.volume.collectAsStateWithLifecycle()
    val muted by AppState.muted.collectAsStateWithLifecycle()
    val eq by AppState.eq.collectAsStateWithLifecycle()
    val requestPairing by AppState.requestPairing.collectAsStateWithLifecycle()

    val c = MaterialTheme.colorScheme
    var showMore by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 200.dp)
        ) {
            // App icon as a tile: the dark background keeps the logo's light
            // line visible in the light theme too.
            Box(
                Modifier
                    .padding(top = 20.dp)
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF181818))
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.width(48.dp))
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = c.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp)
            )

            SectionLabel(stringResource(R.string.section_speaker))
            if (speakers.isEmpty()) {
                EmptySpeaker(searching, onDiscover)
            } else {
                speakers.forEachIndexed { i, sp ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    val isSelected = sp.udn == selected?.udn
                    SpeakerRow(sp, isSelected, live = isSelected && running, audioFlowing, level) { onSelect(sp) }
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel(stringResource(R.string.section_source))
            ModeToggle(mode, enabled = !running)
            Text(
                stringResource(when {
                    !AppState.allAppsSupported -> R.string.mode_all_apps_unavailable
                    mode == CaptureMode.ALL_APPS -> R.string.mode_all_apps_hint
                    else -> R.string.mode_standard_hint
                }),
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )

            if (selected != null && volume != null) {
                Spacer(Modifier.height(28.dp))
                SectionLabel(stringResource(R.string.section_sound))
                VolumeCard(volume!!, muted, onSetVolume)
                eq?.let {
                    Spacer(Modifier.height(10.dp))
                    EqCard(it)
                }
            }
        }

        // Scrolled content fades out under the status bar and the controls.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(c.background)
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0f), c.background)))
            )
            Column(Modifier.fillMaxWidth().background(c.background).navigationBarsPadding()) {
                Text(
                    statusText(running, status, audioFlowing, selected, searching),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) c.primary else c.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconSpot(onClick = onDiscover) {
                        if (searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.onSurfaceVariant)
                        else SearchGlyph()
                    }
                    PowerButton(running, selected != null, audioFlowing, level, onStart, onStop)
                    IconSpot(onClick = { showMore = true }) { MoreGlyph() }
                }
            }
        }

        message?.let { Snackbar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) }

        if (showMore) MoreSheet(mode, running, onAddManual, onLicenses = { showMore = false; showLicenses = true }) {
            showMore = false
        }
        if (requestPairing) PairingDialog(onStartPairing)
        if (showLicenses) LicensesScreen { showLicenses = false }
    }
}

@Composable
private fun statusText(
    running: Boolean, status: String, audioFlowing: Boolean, selected: Speaker?, searching: Boolean
): String = when {
    // The live statuses start with "Live" in every language.
    running && status.startsWith("Live") -> stringResource(
        if (audioFlowing) R.string.status_live else R.string.status_live_silent, selected?.room ?: ""
    )
    running -> status
    searching -> stringResource(R.string.status_searching)
    selected == null -> stringResource(R.string.status_no_speaker)
    else -> stringResource(R.string.status_ready)
}

// ---- Speakers -----------------------------------------------------------

@Composable
private fun SpeakerRow(
    sp: Speaker, selected: Boolean, live: Boolean, audioFlowing: Boolean, level: Float, onClick: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = ROW,
        color = if (selected) c.primaryContainer.copy(alpha = 0.55f) else c.surfaceContainer
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeakerGlyph(selected)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(sp.room, style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sp.model.removePrefix("Sonos "), style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant)
            }
            when {
                live -> LiveBars(audioFlowing, level)
                selected -> CheckGlyph()
            }
        }
    }
}

@Composable
private fun EmptySpeaker(searching: Boolean, onDiscover: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().clickable(onClick = onDiscover), shape = ROW, color = c.surfaceContainer) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (searching) R.string.empty_searching else R.string.empty_none),
                style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            if (!searching) {
                Text(stringResource(R.string.empty_hint), style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Small heading above a group of rows, as in Wavelet. */
@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 18.dp, bottom = 10.dp))
}

// ---- Mode as a slim toggle ----------------------------------------------

@Composable
private fun ModeToggle(mode: CaptureMode, enabled: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = CircleShape, color = c.surfaceContainer) {
        Row(Modifier.padding(4.dp)) {
            Pill(stringResource(R.string.mode_all_apps), mode == CaptureMode.ALL_APPS,
                enabled && AppState.allAppsSupported, Modifier.weight(1f)) {
                AppState.setMode(CaptureMode.ALL_APPS)
            }
            Pill(stringResource(R.string.mode_standard), mode == CaptureMode.STANDARD, enabled, Modifier.weight(1f)) {
                AppState.setMode(CaptureMode.STANDARD)
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .height(44.dp)
            .background(if (selected) c.primary else Color.Transparent, CircleShape)
            .clickable(enabled = enabled && !selected, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = when {
                selected -> c.onPrimary
                enabled -> c.onSurfaceVariant
                else -> c.onSurfaceVariant.copy(alpha = 0.5f)
            })
    }
}

// ---- Volume -------------------------------------------------------------

/** Volume of the speaker; the speaker symbol mutes it. */
@Composable
private fun VolumeCard(volume: Int, muted: Boolean, onSetVolume: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = if (muted) c.onSurfaceVariant else c.primary
    val label = stringResource(if (muted) R.string.volume_unmute else R.string.volume_mute)
    Surface(Modifier.fillMaxWidth(), shape = ROW, color = c.surfaceContainer) {
        Row(
            Modifier.padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSpot(
                onClick = { AppState.setMuted(!muted) },
                modifier = Modifier.semantics { contentDescription = label }
            ) { VolumeGlyph(muted) }
            Spacer(Modifier.width(6.dp))
            Slider(
                value = volume.toFloat(), onValueChange = { onSetVolume(it.toInt()) }, valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text("$volume", style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR),
                color = c.onSurfaceVariant, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
        }
    }
}

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
private fun EqCard(eq: Eq) {
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
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private const val TABULAR = "tnum"

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

// ---- Power button with level ring --------------------------------------

@Composable
private fun PowerButton(
    running: Boolean, canStart: Boolean, audioFlowing: Boolean, level: Float,
    onStart: () -> Unit, onStop: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val lvl by animateFloatAsState(if (running && audioFlowing) level.coerceIn(0f, 1f) else 0f,
        tween(140), label = "ring")
    val active = canStart || running
    val fill = when {
        running -> c.primary
        canStart -> c.primaryContainer
        else -> c.surfaceContainer
    }
    val fg = when {
        running -> c.onPrimary
        canStart -> c.onPrimaryContainer
        else -> c.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        // Glow ring: grows with the level, only when audio is really flowing.
        Canvas(Modifier.fillMaxSize()) {
            if (running) {
                val base = 34.dp.toPx()
                drawCircle(c.primary.copy(alpha = 0.16f), radius = base + 6.dp.toPx() + lvl * 12.dp.toPx())
                drawCircle(c.primary.copy(alpha = 0.10f), radius = base + 14.dp.toPx() + lvl * 18.dp.toPx())
            }
        }
        Box(
            Modifier.size(68.dp).background(fill, CircleShape)
                .clickable(enabled = active) { if (running) onStop() else onStart() },
            contentAlignment = Alignment.Center
        ) {
            if (running) StopGlyph(fg) else PowerGlyph(fg)
        }
    }
}

@Composable
private fun IconSpot(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Message; tapping dismisses it. Errors with a code come with the report. */
@Composable
private fun Snackbar(text: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val withReport = Regex("SL-[A-Z][0-9]").containsMatchIn(text)
    Surface(
        modifier.padding(horizontal = 24.dp).clickable { AppState.message.value = null },
        shape = RoundedCornerShape(14.dp), color = c.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurface, textAlign = TextAlign.Center)
            if (withReport) {
                Row {
                    TextButton(onClick = { Diagnose.share(context) }) { Text(stringResource(R.string.report_share)) }
                    TextButton(onClick = { Diagnose.saveToDownloads(context) }) { Text(stringResource(R.string.report_save)) }
                }
            }
        }
    }
}

// ---- "More": manual IP, mute phone, hints, licenses -------------------

@Composable
private fun MoreSheet(
    mode: CaptureMode, running: Boolean, onAddManual: (String) -> Unit, onLicenses: () -> Unit, onClose: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val muteLocal by AppState.muteLocal.collectAsStateWithLifecycle()
    val report by AppState.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ip by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            Modifier.fillMaxWidth().clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = c.surfaceContainerHigh
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(c.outline, CircleShape))
                }

                Text(stringResource(R.string.more_ip), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, placeholder = { Text("192.168.x.x") },
                        singleLine = true, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onAddManual(ip); ip = ""; onClose() }) { Text(stringResource(R.string.ok)) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.more_mute), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                        Text(stringResource(R.string.more_mute_hint),
                            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                    Switch(
                        checked = muteLocal, enabled = !running,
                        onCheckedChange = { AppState.setMuteLocal(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary)
                    )
                }

                HorizontalDivider(color = c.outlineVariant)

                Hint(stringResource(R.string.hint_upnp))
                Hint(stringResource(R.string.hint_lossless))
                if (mode == CaptureMode.ALL_APPS) Hint(stringResource(R.string.hint_all_apps))

                if (report != null) {
                    HorizontalDivider(color = c.outlineVariant)
                    Text(stringResource(R.string.last_report), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    Text(
                        report!!.lineSequence().drop(1).take(2).joinToString("\n") { it.substringAfter(":").trim() },
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurface
                    )
                    Row {
                        TextButton(onClick = { Diagnose.share(context) }) { Text(stringResource(R.string.share)) }
                        TextButton(onClick = { Diagnose.saveToDownloads(context); onClose() }) {
                            Text(stringResource(R.string.save_downloads))
                        }
                    }
                }

                HorizontalDivider(color = c.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onLicenses) { Text(stringResource(R.string.licenses)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Pairing (All apps mode) --------------------------------------------

@Composable
private fun PairingDialog(onStartPairing: (Int?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val busy by AppState.adbBusy.collectAsStateWithLifecycle()
    val adbMessage by AppState.adbMessage.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).imePadding().padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = c.surfaceContainerHigh) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text(stringResource(R.string.pair_steps),
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Text(stringResource(R.string.pair_hint),
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)

                if (manual) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.pair_port_label)) }, singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                adbMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.primary) }

                // Own row: with three buttons in one row, "Start pairing" wrapped several times.
                TextButton(onClick = { manual = !manual }) {
                    Text(stringResource(if (manual) R.string.pair_port_auto else R.string.pair_port_manual),
                        style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AppState.requestPairing.value = false }) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onStartPairing(if (manual) port.toIntOrNull() else null) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onPrimary)
                        else Text(stringResource(R.string.pair_start))
                    }
                }
            }
        }
    }
}

// ---- Hand-drawn icons --------------------------------------------------

@Composable
private fun CheckGlyph() {
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
private fun SpeakerGlyph(selected: Boolean) {
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
private fun VolumeGlyph(muted: Boolean) {
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
private fun EqGlyph() {
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
private fun ChevronGlyph(modifier: Modifier) {
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
private fun LiveBars(audioFlowing: Boolean, level: Float) {
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
private fun PowerGlyph(color: Color) {
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
private fun StopGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension * 0.56f
        drawRoundRect(color, topLeft = Offset(center.x - s / 2, center.y - s / 2),
            size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f, s * 0.22f))
    }
}

/** Magnifying glass (search). */
@Composable
private fun SearchGlyph() {
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
private fun MoreGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val r = size.minDimension * 0.08f
        listOf(0.22f, 0.5f, 0.78f).forEach { x ->
            drawCircle(c.onSurfaceVariant, radius = r, center = Offset(size.width * x, center.y))
        }
    }
}
